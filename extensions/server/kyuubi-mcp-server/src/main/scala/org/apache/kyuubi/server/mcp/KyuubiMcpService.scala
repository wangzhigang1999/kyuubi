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

import java.util.{Collections, ServiceLoader}
import java.util.function.BiFunction

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.server.McpStatelessSyncServer
import io.modelcontextprotocol.spec.McpSchema

import org.apache.kyuubi.{KYUUBI_VERSION, Logging}
import org.apache.kyuubi.server.KyuubiRestFrontendService

private[server] class KyuubiMcpService(frontendService: KyuubiRestFrontendService)
  extends Logging {

  import KyuubiMcpService._

  private val objectMapper = new ObjectMapper()
  private val jsonMapper = new JacksonMcpJsonMapper(objectMapper)
  val transport = new KyuubiMcpHttpTransport(jsonMapper, requestId => requestContext(requestId))
  private val tools = new KyuubiMcpTools(frontendService, objectMapper, jsonMapper)
  private val pluginContext = new KyuubiMcpToolProvider.Context(
    frontendService,
    objectMapper,
    jsonMapper)
  private val pluginProviders = loadPluginProviders()
  private val specifications = validateUniqueNames(
    tools.specifications ++ pluginProviders.flatMap(loadPluginTools))
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
      : Seq[McpStatelessServerFeatures.SyncToolSpecification] = {
    val providedTools = Option(provider.tools(pluginContext)).getOrElse {
      throw new IllegalArgumentException(
        s"MCP tool provider ${provider.getClass.getName} returned null")
    }
    providedTools.asScala.toSeq.map { providedTool =>
      if (providedTool == null) {
        throw new IllegalArgumentException(
          s"MCP tool provider ${provider.getClass.getName} returned a null tool")
      }
      validatePluginDefinition(provider, providedTool.definition())
      val raw = McpStatelessServerFeatures.SyncToolSpecification.builder()
        .tool(providedTool.definition())
        .callHandler(new BiFunction[
          McpTransportContext,
          McpSchema.CallToolRequest,
          McpSchema.CallToolResult]() {
          override def apply(
              context: McpTransportContext,
              request: McpSchema.CallToolRequest): McpSchema.CallToolResult = {
            val caller = new KyuubiMcpToolProvider.Caller(
              auditContextValue(context, REQUEST_ID),
              auditContextValue(context, REAL_USER),
              auditContextValue(context, CLIENT_IP),
              java.lang.Boolean.TRUE == context.get(ADMINISTRATOR))
            val arguments = Option(request.arguments())
              .getOrElse(Collections.emptyMap[String, Object]())
            providedTool.handler().apply(caller, arguments)
          }
        })
        .build()
      tools.instrument(raw)
    }
  }

  private def validatePluginDefinition(
      provider: KyuubiMcpToolProvider,
      definition: McpSchema.Tool): Unit = {
    val providerName = provider.getClass.getName
    require(
      definition.name() != null && definition.name().nonEmpty,
      s"MCP tool provider $providerName returned a tool without a name")
    require(
      definition.inputSchema() != null,
      s"MCP plugin tool ${definition.name()} from $providerName requires an input schema")
    require(
      definition.outputSchema() != null,
      s"MCP plugin tool ${definition.name()} from $providerName requires an output schema")
    val annotations = definition.annotations()
    require(
      annotations != null && java.lang.Boolean.TRUE == annotations.readOnlyHint(),
      s"MCP plugin tool ${definition.name()} from $providerName must declare readOnlyHint=true")
    require(
      java.lang.Boolean.TRUE != annotations.destructiveHint(),
      s"MCP plugin tool ${definition.name()} from $providerName must not be destructive")
  }

  private def validateUniqueNames(
      values: Seq[McpStatelessServerFeatures.SyncToolSpecification])
      : Seq[McpStatelessServerFeatures.SyncToolSpecification] = {
    val duplicateNames = values.groupBy(_.tool().name()).collect {
      case (name, matches) if matches.size > 1 => name
    }.toSeq.sorted
    require(duplicateNames.isEmpty, s"Duplicate MCP tool names: ${duplicateNames.mkString(", ")}")
    values
  }

  private def auditContextValue(context: McpTransportContext, name: String): String =
    Option(context.get(name)).map { value =>
      value.toString.iterator
        .map(character => if (Character.isISOControl(character)) '?' else character)
        .take(MAX_CALLER_VALUE_LENGTH)
        .mkString
    }.getOrElse("")

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
  private val MAX_CALLER_VALUE_LENGTH = 128
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
