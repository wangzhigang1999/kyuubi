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

/** Real SQLite tests for SqlQueryTool with all restrictions removed. */
public class SqlSecurityTest {

  private SQLiteDataSource ds;
  private SqlQueryTool queryTool;
  private final List<File> tempFiles = new ArrayList<>();

  @Before
  public void setUp() {
    ds = createDataSource();
    setupTestData(ds);
    queryTool = new SqlQueryTool(ds, 30);
  }

  @After
  public void tearDown() {
    tempFiles.forEach(File::delete);
  }

  // --- Basic SQL execution ---

  @Test
  public void testAllowsValidSelect() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "SELECT name, age FROM users WHERE age > 25 ORDER BY age";
    String result = queryTool.execute(args);
    assertTrue(result.contains("Bob"));
    assertTrue(result.contains("Charlie"));
  }

  @Test
  public void testAllowsWithCTE() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "WITH cte AS (SELECT id, name FROM users) SELECT * FROM cte";
    String result = queryTool.execute(args);
    assertTrue("CTE query should work", result.contains("row(s)"));
  }

  @Test
  public void testAllowsSchemaExploration() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "SELECT name FROM sqlite_master WHERE type='table'";
    String result = queryTool.execute(args);
    assertTrue(result.contains("users"));
  }

  @Test
  public void testAllowsPragma() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "PRAGMA table_info(users)";
    String result = queryTool.execute(args);
    assertTrue(result.contains("name"));
    assertTrue(result.contains("age"));
  }

  // --- Write operations are allowed (general-purpose SQL tool) ---

  @Test
  public void testAllowsInsert() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "INSERT INTO users VALUES (99, 'NewUser', 40)";
    String result = queryTool.execute(args);
    assertTrue(result.contains("1 row(s) affected"));
  }

  @Test
  public void testAllowsUpdate() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "UPDATE users SET name = 'Updated' WHERE id = 1";
    String result = queryTool.execute(args);
    assertTrue(result.contains("1 row(s) affected"));
  }

  @Test
  public void testAllowsDelete() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "DELETE FROM users WHERE id = 1";
    String result = queryTool.execute(args);
    assertTrue(result.contains("1 row(s) affected"));
  }

  // --- Edge cases ---

  @Test
  public void testRejectsEmptySql() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "";
    String result = queryTool.execute(args);
    assertTrue(result.startsWith("Error:"));
  }

  @Test
  public void testRejectsNullSql() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = null;
    String result = queryTool.execute(args);
    assertTrue(result.startsWith("Error:"));
  }

  // --- Markdown stripping ---

  @Test
  public void testStripsMarkdownCodeFenceSql() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "```sql\nSELECT COUNT(*) FROM users\n```";
    String result = queryTool.execute(args);
    assertTrue(result.contains("3"));
  }

  @Test
  public void testStripsMarkdownCodeFenceWithSpace() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "```\nSELECT COUNT(*) FROM users\n```";
    String result = queryTool.execute(args);
    assertTrue(result.contains("3"));
  }

  // --- Error message format ---

  @Test
  public void testErrorMessagesStartWithError() {
    SqlQueryArgs emptyArgs = new SqlQueryArgs();
    emptyArgs.sql = "";
    assertTrue(queryTool.execute(emptyArgs).startsWith("Error:"));

    // Invalid SQL should return database error
    SqlQueryArgs badArgs = new SqlQueryArgs();
    badArgs.sql = "SELECT * FROM nonexistent_table";
    assertTrue(queryTool.execute(badArgs).startsWith("Error:"));
  }

  // --- Helpers ---

  private SQLiteDataSource createDataSource() {
    try {
      File tmpFile = File.createTempFile("kyuubi-security-test-", ".db");
      tmpFile.deleteOnExit();
      tempFiles.add(tmpFile);
      SQLiteDataSource dataSource = new SQLiteDataSource();
      dataSource.setUrl("jdbc:sqlite:" + tmpFile.getAbsolutePath());
      return dataSource;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void setupTestData(SQLiteDataSource dataSource) {
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)");
      stmt.execute("INSERT INTO users VALUES (1, 'Alice', 25), (2, 'Bob', 30), (3, 'Charlie', 35)");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
