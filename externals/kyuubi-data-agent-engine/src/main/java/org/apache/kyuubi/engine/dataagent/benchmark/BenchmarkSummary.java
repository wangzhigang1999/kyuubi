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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregate statistics over a set of {@link BenchmarkResult}s. Emits a compact TSV suitable for
 * pasting into {@code results.tsv} and a human-readable table.
 */
public final class BenchmarkSummary {

  /** Mutable per-group counters. Public fields are fine — this class is a bag of numbers. */
  public static final class Bucket {
    public final String name;
    public int total;
    public int genOk;
    public int exOk;
    public double f1Sum;
    public long promptTokens;
    public long completionTokens;
    public long totalTokens;
    public long elapsedMs;
    public int steps;

    Bucket(String name) {
      this.name = name;
    }

    public double exAcc() {
      return total == 0 ? 0.0 : 100.0 * exOk / total;
    }

    public double genRate() {
      return total == 0 ? 0.0 : 100.0 * genOk / total;
    }

    public double avgF1() {
      return total == 0 ? 0.0 : 100.0 * f1Sum / total;
    }

    public long avgTokens() {
      return total == 0 ? 0 : totalTokens / total;
    }

    public double avgSteps() {
      return total == 0 ? 0 : (double) steps / total;
    }

    public long avgElapsedMs() {
      return total == 0 ? 0 : elapsedMs / total;
    }
  }

  private final Bucket overall = new Bucket("overall");
  private final Map<String, Bucket> byDb = new LinkedHashMap<>();
  private final Map<String, Bucket> byDifficulty = new LinkedHashMap<>();

  public static BenchmarkSummary from(Collection<BenchmarkResult> results) {
    BenchmarkSummary s = new BenchmarkSummary();
    for (BenchmarkResult r : results) s.add(r);
    return s;
  }

  public synchronized void add(BenchmarkResult r) {
    addTo(overall, r);
    addTo(byDb.computeIfAbsent(r.dbId, Bucket::new), r);
    String diff = r.difficulty == null || r.difficulty.isEmpty() ? "unknown" : r.difficulty;
    addTo(byDifficulty.computeIfAbsent(diff, Bucket::new), r);
  }

  private static void addTo(Bucket b, BenchmarkResult r) {
    b.total++;
    if (r.genOk) b.genOk++;
    if (r.ex) b.exOk++;
    b.f1Sum += r.softF1;
    b.promptTokens += r.promptTokens;
    b.completionTokens += r.completionTokens;
    b.totalTokens += r.totalTokens;
    b.elapsedMs += r.elapsedMs;
    b.steps += r.steps;
  }

  public Bucket overall() {
    return overall;
  }

  public Map<String, Bucket> byDb() {
    return byDb;
  }

  public Map<String, Bucket> byDifficulty() {
    return byDifficulty;
  }

  public String toTsv() {
    StringBuilder sb = new StringBuilder();
    sb.append("group\tname\ttotal\tgen_ok\tex_ok\tex_acc\tsoft_f1\tavg_tokens\tavg_steps\tavg_ms\n");
    appendTsvRow(sb, "overall", overall);
    for (Bucket b : byDb.values()) appendTsvRow(sb, "db", b);
    for (Bucket b : byDifficulty.values()) appendTsvRow(sb, "difficulty", b);
    return sb.toString();
  }

  private static void appendTsvRow(StringBuilder sb, String group, Bucket b) {
    sb.append(group).append('\t').append(b.name).append('\t')
        .append(b.total).append('\t')
        .append(b.genOk).append('\t')
        .append(b.exOk).append('\t')
        .append(String.format("%.2f", b.exAcc())).append('\t')
        .append(String.format("%.2f", b.avgF1())).append('\t')
        .append(b.avgTokens()).append('\t')
        .append(String.format("%.1f", b.avgSteps())).append('\t')
        .append(b.avgElapsedMs()).append('\n');
  }

  public String toPrettyString() {
    StringBuilder sb = new StringBuilder();
    sb.append("==== Benchmark Summary ====\n");
    appendPrettyRow(sb, overall);
    if (!byDifficulty.isEmpty()) {
      sb.append("-- by difficulty --\n");
      for (Bucket b : byDifficulty.values()) appendPrettyRow(sb, b);
    }
    if (!byDb.isEmpty() && byDb.size() <= 20) {
      sb.append("-- by database --\n");
      for (Bucket b : byDb.values()) appendPrettyRow(sb, b);
    }
    return sb.toString();
  }

  private static void appendPrettyRow(StringBuilder sb, Bucket b) {
    sb.append(String.format(
            "%-20s total=%d gen=%.1f%% EX=%.2f%% F1=%.2f%% tok=%d steps=%.1f %dms%n",
            b.name,
            b.total,
            b.genRate(),
            b.exAcc(),
            b.avgF1(),
            b.avgTokens(),
            b.avgSteps(),
            b.avgElapsedMs()));
  }
}
