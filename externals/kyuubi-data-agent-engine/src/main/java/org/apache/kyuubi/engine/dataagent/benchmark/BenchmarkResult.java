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

/** Per-example benchmark outcome. Serialized as one line in {@code results.jsonl}. */
public final class BenchmarkResult {

  public final String id;
  public final String dbId;
  public final String difficulty;
  public final String question;
  public final String goldSql;
  public final String predSql;
  public final boolean genOk;
  public final boolean ex;
  public final double softF1;
  public final int steps;
  public final long promptTokens;
  public final long completionTokens;
  public final long totalTokens;
  public final long elapsedMs;
  public final String error;

  public BenchmarkResult(
      String id,
      String dbId,
      String difficulty,
      String question,
      String goldSql,
      String predSql,
      boolean genOk,
      boolean ex,
      double softF1,
      int steps,
      long promptTokens,
      long completionTokens,
      long totalTokens,
      long elapsedMs,
      String error) {
    this.id = id;
    this.dbId = dbId;
    this.difficulty = difficulty;
    this.question = question;
    this.goldSql = goldSql;
    this.predSql = predSql;
    this.genOk = genOk;
    this.ex = ex;
    this.softF1 = softF1;
    this.steps = steps;
    this.promptTokens = promptTokens;
    this.completionTokens = completionTokens;
    this.totalTokens = totalTokens;
    this.elapsedMs = elapsedMs;
    this.error = error;
  }
}
