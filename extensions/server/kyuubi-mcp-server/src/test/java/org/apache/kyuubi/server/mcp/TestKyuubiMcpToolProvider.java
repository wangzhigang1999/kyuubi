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

package org.apache.kyuubi.server.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class TestKyuubiMcpToolProvider implements KyuubiMcpToolProvider {

  @Override
  public Collection<Tool> tools(Context context) {
    Map<String, Object> inputSchema =
        Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
    Map<String, Object> outputSchema =
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "provider",
                Map.of("type", "string", "description", "Test provider marker."),
                "realUser",
                Map.of("type", "string", "description", "Authenticated caller."),
                "administrator",
                Map.of("type", "boolean", "description", "Administrator status.")),
            "required",
            List.of("provider", "realUser", "administrator"),
            "additionalProperties",
            false);
    McpSchema.Tool definition =
        McpSchema.Tool.builder("test_extension_status")
            .description("Return caller data from a ServiceLoader-discovered test tool.")
            .inputSchema(inputSchema)
            .outputSchema(outputSchema)
            .annotations(
                McpSchema.ToolAnnotations.builder()
                    .readOnlyHint(true)
                    .destructiveHint(false)
                    .idempotentHint(true)
                    .openWorldHint(false)
                    .build())
            .build();
    return List.of(
        new Tool(
            definition,
            (caller, arguments) -> {
              Map<String, Object> value =
                  Map.of(
                      "provider", "service_loader",
                      "realUser", caller.realUser(),
                      "administrator", caller.administrator());
              return McpSchema.CallToolResult.builder()
                  .addTextContent(context.objectMapper().valueToTree(value).toString())
                  .structuredContent(value)
                  .isError(false)
                  .build();
            }));
  }
}
