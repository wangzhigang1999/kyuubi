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
import org.apache.kyuubi.server.mcp.KyuubiMcpToolProvider;
import org.apache.kyuubi.server.mcp.McpToolProperty;

/** Bounded JVM and host runtime observations from every responding server. */
public final class GetServerRuntimeTool
    implements KyuubiMcpToolProvider.Tool<
        GetServerRuntimeTool.Args, GetServerRuntimeTool.Response> {

  public static final String NAME = "get_server_runtime";
  private final KyuubiMcpDiagnostics diagnostics;

  public GetServerRuntimeTool(KyuubiMcpDiagnostics diagnostics) {
    this.diagnostics = diagnostics;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "Inspect bounded JVM, memory, thread, uptime, and host-load metrics on every reachable "
        + "Kyuubi Server. Administrators only.";
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
    if (!caller.administrator()) {
      return KyuubiMcpToolProvider.Result.denied(
          "Inspecting Kyuubi Server runtime metrics requires administrator permission.");
    }
    return KyuubiMcpToolProvider.Result.success(
        diagnostics.response(diagnostics.serverRuntime(caller), Response.class));
  }

  public record Args() {}

  public record Response(
      @JsonProperty(required = true)
          @JsonPropertyDescription("Point-in-time runtime snapshots from responded servers.")
          List<RuntimeSnapshot> serverRuntimes,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Number of returned runtime snapshots.")
          int count,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "True when discovery failed or a discovered server failed or was omitted.")
          boolean partial,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Failures that made this result incomplete; a failure does not prove a process stopped.")
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
          @JsonPropertyDescription("Scope covered by the returned snapshots and counts.")
          CountScope countScope,
      @JsonProperty(required = true)
          @JsonPropertyDescription("UTC time when this observation completed.")
          @McpToolProperty(format = "date-time")
          String observedAt) {}

  public record RuntimeSnapshot(
      @JsonProperty(required = true) @JsonPropertyDescription("Kyuubi Server diagnostic address.")
          String server,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Kyuubi version reported by this server.")
          String kyuubiVersion,
      @JsonProperty(required = true) @JsonPropertyDescription("Java runtime version.")
          String javaVersion,
      @JsonProperty(required = true) @JsonPropertyDescription("Java runtime vendor.")
          String javaVendor,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Java virtual machine implementation name.")
          String vmName,
      @JsonProperty(required = true)
          @JsonPropertyDescription("JVM start time as Unix epoch milliseconds.")
          long startTime,
      @JsonProperty(required = true) @JsonPropertyDescription("JVM uptime in milliseconds.")
          long uptimeMs,
      @JsonProperty(required = true) @JsonPropertyDescription("Processors available to the JVM.")
          int availableProcessors,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Currently used heap memory in bytes.")
          long heapUsedBytes,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Heap memory committed to the JVM in bytes.")
          long heapCommittedBytes,
      @JsonProperty(required = true) @JsonPropertyDescription("Maximum heap memory in bytes.")
          long heapMaxBytes,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Currently used non-heap memory in bytes.")
          long nonHeapUsedBytes,
      @JsonProperty(required = true) @JsonPropertyDescription("Committed non-heap memory in bytes.")
          long nonHeapCommittedBytes,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Live JVM thread count at observation time.")
          int liveThreads,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Live daemon thread count at observation time.")
          int daemonThreads,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Highest live thread count since JVM start; not a time-series trend.")
          int peakThreads,
      @JsonPropertyDescription(
              "Operating-system load average when supported; interpretation is platform dependent.")
          @McpToolProperty(nullable = true)
          Double systemLoadAverage) {}

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
