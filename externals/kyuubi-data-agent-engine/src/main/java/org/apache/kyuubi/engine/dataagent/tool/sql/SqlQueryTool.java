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
import javax.sql.DataSource;
import org.apache.kyuubi.engine.dataagent.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tool for executing SQL SELECT queries against the database. Only SELECT is allowed. */
public class SqlQueryTool implements AgentTool<SqlQueryArgs> {

  private static final Logger LOG = LoggerFactory.getLogger(SqlQueryTool.class);
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
    sql = cleanSql(sql);

    // Safety check: only SELECT allowed
    String upper = sql.toUpperCase().replaceAll("^\\s+", "");
    if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
      return "Error: Only SELECT queries are allowed. Got: "
          + sql.substring(0, Math.min(50, sql.length()));
    }

    int maxRows = args.maxRows > 0 ? args.maxRows : 100;

    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.setMaxRows(maxRows);
      ResultSet rs = stmt.executeQuery(sql);
      return formatResult(rs);
    } catch (Exception e) {
      LOG.warn("SQL execution error: {}", e.getMessage());
      return "SQL Error: " + e.getMessage();
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

  private static String cleanSql(String sql) {
    if (sql.startsWith("```")) {
      String[] lines = sql.split("\n");
      StringBuilder cleaned = new StringBuilder();
      for (int i = 1; i < lines.length; i++) {
        if (!lines[i].startsWith("```")) {
          cleaned.append(lines[i]).append("\n");
        }
      }
      return cleaned.toString().trim();
    }
    return sql;
  }
}
