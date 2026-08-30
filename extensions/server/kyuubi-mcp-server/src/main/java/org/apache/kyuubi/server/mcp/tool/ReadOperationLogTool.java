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

/** Bounded and redacted operation-log reader. */
public final class ReadOperationLogTool
    implements KyuubiMcpTool<ReadOperationLogTool.Args, ReadOperationLogTool.Response> {

  public static final String NAME = "read_operation_log";
  private static final String INCOMPLETE =
      "The resource could not be resolved because the cluster lookup was incomplete.";
  private static final String INACCESSIBLE = "The resource does not exist or is not accessible.";
  private final KyuubiMcpDiagnostics diagnostics;

  public ReadOperationLogTool(KyuubiMcpDiagnostics diagnostics) {
    this.diagnostics = diagnostics;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "Read a bounded portion of an accessible live operation log from any cluster server.";
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
    put(values, "max_rows", arguments.maxRows());
    put(values, "max_bytes", arguments.maxBytes());
    put(values, "regex", arguments.regex());
    Response response =
        diagnostics.response(diagnostics.readOperationLog(values, caller), Response.class);
    if (response.found()) {
      return KyuubiMcpTool.Result.success(response);
    }
    return response.partial()
        ? KyuubiMcpTool.Result.error(INCOMPLETE, response)
        : KyuubiMcpTool.Result.error(INACCESSIBLE);
  }

  private static void put(Map<String, Object> values, String name, Object value) {
    if (value != null) {
      values.put(name, value);
    }
  }

  public record Args(
      @JsonProperty(value = "operation_id", required = true)
          @JsonPropertyDescription("Stable Kyuubi operation identifier.")
          @McpToolProperty(minLength = 1, maxLength = 128, pattern = "^[A-Za-z0-9_-]+$")
          String operationId,
      @JsonProperty("max_rows")
          @JsonPropertyDescription("Maximum returned log lines; defaults to 100.")
          @McpToolProperty(minimum = 1, maximum = 1000)
          Integer maxRows,
      @JsonProperty("max_bytes")
          @JsonPropertyDescription("Maximum returned UTF-8 log bytes; defaults to 65536.")
          @McpToolProperty(minimum = 1, maximum = 262144)
          Integer maxBytes,
      @JsonProperty("regex")
          @JsonPropertyDescription(
              "Optional Java regular expression matched against each line. Matching is "
                  + "case-sensitive unless the expression uses an inline flag such as (?i).")
          @McpToolProperty(minLength = 1, maxLength = 256)
          String regex) {}

  public record Response(
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "True when an accessible operation log was found on a responded server.")
          boolean found,
      @JsonPropertyDescription(
              "Bounded, redacted operation-log snapshot when found; null otherwise.")
          @McpToolProperty(nullable = true)
          OperationLog operationLog,
      @JsonPropertyDescription("Server that owns the operation when found; null otherwise.")
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

  public record OperationLog(
      @JsonProperty(required = true)
          @JsonPropertyDescription("Operation whose in-memory log snapshot was read.")
          String operationId,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Kyuubi Server that owns the operation.")
          String kyuubiInstance,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Requested maximum number of returned lines.")
          int maxRows,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Returned, filtered, and redacted log lines.")
          List<String> lines,
      @JsonProperty(required = true) @JsonPropertyDescription("Number of returned lines.")
          int count,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Requested maximum returned UTF-8 bytes.")
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
