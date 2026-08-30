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

/** Cluster-wide live session listing. */
public final class ListSessionsTool
    implements KyuubiMcpTool<ListSessionsTool.Args, ListSessionsTool.Response> {

  public static final String NAME = "list_sessions";
  private final KyuubiMcpDiagnostics diagnostics;

  public ListSessionsTool(KyuubiMcpDiagnostics diagnostics) {
    this.diagnostics = diagnostics;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "List live sessions visible to the authenticated user. When partial is true, an empty "
        + "list means no matches on responded servers only. Set server to query one discovered "
        + "instance without cluster fanout.";
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
    if (arguments.user() != null
        && !arguments.user().equals(caller.realUser())
        && !caller.administrator()) {
      return KyuubiMcpTool.Result.denied("The requested user is not accessible.");
    }
    Map<String, Object> values = new HashMap<>();
    put(values, "user", arguments.user());
    put(values, "session_type", arguments.sessionType());
    put(values, "limit", arguments.limit());
    put(values, "server", arguments.server());
    return KyuubiMcpTool.Result.success(
        diagnostics.response(diagnostics.listSessions(values, caller), Response.class));
  }

  private static void put(Map<String, Object> values, String name, Object value) {
    if (value != null) {
      values.put(name, value instanceof Enum<?> ? value.toString() : value);
    }
  }

  public record Args(
      @JsonProperty("user")
          @JsonPropertyDescription(
              "User to inspect. A different user requires administrator permission.")
          @McpToolProperty(maxLength = 256)
          String user,
      @JsonProperty("session_type") @JsonPropertyDescription("Session type filter.")
          SessionType sessionType,
      @JsonProperty("limit")
          @JsonPropertyDescription("Maximum sessions returned across responded servers.")
          @McpToolProperty(minimum = 1, maximum = 200)
          Integer limit,
      @JsonProperty("server")
          @JsonPropertyDescription(
              "Optional exact diagnostic address returned by list_servers. When set, only that server is queried.")
          @McpToolProperty(minLength = 3, maxLength = 255)
          String server) {}

  public record Response(
      @JsonProperty(required = true)
          @JsonPropertyDescription("Visible live sessions found on responded servers.")
          List<Session> sessions,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Returned sessions; not a cluster-wide total when partial is true.")
          int count,
      @JsonProperty(required = true)
          @JsonPropertyDescription("True when the selected request scope was not fully queried.")
          boolean partial,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Failures that made this result incomplete; a failure does not prove a process stopped.")
          List<PeerFailure> failedServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Servers selected after HA discovery and optional server filtering.")
          int discoveredServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Servers that successfully returned this diagnostic result.")
          int respondedServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription("UTC time when this observation completed.")
          @McpToolProperty(format = "date-time")
          String observedAt) {}

  public record Session(
      @JsonProperty(required = true) @JsonPropertyDescription("Stable Kyuubi session identifier.")
          String sessionId,
      @JsonProperty(required = true) @JsonPropertyDescription("Session owner.") String user,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Client address recorded when the session opened.")
          String clientIp,
      @JsonProperty(required = true) @JsonPropertyDescription("Kyuubi session type.")
          SessionType sessionType,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Diagnostic address of the Kyuubi Server that owns the session; accepted by the server argument.")
          String server,
      @JsonProperty(required = true)
          @JsonPropertyDescription("UTC time when the session opened.")
          @McpToolProperty(format = "date-time")
          String createdAt,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Session age in milliseconds at observation time.")
          long ageMs,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Time without an active operation, in milliseconds.")
          long idleMs,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Operations created since this session opened.")
          int operationCount) {}

  public record PeerFailure(
      @JsonProperty(required = true)
          @JsonPropertyDescription("Server address or discovery scope associated with the failure.")
          String server,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Bounded transport, deadline, or discovery failure category.")
          String reason) {}

  public enum SessionType {
    INTERACTIVE,
    BATCH
  }
}
