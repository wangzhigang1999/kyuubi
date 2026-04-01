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

package org.apache.kyuubi.engine.dataagent.provider.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.zaxxer.hikari.HikariDataSource;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.apache.kyuubi.config.KyuubiConf;
import org.apache.kyuubi.engine.dataagent.runtime.AgentEvent;
import org.apache.kyuubi.engine.dataagent.runtime.ApprovalMode;
import org.apache.kyuubi.engine.dataagent.runtime.ConversationMemory;
import org.apache.kyuubi.engine.dataagent.runtime.ReactAgent;
import org.apache.kyuubi.engine.dataagent.datasource.DataSourceFactory;
import org.apache.kyuubi.engine.dataagent.prompt.SystemPromptBuilder;
import org.apache.kyuubi.engine.dataagent.provider.DataAgentProvider;
import org.apache.kyuubi.engine.dataagent.tool.ToolRegistry;
import org.apache.kyuubi.engine.dataagent.tool.schema.SchemaInspectTool;
import org.apache.kyuubi.engine.dataagent.tool.sql.SqlQueryTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An OpenAI-compatible provider that wires up the full ReactAgent with streaming LLM, tools, and
 * middleware pipeline. Uses the official OpenAI Java SDK.
 */
public class OpenAiProvider implements DataAgentProvider {

  private static final Logger LOG = LoggerFactory.getLogger(OpenAiProvider.class);

  private final ReactAgent agent;
  private final DataSource dataSource;
  private final int maxMessages;
  private final ConcurrentHashMap<String, ConversationMemory> sessions = new ConcurrentHashMap<>();

  public OpenAiProvider(KyuubiConf conf) {
    scala.Option<String> apiKeyOpt = conf.get(KyuubiConf.ENGINE_DATA_AGENT_LLM_API_KEY());
    if (apiKeyOpt.isEmpty()) {
      throw new IllegalArgumentException(
          KyuubiConf.ENGINE_DATA_AGENT_LLM_API_KEY().key() + " is required for OpenAI provider");
    }
    String apiKey = apiKeyOpt.get();
    String baseUrl = conf.get(KyuubiConf.ENGINE_DATA_AGENT_LLM_API_URL());
    String modelName = conf.get(KyuubiConf.ENGINE_DATA_AGENT_LLM_MODEL());

    OpenAIClient client = OpenAIOkHttpClient.builder().apiKey(apiKey).baseUrl(baseUrl).build();

    int maxIterations = (int) conf.get(KyuubiConf.ENGINE_DATA_AGENT_MAX_ITERATIONS());

    // Register tools and build prompt from JDBC URL
    ToolRegistry toolRegistry = new ToolRegistry();
    SystemPromptBuilder promptBuilder = SystemPromptBuilder.create();
    scala.Option<String> jdbcUrlOpt = conf.get(KyuubiConf.ENGINE_DATA_AGENT_JDBC_URL());
    if (jdbcUrlOpt.isDefined()) {
      String jdbcUrl = jdbcUrlOpt.get();
      this.dataSource = DataSourceFactory.create(jdbcUrl);
      toolRegistry.register(new SchemaInspectTool(dataSource));
      toolRegistry.register(new SqlQueryTool(dataSource));
      promptBuilder.jdbcUrl(jdbcUrl);
    } else {
      this.dataSource = null;
    }

    this.agent =
        ReactAgent.builder()
            .client(client)
            .modelName(modelName)
            .toolRegistry(toolRegistry)
            .maxIterations(maxIterations)
            .systemPrompt(promptBuilder.build())
            .build();

    this.maxMessages = 100;
  }

  @Override
  public void open(String sessionId, String user) {
    sessions.put(sessionId, new ConversationMemory(maxMessages));
    LOG.info("Opened Data Agent session {} for user {}", sessionId, user);
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

  @Override
  public void stop() {
    if (dataSource instanceof HikariDataSource) {
      ((HikariDataSource) dataSource).close();
      LOG.info("Closed Data Agent connection pool");
    }
  }
}
