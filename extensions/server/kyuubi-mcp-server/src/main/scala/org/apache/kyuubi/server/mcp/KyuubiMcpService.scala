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

import java.util.ServiceLoader

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpStatelessSyncServer

import org.apache.kyuubi.{KYUUBI_VERSION, Logging}
import org.apache.kyuubi.server.KyuubiRestFrontendService

private[server] class KyuubiMcpService(frontendService: KyuubiRestFrontendService)
  extends Logging {

  import KyuubiMcpService._

  private val objectMapper = new ObjectMapper()
  private val jsonMapper = new JacksonMcpJsonMapper(objectMapper)
  val transport = new KyuubiMcpHttpTransport(jsonMapper, requestId => requestContext(requestId))
  private val tools = new KyuubiMcpTools(frontendService, objectMapper, jsonMapper)
  private val pluginContext = new KyuubiMcpToolProvider.Context(frontendService, objectMapper)
  private val pluginProviders = loadPluginProviders()
  private val allTools = validateUniqueNames(
    tools.builtIns ++ pluginProviders.flatMap(loadPluginTools))
  private val specifications = allTools.map(tools.specification)
  private val server: McpStatelessSyncServer = McpServer.sync(transport)
    .serverInfo("Apache Kyuubi", KYUUBI_VERSION)
    .instructions(SERVER_INSTRUCTIONS)
    .strictToolNameValidation(true)
    .validateToolInputs(true)
    .tools(specifications.asJava)
    .build()

  def close(): Unit = {
    server.closeGracefully()
    pluginProviders.foreach { provider =>
      try {
        provider.close()
      } catch {
        case NonFatal(e) =>
          warn(s"Failed to close MCP tool provider ${provider.getClass.getName}", e)
      }
    }
    tools.close()
  }

  private def loadPluginProviders(): Seq[KyuubiMcpToolProvider] = {
    val contextClassLoader = Option(Thread.currentThread().getContextClassLoader)
      .getOrElse(getClass.getClassLoader)
    val providers = ServiceLoader.load(classOf[KyuubiMcpToolProvider], contextClassLoader)
      .iterator().asScala.toSeq.sortBy(_.getClass.getName)
    providers.foreach(provider => info(s"Loaded MCP tool provider ${provider.getClass.getName}"))
    providers
  }

  private def loadPluginTools(
      provider: KyuubiMcpToolProvider)
      : Seq[KyuubiMcpToolProvider.Tool[_, _]] = {
    val providedTools = Option(provider.tools(pluginContext)).getOrElse {
      throw new IllegalArgumentException(
        s"MCP tool provider ${provider.getClass.getName} returned null")
    }
    providedTools.asScala.toSeq.map { providedTool =>
      if (providedTool == null) {
        throw new IllegalArgumentException(
          s"MCP tool provider ${provider.getClass.getName} returned a null tool")
      }
      providedTool
    }
  }

  private def validateUniqueNames(
      values: Seq[KyuubiMcpToolProvider.Tool[_, _]])
      : Seq[KyuubiMcpToolProvider.Tool[_, _]] = {
    val duplicateNames = values.groupBy(_.name()).collect {
      case (name, matches) if matches.size > 1 => name
    }.toSeq.sorted
    require(duplicateNames.isEmpty, s"Duplicate MCP tool names: ${duplicateNames.mkString(", ")}")
    values
  }

  private def requestContext(requestId: Object): McpTransportContext = {
    val realUser = frontendService.getRealUser()
    McpTransportContext.create(Map[String, Object](
      REQUEST_ID -> Option(requestId).map(_.toString).getOrElse(""),
      REAL_USER -> realUser,
      CLIENT_IP -> frontendService.getIpAddress,
      ADMINISTRATOR -> Boolean.box(frontendService.isAdministrator(realUser))).asJava)
  }
}

private[server] object KyuubiMcpService {
  private val SERVER_INSTRUCTIONS =
    "Apache Kyuubi read-only cluster monitoring and diagnosis. Call get_cluster_overview first, " +
      "then make targeted follow-up calls; do not launch exhaustive cluster-wide tools in " +
      "parallel. Always inspect partial, countScope, respondedServers, and failedServers. When " +
      "partial is true, empty results and zero counts describe responded servers only. Do not " +
      "infer TCP connectivity, process termination, GC pressure, dependency health, SQL " +
      "availability, or workload loss from a timeout or incomplete response. Users can " +
      "only inspect their own sessions, operations, and logs; tools marked administrator-only " +
      "require Kyuubi administrator permission. Treat responses as point-in-time observations. " +
      "Never use this server to execute SQL, fetch query results, submit or cancel workloads, " +
      "change configuration, or access arbitrary files."

  val REAL_USER = "kyuubi.realUser"
  val CLIENT_IP = "kyuubi.clientIp"
  val ADMINISTRATOR = "kyuubi.administrator"
  val REQUEST_ID = "kyuubi.requestId"
}
