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

package org.apache.kyuubi.server.mcp.tool;

import java.util.Objects;

/**
 * Internal typed definition shared by built-in MCP diagnostic tools.
 *
 * <p>This is not an extension point and may change without compatibility guarantees.
 */
public interface KyuubiMcpTool<A, R> {

  String name();

  String description();

  Class<A> argumentsType();

  Class<R> responseType();

  Result<R> call(Caller caller, A arguments) throws Exception;

  /** Authenticated request identity supplied by Kyuubi, never by tool arguments. */
  final class Caller {
    private final String realUser;
    private final String clientIp;
    private final boolean administrator;

    public Caller(String realUser, String clientIp, boolean administrator) {
      this.realUser = Objects.requireNonNull(realUser, "realUser");
      this.clientIp = Objects.requireNonNull(clientIp, "clientIp");
      this.administrator = administrator;
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

  /** The protocol-neutral outcome of a built-in tool call. */
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
