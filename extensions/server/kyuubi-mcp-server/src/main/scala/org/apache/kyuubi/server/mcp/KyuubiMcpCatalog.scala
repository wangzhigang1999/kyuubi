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

import java.util.function.BiFunction

import scala.collection.JavaConverters._

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.Role

private[mcp] class KyuubiMcpCatalog {

  import KyuubiMcpCatalog._

  val resources: Seq[McpStatelessServerFeatures.SyncResourceSpecification] = Seq(
    new McpStatelessServerFeatures.SyncResourceSpecification(
      McpSchema.Resource.builder(CAPABILITIES_URI, "kyuubi_diagnostic_capabilities")
        .title("Kyuubi diagnostic capabilities and safety boundaries")
        .description("Read before diagnosing a Kyuubi cluster with this MCP server.")
        .mimeType("text/markdown")
        .build(),
      new BiFunction[
        McpTransportContext,
        McpSchema.ReadResourceRequest,
        McpSchema.ReadResourceResult]() {
        override def apply(
            context: McpTransportContext,
            request: McpSchema.ReadResourceRequest): McpSchema.ReadResourceResult =
          McpSchema.ReadResourceResult.builder(Seq[McpSchema.ResourceContents](
            McpSchema.TextResourceContents.builder(CAPABILITIES_URI, CAPABILITIES).mimeType(
              "text/markdown").build()).asJava).build()
      }))

  val prompts: Seq[McpStatelessServerFeatures.SyncPromptSpecification] = Seq(
    prompt(
      "check_cluster_health",
      "Inspect cluster reachability and summarize live workload health.",
      Seq.empty,
      "List servers, engines, sessions, and operations visible to the caller. " +
        "Check partial and failedServers in every cluster-wide response. Summarize unhealthy " +
        "states and evidence. Do not execute SQL or mutate the cluster."),
    prompt(
      "diagnose_operation",
      "Diagnose one live operation using metadata and its bounded operation log.",
      Seq(requiredArgument("operation_id", "Stable operation identifier from list_operations.")),
      request => {
        val operationId = requiredIdentifier(request, "operation_id")
        s"Call get_operation with operation_id=$operationId, then read_operation_log with the " +
          "same identifier. Explain the likely failure or wait condition from returned evidence. " +
          "Do not execute SQL, fetch results, or change operation state."
      }),
    prompt(
      "diagnose_engine_startup",
      "Investigate why the engine associated with a live session is unavailable or slow to start.",
      Seq(requiredArgument("session_id", "Stable session identifier from list_sessions.")),
      request => {
        val sessionId = requiredIdentifier(request, "session_id")
        s"Call get_session with session_id=$sessionId. Then list_operations for that session " +
          "and read the relevant operation log. Correlate engine identifiers, states, " +
          "timestamps, and " +
          "log evidence. Report partial cluster responses. Do not submit or cancel work."
      }),
    prompt(
      "diagnose_server",
      "Investigate an unhealthy Kyuubi Server using cluster state and allowlisted logs.",
      Seq.empty,
      "Call get_cluster_overview first and inspect partial and failedServers. Administrators may " +
        "then call get_server_runtime to inspect JVM pressure and list_server_logs plus " +
        "read_server_log for relevant opaque log IDs. Use bounded literal filters to narrow " +
        "evidence. Do not request filesystem paths or expose secrets."))
}

