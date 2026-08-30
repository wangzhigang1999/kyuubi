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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import java.math.BigDecimal;
import java.util.Map;

/** Generates MCP input and output schemas from annotated Java classes. */
final class KyuubiMcpSchemaGenerator {

  private final ObjectMapper objectMapper;
  private final SchemaGenerator generator;

  KyuubiMcpSchemaGenerator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    JacksonModule jacksonModule = new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED);
    SchemaGeneratorConfigBuilder builder =
        new SchemaGeneratorConfigBuilder(
                objectMapper, SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON)
            .with(jacksonModule)
            .with(Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES)
            .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT)
            .without(Option.SCHEMA_VERSION_INDICATOR);
    builder
        .forFields()
        .withStringMinLengthResolver(
            field -> positive(field.getAnnotation(McpToolProperty.class), true))
        .withStringMaxLengthResolver(
            field -> positive(field.getAnnotation(McpToolProperty.class), false))
        .withStringPatternResolver(
            field -> stringValue(field.getAnnotation(McpToolProperty.class), Value.PATTERN))
        .withStringFormatResolver(
            field -> stringValue(field.getAnnotation(McpToolProperty.class), Value.FORMAT))
        .withNumberInclusiveMinimumResolver(
            field -> numberValue(field.getAnnotation(McpToolProperty.class), true))
        .withNumberInclusiveMaximumResolver(
            field -> numberValue(field.getAnnotation(McpToolProperty.class), false))
        .withNullableCheck(
            field -> {
              McpToolProperty property = field.getAnnotation(McpToolProperty.class);
              return property != null && property.nullable();
            });
    generator = new SchemaGenerator(builder.build());
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> generate(Class<?> type) {
    JsonNode schema = generator.generateSchema(type);
    return objectMapper.convertValue(schema, Map.class);
  }

  private static Integer positive(McpToolProperty property, boolean minimum) {
    if (property == null) {
      return null;
    }
    int value = minimum ? property.minLength() : property.maxLength();
    return value >= 0 ? value : null;
  }

  private static BigDecimal numberValue(McpToolProperty property, boolean minimum) {
    if (property == null) {
      return null;
    }
    long value = minimum ? property.minimum() : property.maximum();
    if (value == Long.MIN_VALUE || value == Long.MAX_VALUE) {
      return null;
    }
    return BigDecimal.valueOf(value);
  }

  private static String stringValue(McpToolProperty property, Value value) {
    if (property == null) {
      return null;
    }
    String text = value == Value.PATTERN ? property.pattern() : property.format();
    return text.isEmpty() ? null : text;
  }

  private enum Value {
    PATTERN,
    FORMAT
  }
}
