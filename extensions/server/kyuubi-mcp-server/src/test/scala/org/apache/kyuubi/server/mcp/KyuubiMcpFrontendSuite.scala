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

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.servlet.http.HttpServletResponse
import javax.ws.rs.client.Entity

import scala.collection.JavaConverters._

import org.apache.kyuubi.{RestClientTestHelper, RestFrontendTestHelper, Utils}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.server.http.util.HttpAuthUtils.AUTHORIZATION_HEADER
import org.apache.kyuubi.server.http.util.HttpAuthUtils.basicAuthorizationHeader
import org.apache.kyuubi.service.authentication.UserDefineAuthenticationProviderImpl

class KyuubiMcpFrontendSuite extends RestFrontendTestHelper {

  override protected lazy val conf: KyuubiConf = KyuubiConf()
    .set(AUTHENTICATION_METHOD, Seq("NONE"))
    .set(FRONTEND_MCP_ENABLED, true)
    .set(FRONTEND_MCP_ALLOW_INSECURE_AUTHENTICATION, true)

  test("MCP endpoint") {
    val response = call("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
    assert(response.getStatus === 200)
    val tools = response.readEntity(classOf[String])
    Seq(
      "get_cluster_overview",
      "list_servers",
      "get_server_runtime",
      "list_engines",
      "list_sessions",
      "get_session",
      "list_operations",
      "get_operation",
      "read_operation_log").foreach(tool => assert(tools.contains("\"name\":\"" + tool + "\"")))
    assert(!tools.contains("execute_sql"))
    assert(!tools.contains("submit_batch"))
    assert(tools.contains("\"maximum\":200"))
    assert(tools.contains("\"pattern\":\"^[A-Za-z0-9_-]+$\""))

    val overviewResponse = call(
      """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":""" +
        """"get_cluster_overview","arguments":{}}}""")
    assert(overviewResponse.getStatus === 200)
    val overview = overviewResponse.readEntity(classOf[String])
    assert(overview.contains("\"sessionCount\":0"))
    assert(overview.contains("\"operationCount\":0"))
    assert(overview.contains("\"partial\":false"))
    val expectedFanoutMode =
      try {
        classOf[Thread].getMethod("isVirtual")
        "virtual_threads"
      } catch {
        case _: NoSuchMethodException => "platform_pool"
      }
    assert(overview.contains("\"fanoutMode\":\"" + expectedFanoutMode + "\""))

    val runtimeResponse = call(
      """{"jsonrpc":"2.0","id":20,"method":"tools/call","params":{"name":""" +
        """"get_server_runtime","arguments":{}}}""")
    assert(runtimeResponse.getStatus === 200)
    val runtime = runtimeResponse.readEntity(classOf[String])
    assert(runtime.contains("\"serverRuntimes\""))
    assert(runtime.contains("\"heapUsedBytes\""))
    assert(runtime.contains("\"liveThreads\""))
    assert(runtime.contains("\"partial\":false"))
    assert(!runtime.contains("inputArguments"))
    assert(!runtime.contains("systemProperties"))

    val callResponse = call(
      """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_sessions",""" +
        """"arguments":{}}}""")
    assert(callResponse.getStatus === 200)
    val callResult = callResponse.readEntity(classOf[String])
    assert(callResult.contains("\"isError\":false"))
    assert(callResult.contains("\"sessions\":[]"))
    assert(callResult.contains("\"partial\":false"))
    assert(callResult.contains("\"discoveredServers\":1"))
    assert(callResult.contains("\"respondedServers\":1"))

    val missingArgumentResponse = call(
      """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_session",""" +
        """"arguments":{}}}""")
    assert(missingArgumentResponse.getStatus === 200)
    val missingArgumentResult = missingArgumentResponse.readEntity(classOf[String])
    assert(missingArgumentResult.contains("\"isError\":true"))
    assert(missingArgumentResult.contains("session_id"))

    val invalidLimitResponse = call(
      """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"list_sessions",""" +
        """"arguments":{"limit":201}}}""")
    assert(invalidLimitResponse.getStatus === 200)
    val invalidLimit = invalidLimitResponse.readEntity(classOf[String])
    assert(invalidLimit.contains("\"isError\":true"))

    val unknownArgumentResponse = call(
      """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"list_sessions",""" +
        """"arguments":{"path":"/etc/passwd"}}}""")
    assert(unknownArgumentResponse.getStatus === 200)
    val unknownArgument = unknownArgumentResponse.readEntity(classOf[String])
    assert(unknownArgument.contains("\"isError\":true"))
    assert(unknownArgument.contains("path"))
  }

  test("MCP transport rejects invalid requests without exposing a stack trace") {
    val getResponse = webTarget.path("/mcp").request().get()
    assert(getResponse.getStatus === 405)
    val getError = getResponse.readEntity(classOf[String])
    assert(getError.contains("\"code\":-32600"))
    assert(getResponse.getMediaType.toString.startsWith("application/json"))

    val invalidAcceptResponse = webTarget.path("/mcp").request()
      .accept("text/plain")
      .post(Entity.json("""{"jsonrpc":"2.0","id":4,"method":"tools/list"}"""))
    assert(invalidAcceptResponse.getStatus === 400)
    val error = invalidAcceptResponse.readEntity(classOf[String])
    assert(error.contains("\"code\":-32600"))
    assert(!error.contains("stackTrace"))

    val malformedResponse = call("{")
    assert(malformedResponse.getStatus === 400)
    assert(!malformedResponse.readEntity(classOf[String]).contains("stackTrace"))

    val unknownMethodResponse = call(
      """{"jsonrpc":"2.0","id":7,"method":"server/discover","params":{}}""")
    assert(unknownMethodResponse.getStatus === 404)
    val unknownMethod = unknownMethodResponse.readEntity(classOf[String])
    assert(unknownMethod.contains("\"code\":-32601"))
    assert(!unknownMethod.contains("stackTrace"))

    val oversizedResponse = call(
      """{"jsonrpc":"2.0","id":8,"method":"tools/list","params":{"padding":""" +
        ("x" * (64 * 1024)) + "\"}}")
    assert(oversizedResponse.getStatus === 400)
    val oversized = oversizedResponse.readEntity(classOf[String])
    assert(oversized.contains("\"code\":-32600"))
    assert(!oversized.contains("padding"))
  }

  test("MCP advertises its read-only diagnostic contract, resources and prompts") {
    val initializeResponse = call(
      """{"jsonrpc":"2.0","id":10,"method":"initialize","params":{"protocolVersion":""" +
        """"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""")
    assert(initializeResponse.getStatus === 200)
    val initialization = initializeResponse.readEntity(classOf[String])
    assert(initialization.contains("read-only cluster monitoring and diagnosis"))
    assert(initialization.contains("\"resources\""))
    assert(initialization.contains("\"prompts\""))

    val resourcesResponse = call(
      """{"jsonrpc":"2.0","id":11,"method":"resources/list","params":{}}""")
    assert(resourcesResponse.getStatus === 200)
    assert(resourcesResponse.readEntity(classOf[String]).contains(
      KyuubiMcpCatalog.CAPABILITIES_URI))

    val resourceResponse = call(
      s"""{"jsonrpc":"2.0","id":12,"method":"resources/read","params":{"uri":""" +
        s""""${KyuubiMcpCatalog.CAPABILITIES_URI}"}}}""")
    assert(resourceResponse.getStatus === 200)
    val resource = resourceResponse.readEntity(classOf[String])
    assert(resource.contains("does not execute SQL"))
    assert(resource.contains("never accepts an arbitrary filesystem path"))

    val promptsResponse = call(
      """{"jsonrpc":"2.0","id":13,"method":"prompts/list","params":{}}""")
    assert(promptsResponse.getStatus === 200)
    val prompts = promptsResponse.readEntity(classOf[String])
    Seq("check_cluster_health", "diagnose_operation", "diagnose_engine_startup", "diagnose_server")
      .foreach(name => assert(prompts.contains(name)))

    val promptResponse = call(
      """{"jsonrpc":"2.0","id":14,"method":"prompts/get","params":{"name":""" +
        """"diagnose_operation","arguments":{"operation_id":"operation-1"}}}""")
    assert(promptResponse.getStatus === 200)
    val prompt = promptResponse.readEntity(classOf[String])
    assert(prompt.contains("get_operation"))
    assert(prompt.contains("operation-1"))
    assert(prompt.contains("Do not execute SQL"))
  }

  test("MCP diagnostic projections exclude statements, configuration and credentials") {
    val source = Map[String, Object](
      "identifier" -> "id",
      "user" -> "alice",
      "state" -> "RUNNING",
      "conf" -> Map("password" -> "secret").asJava,
      "statement" -> "select secret from table",
      "exception" -> "token=secret",
      "ipAddr" -> "127.0.0.1").asJava

    val session = KyuubiMcpLocalDiagnostics.safeSessionProjection(source)
    assert(session.keySet().asScala === Set("identifier", "user"))
    val operation = KyuubiMcpLocalDiagnostics.safeOperationProjection(source)
    assert(operation.keySet().asScala === Set("identifier", "state"))

    val logLine = "password=secret Bearer ey.secret token:another jdbc://user:pass@host"
    val redacted = KyuubiMcpLogSandbox.redact(logLine)
    assert(!redacted.contains("secret"))
    assert(!redacted.contains("another"))
    assert(!redacted.contains("user:pass"))
    assert(redacted.contains("[REDACTED]"))

    val bounded = KyuubiMcpLocalDiagnostics.boundedRedactedLog(
      Seq("password=first", "safe line", "another line"),
      maxRows = 2,
      maxBytes = 100,
      contains = None)
    assert(bounded.lines.size === 2)
    assert(!bounded.lines.mkString.contains("first"))
    assert(bounded.truncated)
    val oversizedLine = KyuubiMcpLocalDiagnostics.boundedRedactedLog(
      Seq("a line larger than the budget"),
      maxRows = 10,
      maxBytes = 4,
      contains = None)
    assert(oversizedLine.lines.isEmpty)
    assert(oversizedLine.truncated)
  }

  private def call(body: String) = webTarget.path("/mcp").request()
    .accept("application/json", "text/event-stream")
    .post(Entity.json(body))
}

class KyuubiMcpFrontendAuthenticationSuite extends RestFrontendTestHelper {

