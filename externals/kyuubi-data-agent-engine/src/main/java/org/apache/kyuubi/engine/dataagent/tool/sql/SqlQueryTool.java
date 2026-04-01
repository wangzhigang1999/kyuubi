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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.kyuubi.engine.dataagent.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tool for executing SQL SELECT queries against the database. Only SELECT is allowed. */
public class SqlQueryTool implements AgentTool<SqlQueryArgs> {

  private static final Logger LOG = LoggerFactory.getLogger(SqlQueryTool.class);

  /** Matches dangerous SQL keywords as whole words (case-insensitive). */
  private static final Pattern DANGEROUS_KEYWORDS =
      Pattern.compile(
          "\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|REPLACE|MERGE|GRANT|REVOKE)\\b",
          Pattern.CASE_INSENSITIVE);

  private final DataSource dataSource;

  public SqlQueryTool(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public String name() {
    return "sql_query";
  }

  @Override
  public String description() {
    return "Execute a SQL SELECT query to retrieve data from the database. "
        + "Only SELECT statements are allowed. "
        + "Use after inspecting schema with describe_schema. "
        + "Parameter 'sql' is required.";
  }

  @Override
  public Class<SqlQueryArgs> argsType() {
    return SqlQueryArgs.class;
  }

  @Override
  public String execute(SqlQueryArgs args) {
    String sql = args.sql != null ? args.sql.trim() : "";
    if (sql.isEmpty()) {
      return "Error: 'sql' parameter is required.";
    }

    // Strip markdown code block if present
    sql = stripMarkdown(sql);

    // Reject multiple statements (semicolons outside of string literals)
    if (containsSemicolon(sql)) {
      return "Error: Only single SELECT statements are allowed. Multiple statements are rejected.";
    }

    // Strip SQL comments before safety check
    String stripped = stripComments(sql).trim();
    if (stripped.isEmpty()) {
      return "Error: SQL is empty after stripping comments.";
    }

    // Safety check: must start with SELECT or WITH, no dangerous keywords
    String upper = stripped.toUpperCase();
    if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
      return "Error: Only SELECT queries are allowed. Got: "
          + stripped.substring(0, Math.min(50, stripped.length()));
    }

    if (DANGEROUS_KEYWORDS.matcher(stripped).find()) {
      return "Error: SQL contains forbidden keywords. Only read-only SELECT is allowed.";
    }

    int maxRows = args.maxRows > 0 ? args.maxRows : 100;

    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.setMaxRows(maxRows);
      try (ResultSet rs = stmt.executeQuery(sql)) {
        return formatResult(rs);
      }
    } catch (Exception e) {
      LOG.warn("SQL execution error: {}", e.getMessage());
      return "Error: SQL execution failed: " + e.getMessage();
    }
  }

  private String formatResult(ResultSet rs) throws Exception {
    ResultSetMetaData meta = rs.getMetaData();
    int colCount = meta.getColumnCount();

    StringBuilder sb = new StringBuilder();

    // Header
    for (int i = 1; i <= colCount; i++) {
      if (i > 1) sb.append(" | ");
      sb.append(meta.getColumnName(i));
    }
    sb.append("\n");

    // Separator
    for (int i = 1; i <= colCount; i++) {
      if (i > 1) sb.append("-+-");
      sb.append("---");
    }
    sb.append("\n");

    // Rows
    int rowCount = 0;
    while (rs.next()) {
      for (int i = 1; i <= colCount; i++) {
        if (i > 1) sb.append(" | ");
        String val = rs.getString(i);
        sb.append(val != null ? val : "NULL");
      }
      sb.append("\n");
      rowCount++;
    }

    sb.append("\n[").append(rowCount).append(" row(s) returned]");
    return sb.toString();
  }

  /** Strip markdown code fences (``` or ```sql etc.) wrapping the SQL. */
  static String stripMarkdown(String sql) {
    String trimmed = sql.trim();
    if (trimmed.startsWith("```")) {
      String[] lines = trimmed.split("\n");
      StringBuilder cleaned = new StringBuilder();
      for (int i = 1; i < lines.length; i++) {
        if (!lines[i].trim().startsWith("```")) {
          cleaned.append(lines[i]).append("\n");
        }
      }
      return cleaned.toString().trim();
    }
    return sql;
  }

  /** Strip single-line (--) and multi-line comments from SQL. */
  static String stripComments(String sql) {
    StringBuilder sb = new StringBuilder(sql.length());
    int i = 0;
    boolean inSingleQuote = false;

    while (i < sql.length()) {
      char c = sql.charAt(i);

      // Track string literals to avoid stripping comments inside them
      if (c == '\'' && !inSingleQuote) {
        inSingleQuote = true;
        sb.append(c);
        i++;
      } else if (c == '\'' && inSingleQuote) {
        inSingleQuote = false;
        sb.append(c);
        i++;
      } else if (inSingleQuote) {
        sb.append(c);
        i++;
      } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
        // Single-line comment: skip until end of line
        while (i < sql.length() && sql.charAt(i) != '\n') {
          i++;
        }
      } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
        // Multi-line comment: skip until */
        i += 2;
        while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
          i++;
        }
        i += 2; // skip */
      } else {
        sb.append(c);
        i++;
      }
    }

    return sb.toString();
  }

  /** Check for semicolons outside of string literals (stacked query prevention). */
  static boolean containsSemicolon(String sql) {
    boolean inSingleQuote = false;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (c == '\'') {
        inSingleQuote = !inSingleQuote;
      } else if (c == ';' && !inSingleQuote) {
        return true;
      }
    }
    return false;
  }
}
