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

package org.apache.kyuubi.engine.dataagent.benchmark.tool;

import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.kyuubi.engine.dataagent.tool.AgentTool;
import org.apache.kyuubi.engine.dataagent.tool.ToolContext;
import org.apache.kyuubi.engine.dataagent.tool.sql.RunSelectQueryTool;
import org.apache.kyuubi.engine.dataagent.tool.sql.SqlQueryArgs;

/**
 * Benchmark-only tool: lets the agent explicitly commit a final SQL answer instead of relying on
 * the "last successful run_select_query" heuristic used by v1/v2 of this harness.
 *
 * <p>The heuristic has two failure modes we saw in v2's regression analysis: (1) agent ran an
 * exploratory query (e.g. {@code SELECT DISTINCT category LIMIT 20}, a PRAGMA) as its final tool
 * call and that got scored; (2) agent ran the correct final SQL mid-trace, then ran another
 * exploratory query after, and the last-successful heuristic picked the wrong one.
 *
 * <p>This tool delegates the actual JDBC execution to {@link RunSelectQueryTool} so the agent still
 * sees the query's result set (useful for catching typos), then stashes the submitted SQL. The
 * benchmark runner prefers the stashed SQL over the last-successful heuristic and only falls back
 * to the heuristic if the agent never called this tool.
 *
 * <p>Main-scope package instead of plain benchmark-scope because the tool reuses {@link
 * RunSelectQueryTool} and shares its {@link DataSource} per question.
 */
public class SubmitSqlTool implements AgentTool<SqlQueryArgs> {

  private final RunSelectQueryTool runner;
  private final AtomicReference<String> submitted = new AtomicReference<>();

  public SubmitSqlTool(DataSource dataSource, int queryTimeoutSeconds) {
    this.runner = new RunSelectQueryTool(dataSource, queryTimeoutSeconds);
  }

  @Override
  public String name() {
    return "submit_sql";
  }

  @Override
  public String description() {
    return "Submit your FINAL SQL answer for scoring. Only the SQL submitted through this tool is"
        + " scored — queries run via run_select_query are treated as exploration/validation and"
        + " are NOT scored. Call this exactly once, at the very end, after you have validated the"
        + " candidate query with run_select_query. The submitted SQL is also executed and its"
        + " result is returned so you can confirm the answer shape before stopping.";
  }

  @Override
  public Class<SqlQueryArgs> argsType() {
    return SqlQueryArgs.class;
  }

  @Override
  public String execute(SqlQueryArgs args, ToolContext ctx) {
    if (args.sql == null || args.sql.trim().isEmpty()) {
      return "Error: 'sql' parameter is required.";
    }
    String result = runner.execute(args, ctx);
    // Stash whether execution succeeded or not — if it failed the agent will re-submit with a
    // fix, and the last call wins. This also means a later accidental submission cannot be
    // "undone", but that is consistent with the tool's contract of "exactly once".
    submitted.set(args.sql);
    return "Submitted as final answer. Execution result:\n" + result;
  }

  /** Returns the last submitted SQL, or {@code null} if the agent never called this tool. */
  public String submittedSql() {
    return submitted.get();
  }
}
