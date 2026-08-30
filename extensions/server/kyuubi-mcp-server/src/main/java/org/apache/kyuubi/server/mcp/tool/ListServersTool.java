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
import org.apache.kyuubi.server.mcp.KyuubiMcpDiagnostics;
import org.apache.kyuubi.server.mcp.McpToolProperty;

/** Cluster-wide Kyuubi Server discovery and reachability probe. */
public final class ListServersTool
    implements KyuubiMcpTool<ListServersTool.Args, ListServersTool.Response> {

  public static final String NAME = "list_servers";
  private final KyuubiMcpDiagnostics diagnostics;

  public ListServersTool(KyuubiMcpDiagnostics diagnostics) {
    this.diagnostics = diagnostics;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "List reachable Kyuubi Server instances across the cluster. A failed response does not "
        + "prove that the process stopped. Administrators only.";
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
    if (!caller.administrator()) {
      return KyuubiMcpTool.Result.denied(
          "Listing Kyuubi servers requires administrator permission.");
    }
    return KyuubiMcpTool.Result.success(
        diagnostics.response(diagnostics.listServers(caller), Response.class));
  }

  public record Args() {}

  public record Response(
      @JsonProperty(required = true)
          @JsonPropertyDescription("Servers that answered the runtime probe during this call.")
          List<Server> servers,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Number of servers that answered this call.")
          int count,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "True when the result does not cover every discovered server. Empty results then cover responded servers only.")
          boolean partial,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Failures that made this result incomplete; a failure does not prove a process stopped.")
          List<PeerFailure> failedServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Distinct Kyuubi Server registrations found by HA discovery; registration does not prove reachability.")
          int discoveredServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Servers that successfully returned this diagnostic result.")
          int respondedServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "UTC time when this aggregated point-in-time observation completed.")
          @McpToolProperty(format = "date-time")
          String observedAt) {}

  public record Server(
      @JsonProperty(required = true)
          @JsonPropertyDescription("Kyuubi Server diagnostic address that answered the probe.")
          String instance,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Running means the server answered this probe; it is not a full service health check.")
          Status status) {}

  public record PeerFailure(
      @JsonProperty(required = true)
          @JsonPropertyDescription("Server address or discovery scope associated with the failure.")
          String server,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Bounded transport, deadline, or discovery failure category.")
          String reason) {}

  public enum Status {
    Running
  }
}
