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

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages conversation history for a Data Agent session with smart windowing. Ensures tool result
 * messages are never orphaned from their corresponding AI messages when truncating history.
 */
public class ConversationMemory {

  private final int maxMessages;
  private String systemPrompt;
  private final List<ChatMessage> messages = new ArrayList<>();
  private int persistedCount = 0;

  public ConversationMemory(int maxMessages) {
    this.maxMessages = maxMessages;
  }

  public void setSystemPrompt(String prompt) {
    this.systemPrompt = prompt;
  }

  public void addUserMessage(String content) {
    messages.add(UserMessage.from(content));
  }

  public void addAiMessage(AiMessage message) {
    messages.add(message);
  }

  public void addToolResult(String toolCallId, String toolName, String content) {
    messages.add(ToolExecutionResultMessage.from(toolCallId, toolName, content));
  }

  /**
   * Returns the message list for LLM invocation: system prompt + windowed history. Uses smart cut
   * point to avoid orphaning ToolExecutionResultMessages from their AI message.
   */
  public List<ChatMessage> getMessages() {
    List<ChatMessage> result = new ArrayList<>();
    if (systemPrompt != null) {
      result.add(SystemMessage.from(systemPrompt));
    }

    if (messages.size() <= maxMessages) {
      result.addAll(messages);
      return result;
    }

    // Window from the end, find a safe cut point
    int cutIndex = messages.size() - maxMessages;
    // Skip past ToolExecutionResultMessages at cut point to avoid orphaning
    while (cutIndex < messages.size()
        && messages.get(cutIndex) instanceof ToolExecutionResultMessage) {
      cutIndex++;
    }

    result.addAll(messages.subList(cutIndex, messages.size()));
    return result;
  }

  /** Returns messages added since the last persistence checkpoint. */
  public List<ChatMessage> getNewMessagesSincePersisted() {
    if (persistedCount >= messages.size()) {
      return Collections.emptyList();
    }
    List<ChatMessage> newMessages =
        new ArrayList<>(messages.subList(persistedCount, messages.size()));
    persistedCount = messages.size();
    return newMessages;
  }

  public List<ChatMessage> getRawMessages() {
    return Collections.unmodifiableList(messages);
  }

  public void clear() {
    messages.clear();
    persistedCount = 0;
  }

  public int size() {
    return messages.size();
  }
}
