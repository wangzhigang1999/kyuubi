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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public final class TestExtensionStatusTool
    implements KyuubiMcpToolProvider.Tool<
        TestExtensionStatusTool.Args, TestExtensionStatusTool.Response> {

  @Override
  public String name() {
    return "test_extension_status";
  }

  @Override
  public String description() {
    return "Return caller data from a ServiceLoader-discovered test tool.";
  }

  @Override
  public Class<Args> argumentsType() {
    return Args.class;
  }

  @Override
  public Class<Response> responseType() {
    return Response.class;
  }

  @Override
  public KyuubiMcpToolProvider.Result<Response> call(
      KyuubiMcpToolProvider.Caller caller, Args arguments) {
    return KyuubiMcpToolProvider.Result.success(
        new Response("service_loader", caller.realUser(), caller.administrator()));
  }

  public record Args() {}

  public record Response(
      @JsonProperty(required = true) @JsonPropertyDescription("Test provider marker.")
          String provider,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Authenticated caller supplied by Kyuubi.")
          String realUser,
      @JsonProperty(required = true)
          @JsonPropertyDescription("Whether Kyuubi authenticated the caller as an administrator.")
          boolean administrator) {}
}
