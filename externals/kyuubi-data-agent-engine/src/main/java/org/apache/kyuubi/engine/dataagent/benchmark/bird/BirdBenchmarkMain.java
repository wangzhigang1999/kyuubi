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

import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import org.apache.kyuubi.engine.dataagent.benchmark.AgentHandleFactory;
import org.apache.kyuubi.engine.dataagent.benchmark.BenchmarkRunner;
import org.apache.kyuubi.engine.dataagent.benchmark.BenchmarkSummary;

/**
 * CLI entry point for the BIRD-SQL benchmark. LLM credentials are taken from the same environment
 * variables the E2E test suite uses: {@code DATA_AGENT_LLM_API_KEY / API_URL / MODEL}.
 *
 * <p>Example:
 *
 * <pre>
 *   export DATA_AGENT_LLM_API_KEY=... DATA_AGENT_LLM_API_URL=... DATA_AGENT_LLM_MODEL=...
 *   mvn -pl externals/kyuubi-data-agent-engine exec:java \
 *       -Dexec.mainClass=org.apache.kyuubi.engine.dataagent.benchmark.bird.BirdBenchmarkMain \
 *       -Dexec.args="--dataset-json .../mini_dev_sqlite.json --db-dir .../dev_databases \
 *                    --limit 50 --concurrency 8"
 * </pre>
 */
public final class BirdBenchmarkMain {

  private BirdBenchmarkMain() {}

  public static void main(String[] args) throws Exception {
    Map<String, String> opts = parseArgs(args);
    String datasetJson = required(opts, "dataset-json");
    String dbDir = required(opts, "db-dir");
    String outputDir = opts.getOrDefault(
        "output-dir",
        "benchmark-out/"
            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
    int concurrency = Integer.parseInt(opts.getOrDefault("concurrency", "8"));
    int limit = Integer.parseInt(opts.getOrDefault("limit", "0"));
    String difficulty = opts.getOrDefault("difficulty", "");
    String dbFilter = opts.getOrDefault("db", "");
    boolean resume = !"false".equalsIgnoreCase(opts.getOrDefault("resume", "true"));

    String apiKey = envRequired("DATA_AGENT_LLM_API_KEY");
    String apiUrl = envRequired("DATA_AGENT_LLM_API_URL");
    String modelName = envRequired("DATA_AGENT_LLM_MODEL");

    BirdDataset dataset = BirdDataset.load(Paths.get(datasetJson), Paths.get(dbDir),
        "all".equalsIgnoreCase(difficulty) ? "" : difficulty, dbFilter, limit);
    System.out.println("Loaded " + dataset.examples().size() + " BIRD examples");

    AgentHandleFactory.Config fcfg = new AgentHandleFactory.Config();
    fcfg.apiKey = apiKey;
    fcfg.baseUrl = apiUrl;
    fcfg.modelName = modelName;
    fcfg.maxIterations = Integer.parseInt(opts.getOrDefault("max-iterations", "30"));

    BenchmarkRunner.Config rcfg = new BenchmarkRunner.Config();
    rcfg.outputDir = Paths.get(outputDir);
    rcfg.concurrency = concurrency;
    rcfg.resume = resume;

    BenchmarkSummary summary;
    try (AgentHandleFactory factory = new AgentHandleFactory(fcfg)) {
      summary = new BenchmarkRunner(rcfg).run(dataset, factory);
    }
    System.out.println(summary.toPrettyString());
    System.out.println("Output written to " + outputDir);
  }

  private static Map<String, String> parseArgs(String[] args) {
    Map<String, String> out = new HashMap<>();
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      if (!a.startsWith("--")) {
        throw new IllegalArgumentException("Unexpected positional arg: " + a);
      }
      String key = a.substring(2);
      if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
        out.put(key, args[++i]);
      } else {
        out.put(key, "true");
      }
    }
    return out;
  }

  private static String required(Map<String, String> opts, String key) {
    String v = opts.get(key);
    if (v == null || v.isEmpty()) throw new IllegalArgumentException("--" + key + " is required");
    return v;
  }

  private static String envRequired(String key) {
    String v = System.getenv(key);
    if (v == null || v.isEmpty()) throw new IllegalStateException(key + " env var is required");
    return v;
  }
}
