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

package org.apache.kyuubi.engine.dataagent.datasource;

import static org.junit.Assert.*;

import org.junit.Test;

/** Direct unit tests for SparkDialect and SqliteDialect implementations. */
public class DialectTest {

  // --- SparkDialect ---

  @Test
  public void testSparkDatasourceName() {
    JdbcDialect dialect = JdbcDialect.fromUrl("jdbc:hive2://localhost:10009");
    assertEquals("spark", dialect.datasourceName());
  }

  @Test
  public void testSparkQuoteIdentifier() {
    JdbcDialect dialect = JdbcDialect.fromUrl("jdbc:hive2://localhost:10009");
    assertEquals("`my_table`", dialect.quoteIdentifier("my_table"));
  }

  @Test
  public void testSparkQuoteIdentifierWithBacktick() {
    JdbcDialect dialect = JdbcDialect.fromUrl("jdbc:hive2://localhost:10009");
    assertEquals("`my``table`", dialect.quoteIdentifier("my`table"));
  }

  // --- SqliteDialect ---

  @Test
  public void testSqliteDatasourceName() {
    JdbcDialect dialect = JdbcDialect.fromUrl("jdbc:sqlite:/tmp/test.db");
    assertEquals("sqlite", dialect.datasourceName());
  }

  @Test
  public void testSqliteQuoteIdentifier() {
    JdbcDialect dialect = JdbcDialect.fromUrl("jdbc:sqlite:/tmp/test.db");
    assertEquals("\"my_table\"", dialect.quoteIdentifier("my_table"));
  }

  @Test
  public void testSqliteQuoteIdentifierWithQuote() {
    JdbcDialect dialect = JdbcDialect.fromUrl("jdbc:sqlite:/tmp/test.db");
    assertEquals("\"my\"\"table\"", dialect.quoteIdentifier("my\"table"));
  }

  // --- JdbcDialect.fromUrl edge cases ---

  @Test
  public void testFromUrlSparkPrefix() {
    JdbcDialect dialect = JdbcDialect.fromUrl("jdbc:spark://localhost:10009");
    assertNotNull(dialect);
    assertEquals("spark", dialect.datasourceName());
  }

  @Test
  public void testFromUrlCaseInsensitive() {
    JdbcDialect dialect = JdbcDialect.fromUrl("JDBC:HIVE2://localhost:10009");
    assertNotNull(dialect);
    assertEquals("spark", dialect.datasourceName());
  }
}
