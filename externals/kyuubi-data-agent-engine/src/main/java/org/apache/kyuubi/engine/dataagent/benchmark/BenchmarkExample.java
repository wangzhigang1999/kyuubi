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

/** A single benchmark example: one natural-language question paired with a gold SQL. */
public final class BenchmarkExample {

  private final String id;
  private final String dbId;
  private final String question;
  private final String goldSql;
  private final String evidence;
  private final String difficulty;

  public BenchmarkExample(
      String id, String dbId, String question, String goldSql, String evidence, String difficulty) {
    this.id = id;
    this.dbId = dbId;
    this.question = question;
    this.goldSql = goldSql;
    this.evidence = evidence == null ? "" : evidence;
    this.difficulty = difficulty == null ? "" : difficulty;
  }

  public String id() {
    return id;
  }

  public String dbId() {
    return dbId;
  }

  public String question() {
    return question;
  }

  public String goldSql() {
    return goldSql;
  }

  public String evidence() {
    return evidence;
  }

  public String difficulty() {
    return difficulty;
  }
}
