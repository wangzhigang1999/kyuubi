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
 * Tool for inspecting database schema at three levels:
 *
 * <ul>
 *   <li>No args → list all databases/schemas
 *   <li>database only → list tables in that database
 *   <li>database + table_name → describe columns and sample data
 * </ul>
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
    return "Inspect database schema at three levels: "
        + "(1) omit both params to list all databases/schemas; "
        + "(2) provide database to list tables in it; "
        + "(3) provide database and table_name to show columns, types, and sample data. "
        + "Always call this before writing SQL against unfamiliar tables.";
  }

  @Override
  public Class<SchemaInspectArgs> argsType() {
    return SchemaInspectArgs.class;
  }

  @Override
  public String execute(SchemaInspectArgs args) {
    String database = args.database != null ? args.database.trim() : "";
    String tableName = args.tableName != null ? args.tableName.trim() : "";
    try (Connection conn = dataSource.getConnection()) {
      if (database.isEmpty() && tableName.isEmpty()) {
        return listDatabases(conn);
      } else if (tableName.isEmpty()) {
        return listTables(conn, database);
      } else {
        return describeTable(conn, database, tableName);
      }
    } catch (Exception e) {
      LOG.error("Schema inspect error", e);
      return "Error: failed to inspect schema: " + e.getMessage();
    }
  }

  private String listDatabases(Connection conn) throws Exception {
    DatabaseMetaData meta = conn.getMetaData();
    try (ResultSet rs = meta.getSchemas()) {
      StringBuilder sb = new StringBuilder();
      while (rs.next()) {
        String schema = rs.getString("TABLE_SCHEM");
        if (schema != null) {
          sb.append("  - ").append(schema).append("\n");
        }
      }
      if (sb.length() > 0) {
        return "Schemas:\n" + sb;
      }
    }
    // Fallback: some databases (MySQL, StarRocks) use catalogs instead of schemas
    try (ResultSet rs = meta.getCatalogs()) {
      StringBuilder sb = new StringBuilder("Databases:\n");
      while (rs.next()) {
        String catalog = rs.getString("TABLE_CAT");
        if (catalog != null) {
          sb.append("  - ").append(catalog).append("\n");
        }
      }
      return sb.toString();
    }
  }

  private String listTables(Connection conn, String database) throws Exception {
    DatabaseMetaData meta = conn.getMetaData();
    // Try as schema first, then as catalog (for MySQL/StarRocks)
    StringBuilder sb = new StringBuilder();
    try (ResultSet rs = meta.getTables(database, database, "%", new String[] {"TABLE"})) {
      while (rs.next()) {
        String name = rs.getString("TABLE_NAME");
        if (name != null) {
          sb.append("  - ").append(name).append("\n");
        }
      }
    }
    if (sb.length() == 0) {
      return "No tables found in database '" + database + "'.";
    }
    return "Tables in " + database + ":\n" + sb;
  }

  private String describeTable(Connection conn, String database, String tableName)
      throws Exception {
    if (!tableExists(conn, database, tableName)) {
      return "Error: table '" + tableName + "' does not exist in database '" + database + "'.";
    }

    StringBuilder sb = new StringBuilder();

    // Column info
    DatabaseMetaData meta = conn.getMetaData();
    try (ResultSet cols = meta.getColumns(database, database, tableName, "%")) {
      sb.append("Table: ").append(database).append(".").append(tableName).append("\nColumns:\n");
      while (cols.next()) {
        String colName = cols.getString("COLUMN_NAME");
        String typeName = cols.getString("TYPE_NAME");
        String nullable = "YES".equals(cols.getString("IS_NULLABLE")) ? " (nullable)" : "";
        sb.append("  - ")
            .append(colName)
            .append(": ")
            .append(typeName)
            .append(nullable)
            .append("\n");
      }
    }

    // Sample data (first 3 rows)
    try (Statement stmt = conn.createStatement();
        ResultSet sample =
            stmt.executeQuery(
                "SELECT * FROM "
                    + stmt.enquoteIdentifier(database, false)
                    + "."
                    + stmt.enquoteIdentifier(tableName, false)
                    + " LIMIT 3")) {
      int colCount = sample.getMetaData().getColumnCount();
      sb.append("Sample data (first 3 rows):\n");
      while (sample.next()) {
        sb.append("  ");
        for (int i = 1; i <= colCount; i++) {
          if (i > 1) sb.append(" | ");
          String val = sample.getString(i);
          sb.append(val != null ? val : "NULL");
        }
        sb.append("\n");
      }
    }

    return sb.toString();
  }

  private static boolean tableExists(Connection conn, String database, String tableName)
      throws Exception {
    DatabaseMetaData meta = conn.getMetaData();
    try (ResultSet rs = meta.getTables(database, database, tableName, new String[] {"TABLE"})) {
      while (rs.next()) {
        if (tableName.equals(rs.getString("TABLE_NAME"))) {
          return true;
        }
      }
    }
    return false;
  }
}
