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

public class JdbcDialectTest {

  @Test
  public void testSqlite() {
    JdbcDialect d = JdbcDialect.fromUrl("jdbc:sqlite:/tmp/test.db");
    assertEquals(JdbcDialect.SQLITE, d);
    assertEquals("sqlite", d.dialectName());
    assertNull(d.engineName());
  }

  @Test
  public void testSparkViaHive2() {
    JdbcDialect d = JdbcDialect.fromUrl("jdbc:hive2://localhost:10009/default");
    assertEquals(JdbcDialect.SPARK, d);
    assertEquals("spark", d.dialectName());
    assertEquals("spark", d.engineName());
  }

  @Test
  public void testSparkViaSpark() {
    JdbcDialect d = JdbcDialect.fromUrl("jdbc:spark://localhost:10009/default");
    assertEquals(JdbcDialect.SPARK, d);
  }

  @Test
  public void testTrino() {
    JdbcDialect d = JdbcDialect.fromUrl("jdbc:trino://localhost:8080/hive/default");
    assertEquals(JdbcDialect.TRINO, d);
    assertEquals("trino", d.dialectName());
    assertEquals("trino", d.engineName());
  }

  @Test
  public void testPresto() {
    JdbcDialect d = JdbcDialect.fromUrl("jdbc:presto://localhost:8080/hive/default");
    assertEquals(JdbcDialect.TRINO, d);
  }

  @Test
  public void testCaseInsensitive() {
    assertEquals(JdbcDialect.SQLITE, JdbcDialect.fromUrl("JDBC:SQLITE:/tmp/test.db"));
    assertEquals(JdbcDialect.SPARK, JdbcDialect.fromUrl("JDBC:HIVE2://localhost:10009"));
  }

  @Test
  public void testUnknownReturnsNull() {
    assertNull(JdbcDialect.fromUrl("jdbc:mysql://localhost/db"));
    assertNull(JdbcDialect.fromUrl("not-a-jdbc-url"));
  }

  @Test
  public void testNullReturnsNull() {
    assertNull(JdbcDialect.fromUrl(null));
  }
}
