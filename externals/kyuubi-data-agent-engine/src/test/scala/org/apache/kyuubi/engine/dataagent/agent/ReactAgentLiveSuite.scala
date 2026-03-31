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

package org.apache.kyuubi.engine.dataagent.agent

import java.util.concurrent.CopyOnWriteArrayList

import scala.collection.JavaConverters._

import dev.langchain4j.model.openai.OpenAiStreamingChatModel

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.engine.dataagent.tool.ToolRegistry

/**
 * Live integration test with a real LLM (Qwen via DashScope).
 * Requires TEAM_API_AK_API environment variable.
 */
class ReactAgentLiveSuite extends KyuubiFunSuite {

  private val apiKey = sys.env.getOrElse("DASHSCOPE_API_KEY", "")
  private val baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
  private val modelName = "qwen3.5-plus-2026-02-15"

  override def beforeAll(): Unit = {
    super.beforeAll()
    assume(apiKey.nonEmpty, "DASHSCOPE_API_KEY not set, skipping live tests")
  }

  test("live streaming with Qwen model - token-by-token") {
    val model = OpenAiStreamingChatModel.builder()
      .apiKey(apiKey)
      .baseUrl(baseUrl)
      .modelName(modelName)
      .build()

    val agent = ReactAgent.builder()
      .model(model)
      .toolRegistry(new ToolRegistry())
      .maxIterations(3)
      .systemPrompt(
        "You are a data analysis agent. Answer concisely in 1-2 sentences.")
      .build()

    val events = new CopyOnWriteArrayList[AgentEvent]()
    val memory = new ConversationMemory(100)

    agent.run(
      "What is Apache Kyuubi?",
      memory,
      ApprovalMode.YOLO,
      e => events.add(e))

    val eventList = events.asScala.toList

    // Print event stream for visual inspection
    // scalastyle:off println
    println("=== Event Stream ===")
    var deltaCount = 0
    val fullText = new StringBuilder
    eventList.foreach {
      case s: AgentEvent.StepStart =>
        println(s"[Step ${s.stepNumber()}]")
      case d: AgentEvent.ContentDelta =>
        deltaCount += 1
        fullText.append(d.text())
        print(d.text()) // real-time token streaming to console
      case c: AgentEvent.ContentComplete =>
        println(s"\n[Complete] ${c.fullText().take(100)}...")
      case f: AgentEvent.AgentFinish =>
        println(s"[Finish] steps=${f.totalSteps()}")
      case e: AgentEvent.AgentError =>
        println(s"[Error] ${e.message()}")
      case _ =>
    }
    println(s"\nTotal ContentDelta events: $deltaCount")
    println(s"Streamed text: ${fullText.toString().take(200)}")
    // scalastyle:on println

    // Verify token-level streaming actually happened
    assert(deltaCount > 1, s"Expected multiple ContentDelta events for streaming, got $deltaCount")
    assert(fullText.nonEmpty, "Streamed text should not be empty")
    assert(eventList.exists(_.isInstanceOf[AgentEvent.StepStart]))
    assert(eventList.exists(_.isInstanceOf[AgentEvent.ContentComplete]))
    assert(eventList.last.isInstanceOf[AgentEvent.AgentFinish])

    // Verify memory recorded the conversation
    val rawMessages = memory.getRawMessages.asScala
    assert(rawMessages.size == 2) // user + AI
  }
}
