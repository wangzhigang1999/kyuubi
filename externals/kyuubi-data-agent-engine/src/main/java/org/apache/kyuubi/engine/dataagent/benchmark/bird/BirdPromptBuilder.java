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
 */
public final class BirdPromptBuilder {

  private BirdPromptBuilder() {}

  public static String build(BenchmarkExample ex) {
    StringBuilder sb = new StringBuilder();
    sb.append("You are solving a BIRD-SQL benchmark question against a SQLite database.\n")
        .append("Use run_select_query to explore the schema and validate candidate queries.\n")
        .append("The LAST successful run_select_query you issue will be scored as your final answer,\n")
        .append("so end with that exact query and nothing else.\n\n");
    sb.append("Question: ").append(ex.question()).append('\n');
    if (ex.evidence() != null && !ex.evidence().isEmpty()) {
      sb.append("Hint: ").append(ex.evidence()).append('\n');
    }
    return sb.toString();
  }
}
