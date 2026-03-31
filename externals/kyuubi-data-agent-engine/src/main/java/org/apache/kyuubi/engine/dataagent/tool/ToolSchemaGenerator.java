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

package org.apache.kyuubi.engine.dataagent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates JSON Schema from annotated Java classes using Jackson annotations. Used to build the
 * {@code parameters} section of OpenAI function definitions.
 */
public class ToolSchemaGenerator {

  /** Generate a JSON Schema (as a Map) from the given args class using Jackson annotations. */
  public static Map<String, Object> generateSchema(Class<?> argsClass) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");

    Map<String, Object> properties = new LinkedHashMap<>();
    List<String> required = new ArrayList<>();

    for (Field field : argsClass.getFields()) {
      Map<String, Object> prop = new LinkedHashMap<>();
      prop.put("type", toJsonType(field.getType()));

      JsonPropertyDescription desc = field.getAnnotation(JsonPropertyDescription.class);
      if (desc != null) {
        prop.put("description", desc.value());
      }

      JsonProperty jsonProp = field.getAnnotation(JsonProperty.class);
      if (jsonProp != null && jsonProp.required()) {
        required.add(field.getName());
      }

      properties.put(field.getName(), prop);
    }

    schema.put("properties", properties);
    if (!required.isEmpty()) {
      schema.put("required", required);
    }

    return schema;
  }

  private static String toJsonType(Class<?> type) {
    if (type == String.class) {
      return "string";
    } else if (type == int.class
        || type == Integer.class
        || type == long.class
        || type == Long.class) {
      return "integer";
    } else if (type == double.class
        || type == Double.class
        || type == float.class
        || type == Float.class) {
      return "number";
    } else if (type == boolean.class || type == Boolean.class) {
      return "boolean";
    } else {
      return "string";
    }
  }
}
