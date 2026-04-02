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

import java.io.{IOException, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import javax.servlet.http.HttpServletResponse
import javax.ws.rs._
import javax.ws.rs.core.{Context, MediaType}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.Logging
import org.apache.kyuubi.client.api.v1.dto.ChatRequest
import org.apache.kyuubi.operation.{FetchOrientation, OperationState}
import org.apache.kyuubi.server.api.ApiRequestContext
import org.apache.kyuubi.session.{KyuubiSessionImpl, SessionHandle}
import org.apache.kyuubi.shaded.hive.service.rpc.thrift._

@Tag(name = "DataAgent")
@Consumes(Array(MediaType.APPLICATION_JSON))
private[v1] class DataAgentResource extends ApiRequestContext with Logging {
  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(mediaType = "text/event-stream")),
    description = "Send a message to the data agent and receive streaming SSE response")
  @POST
  @Path("{sessionHandle}/chat")
  def chat(
      @PathParam("sessionHandle") sessionHandleStr: String,
      request: ChatRequest,
      @Context response: HttpServletResponse): Unit = {
    try {
      val sessionHandle = SessionHandle.fromUUID(sessionHandleStr)
      val session = fe.be.sessionManager.getSession(sessionHandle)
        .asInstanceOf[KyuubiSessionImpl]
      // Wait for the engine client to become ready (engine connection is async)
      val deadline = System.currentTimeMillis() + 120000 // 2 minutes timeout
      val launchOp = session.launchEngineOp
      var client = session.client
      while (client == null && System.currentTimeMillis() < deadline) {
        val launchStatus = launchOp.getStatus
        if (launchStatus.state == OperationState.ERROR) {
          val errMsg = launchStatus.exception
            .map(_.getMessage).getOrElse("Engine launch failed")
          sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, errMsg)
          return
        }
        Thread.sleep(200)
        client = session.client
      }

      if (client == null) {
        sendJsonError(
          response,
          HttpServletResponse.SC_SERVICE_UNAVAILABLE,
          "Engine session is not ready after waiting")
        return
      }

      val text = request.getText
      if (text == null || text.trim.isEmpty) {
        sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "text is required")
        return
      }

      // Set SSE response headers before writing any data
      response.setBufferSize(0) // disable Jetty output buffering for streaming
      response.setStatus(HttpServletResponse.SC_OK)
      response.setContentType("text/event-stream")
      response.setCharacterEncoding("UTF-8")
      response.setHeader("Cache-Control", "no-cache")
      response.setHeader("Connection", "keep-alive")
      response.setHeader("X-Accel-Buffering", "no")
      response.flushBuffer()

      val outputStream = response.getOutputStream
      val writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)

      // Execute statement asynchronously on the engine
      info(s"Data Agent chat: session=$sessionHandleStr, text=${text.take(100)}")
      val confOverlay = Option(request.getModel)
        .filter(_.nonEmpty)
        .map(m => Map("kyuubi.engine.data.agent.llm.model" -> m))
        .getOrElse(Map.empty[String, String])
      val opHandle = client.executeStatement(text, confOverlay, true, 0L)

      try {
        streamResults(client, opHandle, writer, outputStream)
      } catch {
        case _: IOException =>
          info(s"Client disconnected during SSE stream for session $sessionHandleStr")
          cancelOperation(client, opHandle)
        case NonFatal(e) =>
          warn(s"Error during SSE streaming for session $sessionHandleStr", e)
          try {
            writeSseEvent(
              writer,
              outputStream,
              "error",
              s"""{"message":"${escapeJson(e.getMessage)}"}""")
          } catch {
            case _: IOException => // client already gone
          }
      } finally {
        closeOperation(client, opHandle)
      }
    } catch {
      case NonFatal(e) =>
        error(s"Error processing chat for session $sessionHandleStr", e)
        if (!response.isCommitted) {
          sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage)
        }
    }
  }

  private def streamResults(
      client: org.apache.kyuubi.client.KyuubiSyncThriftClient,
      opHandle: TOperationHandle,
      writer: OutputStreamWriter,
      outputStream: javax.servlet.ServletOutputStream): Unit = {
    // Wait for operation to leave PENDING state before fetching
    waitForRunning(client, opHandle)

    // Phase 1: Poll while operation is running
    var operationDone = false
    while (!operationDone) {
      val rows = fetchAndEmit(client, opHandle, writer, outputStream)

      if (rows == 0) {
        val status = client.getOperationStatus(opHandle)
        val opState = status.getOperationState
        if (isTerminalState(opState)) {
          if (opState == TOperationState.ERROR_STATE) {
            val errMsg = Option(status.getErrorMessage).getOrElse("Unknown error")
            writeSseEvent(writer, outputStream, "error", s"""{"message":"${escapeJson(errMsg)}"}""")
          }
          operationDone = true
        } else {
          Thread.sleep(50)
        }
      }
    }

    // Phase 2: Drain remaining data after operation finishes
    var hasMore = true
    while (hasMore) {
      hasMore = fetchAndEmit(client, opHandle, writer, outputStream) > 0
    }

    writeSseEvent(writer, outputStream, "done", "{}")
  }

  private def waitForRunning(
      client: org.apache.kyuubi.client.KyuubiSyncThriftClient,
      opHandle: TOperationHandle): Unit = {
    var ready = false
    while (!ready) {
      val state = client.getOperationStatus(opHandle).getOperationState
      state match {
        case TOperationState.INITIALIZED_STATE | TOperationState.PENDING_STATE =>
          Thread.sleep(50)
        case _ =>
          ready = true
      }
    }
  }

  private def fetchAndEmit(
      client: org.apache.kyuubi.client.KyuubiSyncThriftClient,
      opHandle: TOperationHandle,
      writer: OutputStreamWriter,
      outputStream: javax.servlet.ServletOutputStream): Int = {
    val rowSet = client.fetchResults(opHandle, FetchOrientation.FETCH_NEXT, 10, false)
    val rows = extractStringRows(rowSet)
    for (row <- rows) {
      val eventType = extractJsonType(row)
      writeSseEvent(writer, outputStream, eventType, row)
    }
    rows.size
  }

  /** Extract the "type" field from a JSON string for use as SSE event name. */
  private def extractJsonType(json: String): String = {
    // Simple extraction to avoid adding a JSON library dependency
    val pattern = """"type"\s*:\s*"([^"]+)"""".r
    pattern.findFirstMatchIn(json).map(_.group(1)).getOrElse("message")
  }

  private def extractStringRows(rowSet: TRowSet): Seq[String] = {
    if (rowSet == null) return Seq.empty
    // Engine returns column-based format with a single string column
    val columns = rowSet.getColumns
    if (columns != null && !columns.isEmpty) {
      val stringCol = columns.get(0).getStringVal
      if (stringCol != null) {
        return stringCol.getValues.asScala.toSeq
      }
    }
    // Fallback: row-based format
    val rows = rowSet.getRows
    if (rows != null && !rows.isEmpty) {
      return rows.asScala.map { row =>
        val colVals = row.getColVals
        if (colVals != null && !colVals.isEmpty) {
          colVals.get(0).getStringVal.getValue
        } else ""
      }.toSeq
    }
    Seq.empty
  }

  private def isTerminalState(state: TOperationState): Boolean = {
    state == TOperationState.FINISHED_STATE ||
    state == TOperationState.CANCELED_STATE ||
    state == TOperationState.CLOSED_STATE ||
    state == TOperationState.ERROR_STATE ||
    state == TOperationState.TIMEDOUT_STATE
  }

  private def writeSseEvent(
      writer: OutputStreamWriter,
      outputStream: javax.servlet.ServletOutputStream,
      event: String,
      data: String): Unit = {
    writer.write(s"event: $event\ndata: $data\n\n")
    writer.flush()
    outputStream.flush() // force Jetty to send the chunk to the network
  }

  private def escapeJson(s: String): String = {
    if (s == null) return ""
    s.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
  }

  private def sendJsonError(
      response: HttpServletResponse,
      status: Int,
      message: String): Unit = {
    response.setStatus(status)
    response.setContentType("application/json")
    response.setCharacterEncoding("UTF-8")
    response.getWriter.write(s"""{"message":"${escapeJson(message)}"}""")
    response.getWriter.flush()
  }

  private def cancelOperation(
      client: org.apache.kyuubi.client.KyuubiSyncThriftClient,
      opHandle: TOperationHandle): Unit = {
    try {
      client.cancelOperation(opHandle)
    } catch {
      case NonFatal(e) =>
        debug(s"Failed to cancel operation on client disconnect", e)
    }
  }

  private def closeOperation(
      client: org.apache.kyuubi.client.KyuubiSyncThriftClient,
      opHandle: TOperationHandle): Unit = {
    try {
      client.closeOperation(opHandle)
    } catch {
      case NonFatal(e) =>
        debug(s"Failed to close operation", e)
    }
  }
}
