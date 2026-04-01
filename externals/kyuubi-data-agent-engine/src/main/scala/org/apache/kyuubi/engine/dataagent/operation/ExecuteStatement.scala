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
import org.apache.kyuubi.engine.dataagent.runtime.AgentEvent
import org.apache.kyuubi.engine.dataagent.provider.DataAgentProvider
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

  private def executeStatement(): Unit = {
    setState(OperationState.RUNNING)

    try {
      val sessionId = session.handle.identifier.toString
      dataAgentProvider.run(
        sessionId,
        statement,
        { (event: AgentEvent) =>
          event match {
            case delta: AgentEvent.ContentDelta =>
              incrementalIter.append(Array(delta.text()))
            case complete: AgentEvent.ContentComplete =>
            // ContentComplete is for the full text — already streamed via ContentDelta
            case toolCall: AgentEvent.ToolCall =>
              incrementalIter.append(Array(
                s"\n[Tool: ${toolCall.toolName()}] ${toolCall.toolArgs()}\n"))
            case toolResult: AgentEvent.ToolResult =>
              incrementalIter.append(Array(
                s"\n[Result: ${toolResult.toolName()}] ${toolResult.output()}\n"))
            case stepStart: AgentEvent.StepStart =>
            // optional: could emit step markers
            case err: AgentEvent.AgentError =>
              incrementalIter.append(Array(s"\n[Error] ${err.message()}\n"))
            case _: AgentEvent.AgentFinish =>
            // terminal event
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
