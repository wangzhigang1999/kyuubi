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

package org.apache.kyuubi.engine.dataagent.prompt;

import static org.junit.Assert.*;

import java.time.LocalDate;
import org.junit.Test;

public class SystemPromptsTest {

  @Test
  public void testDefaultPromptContainsBaseAndDate() {
    String prompt = SystemPrompts.defaultPrompt();
    assertTrue(prompt.contains("describe_schema"));
    assertTrue(prompt.contains(LocalDate.now().toString()));
  }

  @Test
  public void testForEngineSparkContainsGuidelines() {
    String prompt = SystemPrompts.forEngine("spark");
    assertTrue(prompt.contains("Spark SQL"));
    assertTrue(prompt.contains("describe_schema"));
  }

  @Test
  public void testForEngineTrinoContainsGuidelines() {
    String prompt = SystemPrompts.forEngine("trino");
    assertTrue(prompt.contains("Trino"));
    assertTrue(prompt.contains("catalog.schema.table"));
  }

  @Test
  public void testForEngineCaseInsensitive() {
    assertEquals(SystemPrompts.forEngine("spark"), SystemPrompts.forEngine("SPARK"));
    assertEquals(SystemPrompts.forEngine("trino"), SystemPrompts.forEngine("Trino"));
  }

  @Test
  public void testForJdbcUrlSqlite() {
    String prompt = SystemPrompts.forJdbcUrl("jdbc:sqlite:/tmp/test.db");
    assertTrue(prompt.contains("SQLite SQL compatibility"));
  }

  @Test
  public void testForJdbcUrlSpark() {
    String prompt = SystemPrompts.forJdbcUrl("jdbc:hive2://localhost:10009");
    assertTrue(prompt.contains("Spark SQL"));
  }

  @Test
  public void testForJdbcUrlTrino() {
    String prompt = SystemPrompts.forJdbcUrl("jdbc:trino://localhost:8080");
    assertTrue(prompt.contains("Trino"));
  }

  @Test
  public void testForJdbcUrlFallsBackToDefault() {
    assertEquals(SystemPrompts.defaultPrompt(), SystemPrompts.forJdbcUrl(null));
    assertEquals(SystemPrompts.defaultPrompt(), SystemPrompts.forJdbcUrl("jdbc:mysql://localhost"));
  }

  @Test
  public void testForEngineFallsBackToDefault() {
    assertEquals(SystemPrompts.defaultPrompt(), SystemPrompts.forEngine(null));
    assertEquals(SystemPrompts.defaultPrompt(), SystemPrompts.forEngine("unknown"));
  }
}