  override protected lazy val conf: KyuubiConf = KyuubiConf()
    .set(AUTHENTICATION_METHOD, Seq("CUSTOM"))
    .set(AUTHENTICATION_CUSTOM_CLASS, classOf[UserDefineAuthenticationProviderImpl].getName)
    .set(FRONTEND_MCP_ENABLED, true)

  test("MCP endpoint uses the REST frontend authentication chain") {
    val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""

    val missingCredentials = call(body)
    assert(missingCredentials.getStatus === HttpServletResponse.SC_UNAUTHORIZED)

    val invalidCredentials = call(body, Some(basicAuthorizationHeader("user", "invalid")))
    assert(invalidCredentials.getStatus === HttpServletResponse.SC_FORBIDDEN)

    val validCredentials = call(body, Some(basicAuthorizationHeader("user", "password")))
    assert(validCredentials.getStatus === HttpServletResponse.SC_OK)
    assert(validCredentials.readEntity(classOf[String]).contains("\"name\":\"list_servers\""))

    val serverLogs = call(
      """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":""" +
        """"list_server_logs","arguments":{}}}""",
      Some(basicAuthorizationHeader("user", "password")))
    assert(serverLogs.getStatus === HttpServletResponse.SC_OK)
    val denied = serverLogs.readEntity(classOf[String])
    assert(denied.contains("\"isError\":true"))
    assert(denied.contains("administrator"))
  }

