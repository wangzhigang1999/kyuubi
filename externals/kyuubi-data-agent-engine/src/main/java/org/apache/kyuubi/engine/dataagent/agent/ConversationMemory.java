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

import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
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
  private final List<ChatCompletionMessageParam> messages = new ArrayList<>();
  private int persistedCount = 0;

  public ConversationMemory(int maxMessages) {
    this.maxMessages = maxMessages;
  }

  public void setSystemPrompt(String prompt) {
    this.systemPrompt = prompt;
  }

  public void addUserMessage(String content) {
    messages.add(
        ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder().content(content).build()));
  }

  public void addAssistantMessage(ChatCompletionAssistantMessageParam message) {
    messages.add(ChatCompletionMessageParam.ofAssistant(message));
  }

  public void addToolResult(String toolCallId, String content) {
    messages.add(
        ChatCompletionMessageParam.ofTool(
            ChatCompletionToolMessageParam.builder()
                .toolCallId(toolCallId)
                .content(content)
                .build()));
  }

  /**
   * Returns the message list for LLM invocation: system prompt + windowed history. Uses smart cut
   * point to avoid orphaning tool result messages from their AI message.
   */
  public List<ChatCompletionMessageParam> getMessages() {
    List<ChatCompletionMessageParam> result = new ArrayList<>();
    if (systemPrompt != null) {
      result.add(
          ChatCompletionMessageParam.ofSystem(
              ChatCompletionSystemMessageParam.builder().content(systemPrompt).build()));
    }

    if (messages.size() <= maxMessages) {
      result.addAll(messages);
      return result;
    }

    // Window from the end, find a safe cut point
    int cutIndex = messages.size() - maxMessages;
    // Skip past tool result messages at cut point to avoid orphaning
    while (cutIndex < messages.size() && messages.get(cutIndex).isTool()) {
      cutIndex++;
    }

    result.addAll(messages.subList(cutIndex, messages.size()));
    return result;
  }

  /** Returns messages added since the last persistence checkpoint. */
  public List<ChatCompletionMessageParam> getNewMessagesSincePersisted() {
    if (persistedCount >= messages.size()) {
      return Collections.emptyList();
    }
    List<ChatCompletionMessageParam> newMessages =
        new ArrayList<>(messages.subList(persistedCount, messages.size()));
    persistedCount = messages.size();
    return newMessages;
  }

  public List<ChatCompletionMessageParam> getRawMessages() {
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
