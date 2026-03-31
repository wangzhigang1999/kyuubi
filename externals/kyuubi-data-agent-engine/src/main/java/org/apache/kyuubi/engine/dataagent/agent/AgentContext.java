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

import java.util.HashMap;
import java.util.Map;

/**
 * Mutable context passed through the middleware pipeline and agent loop. Tracks the current state
 * of agent execution including iteration count, token usage, and custom middleware state.
 */
public class AgentContext {

  private final String userInput;
  private final ConversationMemory memory;
  private int iteration;
  private long promptTokens;
  private long completionTokens;
  private long totalTokens;
  private ApprovalMode approvalMode;
  private final Map<String, Object> extraState = new HashMap<>();

  public AgentContext(String userInput, ConversationMemory memory, ApprovalMode approvalMode) {
    this.userInput = userInput;
    this.memory = memory;
    this.iteration = 0;
    this.approvalMode = approvalMode;
  }

  public String getUserInput() {
    return userInput;
  }

  public ConversationMemory getMemory() {
    return memory;
  }

  public int getIteration() {
    return iteration;
  }

  public void setIteration(int iteration) {
    this.iteration = iteration;
  }

  public long getPromptTokens() {
    return promptTokens;
  }

  public long getCompletionTokens() {
    return completionTokens;
  }

  public long getTotalTokens() {
    return totalTokens;
  }

  public void addTokenUsage(long prompt, long completion, long total) {
    this.promptTokens += prompt;
    this.completionTokens += completion;
    this.totalTokens += total;
  }

  public ApprovalMode getApprovalMode() {
    return approvalMode;
  }

  public void setApprovalMode(ApprovalMode approvalMode) {
    this.approvalMode = approvalMode;
  }

  public Map<String, Object> getExtraState() {
    return extraState;
  }
}
