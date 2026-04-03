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

package org.apache.kyuubi.engine.dataagent.runtime.event;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.kyuubi.engine.dataagent.tool.ToolRiskLevel;
import org.junit.Test;

public class EventTest {

  @Test
  public void testAgentStart() {
    AgentStart event = new AgentStart();
    assertEquals(EventType.AGENT_START, event.eventType());
    assertEquals("AgentStart{}", event.toString());
  }

  @Test
  public void testAgentFinish() {
    AgentFinish event = new AgentFinish(3, 100, 200, 300);
    assertEquals(EventType.AGENT_FINISH, event.eventType());
    assertEquals(3, event.totalSteps());
    assertEquals(100, event.promptTokens());
    assertEquals(200, event.completionTokens());
    assertEquals(300, event.totalTokens());
    assertTrue(event.toString().contains("totalSteps=3"));
  }

  @Test
  public void testAgentError() {
    AgentError event = new AgentError("something failed");
    assertEquals(EventType.ERROR, event.eventType());
    assertEquals("something failed", event.message());
    assertTrue(event.toString().contains("something failed"));
  }

  @Test
  public void testContentDelta() {
    ContentDelta event = new ContentDelta("hello world");
    assertEquals(EventType.CONTENT_DELTA, event.eventType());
    assertEquals("hello world", event.text());
    assertTrue(event.toString().contains("hello world"));
  }

  @Test
  public void testContentDeltaLongTextTruncated() {
    String longText = new String(new char[300]).replace('\0', 'a');
    ContentDelta event = new ContentDelta(longText);
    String str = event.toString();
    assertTrue(str.contains("..."));
    assertTrue(str.length() < longText.length() + 50);
  }

  @Test
  public void testContentDeltaNullText() {
    ContentDelta event = new ContentDelta(null);
    assertNull(event.text());
  }

  @Test
  public void testContentComplete() {
    ContentComplete event = new ContentComplete("full text here");
    assertEquals(EventType.CONTENT_COMPLETE, event.eventType());
    assertEquals("full text here", event.fullText());
    assertTrue(event.toString().contains("length=14"));
  }

  @Test
  public void testContentCompleteNull() {
    ContentComplete event = new ContentComplete(null);
    assertTrue(event.toString().contains("length=0"));
  }

  @Test
  public void testStepStart() {
    StepStart event = new StepStart(5);
    assertEquals(EventType.STEP_START, event.eventType());
    assertEquals(5, event.stepNumber());
    assertTrue(event.toString().contains("stepNumber=5"));
  }

  @Test
  public void testStepEnd() {
    StepEnd event = new StepEnd(3);
    assertEquals(EventType.STEP_END, event.eventType());
    assertEquals(3, event.stepNumber());
    assertTrue(event.toString().contains("stepNumber=3"));
  }

  @Test
  public void testToolCall() {
    Map<String, Object> args = new HashMap<>();
    args.put("sql", "SELECT 1");
    args.put("maxRows", 100);
    ToolCall event = new ToolCall("tc-1", "sql_query", args);
    assertEquals(EventType.TOOL_CALL, event.eventType());
    assertEquals("tc-1", event.toolCallId());
    assertEquals("sql_query", event.toolName());
    assertEquals("SELECT 1", event.toolArgs().get("sql"));
    assertEquals(100, event.toolArgs().get("maxRows"));
  }

  @Test
  public void testToolCallArgsImmutable() {
    Map<String, Object> args = new HashMap<>();
    args.put("key", "value");
    ToolCall event = new ToolCall("tc-1", "tool", args);
    try {
      event.toolArgs().put("new", "entry");
      fail("Should throw on modification");
    } catch (UnsupportedOperationException expected) {
      // expected
    }
  }

  @Test
  public void testToolCallNullArgs() {
    ToolCall event = new ToolCall("tc-1", "tool", null);
    assertNotNull(event.toolArgs());
    assertTrue(event.toolArgs().isEmpty());
  }

  @Test
  public void testToolResult() {
    ToolResult event = new ToolResult("tc-1", "sql_query", "3 rows returned", false);
    assertEquals(EventType.TOOL_RESULT, event.eventType());
    assertEquals("tc-1", event.toolCallId());
    assertEquals("sql_query", event.toolName());
    assertEquals("3 rows returned", event.output());
    assertFalse(event.isError());
  }

  @Test
  public void testToolResultError() {
    ToolResult event = new ToolResult("tc-2", "sql_query", "syntax error", true);
    assertTrue(event.isError());
    assertTrue(event.toString().contains("isError=true"));
  }

  @Test
  public void testToolResultLongOutputTruncated() {
    String longOutput = new String(new char[300]).replace('\0', 'x');
    ToolResult event = new ToolResult("tc-1", "tool", longOutput, false);
    String str = event.toString();
    assertTrue(str.contains("..."));
  }

  @Test
  public void testApprovalRequest() {
    Map<String, Object> args = Collections.singletonMap("sql", "DROP TABLE users");
    ApprovalRequest event =
        new ApprovalRequest("req-1", "tc-1", "sql_query", args, ToolRiskLevel.DESTRUCTIVE);
    assertEquals(EventType.APPROVAL_REQUEST, event.eventType());
    assertEquals("req-1", event.requestId());
    assertEquals("tc-1", event.toolCallId());
    assertEquals("sql_query", event.toolName());
    assertEquals(ToolRiskLevel.DESTRUCTIVE, event.riskLevel());
    assertEquals("DROP TABLE users", event.toolArgs().get("sql"));
  }

  @Test
  public void testApprovalRequestArgsImmutable() {
    Map<String, Object> args = new HashMap<>();
    args.put("key", "value");
    ApprovalRequest event = new ApprovalRequest("req-1", "tc-1", "tool", args, ToolRiskLevel.SAFE);
    try {
      event.toolArgs().put("new", "entry");
      fail("Should throw on modification");
    } catch (UnsupportedOperationException expected) {
      // expected
    }
  }

  @Test
  public void testApprovalRequestNullArgs() {
    ApprovalRequest event = new ApprovalRequest("req-1", "tc-1", "tool", null, ToolRiskLevel.SAFE);
    assertNotNull(event.toolArgs());
    assertTrue(event.toolArgs().isEmpty());
  }

  @Test
  public void testEventTypeSseNames() {
    assertEquals("agent_start", EventType.AGENT_START.sseEventName());
    assertEquals("step_start", EventType.STEP_START.sseEventName());
    assertEquals("content_delta", EventType.CONTENT_DELTA.sseEventName());
    assertEquals("content_complete", EventType.CONTENT_COMPLETE.sseEventName());
    assertEquals("tool_call", EventType.TOOL_CALL.sseEventName());
    assertEquals("tool_result", EventType.TOOL_RESULT.sseEventName());
    assertEquals("step_end", EventType.STEP_END.sseEventName());
    assertEquals("error", EventType.ERROR.sseEventName());
    assertEquals("approval_request", EventType.APPROVAL_REQUEST.sseEventName());
    assertEquals("agent_finish", EventType.AGENT_FINISH.sseEventName());
  }

  @Test
  public void testAllEventTypesHaveUniqueSseNames() {
    EventType[] values = EventType.values();
    java.util.Set<String> names = new java.util.HashSet<>();
    for (EventType type : values) {
      assertTrue("Duplicate SSE name: " + type.sseEventName(), names.add(type.sseEventName()));
    }
    assertEquals(10, values.length);
  }
}