  private def call(body: String, authorization: Option[String] = None) = {
    val request = webTarget.path("/mcp").request()
      .accept("application/json", "text/event-stream")
    authorization.foreach(request.header(AUTHORIZATION_HEADER, _))
    request.post(Entity.json(body))
  }
}

class KyuubiMcpServerLogSuite extends RestFrontendTestHelper {

  private val logRoot = Files.createTempDirectory("kyuubi-mcp-server-logs")
  private val outsideLog = Files.createTempFile("kyuubi-mcp-outside", ".log")
  private val allowedLog = logRoot.resolve("kyuubi-server.log")
  private val deniedFile = logRoot.resolve("credentials.txt")
  Files.write(
    allowedLog,
    Seq(
      "server started",
      "password=top-secret",
      "Authorization: Bearer abc.def.ghi",
      "query failed safely").mkString("\n").getBytes(StandardCharsets.UTF_8))
  Files.write(deniedFile, "must not be visible".getBytes(StandardCharsets.UTF_8))
  Files.write(outsideLog, "must not be reachable".getBytes(StandardCharsets.UTF_8))
  Files.createSymbolicLink(logRoot.resolve("outside.log"), outsideLog)

  override protected lazy val conf: KyuubiConf = KyuubiConf()
    .set(AUTHENTICATION_METHOD, Seq("NONE"))
    .set(FRONTEND_MCP_ENABLED, true)
    .set(FRONTEND_MCP_ALLOW_INSECURE_AUTHENTICATION, true)
    .set(FRONTEND_MCP_SERVER_LOG_DIRECTORIES, Seq(logRoot.toString))

