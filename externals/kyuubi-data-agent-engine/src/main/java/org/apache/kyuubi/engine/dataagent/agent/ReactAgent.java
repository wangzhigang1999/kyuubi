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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionTool;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.kyuubi.engine.dataagent.tool.AgentTool;
import org.apache.kyuubi.engine.dataagent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ReAct (Reasoning + Acting) agent loop using the OpenAI official Java SDK. Iterates through LLM
 * reasoning, tool execution, and result verification until the agent produces a final answer or
 * hits the iteration limit.
 *
 * <p>Emits {@link AgentEvent}s via the provided consumer for real-time token-level streaming.
 */
public class ReactAgent {

  private static final Logger LOG = LoggerFactory.getLogger(ReactAgent.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final OpenAIClient client;
  private final String modelName;
  private final ToolRegistry toolRegistry;
  private final List<AgentMiddleware> middlewares;
  private final int maxIterations;
  private final String systemPrompt;

  public ReactAgent(
      OpenAIClient client,
      String modelName,
      ToolRegistry toolRegistry,
      List<AgentMiddleware> middlewares,
      int maxIterations,
      String systemPrompt) {
    this.client = client;
    this.modelName = modelName;
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
    dispatchAgentStart(ctx);

    try {
      for (int step = 1; step <= maxIterations; step++) {
        ctx.setIteration(step);
        emit(ctx, new AgentEvent.StepStart(step), eventConsumer);

        // 1. Build messages from memory
        List<ChatCompletionMessageParam> messages = memory.getMessages();

        // 2. Dispatch before_llm_call middleware
        if (dispatchBeforeLlmCall(ctx, messages)) {
          continue;
        }

        // 3. Stream LLM response with token-level events
        ChatCompletion completion = streamLlmResponse(ctx, messages, eventConsumer);
        if (completion == null) {
          emit(ctx, new AgentEvent.AgentError("LLM returned null response"), eventConsumer);
          break;
        }

        ChatCompletion.Choice choice = completion.choices().get(0);
        String content = choice.message().content().orElse("");

        // 4. Emit ContentComplete
        emit(ctx, new AgentEvent.ContentComplete(content), eventConsumer);

        // 5. Build assistant message and add to memory
        ChatCompletionAssistantMessageParam.Builder assistantBuilder =
            ChatCompletionAssistantMessageParam.builder();
        if (!content.isEmpty()) {
          assistantBuilder.content(content);
        }

        List<ChatCompletionMessageToolCall> toolCalls = choice.message().toolCalls().orElse(null);
        if (toolCalls != null && !toolCalls.isEmpty()) {
          assistantBuilder.toolCalls(toolCalls);
        }

        ChatCompletionAssistantMessageParam assistantMsg = assistantBuilder.build();
        memory.addAssistantMessage(assistantMsg);

        // 6. Dispatch after_llm_call middleware
        dispatchAfterLlmCall(ctx, assistantMsg);

        // 7. Check for tool calls
        if (toolCalls != null && !toolCalls.isEmpty()) {
          for (ChatCompletionMessageToolCall toolCall : toolCalls) {
            ChatCompletionMessageFunctionToolCall fnCall = toolCall.asFunction();
            String toolName = fnCall.function().name();
            Map<String, Object> toolArgs = parseToolArgs(fnCall.function().arguments());

            // Dispatch before_tool_call
            AgentMiddleware.ToolCallDecision decision =
                dispatchBeforeToolCall(ctx, toolName, toolArgs);
            if (decision != null && !decision.allow()) {
              String denied = "Tool call denied: " + decision.reason();
              memory.addToolResult(fnCall.id(), denied);
              emit(ctx, new AgentEvent.ToolResult(toolName, denied, true), eventConsumer);
              continue;
            }

            // Emit ToolCall event
            emit(ctx, new AgentEvent.ToolCall(toolName, toolArgs), eventConsumer);

            // Execute tool
            String result = executeTool(toolName, toolArgs);

            // Dispatch after_tool_call
            String modifiedResult = dispatchAfterToolCall(ctx, toolName, toolArgs, result);
            if (modifiedResult != null) {
              result = modifiedResult;
            }

            // Add tool result to memory and emit event
            memory.addToolResult(fnCall.id(), result);
            emit(ctx, new AgentEvent.ToolResult(toolName, result, false), eventConsumer);
          }
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
      dispatchAgentFinish(ctx);
    }
  }

  /**
   * Stream LLM response via OpenAI SDK, emitting ContentDelta for each chunk. Returns the
   * accumulated ChatCompletion.
   */
  private ChatCompletion streamLlmResponse(
      AgentContext ctx,
      List<ChatCompletionMessageParam> messages,
      Consumer<AgentEvent> eventConsumer) {

    try {
      ChatCompletionCreateParams.Builder paramsBuilder =
          ChatCompletionCreateParams.builder().model(modelName);

      for (ChatCompletionMessageParam msg : messages) {
        paramsBuilder.addMessage(msg);
      }

      // Add tool specs
      List<ChatCompletionTool> tools = toolRegistry.toChatCompletionTools();
      if (!tools.isEmpty()) {
        for (ChatCompletionTool tool : tools) {
          paramsBuilder.addTool(tool);
        }
      }

      ChatCompletionCreateParams params = paramsBuilder.build();

      // Stream and accumulate
      StringBuilder contentAccumulator = new StringBuilder();
      List<ChatCompletionChunk> allChunks = new ArrayList<>();

      try (StreamResponse<ChatCompletionChunk> stream =
          client.chat().completions().createStreaming(params)) {
        stream.stream()
            .forEach(
                chunk -> {
                  allChunks.add(chunk);
                  // Extract text delta and emit token-level streaming
                  for (ChatCompletionChunk.Choice c : chunk.choices()) {
                    c.delta()
                        .content()
                        .ifPresent(
                            text -> {
                              contentAccumulator.append(text);
                              emit(ctx, new AgentEvent.ContentDelta(text), eventConsumer);
                            });
                  }
                });
      }

      // Check if any chunk indicated tool calls
      boolean hasToolCalls =
          allChunks.stream()
              .flatMap(c -> c.choices().stream())
              .anyMatch(c -> c.delta().toolCalls().isPresent());

      if (hasToolCalls) {
        // For tool calls, use non-streaming to get reliable tool_call parsing
        ChatCompletion completion = client.chat().completions().create(params);
        completion
            .usage()
            .ifPresent(
                u -> ctx.addTokenUsage(u.promptTokens(), u.completionTokens(), u.totalTokens()));
        return completion;
      }

      // Build a minimal ChatCompletion from streamed text content
      return ChatCompletion.builder()
          .id("streamed")
          .model(modelName)
          .addChoice(
              ChatCompletion.Choice.builder()
                  .index(0L)
                  .message(
                      ChatCompletionMessage.builder()
                          .content(contentAccumulator.toString())
                          .build())
                  .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                  .build())
          .build();

    } catch (Exception e) {
      LOG.error("LLM streaming error", e);
      emit(ctx, new AgentEvent.AgentError("LLM error: " + e.getMessage()), eventConsumer);
      return null;
    }
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

  private static Map<String, Object> parseToolArgs(String json) {
    if (json == null || json.isEmpty()) {
      return new HashMap<>();
    }
    try {
      return JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      LOG.warn("Failed to parse tool arguments: {}", json, e);
      Map<String, Object> fallback = new HashMap<>();
      fallback.put("raw", json);
      return fallback;
    }
  }

  // --- Middleware dispatch methods ---

  private void emit(AgentContext ctx, AgentEvent event, Consumer<AgentEvent> consumer) {
    AgentEvent filtered = event;
    for (AgentMiddleware mw : middlewares) {
      try {
        filtered = mw.onEvent(ctx, filtered);
        if (filtered == null) return;
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
    for (int i = middlewares.size() - 1; i >= 0; i--) {
      try {
        middlewares.get(i).onAgentFinish(ctx);
      } catch (Exception e) {
        LOG.warn("Middleware onAgentFinish error: {}", e.getMessage());
      }
    }
  }

  private boolean dispatchBeforeLlmCall(
      AgentContext ctx, List<ChatCompletionMessageParam> messages) {
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

  private void dispatchAfterLlmCall(
      AgentContext ctx, ChatCompletionAssistantMessageParam response) {
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
    private OpenAIClient client;
    private String modelName;
    private ToolRegistry toolRegistry = new ToolRegistry();
    private final List<AgentMiddleware> middlewares = new ArrayList<>();
    private int maxIterations = 20;
    private String systemPrompt;

    public Builder client(OpenAIClient client) {
      this.client = client;
      return this;
    }

    public Builder modelName(String modelName) {
      this.modelName = modelName;
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
      if (client == null) throw new IllegalStateException("client is required");
      if (modelName == null) throw new IllegalStateException("modelName is required");
      return new ReactAgent(
          client, modelName, toolRegistry, middlewares, maxIterations, systemPrompt);
    }
  }
}
