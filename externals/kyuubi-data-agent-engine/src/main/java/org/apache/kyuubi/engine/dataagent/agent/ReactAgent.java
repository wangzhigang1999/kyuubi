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
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import org.apache.kyuubi.engine.dataagent.tool.AgentTool;
import org.apache.kyuubi.engine.dataagent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ReAct (Reasoning + Acting) agent loop. Iterates through LLM reasoning, tool execution, and result
 * verification until the agent produces a final answer or hits the iteration limit.
 *
 * <p>Emits {@link AgentEvent}s via the provided consumer for real-time token-level streaming.
 *
 * <p>Architecture mirrors data-insight's react.py:
 *
 * <pre>
 *   for each iteration:
 *     1. emit StepStart
 *     2. build messages from memory
 *     3. dispatch before_llm_call middleware
 *     4. stream LLM response → emit ContentDelta per token
 *     5. dispatch after_llm_call middleware
 *     6. emit ContentComplete
 *     7. if tool_calls:
 *        a. dispatch before_tool_call middleware
 *        b. execute tool
 *        c. dispatch after_tool_call middleware
 *        d. emit ToolResult
 *        e. add tool result to memory
 *        f. continue
 *     8. if no tool_calls → emit AgentFinish, break
 * </pre>
 */
public class ReactAgent {

  private static final Logger LOG = LoggerFactory.getLogger(ReactAgent.class);

  private final StreamingChatLanguageModel model;
  private final ToolRegistry toolRegistry;
  private final List<AgentMiddleware> middlewares;
  private final int maxIterations;
  private final String systemPrompt;

  public ReactAgent(
      StreamingChatLanguageModel model,
      ToolRegistry toolRegistry,
      List<AgentMiddleware> middlewares,
      int maxIterations,
      String systemPrompt) {
    this.model = model;
    this.toolRegistry = toolRegistry;
    this.middlewares = middlewares != null ? middlewares : Collections.emptyList();
    this.maxIterations = maxIterations;
    this.systemPrompt = systemPrompt;
  }

  /**
   * Run the ReAct loop for the given user input, emitting events via the consumer.
   *
   * @param userInput the natural language question
   * @param memory the conversation memory (may contain prior context)
   * @param approvalMode the tool approval mode
   * @param eventConsumer callback for each agent event (token-level streaming)
   */
  public void run(
      String userInput,
      ConversationMemory memory,
      ApprovalMode approvalMode,
      Consumer<AgentEvent> eventConsumer) {

    memory.setSystemPrompt(systemPrompt);
    memory.addUserMessage(userInput);

    AgentContext ctx = new AgentContext(userInput, memory, approvalMode);

    // Dispatch on_agent_start (first-to-last)
    dispatchAgentStart(ctx);

    try {
      for (int step = 1; step <= maxIterations; step++) {
        ctx.setIteration(step);
        emit(ctx, new AgentEvent.StepStart(step), eventConsumer);

        // 1. Build messages from memory
        List<ChatMessage> messages = memory.getMessages();

        // 2. Dispatch before_llm_call middleware
        if (dispatchBeforeLlmCall(ctx, messages)) {
          continue; // middleware requested skip
        }

        // 3. Stream LLM response with token-level events
        AiMessage aiMessage = streamLlmResponse(ctx, messages, eventConsumer);
        if (aiMessage == null) {
          emit(ctx, new AgentEvent.AgentError("LLM returned null response"), eventConsumer);
          break;
        }

        // 4. Dispatch after_llm_call middleware (last-to-first)
        dispatchAfterLlmCall(ctx, aiMessage);

        // 5. Emit ContentComplete
        String fullText = aiMessage.text() != null ? aiMessage.text() : "";
        emit(ctx, new AgentEvent.ContentComplete(fullText), eventConsumer);

        // 6. Add AI message to memory
        memory.addAiMessage(aiMessage);

        // 7. Check for tool calls
        if (aiMessage.hasToolExecutionRequests()) {
          for (var toolRequest : aiMessage.toolExecutionRequests()) {
            String toolName = toolRequest.name();
            // TODO: parse toolRequest.arguments() JSON into Map
            Map<String, Object> toolArgs = Collections.singletonMap("raw", toolRequest.arguments());

            // Dispatch before_tool_call
            AgentMiddleware.ToolCallDecision decision =
                dispatchBeforeToolCall(ctx, toolName, toolArgs);
            if (decision != null && !decision.allow()) {
              String denied = "Tool call denied: " + decision.reason();
              memory.addToolResult(toolRequest.id(), toolName, denied);
              emit(ctx, new AgentEvent.ToolResult(toolName, denied, true), eventConsumer);
              continue;
            }

            // Emit ToolCall event
            emit(ctx, new AgentEvent.ToolCall(toolName, toolArgs), eventConsumer);

            // Execute tool
            String result = executeTool(toolName, toolArgs);

            // Dispatch after_tool_call (last-to-first), may modify result
            String modifiedResult = dispatchAfterToolCall(ctx, toolName, toolArgs, result);
            if (modifiedResult != null) {
              result = modifiedResult;
            }

            // Add tool result to memory and emit event
            memory.addToolResult(toolRequest.id(), toolName, result);
            emit(ctx, new AgentEvent.ToolResult(toolName, result, false), eventConsumer);
          }
          // Continue to next iteration for further reasoning
          continue;
        }

        // 8. No tool calls — agent finished
        emit(
            ctx,
            new AgentEvent.AgentFinish(
                step, ctx.getPromptTokens(), ctx.getCompletionTokens(), ctx.getTotalTokens()),
            eventConsumer);
        return;
      }

      // Hit max iterations
      emit(
          ctx,
          new AgentEvent.AgentError("Reached maximum iterations (" + maxIterations + ")"),
          eventConsumer);

    } finally {
      // Dispatch on_agent_finish (last-to-first)
      dispatchAgentFinish(ctx);
    }
  }

