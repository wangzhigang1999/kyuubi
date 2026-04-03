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

package org.apache.kyuubi.engine.dataagent.runtime;

import static org.junit.Assert.*;

import org.junit.Test;

public class AgentRunRequestTest {

  @Test
  public void testBasicConstruction() {
    AgentRunRequest request = new AgentRunRequest("What tables exist?");
    assertEquals("What tables exist?", request.getUserInput());
    assertNull(request.getModelName());
    assertEquals(ApprovalMode.NORMAL, request.getApprovalMode());
  }

  @Test(expected = NullPointerException.class)
  public void testNullUserInputThrows() {
    new AgentRunRequest(null);
  }

  @Test
  public void testBuilderChaining() {
    AgentRunRequest request =
        new AgentRunRequest("query").modelName("gpt-4").approvalMode(ApprovalMode.STRICT);
    assertEquals("query", request.getUserInput());
    assertEquals("gpt-4", request.getModelName());
    assertEquals(ApprovalMode.STRICT, request.getApprovalMode());
  }

  @Test
  public void testAutoApproveMode() {
    AgentRunRequest request = new AgentRunRequest("test").approvalMode(ApprovalMode.AUTO_APPROVE);
    assertEquals(ApprovalMode.AUTO_APPROVE, request.getApprovalMode());
  }
}
