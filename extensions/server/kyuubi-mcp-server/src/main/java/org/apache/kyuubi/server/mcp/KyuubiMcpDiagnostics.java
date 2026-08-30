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

package org.apache.kyuubi.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.apache.kyuubi.server.KyuubiRestFrontendService;
import org.apache.kyuubi.server.diagnostics.ClusterDiagnosticService;
import org.apache.kyuubi.server.diagnostics.DiagnosticPrincipal;
import org.apache.kyuubi.server.mcp.tool.KyuubiMcpTool;
import scala.Tuple2;

/** Java-facing adapter for the protocol-neutral cluster diagnostic service. */
public final class KyuubiMcpDiagnostics implements AutoCloseable {

  private final ObjectMapper objectMapper;
  private final ClusterDiagnosticService delegate;

  public KyuubiMcpDiagnostics(
      KyuubiRestFrontendService frontendService, ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.delegate = new ClusterDiagnosticService(frontendService, objectMapper);
  }

  public Map<String, Object> clusterOverview(
      Map<String, Object> arguments, KyuubiMcpTool.Caller caller) {
    return delegate.clusterOverview(toScala(arguments), principal(caller));
  }

  public Map<String, Object> listServers(KyuubiMcpTool.Caller caller) {
    return delegate.listServers(principal(caller));
  }

  public Map<String, Object> serverRuntime(
      Map<String, Object> arguments, KyuubiMcpTool.Caller caller) {
    return delegate.serverRuntime(toScala(arguments), principal(caller));
  }

  public Map<String, Object> listEngines(
      Map<String, Object> arguments, KyuubiMcpTool.Caller caller) {
    return delegate.listEngines(toScala(arguments), principal(caller));
  }

  public Map<String, Object> listSessions(
      Map<String, Object> arguments, KyuubiMcpTool.Caller caller) {
    return delegate.listSessions(toScala(arguments), principal(caller));
  }

  public Map<String, Object> getSession(
      Map<String, Object> arguments, KyuubiMcpTool.Caller caller) {
    return delegate.getSession(toScala(arguments), principal(caller));
  }

  public Map<String, Object> listOperations(
      Map<String, Object> arguments, KyuubiMcpTool.Caller caller) {
    return delegate.listOperations(toScala(arguments), principal(caller));
  }

  public Map<String, Object> getOperation(
      Map<String, Object> arguments, KyuubiMcpTool.Caller caller) {
    return delegate.getOperation(toScala(arguments), principal(caller));
  }

  public Map<String, Object> readOperationLog(
      Map<String, Object> arguments, KyuubiMcpTool.Caller caller) {
    return delegate.readOperationLog(toScala(arguments), principal(caller));
  }

  public Map<String, Object> listServerLogs(
      Map<String, Object> arguments, KyuubiMcpTool.Caller caller) {
    return delegate.listServerLogs(toScala(arguments), principal(caller));
  }

  public Map<String, Object> readServerLog(
      Map<String, Object> arguments, KyuubiMcpTool.Caller caller) {
    return delegate.readServerLog(toScala(arguments), principal(caller));
  }

  public <R> R response(Map<String, Object> value, Class<R> responseType) {
    return objectMapper.convertValue(value, responseType);
  }

  @Override
  public void close() {
    delegate.close();
  }

  private static DiagnosticPrincipal principal(KyuubiMcpTool.Caller caller) {
    return new DiagnosticPrincipal(caller.realUser(), caller.clientIp(), caller.administrator());
  }

  private static scala.collection.immutable.Map<String, Object> toScala(
      Map<String, Object> arguments) {
    scala.collection.immutable.Map<String, Object> result =
        scala.collection.immutable.Map$.MODULE$.empty();
    for (Map.Entry<String, Object> entry : arguments.entrySet()) {
      result = result.$plus(new Tuple2<>(entry.getKey(), entry.getValue()));
    }
    return result;
  }
}
