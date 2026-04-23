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

package org.apache.kyuubi.engine.dataagent.benchmark;

import java.util.List;

/**
 * A set of benchmark examples plus the dataset-specific mapping from {@code dbId} to a JDBC URL.
 * Datasets own the physical layout of their databases — BIRD nests them under {@code
 * {dir}/{dbId}/{dbId}.sqlite}, Spider uses a flat layout, etc.
 */
public interface BenchmarkDataset {

  /** Short dataset name, used in output paths (e.g. "bird", "spider"). */
  String name();

  /** All examples in the dataset, already filtered by any caller-supplied CLI filters. */
  List<BenchmarkExample> examples();

  /**
   * Resolve the JDBC URL the agent (and pred-SQL evaluator) will target. Called once per example.
   */
  String resolveDbJdbcUrl(String dbId);

  /**
   * URL against which gold SQL is executed to produce ground truth. Defaults to the same URL the
   * agent uses, which is correct for single-backend runs; cross-backend setups (e.g. agent on
   * Spark, gold SQL stays on local SQLite) override this.
   */
  default String resolveGoldJdbcUrl(String dbId) {
    return resolveDbJdbcUrl(dbId);
  }
}
