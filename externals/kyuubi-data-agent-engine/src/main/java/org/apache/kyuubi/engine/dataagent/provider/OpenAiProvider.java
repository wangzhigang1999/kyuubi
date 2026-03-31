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

package org.apache.kyuubi.engine.dataagent.provider;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.apache.kyuubi.config.KyuubiConf;
import org.apache.kyuubi.engine.dataagent.agent.AgentEvent;
import org.apache.kyuubi.engine.dataagent.agent.ApprovalMode;
import org.apache.kyuubi.engine.dataagent.agent.ConversationMemory;
import org.apache.kyuubi.engine.dataagent.agent.ReactAgent;
import org.apache.kyuubi.engine.dataagent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An OpenAI-compatible provider that wires up the full ReactAgent with streaming LLM, tools, and
 * middleware pipeline.
 */
public class OpenAiProvider implements DataAgentProvider {

  private static final Logger LOG = LoggerFactory.getLogger(OpenAiProvider.class);

  private static final String SYSTEM_PROMPT =
      "You are a data analysis agent. You query databases and explain data — nothing else.\n"
          + "You write and execute SQL to answer questions. You never fabricate data.\n"
          + "When uncertain about data meaning, ask the user rather than assuming.";

  private final ReactAgent agent;
  private final int maxMessages;
  private final ConcurrentHashMap<String, ConversationMemory> sessions = new ConcurrentHashMap<>();

  public OpenAiProvider(KyuubiConf conf) {
    scala.Option<String> apiKeyOpt = conf.get(KyuubiConf.ENGINE_DATA_AGENT_LLM_API_KEY());
    if (apiKeyOpt.isEmpty()) {
      throw new IllegalArgumentException(
          KyuubiConf.ENGINE_DATA_AGENT_LLM_API_KEY().key() + " is required for OpenAI provider");
    }
    String apiKey = apiKeyOpt.get();

    StreamingChatLanguageModel model =
        OpenAiStreamingChatModel.builder()
            .apiKey(apiKey)
            .baseUrl(conf.get(KyuubiConf.ENGINE_DATA_AGENT_LLM_API_URL()))
            .modelName(conf.get(KyuubiConf.ENGINE_DATA_AGENT_LLM_MODEL()))
            .build();

    int maxIterations = (int) conf.get(KyuubiConf.ENGINE_DATA_AGENT_MAX_ITERATIONS());

    // TODO: register tools (schema_inspect, sql_query, find_relationships, glossary)
    ToolRegistry toolRegistry = new ToolRegistry();

    // TODO: register middlewares (verification, guardrails, deadline, compaction)

    this.agent =
        ReactAgent.builder()
            .model(model)
            .toolRegistry(toolRegistry)
            .maxIterations(maxIterations)
            .systemPrompt(SYSTEM_PROMPT)
            .build();

    this.maxMessages = 100;
  }

  @Override
  public void open(String sessionId, Optional<String> user) {
    sessions.put(sessionId, new ConversationMemory(maxMessages));
    LOG.info("Opened Data Agent session {} for user {}", sessionId, user.orElse("unknown"));
  }

  @Override
  public void run(String sessionId, String question, Consumer<AgentEvent> onEvent) {
    ConversationMemory memory = sessions.get(sessionId);
    if (memory == null) {
      onEvent.accept(new AgentEvent.AgentError("Session not found. Please reconnect."));
      return;
    }

    agent.run(question, memory, ApprovalMode.YOLO, onEvent);
  }

  @Override
  public void close(String sessionId) {
    sessions.remove(sessionId);
    LOG.info("Closed Data Agent session {}", sessionId);
  }
}
