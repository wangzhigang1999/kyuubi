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

package org.apache.kyuubi.engine.dataagent.tool.sql;

import static org.junit.Assert.*;

import java.io.File;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sqlite.SQLiteDataSource;

/**
 * Tests for SqlQueryTool focusing on maxRows enforcement and markdown stripping. Uses real SQLite —
 * no mocks.
 */
public class SqlQueryToolTest {

  private SQLiteDataSource ds;
  private SqlQueryTool tool;
  private final List<File> tempFiles = new ArrayList<>();

  @Before
  public void setUp() {
    ds = createDataSource();
    setupLargeTable(ds);
    tool = new SqlQueryTool(ds);
  }

  @After
  public void tearDown() {
    tempFiles.forEach(File::delete);
  }

  // --- maxRows enforcement ---

  @Test
  public void testMaxRowsDefaultTo100() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "SELECT id FROM large_table";
    String result = tool.execute(args);
    assertTrue(result.contains("[100 row(s) returned]"));
  }

  @Test
  public void testMaxRowsCustomValue() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "SELECT id FROM large_table";
    args.maxRows = 5;
    String result = tool.execute(args);
    assertTrue(result.contains("[5 row(s) returned]"));
  }

  @Test
  public void testMaxRowsHardLimitCapsAt1000() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "SELECT id FROM large_table";
    args.maxRows = Integer.MAX_VALUE;
    String result = tool.execute(args);
    // We only have 1500 rows but maxRows capped at 1000
    assertTrue(result.contains("[1000 row(s) returned]"));
  }

  @Test
  public void testMaxRowsZeroDefaultsTo100() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "SELECT id FROM large_table";
    args.maxRows = 0;
    String result = tool.execute(args);
    assertTrue(result.contains("[100 row(s) returned]"));
  }

  @Test
  public void testMaxRowsNegativeDefaultsTo100() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "SELECT id FROM large_table";
    args.maxRows = -1;
    String result = tool.execute(args);
    assertTrue(result.contains("[100 row(s) returned]"));
  }

  // --- Markdown stripping ---

  @Test
  public void testStripMarkdownCodeFence() {
    assertEquals("SELECT 1", SqlQueryTool.stripMarkdown("```sql\nSELECT 1\n```"));
    assertEquals("SELECT 1", SqlQueryTool.stripMarkdown("```\nSELECT 1\n```"));
    assertEquals("SELECT 1", SqlQueryTool.stripMarkdown("SELECT 1"));
  }

  // --- Write operations ---

  @Test
  public void testAllowsInsert() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "INSERT INTO large_table VALUES (9999, 'test-insert')";
    String result = tool.execute(args);
    assertTrue(result.contains("1 row(s) affected"));
  }

  @Test
  public void testAllowsCreateTable() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "CREATE TABLE test_write (id INTEGER PRIMARY KEY, value TEXT)";
    String result = tool.execute(args);
    assertTrue(result.contains("executed successfully"));
  }

  @Test
  public void testExecutesShowStyleQueries() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "SELECT name FROM sqlite_master WHERE type='table'";
    String result = tool.execute(args);
    assertTrue(result.contains("large_table"));
  }

  // --- Query timeout ---

  @Test
  public void testCustomQueryTimeout() {
    SqlQueryTool customTool = new SqlQueryTool(ds, 5);
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "SELECT COUNT(*) FROM large_table";
    String result = customTool.execute(args);
    assertFalse(result.startsWith("Error:"));
  }

  // --- Helpers ---

  private SQLiteDataSource createDataSource() {
    try {
      File tmpFile = File.createTempFile("kyuubi-sqlquery-test-", ".db");
      tmpFile.deleteOnExit();
      tempFiles.add(tmpFile);
      SQLiteDataSource dataSource = new SQLiteDataSource();
      dataSource.setUrl("jdbc:sqlite:" + tmpFile.getAbsolutePath());
      return dataSource;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void setupLargeTable(SQLiteDataSource dataSource) {
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE large_table (id INTEGER PRIMARY KEY, value TEXT)");
      // Insert 1500 rows to test maxRows capping
      StringBuilder sb = new StringBuilder();
      for (int i = 1; i <= 1500; i++) {
        if (sb.length() > 0) sb.append(",");
        sb.append("(").append(i).append(", 'row-").append(i).append("')");
        if (i % 500 == 0) {
          stmt.execute("INSERT INTO large_table VALUES " + sb);
          sb.setLength(0);
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
