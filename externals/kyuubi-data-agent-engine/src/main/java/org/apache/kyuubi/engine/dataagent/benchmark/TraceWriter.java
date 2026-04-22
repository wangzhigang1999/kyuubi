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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.kyuubi.engine.dataagent.runtime.event.AgentEvent;
import org.apache.kyuubi.engine.dataagent.runtime.event.AgentFinish;
import org.apache.kyuubi.engine.dataagent.runtime.event.ContentComplete;
import org.apache.kyuubi.engine.dataagent.runtime.event.StepStart;
import org.apache.kyuubi.engine.dataagent.runtime.event.ToolCall;
import org.apache.kyuubi.engine.dataagent.runtime.event.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-question trace writer. Mirrors the one-log-per-question layout used by data-insight so a
 * single failing example can be read top-to-bottom without grepping through an interleaved stream.
 *
 * <p>Writes to {@code {outputDir}/logs/{qid}.log}. Not a middleware: subscribes as a plain {@link
 * Consumer} of {@link AgentEvent}, which keeps the engine's middleware chain unchanged and means a
 * run without a trace writer is byte-identical to one with.
 *
 * <p>Tool results are truncated to {@link #RESULT_PREVIEW} chars — enough to see the shape of the
 * returned rows without inflating logs to megabytes on large SELECTs.
 */
public final class TraceWriter implements Consumer<AgentEvent>, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(TraceWriter.class);
  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
  private static final int RESULT_PREVIEW = 2000;

  private final BufferedWriter w;
  private final Path path;

  private TraceWriter(BufferedWriter w, Path path) {
    this.w = w;
    this.path = path;
  }

  public static TraceWriter open(Path logsDir, String qid) throws IOException {
    Files.createDirectories(logsDir);
    Path p = logsDir.resolve(qid + ".log");
    BufferedWriter w =
        Files.newBufferedWriter(
            p,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
    return new TraceWriter(w, p);
  }

  public Path path() {
    return path;
  }

  /** Write a header block with question metadata before the agent starts. */
  public synchronized void header(BenchmarkExample ex, String jdbcUrl) {
    line(
        "=== BIRD question "
            + ex.id()
            + " (db="
            + ex.dbId()
            + ", difficulty="
            + ex.difficulty()
            + ") ===");
    line("jdbc: " + jdbcUrl);
    line("question: " + ex.question());
    if (!ex.evidence().isEmpty()) {
      line("evidence: " + ex.evidence());
    }
    line("gold_sql: " + ex.goldSql());
    line("---");
  }

  /** Write a footer block with the final evaluation outcome. */
  public synchronized void footer(BenchmarkResult r) {
    line("---");
    line("pred_sql: " + (r.predSql == null ? "<none>" : r.predSql));
    line("gen_ok=" + r.genOk + " ex=" + r.ex + " soft_f1=" + String.format("%.3f", r.softF1));
    line(
        "tokens: prompt="
            + r.promptTokens
            + " completion="
            + r.completionTokens
            + " total="
            + r.totalTokens);
    line("steps=" + r.steps + " elapsed_ms=" + r.elapsedMs);
    if (r.error != null) line("eval_message: " + r.error);
  }

  @Override
  public synchronized void accept(AgentEvent event) {
    switch (event.eventType()) {
      case AGENT_START:
        line("AgentStart");
        break;
      case STEP_START:
        line("step " + ((StepStart) event).stepNumber());
        break;
      case CONTENT_COMPLETE:
        String txt = ((ContentComplete) event).fullText();
        if (txt != null && !txt.isEmpty()) line("assistant: " + truncate(txt, 4000));
        break;
      case TOOL_CALL:
        ToolCall tc = (ToolCall) event;
        line("tool_call " + tc.toolName() + " args=" + formatArgs(tc.toolArgs()));
        break;
      case TOOL_RESULT:
        ToolResult tr = (ToolResult) event;
        line(
            "tool_result "
                + tr.toolName()
                + (tr.isError() ? " [ERROR]" : "")
                + ":\n"
                + truncate(tr.output(), RESULT_PREVIEW));
        break;
      case ERROR:
        line("ERROR " + event);
        break;
      case AGENT_FINISH:
        AgentFinish f = (AgentFinish) event;
        line(
            "AgentFinish steps="
                + f.totalSteps()
                + " tokens="
                + f.totalTokens()
                + " (prompt="
                + f.promptTokens()
                + ", completion="
                + f.completionTokens()
                + ")");
        break;
      default:
        // Ignore token-level deltas and step-end — too chatty for a trace log.
        break;
    }
  }

  @Override
  public void close() {
    try {
      w.flush();
      w.close();
    } catch (IOException e) {
      LOG.warn("Failed to close trace log {}: {}", path, e.toString());
    }
  }

  // ---- helpers ----

  private static String formatArgs(Map<String, Object> args) {
    if (args == null || args.isEmpty()) return "{}";
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> e : args.entrySet()) {
      if (!first) sb.append(", ");
      first = false;
      Object v = e.getValue();
      String s = v == null ? "null" : v.toString();
      sb.append(e.getKey()).append('=').append(truncate(s, 1200));
    }
    return sb.append('}').toString();
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max) + "...<truncated>";
  }

  private void line(String s) {
    try {
      w.write(LocalDateTime.now().format(TS));
      w.write(' ');
      w.write(s);
      w.newLine();
      // Flush each line so a process crash still leaves a readable log.
      w.flush();
    } catch (IOException e) {
      LOG.warn("Trace write failed: {}", e.toString());
    }
  }
}
