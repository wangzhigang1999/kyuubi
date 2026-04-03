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

package org.apache.kyuubi.engine.dataagent.provider;

import static org.junit.Assert.*;

import org.junit.Test;

public class ProviderRunRequestTest {

  @Test
  public void testBasicConstruction() {
    ProviderRunRequest request = new ProviderRunRequest("What tables exist?");
    assertEquals("What tables exist?", request.getQuestion());
    assertNull(request.getModelName());
    assertNull(request.getApprovalMode());
  }

  @Test
  public void testBuilderChaining() {
    ProviderRunRequest request =
        new ProviderRunRequest("query").modelName("gpt-4").approvalMode("AUTO_APPROVE");
    assertEquals("query", request.getQuestion());
    assertEquals("gpt-4", request.getModelName());
    assertEquals("AUTO_APPROVE", request.getApprovalMode());
  }

  @Test
  public void testModelNameCanBeOverridden() {
    ProviderRunRequest request = new ProviderRunRequest("test");
    request.modelName("model-a");
    assertEquals("model-a", request.getModelName());
    request.modelName("model-b");
    assertEquals("model-b", request.getModelName());
  }
}