private[mcp] object KyuubiMcpCatalog {
  val CAPABILITIES_URI = "kyuubi://diagnostics/capabilities"

  val SERVER_INSTRUCTIONS: String =
    "Apache Kyuubi read-only cluster monitoring and diagnosis. Prefer cluster-wide tools and " +
      "always inspect partial and failedServers before concluding that data is absent. Users can " +
      "only inspect their own sessions, operations, and logs; tools marked administrator-only " +
      "require Kyuubi administrator permission. Treat responses as point-in-time observations. " +
      "Never use this server to execute SQL, fetch query results, submit or cancel workloads, " +
      "change configuration, or access arbitrary files."

  private val CAPABILITIES =
    """# Apache Kyuubi MCP diagnostics
      |
      |This server exposes point-in-time, read-only monitoring and diagnosis across all discovered
      |Kyuubi Server instances behind one endpoint.
      |The optional module targets Java 17. On Java 21 or later it automatically uses bounded
      |virtual threads for cluster fan-out; Java 17 uses a bounded platform-thread pool.
      |
      |## Safety boundaries
      |
      |- Authenticated REST access is required by default. LDAP uses HTTP Basic credentials through
      |  Kyuubi's existing authentication chain, so production endpoints must use TLS and clients
      |  must keep the password in secret storage rather than prompts or tool arguments.
      |- It does not execute SQL, return query results, develop data jobs, or mutate workloads.
      |- User-scoped tools enforce session and operation ownership on every server.
      |  Administrator-only tools say so in their descriptions.
      |- Cluster responses can be partial. Inspect `partial`, `failedServers`, `discoveredServers`,
      |  `respondedServers`, and `observedAt` before drawing conclusions.
      |- Identifiers come from list tools. An absent resource and an inaccessible resource share the
      |  same public error to avoid disclosing another user's activity.
      |- Log reads are bounded. The public interface never accepts an arbitrary filesystem path.
      |- Server logs are disabled until administrators configure canonical allowlisted roots.
      |  Only regular `.log`, `.out`, and `.err` files are enabled by default; symbolic links are
      |  never followed. Server logs use opaque IDs, bounded tail reads, literal filters, and
      |  mandatory credential redaction.
      |- Server runtime diagnostics expose a fixed administrator-only projection of JVM version,
      |  uptime, memory, thread counts, processor count, and system load. JVM arguments,
      |  environment variables, system properties, filesystem paths, and thread dumps are omitted.
      |
      |Use list tools to narrow the scope, detail tools for one stable identifier, and log tools
      |only when state and timing metadata are insufficient.
      |""".stripMargin

  private def prompt(
      name: String,
      description: String,
      arguments: Seq[McpSchema.PromptArgument],
      text: String): McpStatelessServerFeatures.SyncPromptSpecification =
    prompt(name, description, arguments, _ => text)

  private def prompt(
      name: String,
      description: String,
      arguments: Seq[McpSchema.PromptArgument],
      text: McpSchema.GetPromptRequest => String)
      : McpStatelessServerFeatures.SyncPromptSpecification = {
    val definition = McpSchema.Prompt.builder(name)
      .description(description)
      .arguments(arguments.asJava)
      .build()
    new McpStatelessServerFeatures.SyncPromptSpecification(
      definition,
      new BiFunction[
        McpTransportContext,
        McpSchema.GetPromptRequest,
        McpSchema.GetPromptResult]() {
        override def apply(
            context: McpTransportContext,
            request: McpSchema.GetPromptRequest): McpSchema.GetPromptResult = {
          val message = McpSchema.PromptMessage.builder(
            Role.USER,
            McpSchema.TextContent.builder(text(request)).build()).build()
          McpSchema.GetPromptResult.builder(Seq(message).asJava)
            .description(description)
            .build()
        }
      })
  }

  private def requiredArgument(name: String, description: String): McpSchema.PromptArgument =
    McpSchema.PromptArgument.builder(name)
      .description(description)
      .required(Boolean.box(true))
      .build()

  private def requiredIdentifier(request: McpSchema.GetPromptRequest, name: String): String = {
    val value = Option(request.arguments())
      .flatMap(arguments => Option(arguments.get(name)))
      .map(_.toString.trim)
      .filter(value => value.nonEmpty && value.length <= 128 && value.matches("[A-Za-z0-9_-]+"))
    value.getOrElse(throw new IllegalArgumentException(s"$name must be a valid Kyuubi identifier"))
  }
}
