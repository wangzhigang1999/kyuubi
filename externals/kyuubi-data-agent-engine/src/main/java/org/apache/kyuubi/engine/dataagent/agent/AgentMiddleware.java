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
import java.util.List;
import java.util.Map;

/**
 * Middleware interface for the Data Agent ReAct loop. Middlewares are executed in onion-model
 * order: before_* hooks run first-to-last, after_* hooks run last-to-first.
 *
 * <p>All hooks have default no-op implementations. Override only what you need.
 */
public interface AgentMiddleware {

  /** Called when the agent starts processing a user query. Runs first-to-last. */
  default void onAgentStart(AgentContext ctx) {}

  /** Called when the agent finishes. Runs last-to-first (cleanup order). */
  default void onAgentFinish(AgentContext ctx) {}

  /**
   * Called before each LLM invocation. Return non-null to skip the LLM call. Runs first-to-last.
   */
  default LlmRequestDecision beforeLlmCall(
      AgentContext ctx, List<ChatCompletionMessageParam> messages) {
    return null;
  }

  /** Called after each LLM invocation. Runs last-to-first. */
  default void afterLlmCall(AgentContext ctx, ChatCompletionAssistantMessageParam response) {}

  /**
   * Called before each tool execution. Return non-null to deny/modify the call. Runs first-to-last.
   */
  default ToolCallDecision beforeToolCall(
      AgentContext ctx, String toolName, Map<String, Object> toolArgs) {
    return null;
  }

  /**
   * Called after each tool execution. Return non-null to override the tool result. Runs
   * last-to-first.
   */
  default String afterToolCall(
      AgentContext ctx, String toolName, Map<String, Object> toolArgs, String result) {
    return null;
  }

  /**
   * Called for every event before it is emitted. Return null to suppress the event. Runs
   * first-to-last.
   */
  default AgentEvent onEvent(AgentContext ctx, AgentEvent event) {
    return event;
  }

  /** Decision to skip or modify an LLM request. */
  class LlmRequestDecision {
    private final boolean skip;
    private final String reason;

    public LlmRequestDecision(boolean skip, String reason) {
      this.skip = skip;
      this.reason = reason;
    }

    public boolean skip() {
      return skip;
    }

    public String reason() {
      return reason;
    }
  }

  /** Decision to deny or modify a tool call. */
  class ToolCallDecision {
    private final boolean allow;
    private final Map<String, Object> modifiedArgs;
    private final String reason;

    public ToolCallDecision(boolean allow, Map<String, Object> modifiedArgs, String reason) {
      this.allow = allow;
      this.modifiedArgs = modifiedArgs;
      this.reason = reason;
    }

    public boolean allow() {
      return allow;
    }

    public Map<String, Object> modifiedArgs() {
      return modifiedArgs;
    }

    public String reason() {
      return reason;
    }
  }
}
