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

package org.apache.kyuubi.engine.dataagent.benchmark

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.sql.DriverManager
import java.util.UUID

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.engine.dataagent.benchmark.bird.BirdDataset

/**
 * End-to-end smoke test: loads one synthetic BIRD example against a fresh SQLite DB, runs the
 * real agent, and asserts that a results.jsonl line is produced. Requires LLM env vars; otherwise
 * the whole suite is skipped (matches DataAgentE2ESuite's pattern).
 */
class BirdBenchmarkSmokeSuite extends KyuubiFunSuite {

  private val apiKey = sys.env.getOrElse("DATA_AGENT_LLM_API_KEY", "")
  private val apiUrl = sys.env.getOrElse("DATA_AGENT_LLM_API_URL", "")
  private val modelName = sys.env.getOrElse("DATA_AGENT_LLM_MODEL", "")
  private val enabled: Boolean = apiKey.nonEmpty && apiUrl.nonEmpty && modelName.nonEmpty

  private val workRoot: Path =
    Paths.get(System.getProperty("java.io.tmpdir"), s"bird_smoke_${UUID.randomUUID()}")
  private val dbDir: Path = workRoot.resolve("dev_databases").resolve("toy")
  private val dbPath: Path = dbDir.resolve("toy.sqlite")
  private val datasetJson: Path = workRoot.resolve("mini.json")
  private val outputDir: Path = workRoot.resolve("out")

  override def beforeAll(): Unit = {
    if (!enabled) return
    super.beforeAll()
    Files.createDirectories(dbDir)
    val conn = DriverManager.getConnection(s"jdbc:sqlite:${dbPath.toAbsolutePath}")
    try {
      val stmt = conn.createStatement()
      stmt.execute("CREATE TABLE cities (name TEXT PRIMARY KEY, population INTEGER)")
      stmt.execute("INSERT INTO cities VALUES ('Tokyo', 37400000)")
      stmt.execute("INSERT INTO cities VALUES ('Delhi', 29300000)")
      stmt.execute("INSERT INTO cities VALUES ('Shanghai', 26300000)")
    } finally conn.close()

    val json =
      """[
        |  {
        |    "question_id": 1,
        |    "db_id": "toy",
        |    "question": "Which city has the largest population?",
        |    "SQL": "SELECT name FROM cities ORDER BY population DESC LIMIT 1",
        |    "evidence": "",
        |    "difficulty": "simple"
        |  }
        |]""".stripMargin
    Files.write(datasetJson, json.getBytes(StandardCharsets.UTF_8))
  }

  override def afterAll(): Unit = {
    if (enabled) {
      deleteRecursively(workRoot.toFile)
      super.afterAll()
    }
  }

  private def deleteRecursively(f: java.io.File): Unit = {
    if (f.isDirectory) Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete()
  }

  test("runs one BIRD example end-to-end and writes results.jsonl") {
    assume(enabled, "DATA_AGENT_LLM_API_KEY/API_URL/MODEL not set, skipping smoke test")

    val dataset = BirdDataset.load(datasetJson, workRoot.resolve("dev_databases"), "", "", 0)
    assert(dataset.examples().size() == 1)

    val fcfg = new AgentHandleFactory.Config()
    fcfg.apiKey = apiKey
    fcfg.baseUrl = apiUrl
    fcfg.modelName = modelName
    fcfg.maxIterations = 10

    val rcfg = new BenchmarkRunner.Config()
    rcfg.outputDir = outputDir
    rcfg.concurrency = 1
    rcfg.resume = false

    val factory = new AgentHandleFactory(fcfg)
    val summary =
      try new BenchmarkRunner(rcfg).run(dataset, factory)
      finally factory.close()

    assert(summary.overall().total == 1)
    assert(Files.exists(outputDir.resolve("results.jsonl")))
    assert(Files.exists(outputDir.resolve("summary.tsv")))
    val lines = Files.readAllLines(outputDir.resolve("results.jsonl"))
    assert(lines.size() == 1, s"expected 1 result line, got ${lines.size()}")
    info(s"Smoke result: ${lines.get(0)}")
    info(summary.toPrettyString)
  }
}