  test("MCP server logs stay inside configured roots and redact credentials") {
    val listResponse = call(
      """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":""" +
        """"list_server_logs","arguments":{}}}""")
    assert(listResponse.getStatus === 200)
    val listed = listResponse.readEntity(classOf[String])
    assert(listed.contains("kyuubi-server.log"))
    assert(!listed.contains("credentials.txt"))
    assert(!listed.contains("outside.log"))
    assert(!listed.contains(logRoot.toString))
    val logId = "\"logId\":\"([^\"]+)\"".r.findFirstMatchIn(listed).get.group(1)

    val readResponse = call(
      s"""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":""" +
        s""""read_server_log","arguments":{"log_id":"$logId","max_lines":3}}}""")
    assert(readResponse.getStatus === 200)
    val content = readResponse.readEntity(classOf[String])
    assert(content.contains("query failed safely"))
    assert(content.contains("[REDACTED]"))
    assert(!content.contains("top-secret"))
    assert(!content.contains("abc.def.ghi"))
    assert(content.contains("\"truncated\":true"))

    val forgedResponse = call(
      """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":""" +
        """"read_server_log","arguments":{"log_id":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}}}""")
    assert(forgedResponse.getStatus === 200)
    val forged = forgedResponse.readEntity(classOf[String])
    assert(forged.contains("\"isError\":true"))
    assert(forged.contains("does not exist or is not accessible"))
  }

  override def afterAll(): Unit = {
    try {
      super.afterAll()
    } finally {
      Utils.deleteDirectoryRecursively(logRoot.toFile)
      Files.deleteIfExists(outsideLog)
    }
  }

  private def call(body: String) = webTarget.path("/mcp").request()
    .accept("application/json", "text/event-stream")
    .post(Entity.json(body))
}

class KyuubiMcpFrontendLdapAuthenticationSuite extends RestClientTestHelper {

  override protected val otherConfigs: Map[String, String] =
    Map(FRONTEND_MCP_ENABLED.key -> "true")

  test("MCP endpoint accepts LDAP credentials through HTTP Basic authentication") {
    val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
    val response = webTarget.path("/mcp").request()
      .accept("application/json", "text/event-stream")
      .header(AUTHORIZATION_HEADER, basicAuthorizationHeader(ldapUser, ldapUserPasswd))
      .post(Entity.json(body))

    assert(response.getStatus === HttpServletResponse.SC_OK)
    assert(response.readEntity(classOf[String]).contains("\"name\":\"list_servers\""))
  }
}
