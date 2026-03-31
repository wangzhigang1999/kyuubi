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

package org.apache.kyuubi.engine.dataagent.agent

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

import scala.collection.JavaConverters._

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import org.sqlite.SQLiteDataSource

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.engine.dataagent.agent.react.ReactAgent
import org.apache.kyuubi.engine.dataagent.tool.ToolRegistry
import org.apache.kyuubi.engine.dataagent.tool.schema.SchemaInspectTool
import org.apache.kyuubi.engine.dataagent.tool.sql.SqlQueryTool

/**
 * Live integration test with a real LLM (Qwen via DashScope) and real SQLite database.
 * Exercises the full ReAct loop: LLM reasoning → tool calls → result verification.
 * Requires DASHSCOPE_API_KEY environment variable.
 */
class ReactAgentLiveSuite extends KyuubiFunSuite {

  private val apiKey = sys.env.getOrElse("DASHSCOPE_API_KEY", "")
  private val baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
  private val modelName = "qwen3.5-plus-2026-02-15"

  private val SYSTEM_PROMPT =
    "You are a data analysis agent. You query databases and explain data — nothing else.\n" +
      "You write and execute SQL to answer questions. You never fabricate data.\n" +
      "When uncertain about data meaning, ask the user rather than assuming.\n" +
      "Always call describe_schema first to understand the database before writing SQL."

