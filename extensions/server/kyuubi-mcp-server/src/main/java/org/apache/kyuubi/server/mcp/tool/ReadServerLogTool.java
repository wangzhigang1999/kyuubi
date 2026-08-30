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

/** Bounded, redacted reader for one protected Kyuubi Server log. */
public final class ReadServerLogTool
    implements KyuubiMcpTool<ReadServerLogTool.Args, ReadServerLogTool.Response> {

  public static final String NAME = "read_server_log";
  private static final String INCOMPLETE =
      "The resource could not be resolved because the cluster lookup was incomplete.";
  private static final String INACCESSIBLE = "The resource does not exist or is not accessible.";
  private final KyuubiMcpDiagnostics diagnostics;

  public ReadServerLogTool(KyuubiMcpDiagnostics diagnostics) {
    this.diagnostics = diagnostics;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "Read a bounded, redacted tail of an allowlisted server log. Administrators only.";
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
          "Reading Kyuubi Server logs requires administrator permission.");
    }
    Map<String, Object> values = new HashMap<>();
    values.put("log_id", arguments.logId());
    if (arguments.maxLines() != null) values.put("max_lines", arguments.maxLines());
    if (arguments.maxBytes() != null) values.put("max_bytes", arguments.maxBytes());
    if (arguments.contains() != null) values.put("contains", arguments.contains());
    Response response =
        diagnostics.response(diagnostics.readServerLog(values, caller), Response.class);
    if (response.found()) {
      return KyuubiMcpTool.Result.success(response);
    }
    return response.partial()
        ? KyuubiMcpTool.Result.error(INCOMPLETE, response)
        : KyuubiMcpTool.Result.error(INACCESSIBLE);
  }

  public record Args(
      @JsonProperty(value = "log_id", required = true)
          @JsonPropertyDescription(
              "Opaque identifier returned by list_server_logs; filesystem paths are never accepted.")
          @McpToolProperty(minLength = 43, maxLength = 43, pattern = "^[A-Za-z0-9_-]{43}$")
          String logId,
      @JsonProperty("max_lines")
          @JsonPropertyDescription("Maximum returned log lines; defaults to 200.")
          @McpToolProperty(minimum = 1, maximum = 1000)
          Integer maxLines,
      @JsonProperty("max_bytes")
          @JsonPropertyDescription("Maximum bytes scanned from the file tail; defaults to 65536.")
          @McpToolProperty(minimum = 1, maximum = 262144)
          Integer maxBytes,
      @JsonProperty("contains")
          @JsonPropertyDescription(
              "Optional literal, case-insensitive line filter; this is not a regular expression.")
          @McpToolProperty(minLength = 1, maxLength = 128)
          String contains) {}

  public record Response(
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "True when the opaque log identifier resolved on a responded server.")
          boolean found,
      @JsonPropertyDescription("Bounded, redacted server-log tail when found; null otherwise.")
          @McpToolProperty(nullable = true)
          ServerLog serverLog,
      @JsonPropertyDescription("Server that owns the log when found; null otherwise.")
          @McpToolProperty(nullable = true)
          String server,
      @JsonProperty(required = true)
          @JsonPropertyDescription("True when the result does not cover every discovered server.")
          boolean partial,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Failures that made this lookup incomplete.")
          List<PeerFailure> failedServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Distinct Kyuubi Server registrations found by HA discovery.")
          int discoveredServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Servers that successfully returned this diagnostic result.")
          int respondedServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription("UTC time when this lookup completed.")
          @McpToolProperty(format = "date-time")
          String observedAt) {}

  public record ServerLog(
      @JsonProperty(required = true) @JsonPropertyDescription("Opaque server log identifier.")
          String logId,
      @JsonProperty(required = true) @JsonPropertyDescription("Protected server log file name.")
          String name,
      @JsonProperty(required = true) @JsonPropertyDescription("Kyuubi Server that owns the file.")
          String server,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Requested maximum number of returned lines.")
          int maxLines,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Returned, filtered, and redacted log lines.")
          List<String> lines,
      @JsonProperty(required = true) @JsonPropertyDescription("Number of returned lines.")
          int count,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Requested maximum bytes scanned from the file tail.")
          int maxBytes,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "True when additional source lines or bytes were excluded by a bound.")
          boolean truncated,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Always true: credential-like values are removed before return.")
          boolean redacted) {}

  public record PeerFailure(
      @JsonProperty(required = true)
          @JsonPropertyDescription("Server address or discovery scope associated with the failure.")
          String server,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Bounded transport, deadline, or discovery failure category.")
          String reason) {}
}
