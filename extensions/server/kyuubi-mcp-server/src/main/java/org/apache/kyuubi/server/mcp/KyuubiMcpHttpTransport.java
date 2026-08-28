/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.server.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.io.BufferedReader;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** A stateless MCP transport for the javax Servlet API used by Kyuubi's Jetty 9 server. */
public final class KyuubiMcpHttpTransport extends HttpServlet
    implements McpStatelessServerTransport {

  private static final Logger LOG = LoggerFactory.getLogger(KyuubiMcpHttpTransport.class);
  private static final String APPLICATION_JSON = "application/json";
  private static final String TEXT_EVENT_STREAM = "text/event-stream";
  private static final int MAX_REQUEST_CHARACTERS = 64 * 1024;
  private static final int MAX_RESPONSE_CHARACTERS = 1024 * 1024;
  private static final int MAX_CONCURRENT_REQUESTS = 64;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
  private static final int TOO_MANY_REQUESTS = 429;

  private final McpJsonMapper jsonMapper;
  private final Supplier<McpTransportContext> contextSupplier;
  private final Semaphore requestPermits = new Semaphore(MAX_CONCURRENT_REQUESTS);

  private volatile McpStatelessServerHandler handler;
  private volatile boolean closing;

  public KyuubiMcpHttpTransport(
      McpJsonMapper jsonMapper, Supplier<McpTransportContext> contextSupplier) {
    this.jsonMapper = jsonMapper;
    this.contextSupplier = contextSupplier;
  }

  @Override
  public void setMcpHandler(McpStatelessServerHandler handler) {
    this.handler = handler;
  }

  @Override
  public Mono<Void> closeGracefully() {
    return Mono.fromRunnable(() -> closing = true);
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writeError(
        response,
        HttpServletResponse.SC_METHOD_NOT_ALLOWED,
        McpSchema.ErrorCodes.INVALID_REQUEST,
        "MCP stateless HTTP accepts POST requests only");
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (closing) {
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
      return;
    }

    String contentType = request.getContentType();
    if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(APPLICATION_JSON)) {
      writeError(
          response,
          HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
          McpSchema.ErrorCodes.INVALID_REQUEST,
          "Content-Type must be application/json");
      return;
    }

    String accept = request.getHeader("Accept");
    String normalizedAccept = accept == null ? "" : accept.toLowerCase(Locale.ROOT);
    if (!normalizedAccept.contains(APPLICATION_JSON)
        || !normalizedAccept.contains(TEXT_EVENT_STREAM)) {
      writeError(
          response,
          HttpServletResponse.SC_BAD_REQUEST,
          McpSchema.ErrorCodes.INVALID_REQUEST,
          "Accept must include application/json and text/event-stream");
      return;
    }

    if (!requestPermits.tryAcquire()) {
      writeError(
          response,
          TOO_MANY_REQUESTS,
          McpSchema.ErrorCodes.INTERNAL_ERROR,
          "Too many concurrent MCP requests");
      return;
    }

    try {
      McpSchema.JSONRPCMessage message =
          McpSchema.deserializeJsonRpcMessage(jsonMapper, readBody(request));
      McpTransportContext context = contextSupplier.get();
      if (message instanceof McpSchema.JSONRPCRequest) {
        McpSchema.JSONRPCResponse rpcResponse =
            handler
                .handleRequest(context, (McpSchema.JSONRPCRequest) message)
                .contextWrite(
                    reactorContext -> reactorContext.put(McpTransportContext.KEY, context))
                .block(REQUEST_TIMEOUT);
        if (rpcResponse.error() != null
            && rpcResponse.error().code().intValue() == McpSchema.ErrorCodes.METHOD_NOT_FOUND) {
          response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        } else {
          response.setStatus(HttpServletResponse.SC_OK);
        }
        writeJson(response, rpcResponse);
      } else if (message instanceof McpSchema.JSONRPCNotification) {
        handler
            .handleNotification(context, (McpSchema.JSONRPCNotification) message)
            .contextWrite(reactorContext -> reactorContext.put(McpTransportContext.KEY, context))
            .block(REQUEST_TIMEOUT);
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
      } else {
        writeError(
            response,
            HttpServletResponse.SC_BAD_REQUEST,
            McpSchema.ErrorCodes.INVALID_REQUEST,
            "Expected an MCP request or notification");
      }
    } catch (JsonProcessingException | IllegalArgumentException e) {
      writeError(
          response,
          HttpServletResponse.SC_BAD_REQUEST,
          McpSchema.ErrorCodes.INVALID_REQUEST,
          "Invalid MCP message");
    } catch (Exception e) {
      LOG.error("Failed to handle MCP message", e);
      writeError(
          response,
          HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          McpSchema.ErrorCodes.INTERNAL_ERROR,
          "Failed to handle MCP message");
    } finally {
      requestPermits.release();
    }
  }

  private String readBody(HttpServletRequest request) throws IOException {
    if (request.getContentLengthLong() > MAX_REQUEST_CHARACTERS) {
      throw new IllegalArgumentException("MCP request exceeds the maximum size");
    }
    StringBuilder body = new StringBuilder();
    BufferedReader reader = request.getReader();
    String line;
    while ((line = reader.readLine()) != null) {
      body.append(line);
      if (body.length() > MAX_REQUEST_CHARACTERS) {
        throw new IllegalArgumentException("MCP request exceeds the maximum size");
      }
    }
    return body.toString();
  }

  private void writeError(HttpServletResponse response, int status, int code, String message)
      throws IOException {
    response.setStatus(status);
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("code", code);
    error.put("message", message);

    Map<String, Object> rpcResponse = new LinkedHashMap<>();
    rpcResponse.put("jsonrpc", McpSchema.JSONRPC_VERSION);
    rpcResponse.put("id", null);
    rpcResponse.put("error", error);
    writeJson(response, rpcResponse);
  }

  private void writeJson(HttpServletResponse response, Object value) throws IOException {
    String json = jsonMapper.writeValueAsString(value);
    if (json.length() > MAX_RESPONSE_CHARACTERS) {
      throw new IOException("MCP response exceeds the maximum size");
    }
    response.setCharacterEncoding("UTF-8");
    response.setContentType(APPLICATION_JSON);
    response.getWriter().write(json);
    response.getWriter().flush();
  }
}
