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

package org.apache.kyuubi.engine.dataagent.operation

import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.engine.dataagent.WithDataAgentEngine
import org.apache.kyuubi.operation.HiveJDBCTestHelper

class DataAgentOperationSuite extends HiveJDBCTestHelper with WithDataAgentEngine {

  override def withKyuubiConf: Map[String, String] = Map(
    ENGINE_DATA_AGENT_PROVIDER.key -> "echo")

  override protected def jdbcUrl: String = jdbcConnectionUrl

  test("echo provider returns streaming response via JDBC") {
    withJdbcStatement() { stmt =>
      val result = stmt.executeQuery("What tables are in the database?")
      val sb = new StringBuilder
      while (result.next()) {
        sb.append(result.getString("reply"))
      }
      val reply = sb.toString()
      assert(reply.contains("[DataAgent Echo]"))
      assert(reply.contains("What tables are in the database?"))
    }
  }

  test("multiple queries in same session") {
    withJdbcStatement() { stmt =>
      val result1 = stmt.executeQuery("first question")
      val sb1 = new StringBuilder
      while (result1.next()) {
        sb1.append(result1.getString("reply"))
      }
      assert(sb1.toString().contains("first question"))

      val result2 = stmt.executeQuery("second question")
      val sb2 = new StringBuilder
      while (result2.next()) {
        sb2.append(result2.getString("reply"))
      }
      assert(sb2.toString().contains("second question"))
    }
  }
}
