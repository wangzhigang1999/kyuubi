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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kyuubi.engine.dataagent.benchmark.eval.SqlExecutionEvaluator;
import org.apache.kyuubi.engine.dataagent.runtime.AgentInvocation;
import org.apache.kyuubi.engine.dataagent.runtime.ApprovalMode;
import org.apache.kyuubi.engine.dataagent.runtime.ConversationMemory;
import org.apache.kyuubi.engine.dataagent.runtime.event.AgentEvent;
import org.apache.kyuubi.engine.dataagent.runtime.event.AgentFinish;
import org.apache.kyuubi.engine.dataagent.runtime.event.EventType;
import org.apache.kyuubi.engine.dataagent.runtime.event.ToolCall;
import org.apache.kyuubi.engine.dataagent.runtime.event.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dataset-agnostic driver: for each {@link BenchmarkExample}, build an isolated {@link AgentHandle}
 * via the factory, run the agent, extract the predicted SQL from the event stream, and evaluate
 * against the gold SQL. Writes per-example lines to {@code results.jsonl}, persists a {@code
 * checkpoint.json} for resume, and emits a {@code summary.tsv} at the end.
 *
 * <p>Prediction extraction heuristic: the agent's "answer" is the last successful {@code
 * run_select_query} tool call observed before {@code AgentFinish}. This is a V1 shortcut; we expect
 * to introduce a dedicated {@code submit_sql} tool in a follow-up PR and switch the extractor to
 * read from it instead.
 */
public final class BenchmarkRunner {

