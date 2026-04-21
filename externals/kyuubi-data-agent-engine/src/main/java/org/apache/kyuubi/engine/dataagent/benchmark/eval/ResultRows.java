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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable rectangular result set used by the evaluator. Values are the raw JDBC objects
 * (String/Number/byte[]/null) — normalization happens at compare time.
 */
public final class ResultRows {

  private final List<List<Object>> rows;

  public ResultRows(List<List<Object>> rows) {
    List<List<Object>> copy = new ArrayList<>(rows.size());
    for (List<Object> row : rows) {
      copy.add(Collections.unmodifiableList(new ArrayList<>(row)));
    }
    this.rows = Collections.unmodifiableList(copy);
  }

  public List<List<Object>> rows() {
    return rows;
  }

  public int size() {
    return rows.size();
  }
}
