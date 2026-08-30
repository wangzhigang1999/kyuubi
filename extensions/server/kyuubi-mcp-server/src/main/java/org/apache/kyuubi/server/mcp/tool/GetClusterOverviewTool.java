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

/** Cluster-wide session and operation health summary. */
public final class GetClusterOverviewTool
    implements KyuubiMcpTool<GetClusterOverviewTool.Args, GetClusterOverviewTool.Response> {

  public static final String NAME = "get_cluster_overview";
  private final KyuubiMcpDiagnostics diagnostics;

  public GetClusterOverviewTool(KyuubiMcpDiagnostics diagnostics) {
    this.diagnostics = diagnostics;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "Call first to summarize server reachability and live session and operation health. "
        + "When partial is true, counts cover responded servers only and zero never proves "
        + "cluster-wide absence.";
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
    if (arguments.user() != null) {
      values.put("user", arguments.user());
    }
    return KyuubiMcpTool.Result.success(
        diagnostics.response(diagnostics.clusterOverview(values, caller), Response.class));
  }

  public record Args(
      @JsonProperty("user")
          @JsonPropertyDescription(
              "User to inspect. A different user requires administrator permission.")
          @McpToolProperty(maxLength = 256)
          String user) {}

  public record Response(
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Sum of visible live sessions on responded servers; this is not cluster-wide when partial is true.")
          int sessionCount,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Visible live sessions grouped by type on responded servers.")
          Map<String, Integer> sessionTypes,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Sum of visible live operations on responded servers; this is not cluster-wide when partial is true.")
          int operationCount,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Visible live operations grouped by state on responded servers.")
          Map<String, Integer> operationStates,
      @JsonProperty(required = true)
          @JsonPropertyDescription("One point-in-time summary for each server that responded.")
          List<ServerSummary> servers,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "True when discovery failed or a discovered server failed or was omitted. Empty results then cover responded servers only.")
          boolean partial,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Failures that made this result incomplete; a failure does not prove a process stopped.")
          List<PeerFailure> failedServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Distinct server registrations discovered before the fanout limit; registration does not prove reachability.")
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
          @JsonPropertyDescription("Scope covered by the returned items and aggregate counts.")
          CountScope countScope,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "UTC time when this aggregated point-in-time observation completed.")
          @McpToolProperty(format = "date-time")
          String observedAt) {}

  public record ServerSummary(
      @JsonProperty(required = true) @JsonPropertyDescription("Kyuubi Server diagnostic address.")
          String server,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Visible live sessions on this server.")
          int sessionCount,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Visible sessions grouped by type on this server.")
          Map<String, Integer> sessionTypes,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Visible live operations on this server.")
          int operationCount,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Visible operations grouped by state on this server.")
          Map<String, Integer> operationStates) {}

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
