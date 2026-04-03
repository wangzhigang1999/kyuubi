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
package org.apache.kyuubi.engine.dataagent.operation

import org.apache.kyuubi.Logging
import org.apache.kyuubi.engine.dataagent.provider.DataAgentProvider
import org.apache.kyuubi.operation.{ArrayFetchIterator, OperationState}
import org.apache.kyuubi.session.Session

/**
 * A lightweight synchronous operation that resolves a pending tool approval request.
 *
 * The client sends a statement with the format `__approve:<requestId>` or `__deny:<requestId>`.
 * This operation parses the command, calls the provider's `resolveApproval`, and returns
 * a single-row result indicating whether the resolution succeeded.
 */
class ApproveToolCall(
    session: Session,
    override val statement: String,
    dataAgentProvider: DataAgentProvider)
  extends DataAgentOperation(session) with Logging {

  override val shouldRunAsync: Boolean = false

  override protected def runInternal(): Unit = {
    setState(OperationState.RUNNING)

    try {
      val trimmed = statement.trim
      val (requestId, approved) = if (trimmed.startsWith(ApproveToolCall.APPROVE_PREFIX)) {
        (trimmed.substring(ApproveToolCall.APPROVE_PREFIX.length).trim, true)
      } else if (trimmed.startsWith(ApproveToolCall.DENY_PREFIX)) {
        (trimmed.substring(ApproveToolCall.DENY_PREFIX.length).trim, false)
      } else {
        throw new IllegalArgumentException(s"Invalid approval command: $trimmed")
      }

      val resolved = dataAgentProvider.resolveApproval(requestId, approved)
      val action = if (approved) "approved" else "denied"
      val safeId = escapeJson(requestId)
      val result = if (resolved) {
        s"""{"status":"ok","action":"$action","requestId":"$safeId"}"""
      } else {
        s"""{"status":"not_found","action":"$action","requestId":"$safeId"}"""
      }

      iter = new ArrayFetchIterator[Array[String]](Array(Array(result)))
      setState(OperationState.FINISHED)
    } catch {
      onError(cancel = true)
    }
  }

  private def escapeJson(s: String): String = {
    if (s == null) return ""
    val sb = new StringBuilder(s.length)
    s.foreach {
      case '\\' => sb.append("\\\\")
      case '"' => sb.append("\\\"")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c if c < 0x20 => sb.append("\\u%04x".format(c.toInt))
      case c => sb.append(c)
    }
    sb.toString
  }
}

object ApproveToolCall {
  val APPROVE_PREFIX = "__approve:"
  val DENY_PREFIX = "__deny:"

  def isApprovalCommand(statement: String): Boolean = {
    val trimmed = statement.trim
    trimmed.startsWith(APPROVE_PREFIX) || trimmed.startsWith(DENY_PREFIX)
  }
}
