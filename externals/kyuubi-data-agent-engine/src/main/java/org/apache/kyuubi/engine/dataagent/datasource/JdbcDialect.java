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

/**
 * SQL dialect inferred from a JDBC URL. Each dialect maps to:
 *
 * <ul>
 *   <li>a dialect name — for SQL compatibility hints ({@code prompts/dialect-{name}.md})
 *   <li>an optional engine name — for engine-specific guidelines ({@code prompts/engine-{name}.md})
 * </ul>
 */
public enum JdbcDialect {
  SQLITE("sqlite", null),
  SPARK("spark", "spark"),
  TRINO("trino", "trino");

  private final String dialectName;
  private final String engineName;

  JdbcDialect(String dialectName, String engineName) {
    this.dialectName = dialectName;
    this.engineName = engineName;
  }

  /** Dialect name for prompt resource lookup (e.g. "sqlite", "spark", "trino"). */
  public String dialectName() {
    return dialectName;
  }

  /** Engine name for prompt resource lookup, or {@code null} if not applicable. */
  public String engineName() {
    return engineName;
  }

  /**
   * Infer the dialect from a JDBC URL.
   *
   * @param jdbcUrl the JDBC connection URL
   * @return the matching dialect, or {@code null} if unrecognized
   */
  public static JdbcDialect fromUrl(String jdbcUrl) {
    if (jdbcUrl == null) {
      return null;
    }
    String lower = jdbcUrl.toLowerCase();
    if (lower.startsWith("jdbc:sqlite:")) {
      return SQLITE;
    }
    if (lower.startsWith("jdbc:hive2:") || lower.startsWith("jdbc:spark:")) {
      return SPARK;
    }
    if (lower.startsWith("jdbc:trino:") || lower.startsWith("jdbc:presto:")) {
      return TRINO;
    }
    return null;
  }
}
