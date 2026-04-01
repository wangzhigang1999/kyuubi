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

package org.apache.kyuubi.engine.dataagent.runtime.middleware;

import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import java.util.List;
import java.util.Map;
import org.apache.kyuubi.engine.dataagent.runtime.AgentContext;
import org.apache.kyuubi.engine.dataagent.runtime.event.AgentError;
import org.apache.kyuubi.engine.dataagent.runtime.event.AgentEvent;
import org.apache.kyuubi.engine.dataagent.runtime.event.StepStart;
import org.apache.kyuubi.engine.dataagent.runtime.event.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logging middleware that prints agent lifecycle events to the console for debugging and
 * observability.
 *
 * <p>Log structure mirrors the ReAct loop:
 *
 * <pre>
 *   [agent] START user_input="..."
 *     [agent] Step 1
 *       [agent] LLM call: 3 messages
 *       [agent] LLM response: 256 chars
 *       [agent] Tool call: sql_query {sql=SELECT ...}
 *       [agent] Tool result: sql_query (128 chars)
 *     [agent] Step 2
 *       [agent] LLM call: 5 messages
 *       [agent] LLM response: 512 chars
 *   [agent] FINISH steps=2, tokens=1234
 * </pre>
 */
public class LoggingMiddleware implements AgentMiddleware {

  private static final Logger LOG = LoggerFactory.getLogger(LoggingMiddleware.class);

  private static final int MAX_PREVIEW_LENGTH = 200;

  @Override
  public void onAgentStart(AgentContext ctx) {
    LOG.info("[agent] START user_input=\"{}\"", truncate(ctx.getUserInput()));
  }

  @Override
  public void onAgentFinish(AgentContext ctx) {
    LOG.info(
        "[agent] FINISH steps={}, prompt_tokens={}, completion_tokens={}, total_tokens={}",
        ctx.getIteration(),
        ctx.getPromptTokens(),
        ctx.getCompletionTokens(),
        ctx.getTotalTokens());
  }

  @Override
  public LlmRequestDecision beforeLlmCall(
      AgentContext ctx, List<ChatCompletionMessageParam> messages) {
    LOG.info("[agent] LLM call: step={}, messages={}", ctx.getIteration(), messages.size());
    return null;
  }

  @Override
  public void afterLlmCall(AgentContext ctx, ChatCompletionAssistantMessageParam response) {
    String content = response.content().map(Object::toString).orElse("");
    int toolCallCount = response.toolCalls().map(List::size).orElse(0);
    LOG.info(
        "[agent] LLM response: step={}, content_length={}, tool_calls={}",
        ctx.getIteration(),
        content.length(),
        toolCallCount);
  }

  @Override
  public ToolCallDecision beforeToolCall(
      AgentContext ctx, String toolName, Map<String, Object> toolArgs) {
    LOG.info("[agent] Tool call: {} {}", toolName, toolArgs);
    return null;
  }

  @Override
  public String afterToolCall(
      AgentContext ctx, String toolName, Map<String, Object> toolArgs, String result) {
    LOG.info("[agent] Tool result: {} ({} chars)", toolName, result.length());
    LOG.debug("[agent] Tool result detail: {} -> {}", toolName, truncate(result));
    return null;
  }

  @Override
  public AgentEvent onEvent(AgentContext ctx, AgentEvent event) {
    switch (event.eventType()) {
      case STEP_START:
        LOG.info("[agent] Step {}", ((StepStart) event).stepNumber());
        break;
      case ERROR:
        LOG.error("[agent] ERROR: {}", ((AgentError) event).message());
        break;
      case TOOL_RESULT:
        ToolResult tr = (ToolResult) event;
        if (tr.isError()) {
          LOG.warn("[agent] Tool error: {} -> {}", tr.toolName(), truncate(tr.output()));
        }
        break;
      default:
        break;
    }
    return event;
  }

  private static String truncate(String s) {
    if (s == null) return "";
    return s.length() <= MAX_PREVIEW_LENGTH ? s : s.substring(0, MAX_PREVIEW_LENGTH) + "...";
  }
}
