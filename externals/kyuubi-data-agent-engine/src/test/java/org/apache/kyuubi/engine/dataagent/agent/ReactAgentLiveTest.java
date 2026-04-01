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

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import java.io.File;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.apache.kyuubi.engine.dataagent.prompt.SystemPrompts;
import org.apache.kyuubi.engine.dataagent.tool.ToolRegistry;
import org.apache.kyuubi.engine.dataagent.tool.schema.SchemaInspectTool;
import org.apache.kyuubi.engine.dataagent.tool.sql.SqlQueryTool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sqlite.SQLiteDataSource;

/**
 * Live integration test with a real LLM (Qwen via DashScope) and real SQLite database. Exercises
 * the full ReAct loop: LLM reasoning -> tool calls -> result verification. Requires
 * DASHSCOPE_API_KEY environment variable.
 */
public class ReactAgentLiveTest {

  private static final String API_KEY = System.getenv().getOrDefault("DASHSCOPE_API_KEY", "");
  private static final String BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
  private static final String MODEL_NAME = "qwen3.5-plus-2026-02-15";

  private static final String SYSTEM_PROMPT = SystemPrompts.defaultPrompt();

  private final List<File> tempFiles = new ArrayList<>();
  private OpenAIClient client;

  @Before
  public void setUp() {
    assumeTrue("DASHSCOPE_API_KEY not set, skipping live tests", !API_KEY.isEmpty());
    client = OpenAIOkHttpClient.builder().apiKey(API_KEY).baseUrl(BASE_URL).build();
  }

  @After
  public void tearDown() {
    tempFiles.forEach(File::delete);
  }

