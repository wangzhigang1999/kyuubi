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

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

import com.fasterxml.jackson.core.`type`.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper

import org.apache.kyuubi.operation.{FetchOrientation, KyuubiOperation, OperationHandle}
import org.apache.kyuubi.server.KyuubiRestFrontendService
import org.apache.kyuubi.server.api.ApiUtils
import org.apache.kyuubi.session.{KyuubiSession, SessionHandle}

private[server] case class KyuubiMcpPrincipal(
    realUser: String,
    clientIp: String,
    administrator: Boolean)

/**
 * Node-local diagnostic primitives. Public MCP tools never expose these methods directly: the
 * cluster coordinator invokes them locally or through the authenticated internal REST endpoint.
 */
private[server] class KyuubiMcpLocalDiagnostics(
    frontendService: KyuubiRestFrontendService,
    objectMapper: ObjectMapper) {

  import KyuubiMcpLocalDiagnostics._

  private val logSandbox = new KyuubiMcpLogSandbox(frontendService)

  def execute(
      action: String,
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = action match {
    case CLUSTER_OVERVIEW => clusterOverview(arguments, principal)
    case LIST_SESSIONS => listSessions(arguments, principal)
    case GET_SESSION => getSession(arguments, principal)
    case LIST_OPERATIONS => listOperations(arguments, principal)
    case GET_OPERATION => getOperation(arguments, principal)
    case READ_OPERATION_LOG => readOperationLog(arguments, principal)
    case LIST_SERVER_LOGS => logSandbox.list(arguments, principal)
    case READ_SERVER_LOG => logSandbox.read(arguments, principal)
    case _ => throw new IllegalArgumentException(s"Unsupported diagnostic action: $action")
  }

  private def clusterOverview(
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = {
    val requestedUser = stringArgument(arguments, "user")
    if (requestedUser.exists(_ != principal.realUser) && !principal.administrator) {
      throw new IllegalArgumentException("The requested user is not accessible.")
    }
    val sessions = frontendService.sessionManager.allSessions()
      .collect { case session: KyuubiSession => session }
      .filter(session => canAccess(principal, session.user))
      .filter(session => requestedUser.forall(_ == session.user))
      .toSeq
    val operations = frontendService.sessionManager.operationManager.allOperations()
      .collect { case operation: KyuubiOperation => operation }
      .filter(operation => canAccess(principal, operation.getSession.user))
      .filter(operation => requestedUser.forall(_ == operation.getSession.user))
      .toSeq
    val operationStates = operations.groupBy(_.getStatus.toString).map { case (state, values) =>
      state -> Int.box(values.size)
    }.asJava
    val sessionTypes = sessions.groupBy(_.sessionType.toString).map { case (sessionType, values) =>
      sessionType -> Int.box(values.size)
    }.asJava
    Map[String, Object](
      "server" -> frontendService.connectionUrl,
      "sessionCount" -> Int.box(sessions.size),
      "sessionTypes" -> sessionTypes,
      "operationCount" -> Int.box(operations.size),
      "operationStates" -> operationStates).asJava
  }

  private def listSessions(
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = {
    val requestedUser = stringArgument(arguments, "user")
    if (requestedUser.exists(_ != principal.realUser) && !principal.administrator) {
      throw new IllegalArgumentException("The requested user is not accessible.")
    }
    val sessionType = stringArgument(arguments, "session_type")
    val limit = boundedIntArgument(arguments, "limit", 100, 200)
    val sessions = frontendService.sessionManager.allSessions()
      .collect { case session: KyuubiSession => session }
      .filter(session => principal.administrator || session.user == principal.realUser)
      .filter(session => requestedUser.forall(_ == session.user))
      .filter(session => sessionType.forall(_.equalsIgnoreCase(session.sessionType.toString)))
      .toSeq
      .sortBy(_.createTime)(Ordering.Long.reverse)
      .take(limit)
      .map(session => safeSessionData(ApiUtils.sessionData(session)))
      .asJava
    Map[String, Object]("items" -> sessions).asJava
  }

  private def getSession(
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = {
    val session =
      try {
        val sessionId = requiredStringArgument(arguments, "session_id")
        frontendService.sessionManager.getSessionOption(SessionHandle.fromUUID(sessionId)) match {
          case Some(value: KyuubiSession) if canAccess(principal, value.user) =>
            Some(safeSessionData(ApiUtils.sessionData(value)))
          case _ => None
        }
      } catch {
        case NonFatal(_) => None
      }
    optionalValue(session)
  }

  private def listOperations(
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = {
    val requestedUser = stringArgument(arguments, "user")
    if (requestedUser.exists(_ != principal.realUser) && !principal.administrator) {
      throw new IllegalArgumentException("The requested user is not accessible.")
    }
    val sessionId = stringArgument(arguments, "session_id")
    val state = stringArgument(arguments, "state")
    val limit = boundedIntArgument(arguments, "limit", 100, 200)
    val operations = frontendService.sessionManager.operationManager.allOperations()
      .collect { case operation: KyuubiOperation => operation }
      .filter(operation => canAccess(principal, operation.getSession.user))
      .filter(operation => requestedUser.forall(_ == operation.getSession.user))
      .filter(operation => sessionId.forall(_ == operation.getSession.handle.identifier.toString))
      .filter(operation => state.forall(_.equalsIgnoreCase(operation.getStatus.toString)))
      .toSeq
      .sortBy(_.getOperationEvent.createTime)(Ordering.Long.reverse)
      .take(limit)
      .map(operation => safeOperationData(ApiUtils.operationData(operation)))
      .asJava
    Map[String, Object]("items" -> operations).asJava
  }

  private def getOperation(
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = {
    val operationId = requiredStringArgument(arguments, "operation_id")
    optionalValue(accessibleOperation(operationId, principal).map(operation =>
      safeOperationData(ApiUtils.operationData(operation))))
  }

  private def readOperationLog(
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = {
    val operationId = requiredStringArgument(arguments, "operation_id")
    accessibleOperation(operationId, principal) match {
      case Some(_) =>
        val maxRows = boundedIntArgument(arguments, "max_rows", 100, 1000)
        val maxBytes = boundedIntArgument(arguments, "max_bytes", 64 * 1024, 256 * 1024)
        val contains = stringArgument(arguments, "contains")
        if (contains.exists(_.length > 128)) {
          throw new IllegalArgumentException("contains must not exceed 128 characters")
        }
        val result = frontendService.sessionManager.operationManager.getOperationLogRowSet(
          OperationHandle(operationId),
          FetchOrientation.FETCH_FIRST,
          maxRows + 1)
        val rowSet = result.getResults
        val sourceLines = if (rowSet.getColumns == null || rowSet.getColumns.isEmpty) {
          Seq.empty[String]
        } else {
          rowSet.getColumns.get(0).getStringVal.getValues.asScala.toSeq
        }
        val content = boundedRedactedLog(sourceLines, maxRows, maxBytes, contains)
        optionalValue(Some(Map[String, Object](
          "operationId" -> operationId,
          "kyuubiInstance" -> frontendService.connectionUrl,
          "lines" -> content.lines.asJava,
          "count" -> Int.box(content.lines.size),
          "maxRows" -> Int.box(maxRows),
          "maxBytes" -> Int.box(maxBytes),
          "truncated" -> Boolean.box(content.truncated),
          "redacted" -> Boolean.box(true)).asJava))
      case None => optionalValue(None)
    }
  }

  private def accessibleOperation(
      operationId: String,
      principal: KyuubiMcpPrincipal): Option[KyuubiOperation] = {
    try {
      frontendService.sessionManager.operationManager.getOperation(OperationHandle(
        operationId)) match {
        case operation: KyuubiOperation if canAccess(principal, operation.getSession.user) =>
          Some(operation)
        case _ => None
      }
    } catch {
      case NonFatal(_) => None
    }
  }

  private def optionalValue(
      value: Option[java.util.Map[String, Object]]): java.util.Map[String, Object] =
    Map[String, Object](
      "found" -> Boolean.box(value.nonEmpty),
      "value" -> value.orNull).asJava

  private def canAccess(principal: KyuubiMcpPrincipal, owner: String): Boolean =
    principal.administrator || owner == principal.realUser

  private def safeSessionData(value: Object): java.util.Map[String, Object] =
    safeSessionProjection(objectMapper.convertValue(value, MAP_TYPE))

  private def safeOperationData(value: Object): java.util.Map[String, Object] =
    safeOperationProjection(objectMapper.convertValue(value, MAP_TYPE))
}

private[server] object KyuubiMcpLocalDiagnostics {
  val CLUSTER_OVERVIEW = "get_cluster_overview"
  val LIST_SESSIONS = "list_sessions"
  val GET_SESSION = "get_session"
  val LIST_OPERATIONS = "list_operations"
  val GET_OPERATION = "get_operation"
  val READ_OPERATION_LOG = "read_operation_log"
  val LIST_SERVER_LOGS = "list_server_logs"
  val READ_SERVER_LOG = "read_server_log"

  private val MAP_TYPE = new TypeReference[java.util.Map[String, Object]]() {}
  private val SAFE_SESSION_FIELDS = Seq(
    "identifier",
    "user",
    "createTime",
    "duration",
    "idleTime",
    "sessionType",
    "kyuubiInstance",
    "engineId",
    "engineName",
    "engineUrl",
    "totalOperations")
  private val SAFE_OPERATION_FIELDS = Seq(
    "identifier",
    "state",
    "createTime",
    "startTime",
    "completeTime",
    "sessionId",
    "sessionUser",
    "sessionType",
    "kyuubiInstance",
    "metrics")

  private[server] case class BoundedLog(lines: Seq[String], truncated: Boolean)

  private[server] def safeSessionProjection(
      source: java.util.Map[String, Object]): java.util.Map[String, Object] =
    safeProjection(source, SAFE_SESSION_FIELDS)

  private[server] def safeOperationProjection(
      source: java.util.Map[String, Object]): java.util.Map[String, Object] =
    safeProjection(source, SAFE_OPERATION_FIELDS)

  private def safeProjection(
      source: java.util.Map[String, Object],
      allowedFields: Seq[String]): java.util.Map[String, Object] =
    allowedFields.flatMap(name => Option(source.get(name)).map(name -> _)).toMap.asJava

  private[server] def boundedRedactedLog(
      source: Seq[String],
      maxRows: Int,
      maxBytes: Int,
      contains: Option[String]): BoundedLog = {
    val filter = contains.map(_.toLowerCase(java.util.Locale.ROOT))
    val lines = ListBuffer[String]()
    var size = 0
    var truncated = false
    source.foreach { rawLine =>
      if (filter.forall(value => rawLine.toLowerCase(java.util.Locale.ROOT).contains(value))) {
        val line = KyuubiMcpLogSandbox.redact(rawLine)
        val lineSize = line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1
        if (lines.size >= maxRows || size + lineSize > maxBytes) {
          truncated = true
        } else {
          lines += line
          size += lineSize
        }
      }
    }
    BoundedLog(lines.toSeq, truncated)
  }

  def stringArgument(arguments: Map[String, AnyRef], name: String): Option[String] =
    arguments.get(name).map(_.toString.trim).filter(_.nonEmpty)

  def requiredStringArgument(arguments: Map[String, AnyRef], name: String): String =
    stringArgument(arguments, name)
      .getOrElse(throw new IllegalArgumentException(s"$name is required"))

  def boundedIntArgument(
      arguments: Map[String, AnyRef],
      name: String,
      defaultValue: Int,
      maximum: Int): Int = {
    val value = arguments.get(name).map {
      case number: Number => number.intValue()
      case other => other.toString.toInt
    }.getOrElse(defaultValue)
    if (value < 1 || value > maximum) {
      throw new IllegalArgumentException(s"$name must be between 1 and $maximum")
    }
    value
  }
}
