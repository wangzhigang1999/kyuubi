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

import java.util.concurrent.TimeUnit
import java.util.function.BiFunction

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import com.codahale.metrics.MetricRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.spec.McpSchema

import org.apache.kyuubi.Logging
import org.apache.kyuubi.engine.{EngineType, ShareLevel}
import org.apache.kyuubi.metrics.MetricsConstants.{MCP_TOOL_CALL_TIME, MCP_TOOL_CALL_TOTAL}
import org.apache.kyuubi.metrics.MetricsSystem
import org.apache.kyuubi.operation.OperationState
import org.apache.kyuubi.server.KyuubiRestFrontendService
import org.apache.kyuubi.server.diagnostics.{ClusterDiagnosticService, DiagnosticPrincipal, DiagnosticService}
import org.apache.kyuubi.session.SessionType

private[mcp] class KyuubiMcpTools(
    frontendService: KyuubiRestFrontendService,
    objectMapper: ObjectMapper,
    jsonMapper: McpJsonMapper) extends Logging {

  import DiagnosticService._
  import KyuubiMcpService._
  import KyuubiMcpTools._

  private val clusterDiagnostics =
    new ClusterDiagnosticService(frontendService, objectMapper)

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
    tool(
      "list_servers",
      "List reachable Kyuubi Server instances across the cluster. Administrators only.") {
      (context, _) =>
        if (!isAdministrator(context)) {
          accessDenied("Listing Kyuubi servers requires administrator permission.")
        } else {
          success(clusterDiagnostics.listServers(principal(context)))
        }
    },
    tool(
      GET_SERVER_RUNTIME,
      "Inspect bounded JVM, memory, thread, uptime, and host-load metrics on every reachable " +
        "Kyuubi Server. Administrators only.") {
      (context, _) =>
        if (!isAdministrator(context)) {
          accessDenied(
            "Inspecting Kyuubi Server runtime metrics requires administrator permission.")
        } else {
          success(clusterDiagnostics.serverRuntime(principal(context)))
        }
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
        "subdomain" -> boundedStringProperty("Engine share-level subdomain."),
        "limit" -> integerProperty("Maximum results from service discovery.", 200))) {
      (context, arguments) =>
        clusterListResult(context, arguments) {
          clusterDiagnostics.listEngines(arguments, principal(context))
        }
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
          val startNanos = System.nanoTime()
          val result =
            try {
              handler(
                context,
                Option(request.arguments()).map(_.asScala.toMap).getOrElse(Map.empty))
            } catch {
              case e: IllegalArgumentException => failure(e.getMessage)
              case NonFatal(e) =>
                error(s"MCP tool $name failed for ${auditContextValue(context, REAL_USER)}", e)
                failure("The tool could not complete the request.")
            }
          audit(context, name, result, System.nanoTime() - startNanos)
          result
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

  private def accessDenied(message: String): McpSchema.CallToolResult =
    McpSchema.CallToolResult.builder()
      .addTextContent(message)
      .isError(Boolean.box(true))
      .meta(Map[String, Object](AUDIT_STATUS -> "denied").asJava)
      .build()

  private def inaccessibleResource(): McpSchema.CallToolResult =
    failure("The resource does not exist or is not accessible.")

  private def principal(context: McpTransportContext): DiagnosticPrincipal =
    DiagnosticPrincipal(
      realUser(context),
      Option(context.get(CLIENT_IP)).map(_.toString).getOrElse(""),
      isAdministrator(context))

  private def realUser(context: McpTransportContext): String =
    Option(context.get(REAL_USER)).map(_.toString).getOrElse("anonymous")

  private def isAdministrator(context: McpTransportContext): Boolean =
    java.lang.Boolean.TRUE == context.get(ADMINISTRATOR)

  private def audit(
      context: McpTransportContext,
      name: String,
      result: McpSchema.CallToolResult,
      elapsedNanos: Long): Unit = {
    val structured = result.structuredContent() match {
      case value: java.util.Map[_, _] => value.asInstanceOf[java.util.Map[String, Object]]
      case _ => java.util.Collections.emptyMap[String, Object]()
    }
    val status = if (Option(result.meta()).exists(map => "denied" == map.get(AUDIT_STATUS))) {
      "denied"
    } else if (java.lang.Boolean.TRUE == structured.get("partial")) {
      "partial"
    } else if (java.lang.Boolean.TRUE == result.isError()) {
      "error"
    } else {
      "success"
    }
    val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
    val failedServers = structured.get("failedServers") match {
      case values: java.util.Collection[_] => values.size()
      case _ => 0
    }
    info(
      s"MCP tool requestId=${auditContextValue(context, REQUEST_ID)} name=$name " +
        s"user=${auditContextValue(context, REAL_USER)} " +
        s"clientIp=${auditContextValue(context, CLIENT_IP)} status=$status " +
        s"elapsedMs=$elapsedMillis count=${structured.get("count")} " +
        s"truncated=${structured.get("truncated")} " +
        s"discoveredServers=${structured.get("discoveredServers")} " +
        s"respondedServers=${structured.get("respondedServers")} failedServers=$failedServers")
    MetricsSystem.tracing { metrics =>
      metrics.markMeter(MetricRegistry.name(MCP_TOOL_CALL_TOTAL, name, status))
      metrics.updateTimer(
        MetricRegistry.name(MCP_TOOL_CALL_TIME, name),
        elapsedNanos,
        TimeUnit.NANOSECONDS)
    }
  }

  private def auditContextValue(context: McpTransportContext, name: String): String =
    Option(context.get(name)).map { value =>
      value.toString.iterator
        .map(character => if (Character.isISOControl(character)) '?' else character)
        .take(MAX_AUDIT_VALUE_LENGTH)
        .mkString
    }.getOrElse("")
}

private[mcp] object KyuubiMcpTools {
  private val AUDIT_STATUS = "kyuubi.audit.status"
  private val MAX_AUDIT_VALUE_LENGTH = 128
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
