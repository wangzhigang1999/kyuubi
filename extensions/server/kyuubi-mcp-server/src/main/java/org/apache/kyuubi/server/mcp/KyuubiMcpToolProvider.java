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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import org.apache.kyuubi.server.KyuubiRestFrontendService;

/**
 * Discovers trusted, deployment-specific MCP tools through {@link java.util.ServiceLoader}.
 *
 * <p>Provider jars run inside the Kyuubi Server process and are therefore part of the trusted
 * server installation. Kyuubi validates their schemas and read-only declarations and wraps their
 * calls with the same input validation, concurrency controls, audit logging, and metrics used by
 * built-in tools. It cannot sandbox arbitrary provider code.
 */
public interface KyuubiMcpToolProvider extends AutoCloseable {

  Collection<Tool> tools(Context context);

  @Override
  default void close() {}

  /** Initialization context shared by all tools from one provider. */
  final class Context {
    private final KyuubiRestFrontendService frontendService;
    private final ObjectMapper objectMapper;
    private final McpJsonMapper jsonMapper;

    public Context(
        KyuubiRestFrontendService frontendService,
        ObjectMapper objectMapper,
        McpJsonMapper jsonMapper) {
      this.frontendService = Objects.requireNonNull(frontendService, "frontendService");
      this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
      this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    public KyuubiRestFrontendService frontendService() {
      return frontendService;
    }

    public ObjectMapper objectMapper() {
      return objectMapper;
    }

    public McpJsonMapper jsonMapper() {
      return jsonMapper;
    }
  }

  /** Authenticated request identity supplied by Kyuubi, never by tool arguments. */
  final class Caller {
    private final String requestId;
    private final String realUser;
    private final String clientIp;
    private final boolean administrator;

    public Caller(String requestId, String realUser, String clientIp, boolean administrator) {
      this.requestId = Objects.requireNonNull(requestId, "requestId");
      this.realUser = Objects.requireNonNull(realUser, "realUser");
      this.clientIp = Objects.requireNonNull(clientIp, "clientIp");
      this.administrator = administrator;
    }

    public String requestId() {
      return requestId;
    }

    public String realUser() {
      return realUser;
    }

    public String clientIp() {
      return clientIp;
    }

    public boolean administrator() {
      return administrator;
    }
  }

  /** A tool definition and its synchronous handler. */
  final class Tool {
    private final McpSchema.Tool definition;
    private final BiFunction<Caller, Map<String, Object>, McpSchema.CallToolResult> handler;

    public Tool(
        McpSchema.Tool definition,
        BiFunction<Caller, Map<String, Object>, McpSchema.CallToolResult> handler) {
      this.definition = Objects.requireNonNull(definition, "definition");
      this.handler = Objects.requireNonNull(handler, "handler");
    }

    public McpSchema.Tool definition() {
      return definition;
    }

    public BiFunction<Caller, Map<String, Object>, McpSchema.CallToolResult> handler() {
      return handler;
    }
  }
}
