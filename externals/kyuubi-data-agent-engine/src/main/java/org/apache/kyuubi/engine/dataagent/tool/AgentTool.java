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

/**
 * Base interface for tools that the Data Agent can invoke during its ReAct loop. Each tool
 * represents a capability like schema inspection, SQL execution, or glossary lookup.
 *
 * @param <T> the strongly typed arguments class for this tool
 */
public interface AgentTool<T> {

  /** Unique name for this tool, used by the LLM to select it. */
  String name();

  /** Description shown to the LLM to help it decide when to use this tool. */
  String description();

  /** Returns the class of the arguments type for JSON deserialization and schema generation. */
  Class<T> argsType();

  /**
   * Whether this tool is read-only (no side effects). Read-only tools are auto-approved in NORMAL
   * approval mode.
   */
  default boolean isReadonly() {
    return true;
  }

  /**
   * Execute the tool with the given strongly typed arguments.
   *
   * @param args the deserialized arguments from the LLM's tool call
   * @return the result string to feed back to the LLM
   */
  String execute(T args);
}
