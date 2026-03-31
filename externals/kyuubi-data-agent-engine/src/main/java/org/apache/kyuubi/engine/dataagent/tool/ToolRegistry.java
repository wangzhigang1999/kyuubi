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

import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionTool;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kyuubi.engine.dataagent.agent.ApprovalMode;

/**
 * Registry for agent tools. Provides lookup, approval checking, and OpenAI tool spec generation.
 */
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

  /** Returns OpenAI ChatCompletionTool specs for the LLM request. */
  public List<ChatCompletionTool> toChatCompletionTools() {
    List<ChatCompletionTool> result = new ArrayList<>();
    for (AgentTool tool : tools.values()) {
      result.add(toChatCompletionTool(tool));
    }
    return result;
  }

  public boolean isEmpty() {
    return tools.isEmpty();
  }

  public boolean requiresApproval(String toolName, ApprovalMode mode) {
    if (mode == ApprovalMode.YOLO) return false;
    if (mode == ApprovalMode.STRICT) return true;
    AgentTool tool = tools.get(toolName);
    if (tool == null) return true;
    return !tool.isReadonly();
  }

  private static ChatCompletionTool toChatCompletionTool(AgentTool tool) {
    // Build JSON Schema for function parameters using JsonValue
    Map<String, JsonValue> properties = new LinkedHashMap<>();
    List<JsonValue> required = new ArrayList<>();

    if ("describe_schema".equals(tool.name())) {
      Map<String, Object> tableNameSchema = new LinkedHashMap<>();
      tableNameSchema.put("type", "string");
      tableNameSchema.put("description", "Table name to describe. Empty to list all tables.");
      properties.put("table_name", JsonValue.from(tableNameSchema));
    } else if ("sql_query".equals(tool.name())) {
      Map<String, Object> sqlSchema = new LinkedHashMap<>();
      sqlSchema.put("type", "string");
      sqlSchema.put("description", "A valid SQL SELECT query.");
      properties.put("sql", JsonValue.from(sqlSchema));
      required.add(JsonValue.from("sql"));
    }

    FunctionParameters params =
        FunctionParameters.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("properties", JsonValue.from(properties))
            .putAdditionalProperty("required", JsonValue.from(required))
            .build();

    return ChatCompletionTool.ofFunction(
        ChatCompletionFunctionTool.builder()
            .function(
                FunctionDefinition.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .parameters(params)
                    .build())
            .build());
  }
}
