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
      "Call first to summarize server reachability and live session and operation health. " +
        "When partial is true, counts cover responded servers only and zero never proves " +
        "cluster-wide absence.",
      properties = Map(
        "user" -> boundedStringProperty("User to inspect. Administrators only."))) {
      (context, arguments) =>
        clusterListResult(context, arguments) {
          clusterDiagnostics.clusterOverview(arguments, principal(context))
        }
    },
    tool(
      "list_servers",
      "List reachable Kyuubi Server instances across the cluster. A failed response does not " +
        "prove that the process stopped. Administrators only.") {
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
      "List engine registrations visible to the authenticated user directly from HA service " +
        "discovery. This does not test Kyuubi Server or engine process reachability.",
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
      "List live sessions visible to the authenticated user. When partial is true, an empty " +
        "list means no matches on responded servers only.",
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
      "List live operations visible to the authenticated user. Prefer one relevant state filter " +
        "instead of exhaustive parallel calls. When partial is true, an empty list means no " +
        "matches on responded servers only.",
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
      "List allowlisted Kyuubi Server log files on responded cluster nodes. Check partial and " +
        "failedServers before concluding that logs are absent. Administrators only.",
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

  private[mcp] def instrument(
      specification: McpStatelessServerFeatures.SyncToolSpecification)
      : McpStatelessServerFeatures.SyncToolSpecification = {
    val name = specification.tool().name()
    val handler = specification.callHandler()
    McpStatelessServerFeatures.SyncToolSpecification.builder()
      .tool(specification.tool())
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
              handler.apply(context, request)
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
      .outputSchema(outputSchema(name))
      .annotations(READ_ONLY_ANNOTATIONS)
      .build()
    instrument(McpStatelessServerFeatures.SyncToolSpecification.builder()
      .tool(definition)
      .callHandler(new BiFunction[
        McpTransportContext,
        McpSchema.CallToolRequest,
        McpSchema.CallToolResult]() {
        override def apply(
            context: McpTransportContext,
            request: McpSchema.CallToolRequest): McpSchema.CallToolResult = {
          handler(
            context,
            Option(request.arguments()).map(_.asScala.toMap).getOrElse(Map.empty))
        }
      })
      .build())
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
  import DiagnosticService._

  private val AUDIT_STATUS = "kyuubi.audit.status"
  private val MAX_AUDIT_VALUE_LENGTH = 128
  private val READ_ONLY_ANNOTATIONS = McpSchema.ToolAnnotations.builder()
    .readOnlyHint(Boolean.box(true))
    .destructiveHint(Boolean.box(false))
    .idempotentHint(Boolean.box(true))
    .openWorldHint(Boolean.box(false))
    .build()

  private val FAILURE_SCHEMA = objectSchema(
    Map(
      "server" -> outputString(
        "Discovered server address or discovery scope associated with the failure."),
      "reason" -> outputString(
        "Bounded failure category. fanout_deadline means the cluster-wide deadline expired; " +
          "peer_timeout means the peer request timed out; peer_unavailable means the request " +
          "failed for another transport reason; local_failure means local diagnostics failed.")))

  private val COVERAGE_PROPERTIES = Map[String, Object](
    "partial" -> outputBoolean(
      "True when at least one discovered server failed, was omitted, or discovery itself failed. " +
        "When true, never treat empty lists or zero counts as cluster-wide absence."),
    "failedServers" -> outputArray(
      "Failures that made this result incomplete. A failure does not prove that a process " +
        "stopped or that TCP connected successfully.",
      FAILURE_SCHEMA),
    "discoveredServers" -> outputInteger(
      "Number of distinct Kyuubi Server registrations found before applying the fanout limit. " +
        "Registration does not prove reachability."),
    "omittedServers" -> outputInteger(
      "Number of discovered registrations not queried because fanoutLimit was reached."),
    "fanoutLimit" -> outputInteger(
      "Maximum number of discovered servers this tool can query in one call."),
    "respondedServers" -> outputInteger(
      "Number of servers that successfully returned this diagnostic result."),
    "countScope" -> outputEnum(
      "Defines which servers the returned items and aggregate counts cover. " +
        "responded_servers_only means the result is not a cluster-wide absence statement.",
      Seq("all_discovered_servers", "responded_servers_only")),
    "observedAt" -> outputTimestamp(
      "UTC time when the aggregated point-in-time observation was completed."))

  private val COVERAGE_REQUIRED = Seq(
    "partial",
    "failedServers",
    "discoveredServers",
    "omittedServers",
    "fanoutLimit",
    "respondedServers",
    "countScope",
    "observedAt")

  private def countersSchema(description: String) = objectSchema(
    Map.empty,
    description = description,
    additionalProperties = outputInteger("Count for this category."))

  private val OVERVIEW_SERVER_SCHEMA = objectSchema(Map(
    "server" -> outputString("Kyuubi Server REST diagnostic address."),
    "sessionCount" -> outputInteger(
      "Live sessions visible to the authenticated principal on this server."),
    "sessionTypes" -> countersSchema(
      "Live sessions grouped by session type on this server."),
    "operationCount" -> outputInteger(
      "Live operations visible to the authenticated principal on this server."),
    "operationStates" -> countersSchema(
      "Live operations grouped by current state on this server.")))

  private val SESSION_SCHEMA = objectSchema(Map(
    "identifier" -> outputString("Stable Kyuubi session identifier."),
    "user" -> outputString("Session owner."),
    "createTime" -> outputEpochMillis("Session creation time."),
    "duration" -> outputInteger("Session age in milliseconds at observation time."),
    "idleTime" -> outputInteger("Session idle duration in milliseconds at observation time."),
    "sessionType" -> outputString("Kyuubi session type, such as INTERACTIVE or BATCH."),
    "kyuubiInstance" -> outputString("Kyuubi Server instance that owns the session."),
    "engineId" -> outputString("Associated engine identifier when available."),
    "engineName" -> outputString("Associated engine name when available."),
    "engineUrl" -> outputString("Associated engine URL when available."),
    "totalOperations" -> outputInteger("Number of operations created in this session.")))

  private val OPERATION_SCHEMA = objectSchema(Map(
    "identifier" -> outputString("Stable Kyuubi operation identifier."),
    "state" -> outputString("Current Kyuubi operation state."),
    "createTime" -> outputEpochMillis("Operation creation time."),
    "startTime" -> outputEpochMillis("Operation start time when available."),
    "completeTime" -> outputEpochMillis("Operation completion time when available."),
    "sessionId" -> outputString("Identifier of the owning session."),
    "sessionUser" -> outputString("Owner of the operation's session."),
    "sessionType" -> outputString("Type of the operation's session."),
    "kyuubiInstance" -> outputString("Kyuubi Server instance that owns the operation."),
    "metrics" -> objectSchema(
      Map.empty,
      additionalProperties = outputString("Operation metric value."))))

  private val RUNTIME_SCHEMA = objectSchema(Map(
    "server" -> outputString("Kyuubi Server REST diagnostic address."),
    "kyuubiVersion" -> outputString("Kyuubi version reported by this server."),
    "javaVersion" -> outputString("Java runtime version."),
    "javaVendor" -> outputString("Java runtime vendor."),
    "vmName" -> outputString("Java virtual machine implementation name."),
    "startTime" -> outputEpochMillis("JVM start time."),
    "uptimeMs" -> outputInteger("JVM uptime in milliseconds."),
    "availableProcessors" -> outputInteger("Processors available to the JVM."),
    "heapUsedBytes" -> outputInteger("Currently used heap memory in bytes."),
    "heapCommittedBytes" -> outputInteger("Heap memory committed to the JVM in bytes."),
    "heapMaxBytes" -> outputInteger("Maximum heap memory in bytes."),
    "nonHeapUsedBytes" -> outputInteger("Currently used non-heap memory in bytes."),
    "nonHeapCommittedBytes" -> outputInteger("Committed non-heap memory in bytes."),
    "liveThreads" -> outputInteger("Live JVM thread count at observation time."),
    "daemonThreads" -> outputInteger("Live daemon thread count at observation time."),
    "peakThreads" -> outputInteger(
      "Highest live thread count since JVM start; this is not a time-series trend."),
    "systemLoadAverage" -> outputNumber(
      "Operating-system load average when supported; interpretation is platform dependent.")))

  private val ENGINE_SCHEMA = objectSchema(Map(
    "version" -> outputString("Kyuubi version encoded in the engine registration."),
    "user" -> outputString("Engine owner."),
    "engineType" -> outputString("Registered engine type."),
    "shareLevel" -> outputString("Registered engine share level."),
    "subdomain" -> outputString("Engine share-level subdomain."),
    "instance" -> outputString(
      "Engine service registration value; registration does not prove process reachability.")))

  private val LOG_FILE_SCHEMA = objectSchema(Map(
    "logId" -> outputString(
      "Opaque identifier accepted by read_server_log; it is not a filesystem path."),
    "name" -> outputString("Log file name relative to the protected log root."),
    "server" -> outputString("Kyuubi Server instance that owns the file."),
    "sizeBytes" -> outputInteger("File size in bytes at discovery time."),
    "lastModified" -> outputTimestamp("File modification time.")))

  private val OPERATION_LOG_SCHEMA = logContentSchema(Map(
    "operationId" -> outputString("Operation whose in-memory log snapshot was read."),
    "kyuubiInstance" -> outputString("Kyuubi Server that owns the operation."),
    "maxRows" -> outputInteger("Requested maximum number of returned lines.")))

  private val SERVER_LOG_SCHEMA = logContentSchema(Map(
    "logId" -> outputString("Opaque server log identifier."),
    "name" -> outputString("Protected server log file name."),
    "server" -> outputString("Kyuubi Server that owns the file."),
    "maxLines" -> outputInteger("Requested maximum number of returned lines.")))

  private def outputSchema(name: String): java.util.Map[String, Object] = name match {
    case CLUSTER_OVERVIEW => clusterSchema(
        Map(
          "sessionCount" -> outputInteger(
            "Sum of visible live sessions on responded servers only when partial is true."),
          "sessionTypes" -> countersSchema(
            "Sum of visible live sessions grouped by type on responded servers."),
          "operationCount" -> outputInteger(
            "Sum of visible live operations on responded servers only when partial is true."),
          "operationStates" -> countersSchema(
            "Sum of visible live operations grouped by state on responded servers."),
          "servers" -> outputArray(
            "Per-server summaries returned by responded servers.",
            OVERVIEW_SERVER_SCHEMA)),
        Seq("sessionCount", "sessionTypes", "operationCount", "operationStates", "servers"))
    case "list_servers" => clusterSchema(
        Map(
          "servers" -> outputArray(
            "Servers that responded to a runtime probe during this call.",
            objectSchema(Map(
              "instance" -> outputString("Reachable Kyuubi Server REST diagnostic address."),
              "status" -> outputEnum(
                "Running means this server answered the probe; it is not a full service check.",
                Seq("Running"))))),
          "count" -> outputInteger("Number of servers that responded to this call.")),
        Seq("servers", "count"))
    case GET_SERVER_RUNTIME => clusterSchema(
        Map(
          "serverRuntimes" -> outputArray(
            "Point-in-time runtime snapshots from responded servers.",
            RUNTIME_SCHEMA),
          "count" -> outputInteger("Number of returned runtime snapshots.")),
        Seq("serverRuntimes", "count"))
    case "list_engines" => objectSchema(
        Map(
          "discoveryEnabled" -> outputBoolean(
            "Whether HA service discovery is configured for this engine lookup."),
          "engines" -> outputArray(
            "Engine registrations matching the filters. These are registry records, not " +
              "reachability checks.",
            ENGINE_SCHEMA),
          "count" -> outputInteger("Number of engine registrations returned, not a total."),
          "limit" -> outputInteger("Maximum number of registrations requested."),
          "truncated" -> outputBoolean(
            "True when more engine namespaces or registrations may exist than were returned."),
          "partial" -> outputBoolean(
            "True when discovery failed or the result was truncated."),
          "source" -> outputEnum(
            "Data source used for this result.",
            Seq("ha_service_discovery")),
          "countScope" -> outputEnum(
            "all_discovered_registrations means count zero is complete for this registry view; " +
              "observed_registrations_only means it is not.",
            Seq("all_discovered_registrations", "observed_registrations_only")),
          "failedServers" -> outputArray(
            "Discovery failures; the server field may name a discovery scope.",
            FAILURE_SCHEMA),
          "observedAt" -> outputTimestamp("UTC completion time of this registry observation.")),
        required = Seq(
          "discoveryEnabled",
          "engines",
          "count",
          "limit",
          "truncated",
          "partial",
          "source",
          "countScope",
          "failedServers",
          "observedAt"))
    case LIST_SESSIONS => clusterSchema(
        Map(
          "sessions" -> outputArray(
            "Visible live sessions found on responded servers.",
            SESSION_SCHEMA),
          "count" -> outputInteger(
            "Number of returned sessions, not a cluster-wide total when partial is true.")),
        Seq("sessions", "count"))
    case GET_SESSION => lookupSchema(
        "session",
        "Session details when found on a responded server; null otherwise.",
        SESSION_SCHEMA)
    case LIST_OPERATIONS => clusterSchema(
        Map(
          "operations" -> outputArray(
            "Visible live operations found on responded servers.",
            OPERATION_SCHEMA),
          "count" -> outputInteger(
            "Number of returned operations, not a cluster-wide total when partial is true.")),
        Seq("operations", "count"))
    case GET_OPERATION => lookupSchema(
        "operation",
        "Operation details when found on a responded server; null otherwise.",
        OPERATION_SCHEMA)
    case READ_OPERATION_LOG => lookupSchema(
        "operationLog",
        "Bounded, redacted in-memory operation log snapshot when found; null otherwise.",
        OPERATION_LOG_SCHEMA)
    case LIST_SERVER_LOGS => clusterSchema(
        Map(
          "serverLogs" -> outputArray(
            "Protected log files discovered under KYUUBI_LOG_DIR on responded servers. " +
              "Symlinks and files outside the sandbox are excluded.",
            LOG_FILE_SCHEMA),
          "count" -> outputInteger("Number of returned log files."),
          "enabledServers" -> outputInteger(
            "Responded servers where KYUUBI_LOG_DIR enabled protected file-log access.")),
        Seq("serverLogs", "count", "enabledServers"))
    case READ_SERVER_LOG => lookupSchema(
        "serverLog",
        "Bounded, redacted server log tail when the opaque log ID was resolved; null otherwise.",
        SERVER_LOG_SCHEMA)
    case _ => throw new IllegalArgumentException(s"Missing MCP output schema for tool $name")
  }

  private def clusterSchema(
      properties: Map[String, Object],
      required: Seq[String]): java.util.Map[String, Object] =
    objectSchema(COVERAGE_PROPERTIES ++ properties, COVERAGE_REQUIRED ++ required)

  private def lookupSchema(
      resultName: String,
      resultDescription: String,
      resultSchema: Object): java.util.Map[String, Object] = clusterSchema(
    Map(
      "found" -> outputBoolean(
        "True only when the resource was found on a responded server. If partial is true and " +
          "found is false, absence is not established."),
      resultName -> nullableSchema(resultDescription, resultSchema),
      "server" -> nullableString(
        "Kyuubi Server where the resource was found; null when found is false.")),
    Seq("found", resultName, "server"))

  private def logContentSchema(
      identityProperties: Map[String, Object]): java.util.Map[String, Object] = objectSchema(
    identityProperties ++ Map(
      "lines" -> outputArray(
        "Returned log lines after literal filtering and credential redaction.",
        outputString("One redacted log line.")),
      "count" -> outputInteger("Number of returned lines."),
      "maxBytes" -> outputInteger("Requested UTF-8 byte bound."),
      "truncated" -> outputBoolean(
        "True when row/line or byte limits prevented returning all matching content."),
      "redacted" -> outputBoolean("Always true; credential-like values are masked.")))

  private def objectSchema(
      properties: Map[String, Object],
      required: Seq[String] = Seq.empty,
      additionalProperties: Object = Boolean.box(false),
      description: String = null): java.util.Map[String, Object] =
    (Map[String, Object](
      "type" -> "object",
      "properties" -> properties.asJava,
      "required" -> required.asJava,
      "additionalProperties" -> additionalProperties) ++
      Option(description).map("description" -> _)).asJava

  private def outputString(description: String): Object =
    describedOutput("string", description)

  private def nullableString(description: String): Object =
    Map[String, Object](
      "type" -> Seq("string", "null").asJava,
      "description" -> description).asJava

  private def outputInteger(description: String): Object =
    describedOutput("integer", description)

  private def outputNumber(description: String): Object =
    describedOutput("number", description)

  private def outputBoolean(description: String): Object =
    describedOutput("boolean", description)

  private def outputTimestamp(description: String): Object =
    Map[String, Object](
      "type" -> "string",
      "format" -> "date-time",
      "description" -> description).asJava

  private def outputEpochMillis(description: String): Object =
    outputInteger(s"$description Unix epoch milliseconds.")

  private def outputEnum(description: String, values: Seq[String]): Object =
    Map[String, Object](
      "type" -> "string",
      "description" -> description,
      "enum" -> values.asJava).asJava

  private def outputArray(description: String, items: Object): Object =
    Map[String, Object](
      "type" -> "array",
      "description" -> description,
      "items" -> items).asJava

  private def nullableSchema(description: String, schema: Object): Object =
    Map[String, Object](
      "anyOf" -> Seq(schema, Map[String, Object]("type" -> "null").asJava).asJava,
      "description" -> description).asJava

  private def describedOutput(schemaType: String, description: String): Object =
    Map[String, Object](
      "type" -> schemaType,
      "description" -> description).asJava

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
