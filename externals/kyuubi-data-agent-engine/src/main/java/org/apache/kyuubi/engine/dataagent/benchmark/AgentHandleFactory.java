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

package org.apache.kyuubi.engine.dataagent.benchmark;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import java.time.Duration;
import javax.sql.DataSource;
import org.apache.kyuubi.engine.dataagent.benchmark.tool.SubmitSqlTool;
import org.apache.kyuubi.engine.dataagent.datasource.DataSourceFactory;
import org.apache.kyuubi.engine.dataagent.datasource.JdbcDialect;
import org.apache.kyuubi.engine.dataagent.prompt.SystemPromptBuilder;
import org.apache.kyuubi.engine.dataagent.runtime.ReactAgent;
import org.apache.kyuubi.engine.dataagent.runtime.middleware.LoggingMiddleware;
import org.apache.kyuubi.engine.dataagent.tool.ToolRegistry;
import org.apache.kyuubi.engine.dataagent.tool.sql.RunSelectQueryTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates one isolated {@link AgentHandle} per benchmark question. Mirrors what {@code
 * OpenAiProvider} does at engine startup, but per-question and pointed at a different JDBC URL —
 * BIRD ships one SQLite per database so a single shared {@code DataSource} won't fit.
 *
 * <p>The {@link OpenAIClient} is constructed once and shared across all handles; the SDK's client
 * is thread-safe and pooled, and rebuilding it for every question would crush latency.
 *
 * <p>Benchmark does not need the mutation tool, approval middleware, or logging middleware — those
 * exist to protect production datasources and do not change the SQL-generation behavior being
 * measured.
 */
public final class AgentHandleFactory implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(AgentHandleFactory.class);

  /** Caller-supplied configuration; read once at factory construction. */
  public static final class Config {
    public String apiKey;
    public String baseUrl;
    public String modelName;
    public int maxIterations = 30;
    public int queryTimeoutSeconds = 30;
    public long toolCallTimeoutSeconds = 120;
    public int llmTimeoutSeconds = 180;
    public int llmMaxRetries = 3;
    public boolean verboseLogging = false;
  }

  private final Config cfg;
  private final OpenAIClient client;

  public AgentHandleFactory(Config cfg) {
    if (cfg.apiKey == null || cfg.apiKey.isEmpty()) {
      throw new IllegalArgumentException("apiKey is required");
    }
    if (cfg.baseUrl == null || cfg.baseUrl.isEmpty()) {
      throw new IllegalArgumentException("baseUrl is required");
    }
    if (cfg.modelName == null || cfg.modelName.isEmpty()) {
      throw new IllegalArgumentException("modelName is required");
    }
    this.cfg = cfg;
    this.client =
        OpenAIOkHttpClient.builder()
            .apiKey(cfg.apiKey)
            .baseUrl(cfg.baseUrl)
            .maxRetries(cfg.llmMaxRetries)
            .timeout(Duration.ofSeconds(cfg.llmTimeoutSeconds))
            .build();
  }

  /** Build a fresh {@link AgentHandle} for the given JDBC URL. Caller must close it. */
  public AgentHandle buildFor(String jdbcUrl) {
    DataSource ds = null;
    ToolRegistry registry = null;
    try {
      ds = DataSourceFactory.create(jdbcUrl);
      registry = new ToolRegistry(cfg.toolCallTimeoutSeconds);
      registry.register(new RunSelectQueryTool(ds, cfg.queryTimeoutSeconds));
      SubmitSqlTool submitTool = new SubmitSqlTool(ds, cfg.queryTimeoutSeconds);
      registry.register(submitTool);

      SystemPromptBuilder prompt = SystemPromptBuilder.create();
      JdbcDialect dialect = JdbcDialect.fromUrl(jdbcUrl);
      if (dialect != null) {
        prompt.datasource(dialect.datasourceName());
      }

      ReactAgent.Builder builder =
          ReactAgent.builder()
              .client(client)
              .modelName(cfg.modelName)
              .toolRegistry(registry)
              .maxIterations(cfg.maxIterations)
              .systemPrompt(prompt.build());
      if (cfg.verboseLogging) {
        builder.addMiddleware(new LoggingMiddleware());
      }
      ReactAgent agent = builder.build();

      return new AgentHandle(agent, registry, ds, submitTool);
    } catch (RuntimeException e) {
      if (registry != null) {
        try {
          registry.close();
        } catch (Exception ignored) {
          // noop
        }
      }
      if (ds instanceof com.zaxxer.hikari.HikariDataSource) {
        try {
          ((com.zaxxer.hikari.HikariDataSource) ds).close();
        } catch (Exception ignored) {
          // noop
        }
      }
      throw e;
    }
  }

  @Override
  public void close() {
    try {
      client.close();
    } catch (Exception e) {
      LOG.warn("Error closing OpenAI client", e);
    }
  }
}
