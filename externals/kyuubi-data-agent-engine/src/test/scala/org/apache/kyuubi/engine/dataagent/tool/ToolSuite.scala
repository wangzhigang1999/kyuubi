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

package org.apache.kyuubi.engine.dataagent.tool

import java.io.File

import com.fasterxml.jackson.databind.ObjectMapper
import org.sqlite.SQLiteDataSource

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.engine.dataagent.tool.schema.{SchemaInspectArgs, SchemaInspectTool}
import org.apache.kyuubi.engine.dataagent.tool.sql.{SqlQueryArgs, SqlQueryTool}

class ToolSuite extends KyuubiFunSuite {

  private val mapper = new ObjectMapper()
  private val tempFiles = new java.util.ArrayList[File]()

  override def afterAll(): Unit = {
    tempFiles.forEach(f => f.delete())
    super.afterAll()
  }

  // --- Args deserialization ---

  test("SqlQueryArgs deserializes from JSON with required fields") {
    val json = """{"sql": "SELECT 1", "maxRows": 50}"""
    val args = mapper.readValue(json, classOf[SqlQueryArgs])
    assert(args.sql == "SELECT 1")
    assert(args.maxRows == 50)
  }

  test("SqlQueryArgs uses default maxRows when omitted") {
    val json = """{"sql": "SELECT 1"}"""
    val args = mapper.readValue(json, classOf[SqlQueryArgs])
    assert(args.sql == "SELECT 1")
    assert(args.maxRows == 100)
  }

  test("SchemaInspectArgs deserializes with default tableName") {
    val args = mapper.readValue("{}", classOf[SchemaInspectArgs])
    assert(args.tableName == "")
  }

  test("SchemaInspectArgs deserializes with explicit tableName") {
    val json = """{"tableName": "users"}"""
    val args = mapper.readValue(json, classOf[SchemaInspectArgs])
    assert(args.tableName == "users")
  }

  // --- ToolSchemaGenerator ---

  test("ToolSchemaGenerator produces correct schema for SqlQueryArgs") {
    val schema = ToolSchemaGenerator.generateSchema(classOf[SqlQueryArgs])
    assert(schema.get("type") == "object")
    val props = schema.get("properties").asInstanceOf[java.util.Map[String, AnyRef]]
    assert(props.containsKey("sql"))
    assert(props.containsKey("maxRows"))
    val required = schema.get("required").asInstanceOf[java.util.List[String]]
    assert(required.contains("sql"))
  }

  // --- AgentTool metadata ---

  test("SqlQueryTool has correct name and description") {
    val ds = createDataSource()
    val tool = new SqlQueryTool(ds)
    assert(tool.name() == "sql_query")
    assert(tool.description().contains("SELECT"))
    assert(tool.argsType() == classOf[SqlQueryArgs])
  }

  test("SchemaInspectTool has correct name and description") {
    val ds = createDataSource()
    val tool = new SchemaInspectTool(ds)
    assert(tool.name() == "describe_schema")
    assert(tool.description().contains("schema"))
    assert(tool.argsType() == classOf[SchemaInspectArgs])
  }

  // --- ToolRegistry ---

  test("ToolRegistry registers and builds ChatCompletionTool specs") {
    val ds = createDataSource()
    val registry = new ToolRegistry()
    registry.register(new SchemaInspectTool(ds))
    registry.register(new SqlQueryTool(ds))
    assert(!registry.isEmpty)

    // Build params — verifies the hand-built ChatCompletionTool specs work
    val builder = com.openai.models.chat.completions.ChatCompletionCreateParams.builder()
      .model(com.openai.models.ChatModel.GPT_4O)
      .addUserMessage("test")
    registry.addToolsTo(builder)
    val params = builder.build()

    val tools = params.tools().orElse(java.util.Collections.emptyList())
    assert(tools.size() == 2, s"Expected 2 tools, got ${tools.size()}")

    // Verify function names come from AgentTool.name(), not class name
    val names = new java.util.ArrayList[String]()
    tools.forEach(t => names.add(t.asFunction().function().name()))
    assert(names.contains("describe_schema"), s"Missing describe_schema in $names")
    assert(names.contains("sql_query"), s"Missing sql_query in $names")
  }

  test("ToolRegistry.executeTool deserializes and delegates") {
    val ds = createDataSource()
    setupTestTable(ds)
    val registry = new ToolRegistry()
    registry.register(new SqlQueryTool(ds))

    val result = registry.executeTool("sql_query", """{"sql": "SELECT COUNT(*) FROM users"}""")
    assert(result.contains("3"), s"Expected count of 3, got: $result")
  }

  test("ToolRegistry.executeTool returns error for unknown tool") {
    val registry = new ToolRegistry()
    val result = registry.executeTool("nonexistent", "{}")
    assert(result.startsWith("Error: unknown tool"))
  }

  // --- Tool execution with real SQLite ---

