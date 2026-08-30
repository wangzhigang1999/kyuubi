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
import org.apache.kyuubi.server.mcp.KyuubiMcpToolProvider;
import org.apache.kyuubi.server.mcp.McpToolProperty;

/** Cluster-wide live operation listing. */
public final class ListOperationsTool
    implements KyuubiMcpToolProvider.Tool<ListOperationsTool.Args, ListOperationsTool.Response> {

  public static final String NAME = "list_operations";
  private final KyuubiMcpDiagnostics diagnostics;

  public ListOperationsTool(KyuubiMcpDiagnostics diagnostics) {
    this.diagnostics = diagnostics;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "List live operations visible to the authenticated user. Prefer one relevant state "
        + "filter instead of exhaustive parallel calls. When partial is true, an empty list means "
        + "no matches on responded servers only.";
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
  public KyuubiMcpToolProvider.Result<Response> call(
      KyuubiMcpToolProvider.Caller caller, Args arguments) {
    if (arguments.user() != null
        && !arguments.user().equals(caller.realUser())
        && !caller.administrator()) {
      return KyuubiMcpToolProvider.Result.denied("The requested user is not accessible.");
    }
    Map<String, Object> values = new HashMap<>();
    put(values, "user", arguments.user());
    put(values, "session_id", arguments.sessionId());
    put(values, "state", arguments.state());
    put(values, "limit", arguments.limit());
    return KyuubiMcpToolProvider.Result.success(
        diagnostics.response(diagnostics.listOperations(values, caller), Response.class));
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
      @JsonProperty("session_id")
          @JsonPropertyDescription("Stable session identifier filter.")
          @McpToolProperty(minLength = 1, maxLength = 128, pattern = "^[A-Za-z0-9_-]+$")
          String sessionId,
      @JsonProperty("state") @JsonPropertyDescription("Current operation state filter.")
          State state,
      @JsonProperty("limit")
          @JsonPropertyDescription("Maximum operations returned across responded servers.")
          @McpToolProperty(minimum = 1, maximum = 200)
          Integer limit) {}

  public record Response(
      @JsonProperty(required = true)
          @JsonPropertyDescription("Visible live operations found on responded servers.")
          List<Operation> operations,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Returned operations; not a cluster-wide total when partial is true.")
          int count,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "True when discovery failed or a discovered server failed or was omitted.")
          boolean partial,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Failures that made this result incomplete.")
          List<PeerFailure> failedServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Distinct server registrations discovered before the fanout limit.")
          int discoveredServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Discovered registrations not queried because fanoutLimit was reached.")
          int omittedServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Maximum discovered servers queried by one call.")
          int fanoutLimit,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Servers that successfully returned this diagnostic result.")
          int respondedServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Scope covered by operations and count.")
          CountScope countScope,
      @JsonProperty(required = true)
          @JsonPropertyDescription("UTC time when this observation completed.")
          @McpToolProperty(format = "date-time")
          String observedAt) {}

  public record Operation(
      @JsonProperty(required = true) @JsonPropertyDescription("Stable Kyuubi operation identifier.")
          String identifier,
      @JsonProperty(required = true) @JsonPropertyDescription("Current Kyuubi operation state.")
          String state,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Operation creation time as Unix epoch milliseconds.")
          long createTime,
      @JsonPropertyDescription("Operation start time as Unix epoch milliseconds when available.")
          @McpToolProperty(nullable = true)
          Long startTime,
      @JsonPropertyDescription(
              "Operation completion time as Unix epoch milliseconds when available.")
          @McpToolProperty(nullable = true)
          Long completeTime,
      @JsonProperty(required = true) @JsonPropertyDescription("Identifier of the owning session.")
          String sessionId,
      @JsonProperty(required = true) @JsonPropertyDescription("Owner of the operation's session.")
          String sessionUser,
      @JsonProperty(required = true) @JsonPropertyDescription("Type of the operation's session.")
          String sessionType,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Kyuubi Server instance that owns the operation.")
          String kyuubiInstance,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Bounded operation metrics exposed by Kyuubi.")
          Map<String, Object> metrics) {}

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

  public enum CountScope {
    all_discovered_servers,
    responded_servers_only
  }
}
