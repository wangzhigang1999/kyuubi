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

package org.apache.kyuubi.engine.dataagent.benchmark.eval

import java.sql.DriverManager
import java.util.UUID

import org.apache.kyuubi.KyuubiFunSuite

class SqlExecutionEvaluatorSuite extends KyuubiFunSuite {

  private val dbPath =
    s"${System.getProperty("java.io.tmpdir")}/eval_test_${UUID.randomUUID()}.db"
  private val jdbcUrl = s"jdbc:sqlite:$dbPath"

  override def beforeAll(): Unit = {
    super.beforeAll()
    new java.io.File(dbPath).delete()
    val conn = DriverManager.getConnection(jdbcUrl)
    try {
      val stmt = conn.createStatement()
      stmt.execute("CREATE TABLE t (id INTEGER, name TEXT, score REAL)")
      stmt.execute("INSERT INTO t VALUES (1, 'Alice', 91.5)")
      stmt.execute("INSERT INTO t VALUES (2, 'Bob', 82.0)")
      stmt.execute("INSERT INTO t VALUES (3, 'Carol', 77.25)")
    } finally conn.close()
  }

  override def afterAll(): Unit = {
    new java.io.File(dbPath).delete()
    super.afterAll()
  }

  test("EX: same rows in different order are equal") {
    val o = SqlExecutionEvaluator.evaluate(
      jdbcUrl,
      "SELECT id, name FROM t ORDER BY id DESC",
      "SELECT id, name FROM t ORDER BY id ASC")
    assert(o.ex)
  }

  test("EX: string comparison is case-insensitive") {
    val o = SqlExecutionEvaluator.evaluate(
      jdbcUrl,
      "SELECT LOWER(name) FROM t WHERE id = 1",
      "SELECT 'ALICE'")
    assert(o.ex)
  }

  test("EX: different row set is not equal") {
    val o = SqlExecutionEvaluator.evaluate(
      jdbcUrl,
      "SELECT id FROM t WHERE id <= 2",
      "SELECT id FROM t")
    assert(!o.ex)
  }

  test("Soft-F1: identical result is 1.0") {
    val o = SqlExecutionEvaluator.evaluate(
      jdbcUrl,
      "SELECT id, name FROM t",
      "SELECT id, name FROM t")
    assert(math.abs(o.softF1 - 1.0) < 1e-9)
  }

  test("Soft-F1: both empty is 1.0") {
    val o = SqlExecutionEvaluator.evaluate(
      jdbcUrl,
      "SELECT id FROM t WHERE 1=0",
      "SELECT id FROM t WHERE 1=0")
    assert(math.abs(o.softF1 - 1.0) < 1e-9)
  }

  test("Pred SQL error propagates as non-EX, zero F1") {
    val o = SqlExecutionEvaluator.evaluate(
      jdbcUrl,
      "SELECT bogus FROM nope",
      "SELECT id FROM t")
    assert(!o.ex)
    assert(o.softF1 == 0.0)
    assert(o.message.startsWith("pred error:"))
  }
}
