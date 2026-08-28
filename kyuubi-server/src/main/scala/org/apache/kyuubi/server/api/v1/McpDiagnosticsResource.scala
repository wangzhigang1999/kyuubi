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

package org.apache.kyuubi.server.api.v1

import javax.ws.rs.{Consumes, ForbiddenException, POST, Produces}
import javax.ws.rs.core.MediaType

import scala.collection.JavaConverters._

import com.fasterxml.jackson.databind.ObjectMapper

import org.apache.kyuubi.server.api.ApiRequestContext
import org.apache.kyuubi.server.http.authentication.{AuthenticationFilter, AuthSchemes}
import org.apache.kyuubi.server.mcp.{KyuubiMcpLocalDiagnostics, KyuubiMcpPrincipal}

@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class McpDiagnosticsResource extends ApiRequestContext {

  @POST
  def execute(request: java.util.Map[String, Object]): java.util.Map[String, Object] = {
    if (AuthenticationFilter.getAuthType != AuthSchemes.KYUUBI_INTERNAL.toString) {
      throw new ForbiddenException("The diagnostic endpoint only accepts Kyuubi internal access")
    }

    val action = Option(request.get("action"))
      .map(_.toString)
      .filter(_.nonEmpty)
      .getOrElse(throw new IllegalArgumentException("action is required"))
    val arguments = Option(request.get("arguments")) match {
      case Some(values: java.util.Map[_, _]) =>
        values.asInstanceOf[java.util.Map[String, AnyRef]].asScala.toMap
      case Some(values: scala.collection.Map[_, _]) =>
        values.asInstanceOf[scala.collection.Map[String, AnyRef]].toMap
      case Some(_) => throw new IllegalArgumentException("arguments must be an object")
      case None => Map.empty[String, AnyRef]
    }
    val realUser = fe.getRealUser()
    val principal = KyuubiMcpPrincipal(
      realUser,
      fe.getIpAddress,
      fe.isAdministrator(realUser))
    new KyuubiMcpLocalDiagnostics(fe, new ObjectMapper()).execute(action, arguments, principal)
  }
}
