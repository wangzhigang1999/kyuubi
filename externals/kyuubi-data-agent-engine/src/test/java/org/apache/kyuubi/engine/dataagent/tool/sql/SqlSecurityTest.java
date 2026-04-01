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
import org.apache.kyuubi.engine.dataagent.tool.schema.SchemaInspectArgs;
import org.apache.kyuubi.engine.dataagent.tool.schema.SchemaInspectTool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sqlite.SQLiteDataSource;

/** Real SQLite tests for SQL injection prevention and safety checks. No mocks. */
public class SqlSecurityTest {

  private SQLiteDataSource ds;
  private SqlQueryTool queryTool;
  private SchemaInspectTool schemaTool;
  private final List<File> tempFiles = new ArrayList<>();

  @Before
  public void setUp() {
    ds = createDataSource();
    setupTestData(ds);
    queryTool = new SqlQueryTool(ds);
    schemaTool = new SchemaInspectTool(ds);
  }

  @After
  public void tearDown() {
    tempFiles.forEach(File::delete);
  }

  // --- SqlQueryTool: comment-based injection ---

  @Test
  public void testRejectsSingleLineCommentInjection() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "-- DROP TABLE users\nSELECT * FROM users";
    // After stripping comments, this becomes "SELECT * FROM users" which is fine
    String result = queryTool.execute(args);
    assertTrue("Valid SELECT after comment stripping should succeed", result.contains("row(s)"));
  }

  @Test
  public void testRejectsMultiLineCommentWithDangerousKeyword() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "/* hello */ DROP TABLE users";
    String result = queryTool.execute(args);
    assertTrue("DROP should be rejected", result.startsWith("Error:"));
  }

  @Test
  public void testRejectsCommentHiddenDropAfterSelect() {
    // Even if it starts with SELECT, dangerous keywords in body are rejected
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "SELECT 1; DROP TABLE users";
    String result = queryTool.execute(args);
    assertTrue("Semicolon should be rejected", result.contains("Error:"));
  }

  @Test
  public void testRejectsStackedQueries() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "SELECT 1; DELETE FROM users";
    String result = queryTool.execute(args);
    assertTrue("Stacked queries should be rejected", result.startsWith("Error:"));
  }

  @Test
  public void testAllowsSemicolonInsideStringLiteral() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "SELECT name FROM users WHERE name = 'foo;bar'";
    String result = queryTool.execute(args);
    // Should not be rejected — semicolon is inside a string literal
    assertFalse("Semicolon in string literal should be allowed", result.startsWith("Error:"));
  }

  @Test
  public void testRejectsInsert() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "INSERT INTO users VALUES (99, 'hacker', 99)";
    String result = queryTool.execute(args);
    assertTrue(result.startsWith("Error:"));
  }

  @Test
  public void testRejectsUpdate() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "UPDATE users SET name = 'hacked' WHERE id = 1";
    String result = queryTool.execute(args);
    assertTrue(result.startsWith("Error:"));
  }

  @Test
  public void testRejectsDelete() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "DELETE FROM users";
    String result = queryTool.execute(args);
    assertTrue(result.startsWith("Error:"));
  }

  @Test
  public void testRejectsTruncate() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "TRUNCATE TABLE users";
    String result = queryTool.execute(args);
    assertTrue(result.startsWith("Error:"));
  }

  @Test
  public void testRejectsAlter() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "ALTER TABLE users ADD COLUMN pwned TEXT";
    String result = queryTool.execute(args);
    assertTrue(result.startsWith("Error:"));
  }

  @Test
  public void testRejectsDangerousKeywordInSubquery() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "SELECT * FROM (DELETE FROM users RETURNING *)";
    String result = queryTool.execute(args);
    assertTrue("DELETE in subquery should be rejected", result.startsWith("Error:"));
  }

  @Test
  public void testAllowsWithCTE() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "WITH cte AS (SELECT id, name FROM users) SELECT * FROM cte";
    String result = queryTool.execute(args);
    assertTrue("CTE query should work", result.contains("row(s)"));
  }

  @Test
  public void testAllowsValidSelect() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "SELECT name, age FROM users WHERE age > 25 ORDER BY age";
    String result = queryTool.execute(args);
    assertTrue(result.contains("Bob"));
    assertTrue(result.contains("Charlie"));
  }

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

  @Test
  public void testRejectsCommentOnlySql() {
    SqlQueryArgs args = new SqlQueryArgs();
    args.sql = "-- just a comment";
    String result = queryTool.execute(args);
    assertTrue(result.startsWith("Error:"));
  }

  // --- SqlQueryTool: markdown stripping ---

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

  // --- SqlQueryTool: stripComments unit tests ---

  @Test
  public void testStripSingleLineComment() {
    assertEquals("SELECT 1 ", SqlQueryTool.stripComments("SELECT 1 -- comment"));
  }

  @Test
  public void testStripMultiLineComment() {
    assertEquals("SELECT  1", SqlQueryTool.stripComments("SELECT /* block */ 1"));
  }

  @Test
  public void testPreservesCommentInsideStringLiteral() {
    String sql = "SELECT '-- not a comment' FROM t";
    assertEquals(sql, SqlQueryTool.stripComments(sql));
  }

  @Test
  public void testSemicolonDetection() {
    assertTrue(SqlQueryTool.containsSemicolon("SELECT 1; DROP TABLE"));
    assertFalse(SqlQueryTool.containsSemicolon("SELECT 'a;b' FROM t"));
    assertFalse(SqlQueryTool.containsSemicolon("SELECT 1"));
  }

  // --- SchemaInspectTool: SQL injection prevention ---

  @Test
  public void testSchemaInspectRejectsNonexistentTable() {
    SchemaInspectArgs args = new SchemaInspectArgs();
    args.tableName = "nonexistent_table";
    String result = schemaTool.execute(args);
    assertTrue("Should report table not found", result.contains("does not exist"));
  }

  @Test
  public void testSchemaInspectRejectsInjectionAttempt() {
    SchemaInspectArgs args = new SchemaInspectArgs();
    args.tableName = "users; DROP TABLE users--";
    String result = schemaTool.execute(args);
    assertTrue(
        "Injection attempt should fail with 'does not exist'", result.contains("does not exist"));

    // Verify the users table still exists and has data
    SqlQueryArgs queryArgs = new SqlQueryArgs();
    queryArgs.sql = "SELECT COUNT(*) FROM users";
    String queryResult = queryTool.execute(queryArgs);
    assertTrue("users table should still exist with data", queryResult.contains("3"));
  }

  @Test
  public void testSchemaInspectRejectsQuoteInjection() {
    SchemaInspectArgs args = new SchemaInspectArgs();
    args.tableName = "users\" OR 1=1--";
    String result = schemaTool.execute(args);
    assertTrue(result.contains("does not exist"));
  }

  @Test
  public void testSchemaInspectValidTable() {
    SchemaInspectArgs args = new SchemaInspectArgs();
    args.tableName = "users";
    String result = schemaTool.execute(args);
    assertTrue(result.contains("Table: users"));
    assertTrue(result.contains("name"));
    assertTrue(result.contains("Sample data"));
  }

  @Test
  public void testSchemaInspectListTables() {
    SchemaInspectArgs args = new SchemaInspectArgs();
    String result = schemaTool.execute(args);
    assertTrue(result.contains("users"));
  }

  @Test
  public void testSchemaInspectNullValues() {
    // Insert a row with NULL to verify NULL handling
    try (Connection conn = ds.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("INSERT INTO users VALUES (4, NULL, NULL)");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    SchemaInspectArgs args = new SchemaInspectArgs();
    args.tableName = "users";
    String result = schemaTool.execute(args);
    // Should not throw, nulls handled gracefully
    assertNotNull(result);
    assertTrue(result.contains("Table: users"));
  }

  // --- Error message format ---

  @Test
  public void testErrorMessagesStartWithError() {
    // All error messages from tools should start with "Error:"
    SqlQueryArgs dropArgs = new SqlQueryArgs();
    dropArgs.sql = "DROP TABLE users";
    assertTrue(queryTool.execute(dropArgs).startsWith("Error:"));

    SqlQueryArgs emptyArgs = new SqlQueryArgs();
    emptyArgs.sql = "";
    assertTrue(queryTool.execute(emptyArgs).startsWith("Error:"));

    SchemaInspectArgs badTable = new SchemaInspectArgs();
    badTable.tableName = "nonexistent";
    assertTrue(schemaTool.execute(badTable).startsWith("Error:"));
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
