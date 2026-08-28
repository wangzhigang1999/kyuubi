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

import java.time.Instant
import java.util.Collections
import java.util.function.BiFunction

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.spec.McpSchema

import org.apache.kyuubi.{KYUUBI_VERSION, Logging}
import org.apache.kyuubi.client.api.v1.dto.Engine
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.engine.{EngineType, ShareLevel}
import org.apache.kyuubi.ha.HighAvailabilityConf.HA_NAMESPACE
import org.apache.kyuubi.ha.client.{DiscoveryPaths, ServiceDiscovery, ServiceNodeInfo}
import org.apache.kyuubi.ha.client.DiscoveryClientProvider.withDiscoveryClient
import org.apache.kyuubi.operation.OperationState
import org.apache.kyuubi.server.KyuubiRestFrontendService
import org.apache.kyuubi.session.SessionType

private[mcp] class KyuubiMcpTools(
    frontendService: KyuubiRestFrontendService,
    objectMapper: ObjectMapper,
    jsonMapper: McpJsonMapper) extends Logging {

  import KyuubiMcpLocalDiagnostics._
  import KyuubiMcpService._
  import KyuubiMcpTools._

  private val clusterDiagnostics =
    new KyuubiMcpClusterDiagnostics(frontendService, objectMapper)

  def specifications: Seq[McpStatelessServerFeatures.SyncToolSpecification] = Seq(
    tool(
      CLUSTER_OVERVIEW,
      "Summarize server reachability and live session and operation health across the cluster.",
      properties = Map(
        "user" -> boundedStringProperty("User to inspect. Administrators only."))) {
      (context, arguments) =>
        clusterListResult(context, arguments) {
          clusterDiagnostics.clusterOverview(arguments, principal(context))
        }
    },
    tool("list_servers", "List the live Kyuubi Server instances discovered by this cluster.") {
      (context, _) => listServers(context)
    },
    tool(
      "list_engines",
      "List live Kyuubi engines visible to the authenticated user across the cluster.",
      properties = Map(
        "user" -> boundedStringProperty("User to inspect. Administrators only."),
        "engine_type" -> enumProperty("Engine type.", EngineType.values.map(_.toString).toSeq),
        "share_level" -> enumProperty(
          "Engine share level.",
          ShareLevel.values.map(_.toString).toSeq),
        "subdomain" -> boundedStringProperty("Engine share-level subdomain."))) {
      (context, arguments) => listEngines(context, arguments)
    },
    tool(
      LIST_SESSIONS,
      "List live sessions visible to the authenticated user across the cluster.",
      properties = Map(
        "user" -> boundedStringProperty("User to inspect. Administrators only."),
        "session_type" -> enumProperty(
          "Session type.",
          SessionType.values.map(_.toString).toSeq),
        "limit" -> integerProperty("Maximum cluster-wide results.", 200))) {
      (context, arguments) =>
        clusterListResult(context, arguments) {
          clusterDiagnostics.listSessions(arguments, principal(context))
        }
    },
    tool(
      GET_SESSION,
      "Get a live session by its stable identifier from any server in the cluster.",
      properties = Map("session_id" -> identifierProperty("Kyuubi session identifier.")),
      required = Seq("session_id")) {
      (context, arguments) =>
        lookupResult(
          clusterDiagnostics.getSession(arguments, principal(context)),
          "session")
    },
    tool(
      LIST_OPERATIONS,
      "List live operations visible to the authenticated user across the cluster.",
      properties = Map(
        "user" -> boundedStringProperty("User to inspect. Administrators only."),
        "session_id" -> identifierProperty("Session identifier filter."),
        "state" -> enumProperty(
          "Operation state.",
          OperationState.values.map(_.toString).toSeq),
        "limit" -> integerProperty("Maximum cluster-wide results.", 200))) {
      (context, arguments) =>
        clusterListResult(context, arguments) {
          clusterDiagnostics.listOperations(arguments, principal(context))
        }
    },
    tool(
      GET_OPERATION,
      "Get a live operation by its stable identifier from any server in the cluster.",
      properties = Map("operation_id" -> identifierProperty("Kyuubi operation identifier.")),
      required = Seq("operation_id")) {
      (context, arguments) =>
        lookupResult(
          clusterDiagnostics.getOperation(arguments, principal(context)),
          "operation")
    },
    tool(
      READ_OPERATION_LOG,
      "Read a bounded portion of an accessible live operation log from any cluster server.",
      properties = Map(
        "operation_id" -> identifierProperty("Kyuubi operation identifier."),
        "max_rows" -> integerProperty("Maximum returned log lines.", 1000),
        "max_bytes" -> integerProperty("Maximum returned UTF-8 log bytes.", 256 * 1024),
        "contains" -> literalProperty("Literal case-insensitive line filter.")),
      required = Seq("operation_id")) {
      (context, arguments) =>
        lookupResult(
          clusterDiagnostics.readOperationLog(arguments, principal(context)),
          "operationLog")
    },
    tool(
      LIST_SERVER_LOGS,
      "List allowlisted Kyuubi Server log files on every cluster node. Administrators only.",
      properties = Map(
        "contains" -> literalProperty("Literal case-insensitive file name filter."),
        "limit" -> integerProperty("Maximum cluster-wide results.", 200))) {
      (context, arguments) =>
        if (!isAdministrator(context)) {
          accessDenied("Listing Kyuubi Server logs requires administrator permission.")
        } else {
          success(clusterDiagnostics.listServerLogs(arguments, principal(context)))
        }
    },
    tool(
      READ_SERVER_LOG,
      "Read a bounded, redacted tail of an allowlisted server log. Administrators only.",
      properties = Map(
        "log_id" -> opaqueLogIdProperty,
        "max_lines" -> integerProperty("Maximum returned log lines.", 1000),
        "max_bytes" -> integerProperty("Maximum bytes scanned from the file tail.", 256 * 1024),
        "contains" -> literalProperty("Literal case-insensitive line filter.")),
      required = Seq("log_id")) {
      (context, arguments) =>
        if (!isAdministrator(context)) {
          accessDenied("Reading Kyuubi Server logs requires administrator permission.")
        } else {
          lookupResult(
            clusterDiagnostics.readServerLog(arguments, principal(context)),
            "serverLog")
        }
    })

  def close(): Unit = clusterDiagnostics.close()

  private def listServers(context: McpTransportContext): McpSchema.CallToolResult = {
    if (!isAdministrator(context)) {
      return accessDenied("Listing Kyuubi servers requires administrator permission.")
    }

    val conf = frontendService.getConf
    val discoveryEnabled = ServiceDiscovery.supportServiceDiscovery(conf)
    val servers = if (discoveryEnabled) {
      val serverSpace = DiscoveryPaths.makePath(null, conf.get(HA_NAMESPACE))
      withDiscoveryClient(conf) { client =>
        client.getServiceNodesInfo(serverSpace).map(node =>
          Map[String, Object](
            "nodeName" -> node.nodeName,
            "instance" -> node.instance,
            "host" -> node.host,
            "port" -> Int.box(node.port),
            "status" -> "Running").asJava).asJava
      }
    } else {
      Collections.singletonList(Map(
        "instance" -> frontendService.connectionUrl,
        "status" -> "Running").asJava)
    }
    success(Map[String, Object](
      "discoveryEnabled" -> Boolean.box(discoveryEnabled),
      "servers" -> servers,
      "count" -> Int.box(servers.size()),
      "partial" -> Boolean.box(false),
      "failedServers" -> Collections.emptyList[Object](),
      "observedAt" -> Instant.now().toString).asJava)
  }

  private def listEngines(
      context: McpTransportContext,
      arguments: Map[String, AnyRef]): McpSchema.CallToolResult = {
    val requestedUser = stringArgument(arguments, "user")
    val effectiveUser = requestedUser match {
      case Some(user) if isAdministrator(context) => user
      case Some(user) if user != realUser(context) =>
        return accessDenied("The requested user is not accessible.")
      case _ => realUser(context)
    }

    val clonedConf = frontendService.getConf.clone
    stringArgument(arguments, "engine_type").foreach(clonedConf.set(ENGINE_TYPE, _))
    stringArgument(arguments, "share_level").foreach(clonedConf.set(ENGINE_SHARE_LEVEL, _))
    stringArgument(arguments, "subdomain")
      .foreach(value => clonedConf.set(ENGINE_SHARE_LEVEL_SUBDOMAIN, Some(value)))

    val engine = new Engine(
      KYUUBI_VERSION,
      effectiveUser,
      clonedConf.get(ENGINE_TYPE),
      clonedConf.get(ENGINE_SHARE_LEVEL),
      clonedConf.get(ENGINE_SHARE_LEVEL_SUBDOMAIN).getOrElse(""),
      null,
      clonedConf.get(HA_NAMESPACE),
      Collections.emptyMap())

    if (!ServiceDiscovery.supportServiceDiscovery(clonedConf)) {
      return success(engineResult(discoveryEnabled = false, Collections.emptyList[AnyRef]()))
    }

    val engineSpace = calculateEngineSpace(engine)
    val engineNodes = ListBuffer[ServiceNodeInfo]()
    withDiscoveryClient(clonedConf) { client =>
      stringArgument(arguments, "subdomain") match {
        case Some(_) => engineNodes ++= client.getServiceNodesInfo(engineSpace, silent = true)
        case None if !client.pathNonExists(engineSpace) =>
          client.getChildren(engineSpace).foreach { child =>
            engineNodes ++= client.getServiceNodesInfo(s"$engineSpace/$child", silent = true)
          }
        case _ =>
      }
    }
    val engines = engineNodes.map(node =>
      Map[String, Object](
        "version" -> engine.getVersion,
        "user" -> engine.getUser,
        "engineType" -> engine.getEngineType,
        "shareLevel" -> engine.getSharelevel,
        "subdomain" -> node.namespace.split("/").last,
        "instance" -> node.instance).asJava).asJava
    success(engineResult(discoveryEnabled = true, engines))
  }

  private def engineResult(
      discoveryEnabled: Boolean,
      engines: java.util.List[_]): java.util.Map[String, Object] =
    Map[String, Object](
      "discoveryEnabled" -> Boolean.box(discoveryEnabled),
      "engines" -> engines,
      "count" -> Int.box(engines.size()),
      "partial" -> Boolean.box(false),
      "failedServers" -> Collections.emptyList[Object](),
      "observedAt" -> Instant.now().toString).asJava

  private def calculateEngineSpace(engine: Engine): String = {
    val userOrGroup = engine.getSharelevel match {
      case "GROUP" => frontendService.sessionManager.groupProvider.primaryGroup(
          engine.getUser,
          frontendService.getConf.getAll.asJava)
      case _ => engine.getUser
    }
    val engineSpace =
      s"${engine.getNamespace}_${engine.getVersion}_${engine.getSharelevel}_${engine.getEngineType}"
    DiscoveryPaths.makePath(engineSpace, userOrGroup, engine.getSubdomain)
  }

  private def lookupResult(
      result: java.util.Map[String, Object],
      resultName: String): McpSchema.CallToolResult = {
    if (java.lang.Boolean.TRUE == result.get("found")) {
      success(result)
    } else if (java.lang.Boolean.TRUE == result.get("partial")) {
      failure(
        "The resource could not be resolved because the cluster lookup was incomplete.",
        result)
    } else {
      inaccessibleResource()
    }
  }

  private def clusterListResult(
      context: McpTransportContext,
      arguments: Map[String, AnyRef])(
      execute: => java.util.Map[String, Object]): McpSchema.CallToolResult = {
    stringArgument(arguments, "user") match {
      case Some(user) if user != realUser(context) && !isAdministrator(context) =>
        accessDenied("The requested user is not accessible.")
      case _ => success(execute)
    }
  }

  private def tool(
      name: String,
      description: String,
      properties: Map[String, Object] = Map.empty,
      required: Seq[String] = Seq.empty)(
      handler: (McpTransportContext, Map[String, AnyRef]) => McpSchema.CallToolResult)
      : McpStatelessServerFeatures.SyncToolSpecification = {
    val schema = Map[String, Object](
      "type" -> "object",
      "properties" -> properties.asJava,
      "required" -> required.asJava,
      "additionalProperties" -> Boolean.box(false)).asJava
    val definition = McpSchema.Tool.builder(name)
      .description(description)
      .inputSchema(schema)
      .annotations(READ_ONLY_ANNOTATIONS)
      .build()
    McpStatelessServerFeatures.SyncToolSpecification.builder()
      .tool(definition)
      .callHandler(new BiFunction[
        McpTransportContext,
        McpSchema.CallToolRequest,
        McpSchema.CallToolResult]() {
        override def apply(
            context: McpTransportContext,
            request: McpSchema.CallToolRequest): McpSchema.CallToolResult = {
          try {
            handler(context, Option(request.arguments()).map(_.asScala.toMap).getOrElse(Map.empty))
          } catch {
            case e: IllegalArgumentException => failure(e.getMessage)
            case NonFatal(e) =>
              error(s"MCP tool $name failed for ${realUser(context)}", e)
              failure("The tool could not complete the request.")
          }
        }
      })
      .build()
  }

  private def success(value: Object): McpSchema.CallToolResult =
    McpSchema.CallToolResult.builder()
      .addTextContent(jsonMapper.writeValueAsString(value))
      .structuredContent(value)
      .isError(Boolean.box(false))
      .build()

  private def failure(message: String): McpSchema.CallToolResult =
    McpSchema.CallToolResult.builder()
      .addTextContent(message)
      .isError(Boolean.box(true))
      .build()

  private def failure(message: String, value: Object): McpSchema.CallToolResult =
    McpSchema.CallToolResult.builder()
      .addTextContent(message)
      .structuredContent(value)
      .isError(Boolean.box(true))
      .build()

  private def accessDenied(message: String): McpSchema.CallToolResult = failure(message)

  private def inaccessibleResource(): McpSchema.CallToolResult =
    failure("The resource does not exist or is not accessible.")

  private def principal(context: McpTransportContext): KyuubiMcpPrincipal =
    KyuubiMcpPrincipal(
      realUser(context),
      Option(context.get(CLIENT_IP)).map(_.toString).getOrElse(""),
      isAdministrator(context))

  private def realUser(context: McpTransportContext): String =
    Option(context.get(REAL_USER)).map(_.toString).getOrElse("anonymous")

  private def isAdministrator(context: McpTransportContext): Boolean =
    java.lang.Boolean.TRUE == context.get(ADMINISTRATOR)
}

