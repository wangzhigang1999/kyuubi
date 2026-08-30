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

import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import com.codahale.metrics.MetricRegistry
import com.fasterxml.jackson.core.`type`.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.spec.McpSchema

import org.apache.kyuubi.Logging
import org.apache.kyuubi.metrics.MetricsConstants.{MCP_TOOL_CALL_TIME, MCP_TOOL_CALL_TOTAL}
import org.apache.kyuubi.metrics.MetricsSystem
import org.apache.kyuubi.server.KyuubiRestFrontendService
import org.apache.kyuubi.server.mcp.KyuubiMcpToolProvider.{Caller, Result, Tool}
import org.apache.kyuubi.server.mcp.tool._

private[mcp] class KyuubiMcpTools(
    frontendService: KyuubiRestFrontendService,
    objectMapper: ObjectMapper,
    jsonMapper: McpJsonMapper) extends Logging {

  import KyuubiMcpService._
  import KyuubiMcpTools._

  private val diagnostics = new KyuubiMcpDiagnostics(frontendService, objectMapper)
  private val schemaGenerator = new KyuubiMcpSchemaGenerator(objectMapper)

  val builtIns: Seq[Tool[_, _]] = Seq(
    new GetClusterOverviewTool(diagnostics),
    new ListServersTool(diagnostics),
    new GetServerRuntimeTool(diagnostics),
    new ListEnginesTool(diagnostics),
    new ListSessionsTool(diagnostics),
    new GetSessionTool(diagnostics),
    new ListOperationsTool(diagnostics),
    new GetOperationTool(diagnostics),
    new ReadOperationLogTool(diagnostics),
    new ListServerLogsTool(diagnostics),
    new ReadServerLogTool(diagnostics))

  def specification(tool: Tool[_, _]): McpStatelessServerFeatures.SyncToolSpecification = {
    validate(tool)
    val definition = McpSchema.Tool.builder(tool.name())
      .description(tool.description())
      .inputSchema(schemaGenerator.generate(tool.argumentsType()))
      .outputSchema(schemaGenerator.generate(tool.responseType()))
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
          val startedAt = System.nanoTime()
          val result =
            try {
              invoke(tool, context, request)
            } catch {
              case e: IllegalArgumentException => failure(e.getMessage)
              case NonFatal(e) =>
                error(
                  s"MCP tool ${tool.name()} failed for ${contextValue(context, REAL_USER)}",
                  e)
                failure("The tool could not complete the request.")
            }
          audit(context, tool.name(), result, System.nanoTime() - startedAt)
          result
        }
      })
      .build()
  }

  def close(): Unit = diagnostics.close()

  private def invoke(
      tool: Tool[_, _],
      context: McpTransportContext,
      request: McpSchema.CallToolRequest): McpSchema.CallToolResult = {
    val typed = tool.asInstanceOf[Tool[Object, Object]]
    val rawArguments = Option(request.arguments())
      .getOrElse(Collections.emptyMap[String, Object]())
    val arguments = objectMapper.convertValue(rawArguments, typed.argumentsType())
    val caller = new Caller(
      contextValue(context, REQUEST_ID),
      contextValue(context, REAL_USER),
      contextValue(context, CLIENT_IP),
      java.lang.Boolean.TRUE == context.get(ADMINISTRATOR))
    val outcome = Option(typed.call(caller, arguments)).getOrElse {
      throw new IllegalArgumentException(s"MCP tool ${tool.name()} returned null")
    }
    outcome.status() match {
      case Result.Status.SUCCESS => success(outcome.response())
      case Result.Status.DENIED => denied(outcome.message())
      case Result.Status.ERROR => failure(outcome.message(), Option(outcome.response()))
    }
  }

  private def validate(tool: Tool[_, _]): Unit = {
    require(tool != null, "MCP tool must not be null")
    require(tool.name() != null && tool.name().nonEmpty, "MCP tool requires a name")
    require(
      tool.description() != null && tool.description().nonEmpty,
      s"MCP tool ${tool.name()} requires a description")
    require(tool.argumentsType() != null, s"MCP tool ${tool.name()} requires an argument type")
    require(tool.responseType() != null, s"MCP tool ${tool.name()} requires a response type")
  }

  private def success(value: Object): McpSchema.CallToolResult =
    McpSchema.CallToolResult.builder()
      .addTextContent(jsonMapper.writeValueAsString(value))
      .structuredContent(value)
      .isError(Boolean.box(false))
      .build()

  private def failure(message: String): McpSchema.CallToolResult = failure(message, None)

  private def failure(
      message: String,
      value: Option[Object]): McpSchema.CallToolResult = {
    val builder = McpSchema.CallToolResult.builder()
      .addTextContent(message)
      .isError(Boolean.box(true))
    value.foreach(builder.structuredContent)
    builder.build()
  }

  private def denied(message: String): McpSchema.CallToolResult =
    McpSchema.CallToolResult.builder()
      .addTextContent(message)
      .isError(Boolean.box(true))
      .meta(Map[String, Object](AUDIT_STATUS -> "denied").asJava)
      .build()

  private def audit(
      context: McpTransportContext,
      name: String,
      result: McpSchema.CallToolResult,
      elapsedNanos: Long): Unit = {
    val structured = Option(result.structuredContent()).map { value =>
      objectMapper.convertValue(value, MAP_TYPE)
    }.getOrElse(Collections.emptyMap[String, Object]())
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
      s"MCP tool requestId=${contextValue(context, REQUEST_ID)} name=$name " +
        s"user=${contextValue(context, REAL_USER)} clientIp=${contextValue(context, CLIENT_IP)} " +
        s"status=$status elapsedMs=$elapsedMillis count=${structured.get("count")} " +
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

  private def contextValue(context: McpTransportContext, name: String): String =
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
  private val MAP_TYPE = new TypeReference[java.util.Map[String, Object]]() {}
  private val READ_ONLY_ANNOTATIONS = McpSchema.ToolAnnotations.builder()
    .readOnlyHint(Boolean.box(true))
    .destructiveHint(Boolean.box(false))
    .idempotentHint(Boolean.box(true))
    .openWorldHint(Boolean.box(false))
    .build()
}
