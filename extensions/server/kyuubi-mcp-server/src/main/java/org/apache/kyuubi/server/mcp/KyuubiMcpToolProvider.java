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
import java.util.Collection;
import java.util.Objects;
import org.apache.kyuubi.server.KyuubiRestFrontendService;

/**
 * Discovers trusted, deployment-specific MCP tools through {@link java.util.ServiceLoader}.
 *
 * <p>Provider jars run inside the Kyuubi Server process and are therefore part of the trusted
 * server installation. Kyuubi generates JSON schemas from their argument and response classes and
 * wraps their calls with the same input validation, audit logging, and metrics used by built-in
 * tools. It cannot sandbox arbitrary provider code.
 */
public interface KyuubiMcpToolProvider extends AutoCloseable {

  Collection<? extends Tool<?, ?>> tools(Context context);

  @Override
  default void close() {}

  /** Initialization context shared by all tools from one provider. */
  final class Context {
    private final KyuubiRestFrontendService frontendService;
    private final ObjectMapper objectMapper;

    public Context(KyuubiRestFrontendService frontendService, ObjectMapper objectMapper) {
      this.frontendService = Objects.requireNonNull(frontendService, "frontendService");
      this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public KyuubiRestFrontendService frontendService() {
      return frontendService;
    }

    public ObjectMapper objectMapper() {
      return objectMapper;
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

  /** A typed, synchronous, read-only diagnostic tool. */
  interface Tool<A, R> {

    String name();

    String description();

    Class<A> argumentsType();

    Class<R> responseType();

    Result<R> call(Caller caller, A arguments) throws Exception;
  }

  /** The protocol-neutral outcome of a tool call. */
  final class Result<R> {
    public enum Status {
      SUCCESS,
      ERROR,
      DENIED
    }

    private final Status status;
    private final String message;
    private final R response;

    private Result(Status status, String message, R response) {
      this.status = Objects.requireNonNull(status, "status");
      this.message = message;
      this.response = response;
    }

    public static <R> Result<R> success(R response) {
      return new Result<>(Status.SUCCESS, null, Objects.requireNonNull(response, "response"));
    }

    public static <R> Result<R> error(String message) {
      return new Result<>(Status.ERROR, Objects.requireNonNull(message, "message"), null);
    }

    public static <R> Result<R> error(String message, R response) {
      return new Result<>(
          Status.ERROR,
          Objects.requireNonNull(message, "message"),
          Objects.requireNonNull(response, "response"));
    }

    public static <R> Result<R> denied(String message) {
      return new Result<>(Status.DENIED, Objects.requireNonNull(message, "message"), null);
    }

    public Status status() {
      return status;
    }

    public String message() {
      return message;
    }

    public R response() {
      return response;
    }
  }
}
