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

package org.apache.kyuubi.engine.dataagent.benchmark.eval;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Port of data-insight's BIRD evaluator. Computes two metrics that are independent of the agent
 * implementation:
 *
 * <ul>
 *   <li><b>EX</b> (Execution Accuracy) — predicted result set equals gold result set as an
 *       unordered bag-of-rows, with case-insensitive string comparison and float rounding.
 *   <li><b>Soft-F1</b> — per-row column-level matching from BIRD's official {@code
 *       evaluation_f1.py}; gives partial credit for near-misses.
 * </ul>
 *
 * <p>Execution uses a short-lived JDBC connection per SQL. Result sets are materialized into {@link
 * ResultRows} and capped at {@link #MAX_ROWS} to bound memory; larger gold sets are not expected in
 * BIRD dev split.
 */
public final class SqlExecutionEvaluator {

  public static final int MAX_ROWS = 5000;
  public static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

  private SqlExecutionEvaluator() {}

  /** Result of executing a single SQL: either rows or an error message. */
  public static final class ExecResult {
    public final boolean success;
    public final ResultRows rows;
    public final String error;

    private ExecResult(boolean success, ResultRows rows, String error) {
      this.success = success;
      this.rows = rows;
      this.error = error;
    }

    public static ExecResult ok(ResultRows rows) {
      return new ExecResult(true, rows, null);
    }

    public static ExecResult fail(String error) {
      return new ExecResult(false, null, error);
    }
  }

  /** Outcome of evaluating a single (pred, gold) pair. */
  public static final class EvalOutcome {
    public final boolean ex;
    public final double softF1;
    public final String message;

    public EvalOutcome(boolean ex, double softF1, String message) {
      this.ex = ex;
      this.softF1 = softF1;
      this.message = message;
    }
  }

  /**
   * Execute a single SQL against a fresh JDBC connection. Returns at most {@link #MAX_ROWS} rows.
   */
  public static ExecResult execute(String jdbcUrl, String sql, int timeoutSeconds) {
    if (sql == null || sql.trim().isEmpty()) {
      return ExecResult.fail("empty sql");
    }
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement()) {
      stmt.setQueryTimeout(timeoutSeconds);
      stmt.setMaxRows(MAX_ROWS);
      try (ResultSet rs = stmt.executeQuery(sql)) {
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        List<List<Object>> data = new ArrayList<>();
        while (rs.next() && data.size() < MAX_ROWS) {
          List<Object> row = new ArrayList<>(cols);
          for (int i = 1; i <= cols; i++) {
            row.add(rs.getObject(i));
          }
          data.add(row);
        }
        return ExecResult.ok(new ResultRows(data));
      }
    } catch (Exception e) {
      Throwable root = e;
      while (root.getCause() != null) root = root.getCause();
      String msg = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
      int nl = msg.indexOf('\n');
      if (nl > 0) msg = msg.substring(0, nl);
      return ExecResult.fail(msg);
    }
  }

  /** Run both predicted and gold SQL, compute EX + Soft-F1. */
  public static EvalOutcome evaluate(String jdbcUrl, String predSql, String goldSql) {
    return evaluate(jdbcUrl, predSql, goldSql, DEFAULT_QUERY_TIMEOUT_SECONDS);
  }

  public static EvalOutcome evaluate(
      String jdbcUrl, String predSql, String goldSql, int timeoutSeconds) {
    if (predSql == null || predSql.trim().isEmpty()) {
      return new EvalOutcome(false, 0.0, "no predicted sql");
    }
    ExecResult pred = execute(jdbcUrl, predSql, timeoutSeconds);
    if (!pred.success) {
      return new EvalOutcome(false, 0.0, "pred error: " + pred.error);
    }
    ExecResult gold = execute(jdbcUrl, goldSql, timeoutSeconds);
    if (!gold.success) {
      return new EvalOutcome(false, 0.0, "gold error: " + gold.error);
    }
    boolean ex = computeEx(pred.rows, gold.rows);
    double f1 = computeSoftF1(pred.rows, gold.rows);
    return new EvalOutcome(ex, f1, ex ? "match" : "mismatch");
  }

  /** EX: bag-of-rows equality after normalization. */
  public static boolean computeEx(ResultRows pred, ResultRows gold) {
    Set<List<Object>> p = new HashSet<>();
    for (List<Object> r : pred.rows()) p.add(normalizeRow(r));
    Set<List<Object>> g = new HashSet<>();
    for (List<Object> r : gold.rows()) g.add(normalizeRow(r));
    return p.equals(g);
  }

  /** Soft-F1: per-row column-level matching from BIRD's evaluation_f1.py. */
  public static double computeSoftF1(ResultRows pred, ResultRows gold) {
    List<List<Object>> predList = dedup(pred.rows());
    List<List<Object>> goldList = dedup(gold.rows());
    if (predList.isEmpty() && goldList.isEmpty()) return 1.0;

    double tp = 0.0;
    double fp = 0.0;
    double fn = 0.0;

    for (int i = 0; i < goldList.size(); i++) {
      if (i >= predList.size()) {
        fn += 1.0;
        continue;
      }
      double[] m = rowMatch(predList.get(i), goldList.get(i));
      tp += m[0];
      fp += m[1];
      fn += m[2];
    }
    for (int i = goldList.size(); i < predList.size(); i++) {
      fp += 1.0;
    }

    double precision = (tp + fp) > 0 ? tp / (tp + fp) : 0.0;
    double recall = (tp + fn) > 0 ? tp / (tp + fn) : 0.0;
    if (precision + recall == 0.0) return 0.0;
    return 2 * precision * recall / (precision + recall);
  }

  /** Returns [match_frac, pred_only_frac, truth_only_frac]. */
  private static double[] rowMatch(List<Object> predRow, List<Object> gtRow) {
    int total = gtRow.size();
    if (total == 0) {
      return new double[] {predRow.isEmpty() ? 1.0 : 0.0, predRow.isEmpty() ? 0.0 : 1.0, 0.0};
    }
    Set<Object> predNorm = normalizeValues(predRow);
    Set<Object> gtNorm = normalizeValues(gtRow);
    int matches = 0;
    int predOnly = 0;
    for (Object v : predNorm) {
      if (gtNorm.contains(v)) matches++;
      else predOnly++;
    }
    int truthOnly = 0;
    for (Object v : gtNorm) {
      if (!predNorm.contains(v)) truthOnly++;
    }
    return new double[] {
      (double) matches / total, (double) predOnly / total, (double) truthOnly / total
    };
  }

  private static List<List<Object>> dedup(List<List<Object>> rows) {
    LinkedHashSet<List<Object>> seen = new LinkedHashSet<>();
    for (List<Object> r : rows) {
      seen.add(normalizeRow(r));
    }
    return new ArrayList<>(seen);
  }

  private static List<Object> normalizeRow(List<Object> row) {
    List<Object> out = new ArrayList<>(row.size());
    for (Object v : row) out.add(normalizeValue(v));
    return out;
  }

  private static Set<Object> normalizeValues(List<Object> row) {
    Set<Object> s = new HashSet<>();
    for (Object v : row) s.add(normalizeValue(v));
    return s;
  }

  private static Object normalizeValue(Object v) {
    if (v == null) return null;
    if (v instanceof String) return ((String) v).trim().toLowerCase();
    if (v instanceof Float) return Math.round(((Float) v).doubleValue() * 1_000_000d) / 1_000_000d;
    if (v instanceof Double) return Math.round(((Double) v) * 1_000_000d) / 1_000_000d;
    if (v instanceof byte[]) return Arrays.toString((byte[]) v);
    if (v instanceof Number) return ((Number) v).doubleValue();
    return v.toString();
  }
}