  /**
   * Stream LLM response, emitting ContentDelta for each token chunk. Returns the complete
   * AiMessage.
   */
  private AiMessage streamLlmResponse(
      AgentContext ctx, List<ChatMessage> messages, Consumer<AgentEvent> eventConsumer) {

    CountDownLatch latch = new CountDownLatch(1);
    ChatResponse[] responseHolder = new ChatResponse[1];
    Throwable[] errorHolder = new Throwable[1];

    // TODO: bind tools to the model for function calling
    model.chat(
        messages,
        new StreamingChatResponseHandler() {
          @Override
          public void onPartialResponse(String partialResponse) {
            // Token-level streaming: emit each chunk immediately
            emit(ctx, new AgentEvent.ContentDelta(partialResponse), eventConsumer);
          }

          @Override
          public void onCompleteResponse(ChatResponse completeResponse) {
            responseHolder[0] = completeResponse;
            latch.countDown();
          }

          @Override
          public void onError(Throwable throwable) {
            errorHolder[0] = throwable;
            latch.countDown();
          }
        });

    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }

    if (errorHolder[0] != null) {
      LOG.error("LLM streaming error", errorHolder[0]);
      emit(
          ctx,
          new AgentEvent.AgentError("LLM error: " + errorHolder[0].getMessage()),
          eventConsumer);
      return null;
    }

