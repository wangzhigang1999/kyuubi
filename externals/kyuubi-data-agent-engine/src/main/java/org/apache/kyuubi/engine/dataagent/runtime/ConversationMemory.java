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

  public synchronized String getSystemPrompt() {
    return systemPrompt;
  }

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
   * Build the full message list for LLM API invocation: [system prompt] + conversation history.
   *
   * <p>No windowing is applied — callers are responsible for managing context length (e.g. via a
   * token-based truncation strategy).
   *
   * @see #getHistory() for history-only access without system prompt
   */
  public synchronized List<ChatCompletionMessageParam> buildLlmMessages() {
    List<ChatCompletionMessageParam> result = new ArrayList<>(messages.size() + 1);
    if (systemPrompt != null) {
      result.add(
          ChatCompletionMessageParam.ofSystem(
              ChatCompletionSystemMessageParam.builder().content(systemPrompt).build()));
    }
    result.addAll(messages);
    return Collections.unmodifiableList(result);
  }

  /**
   * Returns the conversation history (user, assistant, tool messages) without the system prompt.
   * Useful for middleware that needs to inspect or compact history.
   */
  public synchronized List<ChatCompletionMessageParam> getHistory() {
    return Collections.unmodifiableList(new ArrayList<>(messages));
  }

  /**
   * Replace the conversation history with a compacted version. Useful for context-length management
   * strategies (e.g., summarizing older messages).
   */
  public synchronized void replaceHistory(List<ChatCompletionMessageParam> compacted) {
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
