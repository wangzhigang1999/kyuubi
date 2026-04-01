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

package org.apache.kyuubi.engine.dataagent.runtime;

import static org.junit.Assert.*;

import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
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
  public void testWindowingEdgeCaseAllToolResults() {
    // Edge case: window boundary lands in a sequence of tool results
    ConversationMemory memory = new ConversationMemory(2);
    memory.addToolResult("call-1", "r1");
    memory.addToolResult("call-2", "r2");
    memory.addToolResult("call-3", "r3");

    // Should not crash; should return at least the last message
    List<ChatCompletionMessageParam> messages = memory.getMessages();
    assertFalse("Should return at least one message", messages.isEmpty());
  }

  @Test
  public void testWindowingWithMaxMessagesOne() {
    ConversationMemory memory = new ConversationMemory(1);
    memory.addUserMessage("q1");
    memory.addAssistantMessage(ChatCompletionAssistantMessageParam.builder().content("a1").build());
    memory.addToolResult("call-1", "result-1");

    List<ChatCompletionMessageParam> messages = memory.getMessages();
    assertFalse("Should return at least one message", messages.isEmpty());
    assertFalse("Should not start with tool message", messages.get(0).isTool());
  }

  @Test
  public void testWindowingPreservesSystemPrompt() {
    ConversationMemory memory = new ConversationMemory(2);
    memory.setSystemPrompt("system");
    memory.addUserMessage("q1");
    memory.addUserMessage("q2");
    memory.addUserMessage("q3");

    List<ChatCompletionMessageParam> messages = memory.getMessages();
    assertTrue("First message should be system", messages.get(0).isSystem());
    // System prompt + 2 windowed messages = 3
    assertEquals(3, messages.size());
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

  @Test
  public void testGetRawMessagesReturnsDefensiveCopy() {
    ConversationMemory memory = new ConversationMemory(100);
    memory.addUserMessage("q1");
    List<ChatCompletionMessageParam> raw = memory.getRawMessages();
    assertEquals(1, raw.size());

    // Modifying returned list should not affect memory
    try {
      raw.add(null);
      fail("Should throw UnsupportedOperationException");
    } catch (UnsupportedOperationException expected) {
      // good
    }
    assertEquals(1, memory.size());
  }

  @Test
  public void testConcurrentAccess() throws Exception {
    ConversationMemory memory = new ConversationMemory(1000);
    int threads = 8;
    int messagesPerThread = 100;
    CountDownLatch latch = new CountDownLatch(threads);
    AtomicInteger errors = new AtomicInteger(0);

    List<Thread> threadList = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      final int threadId = t;
      Thread thread =
          new Thread(
              () -> {
                try {
                  for (int i = 0; i < messagesPerThread; i++) {
                    memory.addUserMessage("t" + threadId + "-q" + i);
                    memory.getMessages(); // concurrent read
                    memory.size();
                  }
                } catch (Exception e) {
                  errors.incrementAndGet();
                } finally {
                  latch.countDown();
                }
              });
      threadList.add(thread);
      thread.start();
    }

    latch.await();
    assertEquals("No concurrent modification errors", 0, errors.get());
    assertEquals(threads * messagesPerThread, memory.size());
  }
}