    return responseHolder[0] != null ? responseHolder[0].aiMessage() : null;
  }

  private String executeTool(String toolName, Map<String, Object> toolArgs) {
    AgentTool tool = toolRegistry.get(toolName);
    if (tool == null) {
      return "Error: unknown tool '" + toolName + "'";
    }
    try {
      return tool.execute(toolArgs);
    } catch (Exception e) {
      LOG.error("Tool execution error: {}", toolName, e);
      return "Error executing " + toolName + ": " + e.getMessage();
    }
  }

  // --- Middleware dispatch methods ---

  private void emit(AgentContext ctx, AgentEvent event, Consumer<AgentEvent> consumer) {
    AgentEvent filtered = event;
    for (AgentMiddleware mw : middlewares) {
      try {
        filtered = mw.onEvent(ctx, filtered);
        if (filtered == null) return; // Event suppressed
      } catch (Exception e) {
        LOG.warn("Middleware onEvent error: {}", e.getMessage());
      }
    }
    consumer.accept(filtered);
  }

  private void dispatchAgentStart(AgentContext ctx) {
    for (AgentMiddleware mw : middlewares) {
      try {
        mw.onAgentStart(ctx);
      } catch (Exception e) {
        LOG.warn("Middleware onAgentStart error: {}", e.getMessage());
      }
    }
  }

  private void dispatchAgentFinish(AgentContext ctx) {
    // Reverse order for cleanup
    for (int i = middlewares.size() - 1; i >= 0; i--) {
      try {
        middlewares.get(i).onAgentFinish(ctx);
      } catch (Exception e) {
        LOG.warn("Middleware onAgentFinish error: {}", e.getMessage());
      }
    }
  }

  /** Returns true if any middleware requested skipping the LLM call. */
  private boolean dispatchBeforeLlmCall(AgentContext ctx, List<ChatMessage> messages) {
    for (AgentMiddleware mw : middlewares) {
      try {
        AgentMiddleware.LlmRequestDecision decision = mw.beforeLlmCall(ctx, messages);
        if (decision != null && decision.skip()) {
          LOG.info("LLM call skipped by middleware: {}", decision.reason());
          return true;
        }
      } catch (Exception e) {
        LOG.warn("Middleware beforeLlmCall error: {}", e.getMessage());
      }
    }
    return false;
  }

  private void dispatchAfterLlmCall(AgentContext ctx, AiMessage response) {
    for (int i = middlewares.size() - 1; i >= 0; i--) {
      try {
        middlewares.get(i).afterLlmCall(ctx, response);
      } catch (Exception e) {
        LOG.warn("Middleware afterLlmCall error: {}", e.getMessage());
      }
    }
  }

  private AgentMiddleware.ToolCallDecision dispatchBeforeToolCall(
      AgentContext ctx, String toolName, Map<String, Object> toolArgs) {
    for (AgentMiddleware mw : middlewares) {
      try {
        AgentMiddleware.ToolCallDecision decision = mw.beforeToolCall(ctx, toolName, toolArgs);
        if (decision != null) return decision;
      } catch (Exception e) {
        LOG.warn("Middleware beforeToolCall error: {}", e.getMessage());
      }
    }
    return null;
  }

  /** Returns modified result or null if no middleware modified it. */
  private String dispatchAfterToolCall(
      AgentContext ctx, String toolName, Map<String, Object> toolArgs, String result) {
    String modified = null;
    for (int i = middlewares.size() - 1; i >= 0; i--) {
      try {
        String mwResult =
            middlewares
                .get(i)
                .afterToolCall(ctx, toolName, toolArgs, modified != null ? modified : result);
        if (mwResult != null) {
          modified = mwResult;
        }
      } catch (Exception e) {
        LOG.warn("Middleware afterToolCall error: {}", e.getMessage());
      }
    }
    return modified;
  }

  // --- Builder ---

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private StreamingChatLanguageModel model;
    private ToolRegistry toolRegistry = new ToolRegistry();
    private final List<AgentMiddleware> middlewares = new ArrayList<>();
    private int maxIterations = 20;
    private String systemPrompt;

    public Builder model(StreamingChatLanguageModel model) {
      this.model = model;
      return this;
    }

    public Builder toolRegistry(ToolRegistry toolRegistry) {
      this.toolRegistry = toolRegistry;
      return this;
    }

    public Builder addMiddleware(AgentMiddleware middleware) {
      this.middlewares.add(middleware);
      return this;
    }

    public Builder maxIterations(int maxIterations) {
      this.maxIterations = maxIterations;
      return this;
    }

    public Builder systemPrompt(String systemPrompt) {
      this.systemPrompt = systemPrompt;
      return this;
    }

    public ReactAgent build() {
      if (model == null) throw new IllegalStateException("model is required");
      return new ReactAgent(model, toolRegistry, middlewares, maxIterations, systemPrompt);
    }
  }
}