  private static final Logger LOG = LoggerFactory.getLogger(BenchmarkRunner.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  public static final class Config {
    public Path outputDir;
    public int concurrency = 8;
    public boolean resume = true;
    public int checkpointEveryN = 20;
  }

  private final Config cfg;

  public BenchmarkRunner(Config cfg) {
    this.cfg = cfg;
  }

  public BenchmarkSummary run(BenchmarkDataset dataset, AgentHandleFactory factory)
      throws IOException {
    Files.createDirectories(cfg.outputDir);
    Path jsonlPath = cfg.outputDir.resolve("results.jsonl");
    Path checkpointPath = cfg.outputDir.resolve("checkpoint.json");
    Path summaryPath = cfg.outputDir.resolve("summary.tsv");
    Path logsDir = cfg.outputDir.resolve("logs");
    Files.createDirectories(logsDir);

    Set<String> completed = cfg.resume ? loadCompleted(checkpointPath) : new java.util.HashSet<>();
    List<BenchmarkResult> priorResults =
        cfg.resume ? loadPriorResults(jsonlPath, completed) : new ArrayList<>();

    List<BenchmarkExample> pending = new ArrayList<>();
    for (BenchmarkExample ex : dataset.examples()) {
      if (!completed.contains(ex.id())) pending.add(ex);
    }
    LOG.info(
        "Benchmark {}: {} pending, {} already done",
        dataset.name(),
        pending.size(),
        completed.size());

    BenchmarkSummary summary = BenchmarkSummary.from(priorResults);
    if (pending.isEmpty()) {
      writeSummary(summaryPath, summary);
      return summary;
    }

    // Append-mode writer, synchronized on the writer itself for JSONL atomicity.
    try (BufferedWriter jsonl =
        Files.newBufferedWriter(
            jsonlPath,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND)) {

      ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, cfg.concurrency));
      List<Future<BenchmarkResult>> futures = new ArrayList<>(pending.size());
      for (final BenchmarkExample ex : pending) {
        futures.add(pool.submit(() -> runOne(ex, dataset, factory, logsDir)));
      }

      AtomicInteger done = new AtomicInteger(0);
      ConcurrentHashMap<String, Boolean> newCompleted = new ConcurrentHashMap<>();
      for (Future<BenchmarkResult> f : futures) {
        BenchmarkResult result;
        try {
          result = f.get();
        } catch (Exception e) {
          LOG.error("Worker future failed", e);
          continue;
        }
        synchronized (jsonl) {
          jsonl.write(JSON.writeValueAsString(toNode(result)));
          jsonl.newLine();
          jsonl.flush();
        }
        summary.add(result);
        newCompleted.put(result.id, Boolean.TRUE);
        int n = done.incrementAndGet();
        if (n % cfg.checkpointEveryN == 0) {
          persistCheckpoint(checkpointPath, unionCompleted(completed, newCompleted), summary);
          LOG.info(
              "Progress {}/{} ex={} f1={}",
              n,
              pending.size(),
              String.format("%.2f%%", summary.overall().exAcc()),
              String.format("%.2f%%", summary.overall().avgF1()));
        }
      }

      pool.shutdown();
      try {
        pool.awaitTermination(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      persistCheckpoint(checkpointPath, unionCompleted(completed, newCompleted), summary);
    }

    writeSummary(summaryPath, summary);
    return summary;
  }

  private BenchmarkResult runOne(
      BenchmarkExample ex, BenchmarkDataset dataset, AgentHandleFactory factory, Path logsDir) {
    String jdbcUrl = dataset.resolveDbJdbcUrl(ex.dbId());
    long start = System.currentTimeMillis();
    EventCollector collector = new EventCollector();

    TraceWriter trace = null;
    try {
      trace = TraceWriter.open(logsDir, ex.id());
      trace.header(ex, jdbcUrl);
    } catch (IOException e) {
      LOG.warn("Failed to open trace log for {}: {}", ex.id(), e.toString());
    }

    // Fan-out consumer: trace writer + internal collector. Trace may be null if file open failed,
    // in which case we still run — tracing is diagnostic, not required for scoring.
    final TraceWriter traceRef = trace;
    java.util.function.Consumer<AgentEvent> consumer =
        event -> {
          if (traceRef != null) traceRef.accept(event);
          collector.accept(event);
        };

    BenchmarkResult result;
    String submittedSql = null;
    try (AgentHandle handle = factory.buildFor(jdbcUrl)) {
      ConversationMemory memory = new ConversationMemory();
      String prompt = org.apache.kyuubi.engine.dataagent.benchmark.bird.BirdPromptBuilder.build(ex);
      // sessionId is required by ToolResultOffloadMiddleware -- it skips the afterToolCall hook
      // when ctx.getSessionId() is null, leaving large tool outputs inline and eventually
      // blowing past the LLM input-length limit. Use the question id so each BIRD question gets
      // its own offload bucket.
      AgentInvocation req =
          new AgentInvocation(prompt)
              .approvalMode(ApprovalMode.AUTO_APPROVE)
              .sessionId(ex.id());
      handle.agent().run(req, memory, consumer);
      submittedSql = handle.submittedSql();
    } catch (Exception e) {
      LOG.warn("Agent run failed for {}: {}", ex.id(), e.toString());
      long elapsed = System.currentTimeMillis() - start;
      result =
          new BenchmarkResult(
              ex.id(),
              ex.dbId(),
              ex.difficulty(),
              ex.question(),
              ex.goldSql(),
              null,
              false,
              false,
              0.0,
              0,
              0,
              0,
              0,
              elapsed,
              "agent error: " + e.getMessage());
      if (trace != null) {
        trace.footer(result);
        trace.close();
      }
      return result;
    }

    long elapsed = System.currentTimeMillis() - start;
    // Prefer the SQL the agent explicitly committed via submit_sql; fall back to the "last
    // successful run_select_query" heuristic only if the agent never submitted.
    String predSql = submittedSql != null ? submittedSql : collector.lastSuccessfulSql;
    if (predSql == null) {
      result =
          new BenchmarkResult(
              ex.id(),
              ex.dbId(),
              ex.difficulty(),
              ex.question(),
              ex.goldSql(),
              null,
              false,
              false,
              0.0,
              collector.steps,
              collector.promptTokens,
              collector.completionTokens,
              collector.totalTokens,
              elapsed,
              "no sql produced");
    } else {
      SqlExecutionEvaluator.EvalOutcome out =
          SqlExecutionEvaluator.evaluate(jdbcUrl, predSql, ex.goldSql());
      result =
          new BenchmarkResult(
              ex.id(),
              ex.dbId(),
              ex.difficulty(),
              ex.question(),
              ex.goldSql(),
              predSql,
              true,
              out.ex,
              out.softF1,
              collector.steps,
              collector.promptTokens,
              collector.completionTokens,
              collector.totalTokens,
              elapsed,
              out.message);
    }
    if (trace != null) {
      trace.footer(result);
      trace.close();
    }
    return result;
  }

  // ---- event collection ----

  private static final class EventCollector implements java.util.function.Consumer<AgentEvent> {
    private final java.util.Map<String, String> pendingSqlByToolCallId = new java.util.HashMap<>();
    String lastSuccessfulSql;
    int steps;
    long promptTokens;
    long completionTokens;
    long totalTokens;

    @Override
    public void accept(AgentEvent event) {
      EventType t = event.eventType();
      if (t == EventType.TOOL_CALL) {
        ToolCall call = (ToolCall) event;
        if ("run_select_query".equals(call.toolName())) {
          Object sql = call.toolArgs().get("sql");
          if (sql instanceof String) {
            pendingSqlByToolCallId.put(call.toolCallId(), (String) sql);
          }
        }
      } else if (t == EventType.TOOL_RESULT) {
        ToolResult res = (ToolResult) event;
        String sql = pendingSqlByToolCallId.remove(res.toolCallId());
        if (sql != null && !res.isError()) {
          String out = res.output();
          if (out != null && !out.startsWith("Error:")) {
            lastSuccessfulSql = sql;
          }
        }
      } else if (t == EventType.AGENT_FINISH) {
        AgentFinish f = (AgentFinish) event;
        steps = f.totalSteps();
        promptTokens = f.promptTokens();
        completionTokens = f.completionTokens();
        totalTokens = f.totalTokens();
      }
    }
  }

  // ---- persistence helpers ----

  private static ObjectNode toNode(BenchmarkResult r) {
    ObjectNode n = JSON.createObjectNode();
    n.put("id", r.id);
    n.put("db_id", r.dbId);
    n.put("difficulty", r.difficulty);
    n.put("question", r.question);
    n.put("gold_sql", r.goldSql);
    n.put("pred_sql", r.predSql);
    n.put("gen_ok", r.genOk);
    n.put("ex", r.ex);
    n.put("soft_f1", r.softF1);
    n.put("steps", r.steps);
    n.put("prompt_tokens", r.promptTokens);
    n.put("completion_tokens", r.completionTokens);
    n.put("total_tokens", r.totalTokens);
    n.put("elapsed_ms", r.elapsedMs);
    n.put("error", r.error);
    return n;
  }

  private static BenchmarkResult fromNode(ObjectNode n) {
    return new BenchmarkResult(
        n.path("id").asText(),
        n.path("db_id").asText(),
        n.path("difficulty").asText(""),
        n.path("question").asText(""),
        n.path("gold_sql").asText(""),
        n.path("pred_sql").asText(null),
        n.path("gen_ok").asBoolean(false),
        n.path("ex").asBoolean(false),
        n.path("soft_f1").asDouble(0.0),
        n.path("steps").asInt(0),
        n.path("prompt_tokens").asLong(0),
        n.path("completion_tokens").asLong(0),
        n.path("total_tokens").asLong(0),
        n.path("elapsed_ms").asLong(0),
        n.path("error").asText(null));
  }

  private static Set<String> loadCompleted(Path checkpoint) {
    Set<String> ids = new java.util.HashSet<>();
    if (!Files.exists(checkpoint)) return ids;
    try {
      ObjectNode root = (ObjectNode) JSON.readTree(checkpoint.toFile());
      ArrayNode arr = (ArrayNode) root.get("completed_ids");
      if (arr != null) {
        for (int i = 0; i < arr.size(); i++) ids.add(arr.get(i).asText());
      }
    } catch (IOException e) {
      LOG.warn("Failed to read checkpoint {}: {}", checkpoint, e.toString());
    }
    return ids;
  }

  private static List<BenchmarkResult> loadPriorResults(Path jsonl, Set<String> completedFilter) {
    List<BenchmarkResult> out = new ArrayList<>();
    if (!Files.exists(jsonl)) return out;
    try {
      for (String line : Files.readAllLines(jsonl, StandardCharsets.UTF_8)) {
        if (line.isEmpty()) continue;
        ObjectNode n = (ObjectNode) JSON.readTree(line);
        BenchmarkResult r = fromNode(n);
        if (completedFilter.contains(r.id)) out.add(r);
      }
    } catch (IOException e) {
      LOG.warn("Failed to read prior results {}: {}", jsonl, e.toString());
    }
    return out;
  }

  private static void persistCheckpoint(
      Path checkpoint, Set<String> completedIds, BenchmarkSummary summary) {
    try {
      ObjectNode root = JSON.createObjectNode();
      ArrayNode arr = root.putArray("completed_ids");
      List<String> sorted = new ArrayList<>(completedIds);
      Collections.sort(sorted);
      for (String id : sorted) arr.add(id);
      root.put("total", summary.overall().total);
      root.put("ex_acc", summary.overall().exAcc());
      root.put("soft_f1", summary.overall().avgF1());
      Path tmp = Paths.get(checkpoint.toString() + ".tmp");
      Files.write(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
      Files.move(
          tmp, checkpoint, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      LOG.warn("Failed to persist checkpoint {}: {}", checkpoint, e.toString());
    }
  }

  private static Set<String> unionCompleted(
      Set<String> prior, ConcurrentHashMap<String, Boolean> added) {
    Set<String> out = new java.util.HashSet<>(prior);
    out.addAll(added.keySet());
    return out;
  }

  private static void writeSummary(Path summaryPath, BenchmarkSummary summary) throws IOException {
    Files.write(summaryPath, summary.toTsv().getBytes(StandardCharsets.UTF_8));
  }
}