  private val tempFiles = new java.util.ArrayList[File]()
  private var client: OpenAIClient = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    assume(apiKey.nonEmpty, "DASHSCOPE_API_KEY not set, skipping live tests")
    client = OpenAIOkHttpClient.builder()
      .apiKey(apiKey)
      .baseUrl(baseUrl)
      .build()
  }

  override def afterAll(): Unit = {
    tempFiles.forEach(f => f.delete())
    super.afterAll()
  }

  test("plain text streaming without tools") {
    val agent = ReactAgent.builder()
      .client(client)
      .modelName(modelName)
      .toolRegistry(new ToolRegistry())
      .maxIterations(3)
      .systemPrompt("You are a helpful assistant. Answer concisely in 1-2 sentences.")
      .build()

    val events = new CopyOnWriteArrayList[AgentEvent]()
    val memory = new ConversationMemory(100)

    agent.run("What is Apache Kyuubi?", memory, ApprovalMode.YOLO, e => events.add(e))

    val eventList = events.asScala.toList
    val deltas = eventList.collect { case d: AgentEvent.ContentDelta => d.text() }

    assert(deltas.size > 1, s"Expected multiple ContentDelta events, got ${deltas.size}")
    assert(deltas.mkString.nonEmpty, "Streamed text should not be empty")
    assert(eventList.exists(_.isInstanceOf[AgentEvent.StepStart]))
    assert(eventList.exists(_.isInstanceOf[AgentEvent.ContentComplete]))
    assert(eventList.last.isInstanceOf[AgentEvent.AgentFinish])

    val rawMessages = memory.getRawMessages.asScala
    assert(rawMessages.size == 2) // user + assistant
  }

  test("full ReAct loop: schema inspect → SQL query → answer") {
    val ds = createSalesDatabase()
    val registry = new ToolRegistry()
    registry.register(new SchemaInspectTool(ds))
    registry.register(new SqlQueryTool(ds))

    val agent = ReactAgent.builder()
      .client(client)
      .modelName(modelName)
      .toolRegistry(registry)
      .maxIterations(10)
      .systemPrompt(SYSTEM_PROMPT)
      .build()

    val events = new CopyOnWriteArrayList[AgentEvent]()
    val memory = new ConversationMemory(100)

    agent.run(
      "What is the total revenue by product category? Which category has the highest revenue?",
      memory,
      ApprovalMode.YOLO,
      e => events.add(e))

    val eventList = events.asScala.toList
    printEventStream(eventList)

    // Verify tool calls happened
    val toolCalls = eventList.collect { case tc: AgentEvent.ToolCall => tc }
    val toolResults = eventList.collect { case tr: AgentEvent.ToolResult => tr }
    assert(toolCalls.nonEmpty, "Agent should have called at least one tool")
    assert(toolResults.nonEmpty, "Agent should have received tool results")
    assert(
      toolResults.forall(!_.isError),
      s"Tool calls should not error: ${toolResults.filter(_.isError).map(_.output())}")

    // Verify schema inspect was called
    val schemaCall = toolCalls.find(_.toolName() == "describe_schema")
    assert(schemaCall.isDefined, "Agent should call describe_schema first")

    // Verify SQL query was called
    val sqlCalls = toolCalls.filter(_.toolName() == "sql_query")
    assert(sqlCalls.nonEmpty, "Agent should execute at least one SQL query")

    // Verify final answer mentions "Electronics" (highest revenue: 200+1200=1400)
    val finalContent = eventList.collect { case c: AgentEvent.ContentComplete => c.fullText() }
    val lastAnswer = finalContent.last
    assert(
      lastAnswer.toLowerCase.contains("electronics"),
      s"Final answer should mention Electronics as highest revenue category, got: $lastAnswer")

    // Verify agent finished successfully
    assert(eventList.last.isInstanceOf[AgentEvent.AgentFinish])
    val finish = eventList.last.asInstanceOf[AgentEvent.AgentFinish]
    assert(finish.totalSteps() > 1, "Should take multiple steps (tool calls + final answer)")
  }

  test("multi-turn conversation with tool use") {
    val ds = createSalesDatabase()
    val registry = new ToolRegistry()
    registry.register(new SchemaInspectTool(ds))
    registry.register(new SqlQueryTool(ds))

    val agent = ReactAgent.builder()
      .client(client)
      .modelName(modelName)
      .toolRegistry(registry)
      .maxIterations(10)
      .systemPrompt(SYSTEM_PROMPT)
      .build()

    // Shared memory across turns
    val memory = new ConversationMemory(100)

    // Turn 1: ask about the database
    val events1 = new CopyOnWriteArrayList[AgentEvent]()
    agent.run("How many orders are there in total?", memory, ApprovalMode.YOLO, e => events1.add(e))

    val eventList1 = events1.asScala.toList
    // scalastyle:off println
    println("=== Turn 1 ===")
    // scalastyle:on println
    printEventStream(eventList1)

    val sqlCalls1 =
      eventList1.collect { case tc: AgentEvent.ToolCall if tc.toolName() == "sql_query" => tc }
    assert(sqlCalls1.nonEmpty, "Turn 1 should query the database")
    assert(eventList1.last.isInstanceOf[AgentEvent.AgentFinish])

    // Turn 2: follow-up that relies on conversation context
    val events2 = new CopyOnWriteArrayList[AgentEvent]()
    agent.run(
      "Now show me only orders above 500 dollars.",
      memory,
      ApprovalMode.YOLO,
      e => events2.add(e))

    val eventList2 = events2.asScala.toList
    // scalastyle:off println
    println("=== Turn 2 ===")
    // scalastyle:on println
    printEventStream(eventList2)

    val sqlCalls2 =
      eventList2.collect { case tc: AgentEvent.ToolCall if tc.toolName() == "sql_query" => tc }
    assert(sqlCalls2.nonEmpty, "Turn 2 should also query the database")
    assert(eventList2.last.isInstanceOf[AgentEvent.AgentFinish])

    // Verify memory accumulated across both turns
    val rawMessages = memory.getRawMessages.asScala
    assert(
      rawMessages.size > 4,
      s"Memory should contain messages from both turns, got ${rawMessages.size}")
  }

  // --- Helper: print event stream ---

  private def printEventStream(events: List[AgentEvent]): Unit = {
    // scalastyle:off println
    events.foreach {
      case s: AgentEvent.StepStart =>
        println(s"[Step ${s.stepNumber()}]")
      case d: AgentEvent.ContentDelta =>
        print(d.text())
      case _: AgentEvent.ContentComplete =>
        println()
      case tc: AgentEvent.ToolCall =>
        println(s"[ToolCall] ${tc.toolName()}(${tc.toolArgs()})")
      case tr: AgentEvent.ToolResult =>
        val output = tr.output()
        val preview = if (output.length > 200) output.take(200) + "..." else output
        println(s"[ToolResult] ${tr.toolName()} -> $preview")
      case f: AgentEvent.AgentFinish =>
        println(s"[Finish] steps=${f.totalSteps()} tokens=${f.totalTokens()}")
      case e: AgentEvent.AgentError =>
        println(s"[Error] ${e.message()}")
    }
    println()
    // scalastyle:on println
  }

  // --- Helper: create test databases ---

  private def createSalesDatabase(): SQLiteDataSource = {
    val ds = createDataSource()
    val conn = ds.getConnection
    try {
      val stmt = conn.createStatement()
      stmt.execute(
        """CREATE TABLE products (
          |  id INTEGER PRIMARY KEY,
          |  name TEXT NOT NULL,
          |  category TEXT NOT NULL,
          |  price REAL NOT NULL
          |)""".stripMargin)
      stmt.execute(
        """INSERT INTO products VALUES
          |  (1, 'Laptop', 'Electronics', 999.99),
          |  (2, 'Headphones', 'Electronics', 199.99),
          |  (3, 'T-Shirt', 'Clothing', 29.99),
          |  (4, 'Jeans', 'Clothing', 59.99),
          |  (5, 'Novel', 'Books', 14.99),
          |  (6, 'Textbook', 'Books', 89.99)""".stripMargin)

      stmt.execute(
        """CREATE TABLE orders (
          |  id INTEGER PRIMARY KEY,
          |  product_id INTEGER NOT NULL,
          |  customer_name TEXT NOT NULL,
          |  quantity INTEGER NOT NULL,
          |  order_date TEXT NOT NULL,
          |  FOREIGN KEY (product_id) REFERENCES products(id)
          |)""".stripMargin)
      stmt.execute(
        """INSERT INTO orders VALUES
          |  (1, 1, 'Alice', 1, '2024-01-15'),
          |  (2, 2, 'Bob', 2, '2024-01-20'),
          |  (3, 3, 'Charlie', 3, '2024-02-01'),
          |  (4, 4, 'Alice', 1, '2024-02-10'),
          |  (5, 5, 'Bob', 5, '2024-02-15'),
          |  (6, 1, 'Diana', 1, '2024-03-01'),
          |  (7, 6, 'Charlie', 2, '2024-03-05'),
          |  (8, 2, 'Diana', 1, '2024-03-10')""".stripMargin)
      stmt.close()
    } finally {
      conn.close()
    }
    ds
  }

  private def createDataSource(): SQLiteDataSource = {
    val tmpFile = File.createTempFile("kyuubi-agent-live-", ".db")
    tmpFile.deleteOnExit()
    tempFiles.add(tmpFile)
    val ds = new SQLiteDataSource()
    ds.setUrl(s"jdbc:sqlite:${tmpFile.getAbsolutePath}")
    ds
  }
}
