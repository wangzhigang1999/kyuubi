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

package org.apache.kyuubi.engine.dataagent.prompt;

/**
 * Convenience facade for building system prompts.
 *
 * <p>For simple cases, use {@link #defaultPrompt()} or {@link #forEngine(String)}. For advanced
 * composition (engine + dialect + tools + custom sections), use {@link SystemPromptBuilder}
 * directly.
 */
public final class SystemPrompts {

  private SystemPrompts() {}

  /** Return the base prompt without engine or dialect guidelines. Includes today's date. */
  public static String defaultPrompt() {
    return SystemPromptBuilder.create().build();
  }

  /**
   * Return a system prompt with engine-specific guidelines appended. Includes today's date.
   *
   * @param engineType engine type string (e.g. "spark", "trino"), case-insensitive
   * @return the composed prompt; falls back to base if engine is null or unrecognized
   */
  public static String forEngine(String engineType) {
    return SystemPromptBuilder.create().engine(engineType).build();
  }

  /**
   * Auto-detect dialect and engine from a JDBC URL and return the appropriate prompt.
   *
   * @param jdbcUrl the JDBC connection URL (e.g. "jdbc:sqlite:...", "jdbc:hive2:...",
   *     "jdbc:trino:...")
   * @return the composed prompt; falls back to base if the URL is null or unrecognized
   */
  public static String forJdbcUrl(String jdbcUrl) {
    return SystemPromptBuilder.create().jdbcUrl(jdbcUrl).build();
  }
}
