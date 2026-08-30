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

/** Cluster-wide listing of protected Kyuubi Server log files. */
public final class ListServerLogsTool
    implements KyuubiMcpTool<ListServerLogsTool.Args, ListServerLogsTool.Response> {

  public static final String NAME = "list_server_logs";
  private final KyuubiMcpDiagnostics diagnostics;

  public ListServerLogsTool(KyuubiMcpDiagnostics diagnostics) {
    this.diagnostics = diagnostics;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "List allowlisted Kyuubi Server log files on responded cluster nodes. Check partial and "
        + "failedServers before concluding that logs are absent. Administrators only.";
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
          "Listing Kyuubi Server logs requires administrator permission.");
    }
    Map<String, Object> values = new HashMap<>();
    if (arguments.contains() != null) values.put("contains", arguments.contains());
    if (arguments.limit() != null) values.put("limit", arguments.limit());
    return KyuubiMcpTool.Result.success(
        diagnostics.response(diagnostics.listServerLogs(values, caller), Response.class));
  }

  public record Args(
      @JsonProperty("contains")
          @JsonPropertyDescription(
              "Optional literal, case-insensitive file-name filter; this is not a regular expression.")
          @McpToolProperty(minLength = 1, maxLength = 128)
          String contains,
      @JsonProperty("limit")
          @JsonPropertyDescription("Maximum log files returned across responded servers.")
          @McpToolProperty(minimum = 1, maximum = 200)
          Integer limit) {}

  public record Response(
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Protected log files found under KYUUBI_LOG_DIR; symlinks and files outside the sandbox are excluded.")
          List<LogFile> serverLogs,
      @JsonProperty(required = true) @JsonPropertyDescription("Number of returned log files.")
          int count,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Responded servers where KYUUBI_LOG_DIR enabled protected file-log access.")
          int enabledServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription("True when the result does not cover every discovered server.")
          boolean partial,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Failures that made this result incomplete.")
          List<PeerFailure> failedServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Distinct Kyuubi Server registrations found by HA discovery.")
          int discoveredServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Servers that successfully returned this diagnostic result.")
          int respondedServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription("UTC time when this observation completed.")
          @McpToolProperty(format = "date-time")
          String observedAt) {}

  public record LogFile(
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Opaque identifier accepted by read_server_log; it is not a filesystem path.")
          String logId,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Log file name relative to the protected log root.")
          String name,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Kyuubi Server instance that owns the file.")
          String server,
      @JsonProperty(required = true)
          @JsonPropertyDescription("File size in bytes at discovery time.")
          long sizeBytes,
      @JsonProperty(required = true)
          @JsonPropertyDescription("UTC file modification time.")
          @McpToolProperty(format = "date-time")
          String lastModified) {}

  public record PeerFailure(
      @JsonProperty(required = true)
          @JsonPropertyDescription("Server address or discovery scope associated with the failure.")
          String server,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Bounded transport, deadline, or discovery failure category.")
          String reason) {}
}
