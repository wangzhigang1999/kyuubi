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

import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages conversation history for a Data Agent session. Ensures tool result messages are never
 * orphaned from their corresponding AI messages.
 *
 * <p>All public methods are synchronized for thread safety.
 */
public class ConversationMemory {

  private String systemPrompt;
  private String lastUserInput;
  private final List<ChatCompletionMessageParam> messages = new ArrayList<>();

  public ConversationMemory() {}

  public synchronized void setSystemPrompt(String prompt) {
    this.systemPrompt = prompt;
  }

  public synchronized void addUserMessage(String content) {
    this.lastUserInput = content;
    messages.add(
        ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder().content(content).build()));
  }

  public synchronized String getLastUserInput() {
    return lastUserInput;
  }

  public synchronized void addAssistantMessage(ChatCompletionAssistantMessageParam message) {
    messages.add(ChatCompletionMessageParam.ofAssistant(message));
  }

  public synchronized void addToolResult(String toolCallId, String content) {
    messages.add(
        ChatCompletionMessageParam.ofTool(
            ChatCompletionToolMessageParam.builder()
                .toolCallId(toolCallId)
                .content(content)
                .build()));
  }

  /**
   * Returns the full message list for LLM invocation: system prompt + all history messages.
   *
   * <p>No windowing is applied — callers are responsible for managing context length (e.g. via a
   * token-based truncation strategy).
   */
  public synchronized List<ChatCompletionMessageParam> getMessages() {
    List<ChatCompletionMessageParam> result = new ArrayList<>();
    if (systemPrompt != null) {
      result.add(
          ChatCompletionMessageParam.ofSystem(
              ChatCompletionSystemMessageParam.builder().content(systemPrompt).build()));
    }
    result.addAll(messages);
    return result;
  }

  public synchronized List<ChatCompletionMessageParam> getRawMessages() {
    return Collections.unmodifiableList(new ArrayList<>(messages));
  }

  /**
   * Replace the entire message history with a compacted list. Useful for context-length management
   * strategies (e.g., summarizing older messages).
   */
  public synchronized void replaceMessages(List<ChatCompletionMessageParam> compacted) {
    messages.clear();
    messages.addAll(compacted);
  }

  public synchronized void clear() {
    messages.clear();
  }

  public synchronized int size() {
    return messages.size();
  }
}
