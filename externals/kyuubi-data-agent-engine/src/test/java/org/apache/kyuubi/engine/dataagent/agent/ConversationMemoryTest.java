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

package org.apache.kyuubi.engine.dataagent.agent;

import static org.junit.Assert.*;

import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import java.util.List;
import org.junit.Test;

public class ConversationMemoryTest {

  @Test
  public void testManagesMessagesCorrectly() {
    ConversationMemory memory = new ConversationMemory(100);
    memory.setSystemPrompt("You are a test agent.");
    memory.addUserMessage("Hello");

    List<ChatCompletionMessageParam> messages = memory.getMessages();
    assertEquals(2, messages.size());
    assertTrue("First message should be system", messages.get(0).isSystem());
    assertTrue("Second message should be user", messages.get(1).isUser());
  }

  @Test
  public void testWindowingSkipsToolResultOrphans() {
    ConversationMemory memory = new ConversationMemory(3);
    memory.addUserMessage("q1");
    memory.addAssistantMessage(
        ChatCompletionAssistantMessageParam.builder().content("thinking").build());
    memory.addToolResult("call-1", "result-1");
    memory.addUserMessage("q2");
    memory.addUserMessage("q3");

    List<ChatCompletionMessageParam> messages = memory.getMessages();
    assertFalse("Should not start with a tool message", messages.get(0).isTool());
  }

  @Test
  public void testNewMessagesSincePersisted() {
    ConversationMemory memory = new ConversationMemory(100);
    memory.addUserMessage("q1");
    memory.addUserMessage("q2");

    List<ChatCompletionMessageParam> batch1 = memory.getNewMessagesSincePersisted();
    assertEquals(2, batch1.size());

    memory.addUserMessage("q3");
    List<ChatCompletionMessageParam> batch2 = memory.getNewMessagesSincePersisted();
    assertEquals(1, batch2.size());

    List<ChatCompletionMessageParam> batch3 = memory.getNewMessagesSincePersisted();
    assertTrue(batch3.isEmpty());
  }

  @Test
  public void testClear() {
    ConversationMemory memory = new ConversationMemory(100);
    memory.addUserMessage("q1");
    assertEquals(1, memory.size());

    memory.clear();
    assertEquals(0, memory.size());
    assertTrue(memory.getRawMessages().isEmpty());
  }
}
