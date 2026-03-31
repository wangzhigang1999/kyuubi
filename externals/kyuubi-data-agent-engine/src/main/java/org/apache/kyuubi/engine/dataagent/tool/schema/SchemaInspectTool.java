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

package org.apache.kyuubi.engine.dataagent.tool.schema;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.kyuubi.engine.dataagent.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool for inspecting database schema. Lists tables when no table_name is given, or describes
 * columns and sample data for a specific table.
 */
public class SchemaInspectTool implements AgentTool<SchemaInspectArgs> {

  private static final Logger LOG = LoggerFactory.getLogger(SchemaInspectTool.class);
  private final DataSource dataSource;

  public SchemaInspectTool(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public String name() {
    return "describe_schema";
  }

  @Override
  public String description() {
    return "Describe database schema. "
        + "If table_name is empty or omitted, lists all tables. "
        + "If table_name is provided, shows columns with types and sample values. "
        + "Always call this before writing SQL against unfamiliar tables.";
  }

  @Override
  public Class<SchemaInspectArgs> argsType() {
    return SchemaInspectArgs.class;
  }

  @Override
  public String execute(SchemaInspectArgs args) {
    String tableName = args.tableName != null ? args.tableName : "";
    try (Connection conn = dataSource.getConnection()) {
      if (tableName.isEmpty()) {
        return listTables(conn);
      } else {
        return describeTable(conn, tableName);
      }
    } catch (Exception e) {
      LOG.error("Schema inspect error", e);
      return "Error inspecting schema: " + e.getMessage();
    }
  }

  private String listTables(Connection conn) throws Exception {
    DatabaseMetaData meta = conn.getMetaData();
    ResultSet rs = meta.getTables(null, null, "%", new String[] {"TABLE"});
    StringBuilder sb = new StringBuilder("Tables in database:\n");
    while (rs.next()) {
      sb.append("  - ").append(rs.getString("TABLE_NAME")).append("\n");
    }
    return sb.toString();
  }

  private String describeTable(Connection conn, String tableName) throws Exception {
    StringBuilder sb = new StringBuilder();

    // Column info
    DatabaseMetaData meta = conn.getMetaData();
    ResultSet cols = meta.getColumns(null, null, tableName, "%");
    sb.append("Table: ").append(tableName).append("\nColumns:\n");
    while (cols.next()) {
      String colName = cols.getString("COLUMN_NAME");
      String typeName = cols.getString("TYPE_NAME");
      String nullable = "YES".equals(cols.getString("IS_NULLABLE")) ? " (nullable)" : "";
      sb.append("  - ").append(colName).append(": ").append(typeName).append(nullable).append("\n");
    }

    // Sample data (first 3 rows)
    try (Statement stmt = conn.createStatement()) {
      ResultSet sample = stmt.executeQuery("SELECT * FROM " + tableName + " LIMIT 3");
      int colCount = sample.getMetaData().getColumnCount();
      sb.append("Sample data (first 3 rows):\n");
      while (sample.next()) {
        sb.append("  ");
        for (int i = 1; i <= colCount; i++) {
          if (i > 1) sb.append(" | ");
          sb.append(sample.getString(i));
        }
        sb.append("\n");
      }
    }

    return sb.toString();
  }
}
