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

package org.apache.kyuubi.engine.dataagent.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kyuubi.engine.dataagent.agent.ApprovalMode;

/** Registry for agent tools. Provides lookup, approval checking, and LLM tool spec generation. */
public class ToolRegistry {

  private final Map<String, AgentTool> tools = new LinkedHashMap<>();

  public ToolRegistry register(AgentTool tool) {
    tools.put(tool.name(), tool);
    return this;
  }

  public AgentTool get(String name) {
    return tools.get(name);
  }

  public List<AgentTool> listTools() {
    return Collections.unmodifiableList(new ArrayList<>(tools.values()));
  }

  /**
   * Determines if a tool requires user approval under the given mode.
   *
   * @return true if approval is required
   */
  public boolean requiresApproval(String toolName, ApprovalMode mode) {
    if (mode == ApprovalMode.YOLO) return false;
    if (mode == ApprovalMode.STRICT) return true;
    // NORMAL mode: readonly tools are auto-approved
    AgentTool tool = tools.get(toolName);
    if (tool == null) return true; // unknown tools require approval
    return !tool.isReadonly();
  }

  /** Format tool descriptions for inclusion in the system prompt. */
  public String formatToolDescriptions() {
    StringBuilder sb = new StringBuilder();
    for (AgentTool tool : tools.values()) {
      sb.append("- **").append(tool.name()).append("**: ").append(tool.description());
      if (tool.isReadonly()) {
        sb.append(" [readonly]");
      }
      sb.append("\n");
    }
    return sb.toString();
  }
}
