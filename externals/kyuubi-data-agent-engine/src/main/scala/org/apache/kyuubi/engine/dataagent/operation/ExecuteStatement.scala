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

import java.util.concurrent.RejectedExecutionException

import org.apache.kyuubi.{KyuubiSQLException, Logging}
import org.apache.kyuubi.engine.dataagent.provider.DataAgentProvider
import org.apache.kyuubi.engine.dataagent.runtime.event.{AgentError, AgentEvent, ContentDelta, EventType, ToolCall, ToolResult}
import org.apache.kyuubi.operation.OperationState
import org.apache.kyuubi.operation.log.OperationLog
import org.apache.kyuubi.session.Session

class ExecuteStatement(
    session: Session,
    override val statement: String,
    override val shouldRunAsync: Boolean,
    queryTimeout: Long,
    dataAgentProvider: DataAgentProvider)
  extends DataAgentOperation(session) with Logging {

  private val operationLog: OperationLog = OperationLog.createOperationLog(session, getHandle)
  override def getOperationLog: Option[OperationLog] = Option(operationLog)

  private val incrementalIter = new IncrementalFetchIterator[Array[String]]()

  override protected def runInternal(): Unit = {
    addTimeoutMonitor(queryTimeout)
    iter = incrementalIter

    val asyncOperation = new Runnable {
      override def run(): Unit = {
        executeStatement()
      }
    }

    try {
      val sessionManager = session.sessionManager
      val backgroundHandle = sessionManager.submitBackgroundOperation(asyncOperation)
      setBackgroundHandle(backgroundHandle)
    } catch {
      case rejected: RejectedExecutionException =>
        setState(OperationState.ERROR)
        val ke =
          KyuubiSQLException("Error submitting query in background, query rejected", rejected)
        setOperationException(ke)
        shutdownTimeoutMonitor()
        throw ke
    }
  }

  private def escapeJson(s: String): String = {
    if (s == null) return ""
    s.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
  }

  private def executeStatement(): Unit = {
    setState(OperationState.RUNNING)

    try {
      val sessionId = session.handle.identifier.toString
      dataAgentProvider.run(
        sessionId,
        statement,
        { (event: AgentEvent) =>
          val sseType = event.eventType().sseEventName()
          event.eventType() match {
            case EventType.CONTENT_DELTA =>
              val delta = event.asInstanceOf[ContentDelta]
              incrementalIter.append(Array(
                s"""{"type":"$sseType","text":"${escapeJson(delta.text())}"}"""))
            case EventType.TOOL_CALL =>
              val toolCall = event.asInstanceOf[ToolCall]
              incrementalIter.append(Array(
                s"""{"type":"$sseType","name":"${escapeJson(toolCall.toolName())}",""" +
                  s""""args":"${escapeJson(toolCall.toolArgs().toString)}"}"""))
            case EventType.TOOL_RESULT =>
              val toolResult = event.asInstanceOf[ToolResult]
              incrementalIter.append(Array(
                s"""{"type":"$sseType","name":"${escapeJson(toolResult.toolName())}",""" +
                  s""""output":"${escapeJson(toolResult.output())}"}"""))
            case EventType.ERROR =>
              val err = event.asInstanceOf[AgentError]
              incrementalIter.append(Array(
                s"""{"type":"$sseType","message":"${escapeJson(err.message())}"}"""))
            case EventType.FINISH =>
              incrementalIter.append(Array(s"""{"type":"$sseType"}"""))
            case _ => // CONTENT_COMPLETE, STEP_START — not sent over SSE
          }
        })

      setState(OperationState.FINISHED)
    } catch {
      onError(true)
    } finally {
      shutdownTimeoutMonitor()
    }
  }
}
