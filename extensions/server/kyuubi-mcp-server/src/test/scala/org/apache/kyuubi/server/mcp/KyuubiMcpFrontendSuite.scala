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

import javax.servlet.http.HttpServletResponse
import javax.ws.rs.client.Entity

import org.apache.kyuubi.{RestClientTestHelper, RestFrontendTestHelper}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.server.http.util.HttpAuthUtils.{basicAuthorizationHeader, AUTHORIZATION_HEADER}
import org.apache.kyuubi.service.authentication.UserDefineAuthenticationProviderImpl

class KyuubiMcpFrontendSuite extends RestFrontendTestHelper {

  override protected lazy val conf: KyuubiConf = KyuubiConf()
    .set(AUTHENTICATION_METHOD, Seq("NONE"))
    .set(FRONTEND_MCP_ENABLED, true)

  test("MCP endpoint") {
    val response = call("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
    assert(response.getStatus === 200)
    val tools = response.readEntity(classOf[String])
    Seq(
      "list_servers",
      "list_engines",
      "list_sessions",
      "get_session",
      "list_operations",
      "get_operation",
      "read_operation_log").foreach(tool => assert(tools.contains("\"name\":\"" + tool + "\"")))

    val callResponse = call(
      """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_sessions","arguments":{}}}""")
    assert(callResponse.getStatus === 200)
    val callResult = callResponse.readEntity(classOf[String])
    assert(callResult.contains("\"isError\":false"))
    assert(callResult.contains("\"sessions\":[]"))
    assert(callResult.contains("\"partial\":false"))
    assert(callResult.contains("\"discoveredServers\":1"))
    assert(callResult.contains("\"respondedServers\":1"))

    val missingArgumentResponse = call(
      """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_session","arguments":{}}}""")
    assert(missingArgumentResponse.getStatus === 200)
    val missingArgumentResult = missingArgumentResponse.readEntity(classOf[String])
    assert(missingArgumentResult.contains("\"isError\":true"))
    assert(missingArgumentResult.contains("session_id"))
  }

  test("MCP transport rejects invalid requests without exposing a stack trace") {
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
  }

  private def call(body: String, authorization: Option[String] = None) = {
    val request = webTarget.path("/mcp").request()
      .accept("application/json", "text/event-stream")
    authorization.foreach(request.header(AUTHORIZATION_HEADER, _))
    request.post(Entity.json(body))
  }
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
