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

import scala.collection.JavaConverters._

import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam

import org.apache.kyuubi.KyuubiFunSuite

class ReactAgentSuite extends KyuubiFunSuite {

  test("conversation memory manages messages correctly") {
    val memory = new ConversationMemory(100)
    memory.setSystemPrompt("You are a test agent.")
    memory.addUserMessage("Hello")

    val messages = memory.getMessages.asScala
    assert(messages.size == 2) // system + user
    assert(messages.head.isSystem, "First message should be system")
    assert(messages(1).isUser, "Second message should be user")
  }

  test("conversation memory windowing skips tool result orphans") {
    val memory = new ConversationMemory(3)
    memory.addUserMessage("q1")
    memory.addAssistantMessage(
      ChatCompletionAssistantMessageParam.builder().content("thinking").build())
    memory.addToolResult("call-1", "result-1")
    memory.addUserMessage("q2")
    memory.addUserMessage("q3")

    val messages = memory.getMessages.asScala
    // Should not start with a tool result message
    assert(!messages.head.isTool, "Should not start with a tool message")
  }

  test("middleware can suppress events") {
    val suppressDelta: AgentMiddleware = new AgentMiddleware {
      override def onEvent(ctx: AgentContext, event: AgentEvent): AgentEvent = {
        event match {
          case _: AgentEvent.ContentDelta => null
          case other => other
        }
      }
    }

    val ctx = new AgentContext("test", new ConversationMemory(100), ApprovalMode.YOLO)
    val middlewares = java.util.Collections.singletonList(suppressDelta)

    val delta = new AgentEvent.ContentDelta("hello")
    var filtered: AgentEvent = delta
    for (mw <- middlewares.asScala) {
      filtered = mw.onEvent(ctx, filtered)
    }
    assert(filtered == null, "Middleware should suppress ContentDelta")

    val stepStart = new AgentEvent.StepStart(1)
    var filtered2: AgentEvent = stepStart
    for (mw <- middlewares.asScala) {
      filtered2 = mw.onEvent(ctx, filtered2)
    }
    assert(filtered2 != null, "Middleware should not suppress StepStart")
  }
}
