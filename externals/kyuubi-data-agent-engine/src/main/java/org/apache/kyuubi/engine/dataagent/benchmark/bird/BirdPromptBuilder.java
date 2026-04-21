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

package org.apache.kyuubi.engine.dataagent.benchmark.bird;

import org.apache.kyuubi.engine.dataagent.benchmark.BenchmarkExample;

/**
 * Build the user prompt for a single BIRD example. Intentionally short: the engine's base system
 * prompt already covers dialect and tooling; we only layer on the BIRD task framing plus any
 * question-specific "evidence" the dataset supplies.
 *
 * <p>The rules below target the failure modes observed in the full mini-dev run: forgetting to
 * aggregate, bleeding ORDER BY/WHERE columns into the SELECT list, and under-using the dataset's
 * evidence hints.
 */
public final class BirdPromptBuilder {

  private BirdPromptBuilder() {}

  public static String build(BenchmarkExample ex) {
    StringBuilder sb = new StringBuilder();
    sb.append("You are solving a BIRD-SQL benchmark question against a SQLite database.\n\n");

    sb.append("Workflow\n")
        .append("- Use run_select_query to explore the schema and validate candidate queries.\n")
        .append("- When confident, call submit_sql exactly once with your final answer. ONLY the SQL submitted that way is scored.\n\n");

    sb.append("Rules for the final query\n")
        .append("- SELECT only the columns the question explicitly asks for, in the order the question mentions them.")
        .append(" Do NOT include columns used only in WHERE, ORDER BY, GROUP BY, or HAVING.\n")
        .append("- If the question asks \"how many / how much / total / average / sum / maximum / minimum / count / percentage / ratio\",")
        .append(" your SELECT MUST use an aggregate (COUNT/SUM/AVG/MAX/MIN) — do not return raw rows.\n")
        .append("- Add LIMIT 1 when the question asks for a single item (\"the most\", \"the highest\", \"the top\", \"the oldest\", \"the best\").\n")
        .append("- Use SELECT DISTINCT when the question asks to \"list\" entities and your JOINs can produce duplicates.\n")
        .append("- Prefer exact string matches over LIKE unless the question uses \"contains\" / \"starts with\" / \"ends with\".\n\n");

    if (ex.evidence() != null && !ex.evidence().isEmpty()) {
      sb.append("Domain rule (apply this when writing the final query):\n")
          .append(ex.evidence().trim()).append("\n\n");
    }

    sb.append("Question: ").append(ex.question()).append('\n');
    return sb.toString();
  }
}
