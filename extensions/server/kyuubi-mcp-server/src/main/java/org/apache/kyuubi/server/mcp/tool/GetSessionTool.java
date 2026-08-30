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
import java.util.List;
import java.util.Map;
import org.apache.kyuubi.server.mcp.KyuubiMcpDiagnostics;
import org.apache.kyuubi.server.mcp.McpToolProperty;

/** Cluster-wide lookup of one live session. */
public final class GetSessionTool
    implements KyuubiMcpTool<GetSessionTool.Args, GetSessionTool.Response> {

  public static final String NAME = "get_session";
  private static final String INCOMPLETE =
      "The resource could not be resolved because the cluster lookup was incomplete.";
  private static final String INACCESSIBLE = "The resource does not exist or is not accessible.";
  private final KyuubiMcpDiagnostics diagnostics;

  public GetSessionTool(KyuubiMcpDiagnostics diagnostics) {
    this.diagnostics = diagnostics;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "Get a live session by its stable identifier from any server in the cluster.";
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
    Response response =
        diagnostics.response(
            diagnostics.getSession(Map.of("session_id", arguments.sessionId()), caller),
            Response.class);
    if (response.found()) {
      return KyuubiMcpTool.Result.success(response);
    }
    return response.partial()
        ? KyuubiMcpTool.Result.error(INCOMPLETE, response)
        : KyuubiMcpTool.Result.error(INACCESSIBLE);
  }

  public record Args(
      @JsonProperty(value = "session_id", required = true)
          @JsonPropertyDescription("Stable Kyuubi session identifier.")
          @McpToolProperty(minLength = 1, maxLength = 128, pattern = "^[A-Za-z0-9_-]+$")
          String sessionId) {}

  public record Response(
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "True when an accessible session was found on a responded server.")
          boolean found,
      @JsonPropertyDescription("Session details when found; null otherwise.")
          @McpToolProperty(nullable = true)
          Session session,
      @JsonPropertyDescription("Server that owns the session when found; null otherwise.")
          @McpToolProperty(nullable = true)
          String server,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "True when discovery failed or a discovered server failed or was omitted.")
          boolean partial,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Failures that made this lookup incomplete.")
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
      @JsonProperty(required = true) @JsonPropertyDescription("Scope covered by this lookup.")
          CountScope countScope,
      @JsonProperty(required = true)
          @JsonPropertyDescription("UTC time when this lookup completed.")
          @McpToolProperty(format = "date-time")
          String observedAt) {}

  public record Session(
      @JsonProperty(required = true) @JsonPropertyDescription("Stable Kyuubi session identifier.")
          String identifier,
      @JsonProperty(required = true) @JsonPropertyDescription("Session owner.") String user,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Session creation time as Unix epoch milliseconds.")
          long createTime,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Session age in milliseconds at observation time.")
          long duration,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Session idle duration in milliseconds at observation time.")
          long idleTime,
      @JsonProperty(required = true) @JsonPropertyDescription("Kyuubi session type.")
          String sessionType,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Kyuubi Server instance that owns the session.")
          String kyuubiInstance,
      @JsonPropertyDescription("Associated engine identifier when available.")
          @McpToolProperty(nullable = true)
          String engineId,
      @JsonPropertyDescription("Associated engine name when available.")
          @McpToolProperty(nullable = true)
          String engineName,
      @JsonPropertyDescription("Associated engine URL when available.")
          @McpToolProperty(nullable = true)
          String engineUrl,
      @JsonProperty(required = true) @JsonPropertyDescription("Operations created in this session.")
          int totalOperations) {}

  public record PeerFailure(
      @JsonProperty(required = true)
          @JsonPropertyDescription("Server address or discovery scope associated with the failure.")
          String server,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Bounded transport, deadline, or discovery failure category.")
          String reason) {}

  public enum CountScope {
    all_discovered_servers,
    responded_servers_only
  }
}
