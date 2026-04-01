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

public class AgentMiddlewareTest {

  @Test
  public void testMiddlewareCanSuppressEvents() {
    AgentMiddleware suppressDelta =
        new AgentMiddleware() {
          @Override
          public AgentEvent onEvent(AgentContext ctx, AgentEvent event) {
            if (event instanceof AgentEvent.ContentDelta) {
              return null;
            }
            return event;
          }
        };

    AgentContext ctx = new AgentContext("test", new ConversationMemory(100), ApprovalMode.YOLO);

    AgentEvent delta = new AgentEvent.ContentDelta("hello");
    assertNull("Middleware should suppress ContentDelta", suppressDelta.onEvent(ctx, delta));

    AgentEvent stepStart = new AgentEvent.StepStart(1);
    assertNotNull(
        "Middleware should not suppress StepStart", suppressDelta.onEvent(ctx, stepStart));
  }

  @Test
  public void testToolCallDecision() {
    AgentMiddleware.ToolCallDecision allow =
        new AgentMiddleware.ToolCallDecision(true, null, "allowed");
    assertTrue(allow.allow());
    assertEquals("allowed", allow.reason());

    AgentMiddleware.ToolCallDecision deny =
        new AgentMiddleware.ToolCallDecision(false, null, "denied");
    assertFalse(deny.allow());
    assertEquals("denied", deny.reason());
  }

  @Test
  public void testLlmRequestDecision() {
    AgentMiddleware.LlmRequestDecision skip =
        new AgentMiddleware.LlmRequestDecision(true, "cached");
    assertTrue(skip.skip());
    assertEquals("cached", skip.reason());

    AgentMiddleware.LlmRequestDecision proceed =
        new AgentMiddleware.LlmRequestDecision(false, null);
    assertFalse(proceed.skip());
  }
}
