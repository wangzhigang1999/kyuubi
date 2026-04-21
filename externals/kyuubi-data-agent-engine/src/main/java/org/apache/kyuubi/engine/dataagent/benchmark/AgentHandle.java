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

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.apache.kyuubi.engine.dataagent.runtime.ReactAgent;
import org.apache.kyuubi.engine.dataagent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A single-use bundle of agent resources bound to one database. Owns the {@link ToolRegistry} and
 * {@link DataSource} created for this run; the shared {@code OpenAIClient} lives in {@link
 * AgentHandleFactory} and is NOT closed here.
 */
public final class AgentHandle implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(AgentHandle.class);

  private final ReactAgent agent;
  private final ToolRegistry registry;
  private final DataSource dataSource;

  public AgentHandle(ReactAgent agent, ToolRegistry registry, DataSource dataSource) {
    this.agent = agent;
    this.registry = registry;
    this.dataSource = dataSource;
  }

  public ReactAgent agent() {
    return agent;
  }

  @Override
  public void close() {
    try {
      agent.close();
    } catch (Exception e) {
      LOG.warn("Error closing ReactAgent", e);
    }
    try {
      registry.close();
    } catch (Exception e) {
      LOG.warn("Error closing ToolRegistry", e);
    }
    if (dataSource instanceof HikariDataSource) {
      try {
        ((HikariDataSource) dataSource).close();
      } catch (Exception e) {
        LOG.warn("Error closing HikariDataSource", e);
      }
    }
  }
}
