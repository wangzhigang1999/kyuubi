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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kyuubi.server.mcp.KyuubiMcpDiagnostics;
import org.apache.kyuubi.server.mcp.McpToolProperty;

/** Cluster-wide lookup of one live operation. */
public final class GetOperationTool
    implements KyuubiMcpTool<GetOperationTool.Args, GetOperationTool.Response> {

  public static final String NAME = "get_operation";
  private static final String INCOMPLETE =
      "The resource could not be resolved because the cluster lookup was incomplete.";
  private static final String INACCESSIBLE = "The resource does not exist or is not accessible.";
  private final KyuubiMcpDiagnostics diagnostics;

  public GetOperationTool(KyuubiMcpDiagnostics diagnostics) {
    this.diagnostics = diagnostics;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "Get a live operation by its stable identifier. Set server when the owning instance is "
        + "already known to avoid cluster fanout.";
  }

  @Override
  public Class<Args> argumentsType() {
    return Args.class;
  }

  @Override
  public Class<Response> responseType() {
    return Response.class;
  }

  @Override
  public KyuubiMcpTool.Result<Response> call(KyuubiMcpTool.Caller caller, Args arguments) {
    Map<String, Object> values = new HashMap<>();
    values.put("operation_id", arguments.operationId());
    if (arguments.server() != null) {
      values.put("server", arguments.server());
    }
    Response response =
        diagnostics.response(diagnostics.getOperation(values, caller), Response.class);
    if (response.found()) {
      return KyuubiMcpTool.Result.success(response);
    }
    return response.partial()
        ? KyuubiMcpTool.Result.error(INCOMPLETE, response)
        : KyuubiMcpTool.Result.error(INACCESSIBLE);
  }

  public record Args(
      @JsonProperty(value = "operation_id", required = true)
          @JsonPropertyDescription("Stable Kyuubi operation identifier.")
          @McpToolProperty(minLength = 1, maxLength = 128, pattern = "^[A-Za-z0-9_-]+$")
          String operationId,
      @JsonProperty("server")
          @JsonPropertyDescription(
              "Optional exact diagnostic address returned by list_servers or a prior operation result. When set, only that server is queried.")
          @McpToolProperty(minLength = 3, maxLength = 255)
          String server) {}

  public record Response(
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "True when an accessible operation was found on a responded server.")
          boolean found,
      @JsonPropertyDescription("Operation details when found; null otherwise.")
          @McpToolProperty(nullable = true)
          Operation operation,
      @JsonPropertyDescription("Server that owns the operation when found; null otherwise.")
          @McpToolProperty(nullable = true)
          String server,
      @JsonProperty(required = true)
          @JsonPropertyDescription("True when the selected request scope was not fully queried.")
          boolean partial,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Failures that made this lookup incomplete.")
          List<PeerFailure> failedServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Servers selected after HA discovery and optional server filtering.")
          int discoveredServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Servers that successfully returned this diagnostic result.")
          int respondedServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription("UTC time when this lookup completed.")
          @McpToolProperty(format = "date-time")
          String observedAt) {}

  public record Operation(
      @JsonProperty(required = true) @JsonPropertyDescription("Stable Kyuubi operation identifier.")
          String operationId,
      @JsonProperty(required = true) @JsonPropertyDescription("Current Kyuubi operation state.")
          State state,
      @JsonProperty(required = true)
          @JsonPropertyDescription("UTC time when the operation was created.")
          @McpToolProperty(format = "date-time")
          String createdAt,
      @JsonPropertyDescription("UTC time when execution started, when available.")
          @McpToolProperty(nullable = true, format = "date-time")
          String startedAt,
      @JsonPropertyDescription("UTC time when execution completed, when available.")
          @McpToolProperty(nullable = true, format = "date-time")
          String completedAt,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Milliseconds since execution started, or since creation while waiting to start; fixed at completion.")
          long elapsedMs,
      @JsonProperty(required = true) @JsonPropertyDescription("Identifier of the owning session.")
          String sessionId,
      @JsonProperty(required = true) @JsonPropertyDescription("Owner of the operation's session.")
          String user,
      @JsonProperty(required = true) @JsonPropertyDescription("Type of the operation's session.")
          SessionType sessionType,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Kyuubi Server instance that owns the operation.")
          String server,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Bounded operation metrics exposed by Kyuubi.")
          Map<String, String> metrics) {}

  public record PeerFailure(
      @JsonProperty(required = true)
          @JsonPropertyDescription("Server address or discovery scope associated with the failure.")
          String server,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Bounded transport, deadline, or discovery failure category.")
          String reason) {}

  public enum State {
    INITIALIZED,
    PENDING,
    RUNNING,
    COMPILED,
    FINISHED,
    TIMEOUT,
    CANCELED,
    CLOSED,
    ERROR,
    UNKNOWN
  }

  public enum SessionType {
    INTERACTIVE,
    BATCH
  }
}
