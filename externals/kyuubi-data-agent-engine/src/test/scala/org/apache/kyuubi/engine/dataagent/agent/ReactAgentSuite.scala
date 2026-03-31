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

import dev.langchain4j.data.message.{AiMessage, ChatMessage}
import dev.langchain4j.model.chat.StreamingChatLanguageModel
import dev.langchain4j.model.chat.response.{ChatResponse, StreamingChatResponseHandler}

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.engine.dataagent.tool.ToolRegistry

class ReactAgentSuite extends KyuubiFunSuite {

  /** A fake streaming model that returns a fixed response, simulating token-level chunks. */
  private def fakeModel(reply: String): StreamingChatLanguageModel = {
    new StreamingChatLanguageModel {
      override def chat(
          messages: java.util.List[ChatMessage],
          handler: StreamingChatResponseHandler): Unit = {
        // Simulate token-level streaming
        reply.split("(?<=\\s)").foreach { token =>
          handler.onPartialResponse(token)
        }
        handler.onCompleteResponse(
          ChatResponse.builder().aiMessage(AiMessage.from(reply)).build())
      }
    }
  }

  test("basic ReAct loop emits correct event sequence") {
    val model = fakeModel("Hello, I can help you analyze data.")
    val agent = ReactAgent.builder()
      .model(model)
      .toolRegistry(new ToolRegistry())
      .maxIterations(5)
      .systemPrompt("You are a test agent.")
      .build()

    val events = new CopyOnWriteArrayList[AgentEvent]()
    val memory = new ConversationMemory(100)

    agent.run("test question", memory, ApprovalMode.YOLO, e => events.add(e))

    val eventList = events.asScala.toList

    // Verify event sequence: StepStart → ContentDelta* → ContentComplete → AgentFinish
    assert(eventList.head.isInstanceOf[AgentEvent.StepStart])
    assert(eventList.head.asInstanceOf[AgentEvent.StepStart].stepNumber() == 1)

    val deltas = eventList.filter(_.isInstanceOf[AgentEvent.ContentDelta])
    assert(deltas.nonEmpty, "Should have ContentDelta events for streaming")

    val deltaText = deltas
      .map(_.asInstanceOf[AgentEvent.ContentDelta].text())
      .mkString("")
    assert(deltaText.contains("Hello"))

    assert(eventList.exists(_.isInstanceOf[AgentEvent.ContentComplete]))
    assert(eventList.last.isInstanceOf[AgentEvent.AgentFinish])
    assert(eventList.last.asInstanceOf[AgentEvent.AgentFinish].totalSteps() == 1)
  }

  test("conversation memory records user and AI messages") {
    val model = fakeModel("The answer is 42.")
    val agent = ReactAgent.builder()
      .model(model)
      .toolRegistry(new ToolRegistry())
      .maxIterations(5)
      .systemPrompt("You are a test agent.")
      .build()

    val memory = new ConversationMemory(100)
    agent.run("What is the answer?", memory, ApprovalMode.YOLO, _ => ())

    // Memory should contain: user message + AI message
    val raw = memory.getRawMessages.asScala
    assert(raw.size == 2)
    assert(raw.head.isInstanceOf[dev.langchain4j.data.message.UserMessage])
    assert(raw(1).isInstanceOf[AiMessage])
  }

  test("middleware can suppress events") {
    val model = fakeModel("Hello world")
    val suppressDelta: AgentMiddleware = new AgentMiddleware {
      override def onEvent(ctx: AgentContext, event: AgentEvent): AgentEvent = {
        event match {
          case _: AgentEvent.ContentDelta => null // suppress all deltas
          case other => other
        }
      }
    }

    val agent = ReactAgent.builder()
      .model(model)
      .toolRegistry(new ToolRegistry())
      .addMiddleware(suppressDelta)
      .maxIterations(5)
      .systemPrompt("test")
      .build()

    val events = new CopyOnWriteArrayList[AgentEvent]()
    agent.run("hi", new ConversationMemory(100), ApprovalMode.YOLO, e => events.add(e))

    val deltas = events.asScala.count(_.isInstanceOf[AgentEvent.ContentDelta])
    assert(deltas == 0, "Middleware should have suppressed all ContentDelta events")
    assert(events.asScala.exists(_.isInstanceOf[AgentEvent.AgentFinish]))
  }

  test("max iterations limit is enforced") {
    // Model always returns text (no tool calls), but we set maxIterations=1
    val model = fakeModel("thinking...")
    val agent = ReactAgent.builder()
      .model(model)
      .toolRegistry(new ToolRegistry())
      .maxIterations(1)
      .systemPrompt("test")
      .build()

    val events = new CopyOnWriteArrayList[AgentEvent]()
    agent.run("question", new ConversationMemory(100), ApprovalMode.YOLO, e => events.add(e))

    // With no tool calls, the agent should finish in 1 step
    assert(events.asScala.last.isInstanceOf[AgentEvent.AgentFinish])
  }
}
