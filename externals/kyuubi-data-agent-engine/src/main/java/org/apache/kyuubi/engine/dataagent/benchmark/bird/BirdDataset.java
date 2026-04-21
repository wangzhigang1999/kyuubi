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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kyuubi.engine.dataagent.benchmark.BenchmarkDataset;
import org.apache.kyuubi.engine.dataagent.benchmark.BenchmarkExample;

/**
 * BIRD-SQL dev-split dataset loader. Accepts the standard JSON schema used by BIRD's {@code
 * mini_dev_sqlite.json}: an array of objects with {@code question_id, db_id, question, SQL,
 * evidence, difficulty}. Databases are expected to live under {@code {dbDir}/{dbId}/{dbId}.sqlite},
 * falling back to {@code {dbDir}/{dbId}.sqlite} for flat layouts.
 */
public final class BirdDataset implements BenchmarkDataset {

  private final List<BenchmarkExample> examples;
  private final Path dbDir;
  private final ConcurrentHashMap<String, String> urlCache = new ConcurrentHashMap<>();

  private BirdDataset(List<BenchmarkExample> examples, Path dbDir) {
    this.examples = Collections.unmodifiableList(examples);
    this.dbDir = dbDir;
  }

  /**
   * @param datasetJson path to the BIRD dev JSON
   * @param dbDir root of {@code dev_databases/}
   * @param difficulty optional filter ({@code simple|moderate|challenging}); null/empty for all
   * @param dbIdFilter optional db_id filter; null/empty for all
   * @param limit optional cap on the number of examples; 0 or negative for unlimited
   */
  public static BirdDataset load(
      Path datasetJson, Path dbDir, String difficulty, String dbIdFilter, int limit)
      throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode root = mapper.readTree(Files.readAllBytes(datasetJson));
    if (!root.isArray()) {
      throw new IOException("Expected JSON array at root of " + datasetJson);
    }
    List<BenchmarkExample> all = new ArrayList<>();
    for (JsonNode node : root) {
      String diff = node.path("difficulty").asText("");
      String dbId = node.path("db_id").asText("");
      if (difficulty != null && !difficulty.isEmpty() && !difficulty.equalsIgnoreCase(diff)) continue;
      if (dbIdFilter != null && !dbIdFilter.isEmpty() && !dbIdFilter.equals(dbId)) continue;
      String id = node.hasNonNull("question_id")
          ? node.get("question_id").asText()
          : String.valueOf(all.size());
      all.add(new BenchmarkExample(
          id,
          dbId,
          node.path("question").asText(""),
          node.path("SQL").asText(""),
          node.path("evidence").asText(""),
          diff));
      if (limit > 0 && all.size() >= limit) break;
    }
    return new BirdDataset(all, dbDir);
  }

  @Override
  public String name() {
    return "bird";
  }

  @Override
  public List<BenchmarkExample> examples() {
    return examples;
  }

  @Override
  public String resolveDbJdbcUrl(String dbId) {
    return urlCache.computeIfAbsent(dbId, this::resolveInternal);
  }

  private String resolveInternal(String dbId) {
    Path nested = dbDir.resolve(dbId).resolve(dbId + ".sqlite");
    if (Files.exists(nested)) return "jdbc:sqlite:" + nested.toAbsolutePath();
    Path flat = dbDir.resolve(dbId + ".sqlite");
    if (Files.exists(flat)) return "jdbc:sqlite:" + flat.toAbsolutePath();
    throw new IllegalStateException(
        "BIRD database file not found for db_id=" + dbId + " under " + dbDir);
  }
}