private[mcp] object KyuubiMcpTools {
  private val READ_ONLY_ANNOTATIONS = McpSchema.ToolAnnotations.builder()
    .readOnlyHint(Boolean.box(true))
    .destructiveHint(Boolean.box(false))
    .idempotentHint(Boolean.box(true))
    .openWorldHint(Boolean.box(false))
    .build()

  private def boundedStringProperty(description: String): Object =
    Map[String, Object](
      "type" -> "string",
      "description" -> description,
      "minLength" -> Int.box(1),
      "maxLength" -> Int.box(256),
      "pattern" -> "^[^\\u0000-\\u001F\\u007F]+$").asJava

  private def identifierProperty(description: String): Object =
    Map[String, Object](
      "type" -> "string",
      "description" -> description,
      "minLength" -> Int.box(1),
      "maxLength" -> Int.box(128),
      "pattern" -> "^[A-Za-z0-9_-]+$").asJava

  private def literalProperty(description: String): Object =
    Map[String, Object](
      "type" -> "string",
      "description" -> description,
      "minLength" -> Int.box(1),
      "maxLength" -> Int.box(128),
      "pattern" -> "^[^\\u0000-\\u001F\\u007F]+$").asJava

  private def opaqueLogIdProperty: Object =
    Map[String, Object](
      "type" -> "string",
      "description" -> "Opaque identifier returned by list_server_logs.",
      "pattern" -> "^[A-Za-z0-9_-]{43}$").asJava

  private def enumProperty(description: String, values: Seq[String]): Object =
    Map[String, Object](
      "type" -> "string",
      "description" -> description,
      "enum" -> values.asJava).asJava

  private def integerProperty(description: String, maximum: Int): Object =
    Map[String, Object](
      "type" -> "integer",
      "description" -> description,
      "minimum" -> Int.box(1),
      "maximum" -> Int.box(maximum)).asJava
}