  @Test
  public void testPlainTextStreamingWithoutTools() {
    ReactAgent agent =
        ReactAgent.builder()
            .client(client)
            .modelName(MODEL_NAME)
            .toolRegistry(new ToolRegistry())
            .maxIterations(3)
            .systemPrompt("You are a helpful assistant. Answer concisely in 1-2 sentences.")
            .build();

    List<AgentEvent> events = new CopyOnWriteArrayList<>();
    ConversationMemory memory = new ConversationMemory(100);

    agent.run("What is Apache Kyuubi?", memory, ApprovalMode.YOLO, events::add);

    List<String> deltas =
        events.stream()
            .filter(e -> e instanceof AgentEvent.ContentDelta)
            .map(e -> ((AgentEvent.ContentDelta) e).text())
            .collect(Collectors.toList());

    assertTrue("Expected multiple ContentDelta events", deltas.size() > 1);
    assertFalse("Streamed text should not be empty", String.join("", deltas).isEmpty());
    assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.StepStart));
    assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ContentComplete));
    assertTrue(events.get(events.size() - 1) instanceof AgentEvent.AgentFinish);
    assertEquals(2, memory.getRawMessages().size()); // user + assistant
  }

  @Test
  public void testFullReActLoopWithSchemaInspectAndSqlQuery() {
    SQLiteDataSource ds = createSalesDatabase();
    ToolRegistry registry = new ToolRegistry();
    registry.register(new SchemaInspectTool(ds));
    registry.register(new SqlQueryTool(ds));

    ReactAgent agent =
        ReactAgent.builder()
            .client(client)
            .modelName(MODEL_NAME)
            .toolRegistry(registry)
            .maxIterations(10)
            .systemPrompt(SYSTEM_PROMPT)
            .build();

    List<AgentEvent> events = new CopyOnWriteArrayList<>();
    ConversationMemory memory = new ConversationMemory(100);

    agent.run(
        "What is the total revenue by product category? Which category has the highest revenue?",
        memory,
        ApprovalMode.YOLO,
        events::add);

    printEventStream(events);

    // Verify tool calls happened
    List<AgentEvent.ToolCall> toolCalls =
        events.stream()
            .filter(e -> e instanceof AgentEvent.ToolCall)
            .map(e -> (AgentEvent.ToolCall) e)
            .collect(Collectors.toList());
    List<AgentEvent.ToolResult> toolResults =
        events.stream()
            .filter(e -> e instanceof AgentEvent.ToolResult)
            .map(e -> (AgentEvent.ToolResult) e)
            .collect(Collectors.toList());

    assertFalse("Agent should have called at least one tool", toolCalls.isEmpty());
    assertFalse("Agent should have received tool results", toolResults.isEmpty());
    assertTrue(
        "Tool calls should not error",
        toolResults.stream().noneMatch(AgentEvent.ToolResult::isError));

    // Verify schema inspect was called
    assertTrue(
        "Agent should call describe_schema first",
        toolCalls.stream().anyMatch(tc -> "describe_schema".equals(tc.toolName())));

    // Verify SQL query was called
    assertTrue(
        "Agent should execute at least one SQL query",
        toolCalls.stream().anyMatch(tc -> "sql_query".equals(tc.toolName())));

    // Verify final answer mentions "Electronics" (highest revenue)
    List<String> completions =
        events.stream()
            .filter(e -> e instanceof AgentEvent.ContentComplete)
            .map(e -> ((AgentEvent.ContentComplete) e).fullText())
            .collect(Collectors.toList());
    String lastAnswer = completions.get(completions.size() - 1);
    assertTrue(
        "Final answer should mention Electronics, got: " + lastAnswer,
        lastAnswer.toLowerCase().contains("electronics"));

    // Verify agent finished successfully
    AgentEvent last = events.get(events.size() - 1);
    assertTrue(last instanceof AgentEvent.AgentFinish);
    assertTrue("Should take multiple steps", ((AgentEvent.AgentFinish) last).totalSteps() > 1);
  }

  @Test
  public void testMultiTurnConversationWithToolUse() {
    SQLiteDataSource ds = createSalesDatabase();
    ToolRegistry registry = new ToolRegistry();
    registry.register(new SchemaInspectTool(ds));
    registry.register(new SqlQueryTool(ds));

    ReactAgent agent =
        ReactAgent.builder()
            .client(client)
            .modelName(MODEL_NAME)
            .toolRegistry(registry)
            .maxIterations(10)
            .systemPrompt(SYSTEM_PROMPT)
            .build();

    // Shared memory across turns
    ConversationMemory memory = new ConversationMemory(100);

    // Turn 1
    List<AgentEvent> events1 = new CopyOnWriteArrayList<>();
    agent.run("How many orders are there in total?", memory, ApprovalMode.YOLO, events1::add);

    System.out.println("=== Turn 1 ===");
    printEventStream(events1);

    assertTrue(
        "Turn 1 should query the database",
        events1.stream()
            .anyMatch(
                e ->
                    e instanceof AgentEvent.ToolCall
                        && "sql_query".equals(((AgentEvent.ToolCall) e).toolName())));
    assertTrue(events1.get(events1.size() - 1) instanceof AgentEvent.AgentFinish);

    // Turn 2: follow-up relying on conversation context
    List<AgentEvent> events2 = new CopyOnWriteArrayList<>();
    agent.run(
        "Now show me only orders above 500 dollars.", memory, ApprovalMode.YOLO, events2::add);

    System.out.println("=== Turn 2 ===");
    printEventStream(events2);

    assertTrue(
        "Turn 2 should also query the database",
        events2.stream()
            .anyMatch(
                e ->
                    e instanceof AgentEvent.ToolCall
                        && "sql_query".equals(((AgentEvent.ToolCall) e).toolName())));
    assertTrue(events2.get(events2.size() - 1) instanceof AgentEvent.AgentFinish);

    // Verify memory accumulated across both turns
    assertTrue(
        "Memory should contain messages from both turns, got " + memory.getRawMessages().size(),
        memory.getRawMessages().size() > 4);
  }

  // --- Helpers ---

  private void printEventStream(List<AgentEvent> events) {
    for (AgentEvent event : events) {
      if (event instanceof AgentEvent.StepStart) {
        System.out.println("[Step " + ((AgentEvent.StepStart) event).stepNumber() + "]");
      } else if (event instanceof AgentEvent.ContentDelta) {
        System.out.print(((AgentEvent.ContentDelta) event).text());
      } else if (event instanceof AgentEvent.ContentComplete) {
        System.out.println();
      } else if (event instanceof AgentEvent.ToolCall) {
        AgentEvent.ToolCall tc = (AgentEvent.ToolCall) event;
        System.out.println("[ToolCall] " + tc.toolName() + "(" + tc.toolArgs() + ")");
      } else if (event instanceof AgentEvent.ToolResult) {
        AgentEvent.ToolResult tr = (AgentEvent.ToolResult) event;
        String output = tr.output();
        String preview = output.length() > 200 ? output.substring(0, 200) + "..." : output;
        System.out.println("[ToolResult] " + tr.toolName() + " -> " + preview);
      } else if (event instanceof AgentEvent.AgentFinish) {
        AgentEvent.AgentFinish f = (AgentEvent.AgentFinish) event;
        System.out.println("[Finish] steps=" + f.totalSteps() + " tokens=" + f.totalTokens());
      } else if (event instanceof AgentEvent.AgentError) {
        System.out.println("[Error] " + ((AgentEvent.AgentError) event).message());
      }
    }
    System.out.println();
  }

  private SQLiteDataSource createSalesDatabase() {
    SQLiteDataSource ds = createDataSource();
    try (Connection conn = ds.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "CREATE TABLE products ("
              + "id INTEGER PRIMARY KEY, name TEXT NOT NULL, "
              + "category TEXT NOT NULL, price REAL NOT NULL)");
      stmt.execute(
          "INSERT INTO products VALUES "
              + "(1, 'Laptop', 'Electronics', 999.99), "
              + "(2, 'Headphones', 'Electronics', 199.99), "
              + "(3, 'T-Shirt', 'Clothing', 29.99), "
              + "(4, 'Jeans', 'Clothing', 59.99), "
              + "(5, 'Novel', 'Books', 14.99), "
              + "(6, 'Textbook', 'Books', 89.99)");
      stmt.execute(
          "CREATE TABLE orders ("
              + "id INTEGER PRIMARY KEY, product_id INTEGER NOT NULL, "
              + "customer_name TEXT NOT NULL, quantity INTEGER NOT NULL, "
              + "order_date TEXT NOT NULL, "
              + "FOREIGN KEY (product_id) REFERENCES products(id))");
      stmt.execute(
          "INSERT INTO orders VALUES "
              + "(1, 1, 'Alice', 1, '2024-01-15'), "
              + "(2, 2, 'Bob', 2, '2024-01-20'), "
              + "(3, 3, 'Charlie', 3, '2024-02-01'), "
              + "(4, 4, 'Alice', 1, '2024-02-10'), "
              + "(5, 5, 'Bob', 5, '2024-02-15'), "
              + "(6, 1, 'Diana', 1, '2024-03-01'), "
              + "(7, 6, 'Charlie', 2, '2024-03-05'), "
              + "(8, 2, 'Diana', 1, '2024-03-10')");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return ds;
  }

  private SQLiteDataSource createDataSource() {
    try {
      File tmpFile = File.createTempFile("kyuubi-agent-live-", ".db");
      tmpFile.deleteOnExit();
      tempFiles.add(tmpFile);
      SQLiteDataSource ds = new SQLiteDataSource();
      ds.setUrl("jdbc:sqlite:" + tmpFile.getAbsolutePath());
      return ds;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
