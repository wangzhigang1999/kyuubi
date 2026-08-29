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

package org.apache.kyuubi.server.mcp

import scala.collection.JavaConverters._

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.server.McpStatelessSyncServer

import org.apache.kyuubi.KYUUBI_VERSION
import org.apache.kyuubi.server.KyuubiRestFrontendService

private[server] class KyuubiMcpService(frontendService: KyuubiRestFrontendService) {

  import KyuubiMcpService._

  private val objectMapper = new ObjectMapper()
  private val jsonMapper = new JacksonMcpJsonMapper(objectMapper)
  val transport = new KyuubiMcpHttpTransport(jsonMapper, () => requestContext())
  private val tools = new KyuubiMcpTools(frontendService, objectMapper, jsonMapper)
  private val server: McpStatelessSyncServer = McpServer.sync(transport)
    .serverInfo("Apache Kyuubi", KYUUBI_VERSION)
    .instructions(SERVER_INSTRUCTIONS)
    .strictToolNameValidation(true)
    .validateToolInputs(true)
    .tools(tools.specifications.asJava)
    .build()

  def addTool(tool: McpStatelessServerFeatures.SyncToolSpecification): Unit = server.addTool(tool)

  def close(): Unit = {
    tools.close()
    server.closeGracefully()
  }

  private def requestContext(): McpTransportContext = {
    val realUser = frontendService.getRealUser()
    McpTransportContext.create(Map[String, Object](
      REAL_USER -> realUser,
      CLIENT_IP -> frontendService.getIpAddress,
      ADMINISTRATOR -> Boolean.box(frontendService.isAdministrator(realUser))).asJava)
  }
}

private[server] object KyuubiMcpService {
  private val SERVER_INSTRUCTIONS =
    "Apache Kyuubi read-only cluster monitoring and diagnosis. Prefer cluster-wide tools and " +
      "always inspect partial and failedServers before concluding that data is absent. Users can " +
      "only inspect their own sessions, operations, and logs; tools marked administrator-only " +
      "require Kyuubi administrator permission. Treat responses as point-in-time observations. " +
      "Never use this server to execute SQL, fetch query results, submit or cancel workloads, " +
      "change configuration, or access arbitrary files."

  val REAL_USER = "kyuubi.realUser"
  val CLIENT_IP = "kyuubi.clientIp"
  val ADMINISTRATOR = "kyuubi.administrator"
}
