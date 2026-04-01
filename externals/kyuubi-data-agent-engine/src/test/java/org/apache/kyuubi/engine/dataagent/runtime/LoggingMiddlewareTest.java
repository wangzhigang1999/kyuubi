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

import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kyuubi.engine.dataagent.runtime.event.*;
import org.apache.kyuubi.engine.dataagent.runtime.middleware.LoggingMiddleware;
import org.junit.Before;
import org.junit.Test;

public class LoggingMiddlewareTest {

  private LoggingMiddleware middleware;
  private AgentContext ctx;

  @Before
  public void setUp() {
    middleware = new LoggingMiddleware();
    ctx =
        new AgentContext("What is the total revenue?", new ConversationMemory(), ApprovalMode.YOLO);
    ctx.setIteration(1);
  }

  @Test
  public void testOnAgentStartDoesNotThrow() {
    middleware.onAgentStart(ctx);
  }

  @Test
  public void testOnAgentFinishDoesNotThrow() {
    ctx.addTokenUsage(100, 50, 150);
    middleware.onAgentFinish(ctx);
  }

  @Test
  public void testBeforeLlmCallReturnsNull() {
    List<ChatCompletionMessageParam> messages = new ArrayList<>();
    assertNull(middleware.beforeLlmCall(ctx, messages));
  }

  @Test
  public void testAfterLlmCallWithContent() {
    ChatCompletionAssistantMessageParam response =
        ChatCompletionAssistantMessageParam.builder()
            .content("The total revenue is $1,000,000.")
            .build();
    middleware.afterLlmCall(ctx, response);
  }

  @Test
  public void testAfterLlmCallWithEmptyContent() {
    ChatCompletionAssistantMessageParam response =
        ChatCompletionAssistantMessageParam.builder().build();
    middleware.afterLlmCall(ctx, response);
  }

  @Test
  public void testBeforeToolCallReturnsNull() {
    Map<String, Object> args = new HashMap<>();
    args.put("sql", "SELECT COUNT(*) FROM orders");
    assertNull(middleware.beforeToolCall(ctx, "sql_query", args));
  }

  @Test
  public void testAfterToolCallReturnsNull() {
    Map<String, Object> args = Collections.singletonMap("sql", "SELECT 1");
    assertNull(middleware.afterToolCall(ctx, "sql_query", args, "count\n---\n42"));
  }

  @Test
  public void testOnEventPassesThroughAllEventTypes() {
    AgentEvent step = new StepStart(1);
    assertSame(step, middleware.onEvent(ctx, step));

    AgentEvent delta = new ContentDelta("hello");
    assertSame(delta, middleware.onEvent(ctx, delta));

    AgentEvent complete = new ContentComplete("full text");
    assertSame(complete, middleware.onEvent(ctx, complete));

    AgentEvent toolCall = new ToolCall("sql_query", Collections.emptyMap());
    assertSame(toolCall, middleware.onEvent(ctx, toolCall));

    AgentEvent toolResult = new ToolResult("sql_query", "result", false);
    assertSame(toolResult, middleware.onEvent(ctx, toolResult));

    AgentEvent toolError = new ToolResult("sql_query", "error msg", true);
    assertSame(toolError, middleware.onEvent(ctx, toolError));

    AgentEvent error = new AgentError("something went wrong");
    assertSame(error, middleware.onEvent(ctx, error));

    AgentEvent finish = new AgentFinish(3, 100, 50, 150);
    assertSame(finish, middleware.onEvent(ctx, finish));
  }

  @Test
  public void testLongInputIsTruncated() {
    String longInput = String.join("", Collections.nCopies(500, "x"));
    AgentContext longCtx = new AgentContext(longInput, new ConversationMemory(), ApprovalMode.YOLO);
    // Should not throw; truncation is internal
    middleware.onAgentStart(longCtx);
  }

  @Test
  public void testLongToolResultIsTruncated() {
    String longResult = String.join("", Collections.nCopies(500, "row\n"));
    Map<String, Object> args = Collections.singletonMap("sql", "SELECT *");
    // Should not throw
    assertNull(middleware.afterToolCall(ctx, "sql_query", args, longResult));
  }
}
