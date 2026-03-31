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

package org.apache.kyuubi.engine.dataagent.agent;

import java.util.Map;

/**
 * Events emitted by the ReAct agent loop. Each event represents a discrete step in the agent's
 * reasoning and execution process, enabling real-time token-level streaming to clients.
 */
public abstract class AgentEvent {

  private AgentEvent() {}

  /** A single token or chunk from the LLM streaming response. */
  public static final class ContentDelta extends AgentEvent {
    private final String text;

    public ContentDelta(String text) {
      this.text = text;
    }

    public String text() {
      return text;
    }
  }

  /** The complete LLM output for one reasoning step. */
  public static final class ContentComplete extends AgentEvent {
    private final String fullText;

    public ContentComplete(String fullText) {
      this.fullText = fullText;
    }

    public String fullText() {
      return fullText;
    }
  }

  /** The agent is about to invoke a tool. */
  public static final class ToolCall extends AgentEvent {
    private final String toolName;
    private final Map<String, Object> toolArgs;

    public ToolCall(String toolName, Map<String, Object> toolArgs) {
      this.toolName = toolName;
      this.toolArgs = toolArgs;
    }

    public String toolName() {
      return toolName;
    }

    public Map<String, Object> toolArgs() {
      return toolArgs;
    }
  }

  /** The result of a tool invocation. */
  public static final class ToolResult extends AgentEvent {
    private final String toolName;
    private final String output;
    private final boolean isError;

    public ToolResult(String toolName, String output, boolean isError) {
      this.toolName = toolName;
      this.output = output;
      this.isError = isError;
    }

    public String toolName() {
      return toolName;
    }

    public String output() {
      return output;
    }

    public boolean isError() {
      return isError;
    }
  }

  /** A new ReAct iteration is starting. */
  public static final class StepStart extends AgentEvent {
    private final int stepNumber;

    public StepStart(int stepNumber) {
      this.stepNumber = stepNumber;
    }

    public int stepNumber() {
      return stepNumber;
    }
  }

  /** An error occurred during agent execution. */
  public static final class AgentError extends AgentEvent {
    private final String message;

    public AgentError(String message) {
      this.message = message;
    }

    public String message() {
      return message;
    }
  }

  /** The agent has finished its analysis. */
  public static final class AgentFinish extends AgentEvent {
    private final int totalSteps;
    private final long promptTokens;
    private final long completionTokens;
    private final long totalTokens;

    public AgentFinish(int totalSteps, long promptTokens, long completionTokens, long totalTokens) {
      this.totalSteps = totalSteps;
      this.promptTokens = promptTokens;
      this.completionTokens = completionTokens;
      this.totalTokens = totalTokens;
    }

    public int totalSteps() {
      return totalSteps;
    }

    public long promptTokens() {
      return promptTokens;
    }

    public long completionTokens() {
      return completionTokens;
    }

    public long totalTokens() {
      return totalTokens;
    }
  }
}