  test("SqlQueryTool executes SELECT and returns formatted result") {
    val ds = createDataSource()
    setupTestTable(ds)
    val tool = new SqlQueryTool(ds)

    val args = new SqlQueryArgs()
    args.sql = "SELECT name, age FROM users ORDER BY age"
    args.maxRows = 10

    val result = tool.execute(args)
    assert(result.contains("name"))
    assert(result.contains("Alice"))
    assert(result.contains("Bob"))
    assert(result.contains("[3 row(s) returned]"))
  }

  test("SqlQueryTool rejects non-SELECT queries") {
    val ds = createDataSource()
    val tool = new SqlQueryTool(ds)
    val args = new SqlQueryArgs()
    args.sql = "DROP TABLE users"
    assert(tool.execute(args).startsWith("Error: Only SELECT"))
  }

  test("SqlQueryTool strips markdown code blocks") {
    val ds = createDataSource()
    setupTestTable(ds)
    val tool = new SqlQueryTool(ds)
    val args = new SqlQueryArgs()
    args.sql = "```sql\nSELECT COUNT(*) FROM users\n```"
    assert(tool.execute(args).contains("3"))
  }

  test("SchemaInspectTool lists tables") {
    val ds = createDataSource()
    setupTestTable(ds)
    val tool = new SchemaInspectTool(ds)
    val args = new SchemaInspectArgs()
    assert(tool.execute(args).contains("users"))
  }

  test("SchemaInspectTool describes table columns and sample data") {
    val ds = createDataSource()
    setupTestTable(ds)
    val tool = new SchemaInspectTool(ds)
    val args = new SchemaInspectArgs()
    args.tableName = "users"
    val result = tool.execute(args)
    assert(result.contains("Table: users"))
    assert(result.contains("name"))
    assert(result.contains("Sample data"))
  }

  // --- Multiple DataSource isolation ---

  test("different tool instances use different data sources") {
    val ds1 = createDataSource()
    val ds2 = createDataSource()
    setupTable(ds1, "CREATE TABLE t1 (x TEXT)", "INSERT INTO t1 VALUES ('from-ds1')")
    setupTable(ds2, "CREATE TABLE t2 (y TEXT)", "INSERT INTO t2 VALUES ('from-ds2')")

    val reg1 = new ToolRegistry()
    reg1.register(new SqlQueryTool(ds1))
    reg1.register(new SchemaInspectTool(ds1))

    val reg2 = new ToolRegistry()
    reg2.register(new SqlQueryTool(ds2))
    reg2.register(new SchemaInspectTool(ds2))

    // ds1 has t1
    assert(reg1.executeTool("sql_query", """{"sql": "SELECT x FROM t1"}""").contains("from-ds1"))
    val schema1 = reg1.executeTool("describe_schema", "{}")
    assert(schema1.contains("t1"))
    assert(!schema1.contains("t2"))

    // ds2 has t2
    assert(reg2.executeTool("sql_query", """{"sql": "SELECT y FROM t2"}""").contains("from-ds2"))
    val schema2 = reg2.executeTool("describe_schema", "{}")
    assert(schema2.contains("t2"))
    assert(!schema2.contains("t1"))
  }

  // --- End-to-end roundtrip ---

  test("end-to-end: register → schema gen → deserialize → execute") {
    val ds = createDataSource()
    setupTestTable(ds)
    val registry = new ToolRegistry()
    registry.register(new SchemaInspectTool(ds))
    registry.register(new SqlQueryTool(ds))

    // 1. Schema generation works (addToolsTo succeeds)
    val builder = com.openai.models.chat.completions.ChatCompletionCreateParams.builder()
      .model(com.openai.models.ChatModel.GPT_4O)
      .addUserMessage("test")
    registry.addToolsTo(builder)
    assert(builder.build().tools().isPresent)

    // 2. Simulated tool calls (as if LLM responded)
    val schemaResult = registry.executeTool("describe_schema", """{"tableName": "users"}""")
    assert(schemaResult.contains("Table: users"))

    val queryResult = registry.executeTool(
      "sql_query",
      """{"sql": "SELECT name FROM users WHERE age > 25", "maxRows": 10}""")
    assert(queryResult.contains("Bob"))
    assert(queryResult.contains("Charlie"))
    assert(!queryResult.contains("Alice"))
  }

  // --- Helpers ---

  private def createDataSource(): SQLiteDataSource = {
    val tmpFile = File.createTempFile("kyuubi-tool-test-", ".db")
    tmpFile.deleteOnExit()
    tempFiles.add(tmpFile)
    val ds = new SQLiteDataSource()
    ds.setUrl(s"jdbc:sqlite:${tmpFile.getAbsolutePath}")
    ds
  }

  private def setupTestTable(ds: SQLiteDataSource): Unit = {
    setupTable(
      ds,
      "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)",
      "INSERT INTO users VALUES (1, 'Alice', 25), (2, 'Bob', 30), (3, 'Charlie', 35)")
  }

  private def setupTable(ds: SQLiteDataSource, ddl: String, dml: String): Unit = {
    val conn = ds.getConnection
    try {
      val stmt = conn.createStatement()
      stmt.execute(ddl)
      stmt.execute(dml)
      stmt.close()
    } finally {
      conn.close()
    }
  }
}
