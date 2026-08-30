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

/** HA service-discovery view of registered Kyuubi engines. */
public final class ListEnginesTool
    implements KyuubiMcpTool<ListEnginesTool.Args, ListEnginesTool.Response> {

  public static final String NAME = "list_engines";
  private final KyuubiMcpDiagnostics diagnostics;

  public ListEnginesTool(KyuubiMcpDiagnostics diagnostics) {
    this.diagnostics = diagnostics;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "List all engine registrations visible to the authenticated user from HA service "
        + "discovery. Optional filters narrow the result. A registration does not prove process "
        + "reachability.";
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
    put(values, "engine_type", arguments.engineType());
    put(values, "share_level", arguments.shareLevel());
    put(values, "subdomain", arguments.subdomain());
    put(values, "limit", arguments.limit());
    return KyuubiMcpTool.Result.success(
        diagnostics.response(diagnostics.listEngines(values, caller), Response.class));
  }

  private static void put(Map<String, Object> values, String name, Object value) {
    if (value != null) {
      values.put(name, value instanceof Enum<?> ? value.toString() : value);
    }
  }

  public record Args(
      @JsonProperty("user")
          @JsonPropertyDescription(
              "Optional owner filter. Regular users can inspect only their own user-scoped registrations; administrators search all owners when omitted.")
          @McpToolProperty(maxLength = 256)
          String user,
      @JsonProperty("engine_type")
          @JsonPropertyDescription(
              "Optional engine type filter; all types are searched by default.")
          EngineType engineType,
      @JsonProperty("share_level")
          @JsonPropertyDescription(
              "Optional engine share-level filter; all levels are searched by default.")
          ShareLevel shareLevel,
      @JsonProperty("subdomain")
          @JsonPropertyDescription("Optional registration namespace filter.")
          @McpToolProperty(maxLength = 256)
          String subdomain,
      @JsonProperty("limit")
          @JsonPropertyDescription("Maximum registrations returned from service discovery.")
          @McpToolProperty(minimum = 1, maximum = 200)
          Integer limit) {}

  public record Response(
      @JsonProperty(required = true)
          @JsonPropertyDescription("Whether HA service discovery is configured for this lookup.")
          boolean discoveryEnabled,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Matching registry records; they do not prove process reachability.")
          List<Engine> engines,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Number of engine registrations returned, not a hidden total.")
          int count,
      @JsonProperty(required = true)
          @JsonPropertyDescription("True when additional namespaces or registrations may exist.")
          boolean truncated,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "True when service discovery failed or the result was truncated.")
          boolean partial,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Service-discovery failures that made the result incomplete.")
          List<PeerFailure> failedServers,
      @JsonProperty(required = true)
          @JsonPropertyDescription("UTC time when this registry observation completed.")
          @McpToolProperty(format = "date-time")
          String observedAt) {}

  public record Engine(
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "User, group, or server identity that owns this registration namespace.")
          String owner,
      @JsonProperty(required = true) @JsonPropertyDescription("Registered engine type.")
          EngineType engineType,
      @JsonProperty(required = true) @JsonPropertyDescription("Registered engine share level.")
          ShareLevel shareLevel,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Registration namespace within the owner and share level.")
          String namespace,
      @JsonProperty(required = true)
          @JsonPropertyDescription(
              "Registered engine address; it does not prove process reachability.")
          String address,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Kyuubi version encoded in the registration.")
          String version) {}

  public record PeerFailure(
      @JsonProperty(required = true)
          @JsonPropertyDescription("Discovery scope associated with the failure.")
          String server,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Bounded service-discovery failure category.")
          String reason) {}

  public enum EngineType {
    SPARK_SQL,
    FLINK_SQL,
    HIVE_SQL,
    TRINO,
    JDBC,
    DATA_AGENT
  }

  public enum ShareLevel {
    CONNECTION,
    USER,
    GROUP,
    SERVER_LOCAL,
    SERVER
  }
}
