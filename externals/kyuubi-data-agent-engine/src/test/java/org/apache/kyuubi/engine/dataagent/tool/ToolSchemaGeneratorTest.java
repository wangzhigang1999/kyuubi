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

import static org.junit.Assert.*;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kyuubi.engine.dataagent.tool.schema.SchemaInspectArgs;
import org.apache.kyuubi.engine.dataagent.tool.sql.SqlQueryArgs;
import org.junit.Test;

public class ToolSchemaGeneratorTest {

  // --- Real args classes ---

  @Test
  public void testSqlQueryArgsSchema() {
    Map<String, Object> schema = ToolSchemaGenerator.generateSchema(SqlQueryArgs.class);
    assertEquals("object", schema.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) schema.get("properties");
    assertNotNull(props);
    assertTrue(props.containsKey("sql"));
    assertTrue(props.containsKey("maxRows"));

    @SuppressWarnings("unchecked")
    Map<String, Object> sqlProp = (Map<String, Object>) props.get("sql");
    assertEquals("string", sqlProp.get("type"));
    assertNotNull("sql should have a description", sqlProp.get("description"));

    @SuppressWarnings("unchecked")
    Map<String, Object> maxRowsProp = (Map<String, Object>) props.get("maxRows");
    assertEquals("integer", maxRowsProp.get("type"));

    @SuppressWarnings("unchecked")
    List<String> required = (List<String>) schema.get("required");
    assertNotNull(required);
    assertTrue(required.contains("sql"));
    assertFalse("maxRows should not be required", required.contains("maxRows"));
  }

  @Test
  public void testSchemaInspectArgsSchema() {
    Map<String, Object> schema = ToolSchemaGenerator.generateSchema(SchemaInspectArgs.class);
    assertEquals("object", schema.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) schema.get("properties");
    assertNotNull(props);
    assertTrue(props.containsKey("tableName"));

    @SuppressWarnings("unchecked")
    Map<String, Object> tableNameProp = (Map<String, Object>) props.get("tableName");
    assertEquals("string", tableNameProp.get("type"));
    assertNotNull("tableName should have a description", tableNameProp.get("description"));
  }

  // --- Synthetic test classes to verify type mapping ---

  public static class AllTypesArgs {
    @JsonProperty(required = true)
    @JsonPropertyDescription("a string field")
    public String stringField;

    @JsonPropertyDescription("an int field")
    public int intField;

    @JsonPropertyDescription("an Integer field")
    public Integer integerField;

    @JsonPropertyDescription("a long field")
    public long longField;

    @JsonPropertyDescription("a double field")
    public double doubleField;

    @JsonPropertyDescription("a float field")
    public float floatField;

    @JsonPropertyDescription("a boolean field")
    public boolean booleanField;

    @JsonPropertyDescription("a Boolean field")
    public Boolean booleanWrapperField;
  }

  @Test
  public void testAllPrimitiveTypeMappings() {
    Map<String, Object> schema = ToolSchemaGenerator.generateSchema(AllTypesArgs.class);

    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) schema.get("properties");

    assertEquals("string", getType(props, "stringField"));
    assertEquals("integer", getType(props, "intField"));
    assertEquals("integer", getType(props, "integerField"));
    assertEquals("integer", getType(props, "longField"));
    assertEquals("number", getType(props, "doubleField"));
    assertEquals("number", getType(props, "floatField"));
    assertEquals("boolean", getType(props, "booleanField"));
    assertEquals("boolean", getType(props, "booleanWrapperField"));
  }

  @Test
  public void testDescriptionsPreserved() {
    Map<String, Object> schema = ToolSchemaGenerator.generateSchema(AllTypesArgs.class);

    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) schema.get("properties");

    assertEquals("a string field", getDescription(props, "stringField"));
    assertEquals("an int field", getDescription(props, "intField"));
    assertEquals("a boolean field", getDescription(props, "booleanField"));
  }

  @Test
  public void testRequiredFieldsRespected() {
    Map<String, Object> schema = ToolSchemaGenerator.generateSchema(AllTypesArgs.class);

    @SuppressWarnings("unchecked")
    List<String> required = (List<String>) schema.get("required");
    assertNotNull(required);
    assertTrue("stringField should be required", required.contains("stringField"));
    assertFalse("intField should not be required", required.contains("intField"));
  }

  // --- Edge cases ---

  public static class EmptyArgs {}

  @Test
  public void testEmptyArgsClass() {
    Map<String, Object> schema = ToolSchemaGenerator.generateSchema(EmptyArgs.class);
    assertEquals("object", schema.get("type"));
  }

  public static class NoRequiredArgs {
    @JsonPropertyDescription("optional field")
    public String name;
  }

  @Test
  public void testNoRequiredFields() {
    Map<String, Object> schema = ToolSchemaGenerator.generateSchema(NoRequiredArgs.class);
    @SuppressWarnings("unchecked")
    List<String> required = (List<String>) schema.get("required");
    assertTrue(required == null || required.isEmpty());
  }

  // --- Ground truth: verify hand-written schema matches victools for ALL registered tools ---

  @Test
  public void testAllToolArgsMatchVictoolsGroundTruth() {
    List<Class<?>> argsClasses = discoverAllToolArgsClasses();
    assertFalse("Should discover at least one tool args class", argsClasses.isEmpty());
    for (Class<?> argsClass : argsClasses) {
      assertSchemaMatchesGroundTruth(argsClass);
    }
  }

  /**
   * Scans the tool package for all concrete AgentTool implementations and collects their
   * argsType(). This ensures any new tool added to the project is automatically covered.
   */
  private static List<Class<?>> discoverAllToolArgsClasses() {
    List<Class<?>> argsClasses = new ArrayList<>();
    String packageName = AgentTool.class.getPackage().getName();
    String packagePath = packageName.replace('.', '/');
    File baseDir =
        new File(AgentTool.class.getProtectionDomain().getCodeSource().getLocation().getPath());
    scanForToolArgs(new File(baseDir, packagePath), packageName, argsClasses);
    return argsClasses;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void scanForToolArgs(File dir, String packageName, List<Class<?>> result) {
    if (dir == null || !dir.isDirectory()) return;
    File[] files = dir.listFiles();
    if (files == null) return;
    for (File file : files) {
      if (file.isDirectory()) {
        scanForToolArgs(file, packageName + "." + file.getName(), result);
      } else if (file.getName().endsWith(".class")) {
        String className = packageName + "." + file.getName().replace(".class", "");
        try {
          Class<?> clazz = Class.forName(className);
          if (AgentTool.class.isAssignableFrom(clazz)
              && !clazz.isInterface()
              && !java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) {
            // Get argsType via reflection on the class — need an instance
            // Use getDeclaredMethod to call argsType()
            java.lang.reflect.Method argsTypeMethod = clazz.getMethod("argsType");
            // Need a dummy instance; tools require DataSource, use null-arg constructor detection
            // Simpler: just read the generic type parameter from the implements clause
            for (java.lang.reflect.Type iface : clazz.getGenericInterfaces()) {
              if (iface instanceof java.lang.reflect.ParameterizedType) {
                java.lang.reflect.ParameterizedType pt =
                    (java.lang.reflect.ParameterizedType) iface;
                if (pt.getRawType() == AgentTool.class) {
                  Class<?> argsClass = (Class<?>) pt.getActualTypeArguments()[0];
                  if (!result.contains(argsClass)) {
                    result.add(argsClass);
                  }
                }
              }
            }
          }
        } catch (ClassNotFoundException | NoSuchMethodException e) {
          // skip non-loadable classes
        }
      }
    }
  }

  /**
   * Compares our hand-written schema against victools jsonschema-generator output. victools is the
   * ground truth — if they diverge, our hand-written logic has a bug.
   */
  @SuppressWarnings("unchecked")
  private static void assertSchemaMatchesGroundTruth(Class<?> argsClass) {
    Map<String, Object> ours = ToolSchemaGenerator.generateSchema(argsClass);
    Map<String, Object> truth = generateWithVictools(argsClass);

    // type
    assertEquals(
        argsClass.getSimpleName() + ": type mismatch", truth.get("type"), ours.get("type"));

    // properties: compare each field's type and description
    Map<String, Object> truthProps = (Map<String, Object>) truth.get("properties");
    Map<String, Object> ourProps = (Map<String, Object>) ours.get("properties");

    if (truthProps == null || truthProps.isEmpty()) {
      assertTrue(
          argsClass.getSimpleName() + ": expected no properties",
          ourProps == null || ourProps.isEmpty());
      return;
    }

    assertNotNull(argsClass.getSimpleName() + ": properties missing", ourProps);
    assertEquals(
        argsClass.getSimpleName() + ": property count mismatch",
        truthProps.size(),
        ourProps.size());

    for (String field : truthProps.keySet()) {
      assertTrue(
          argsClass.getSimpleName() + ": missing field " + field, ourProps.containsKey(field));

      Map<String, Object> truthField = (Map<String, Object>) truthProps.get(field);
      Map<String, Object> ourField = (Map<String, Object>) ourProps.get(field);

      assertEquals(
          argsClass.getSimpleName() + "." + field + ": type mismatch",
          truthField.get("type"),
          ourField.get("type"));
      assertEquals(
          argsClass.getSimpleName() + "." + field + ": description mismatch",
          truthField.get("description"),
          ourField.get("description"));
    }

    // required
    List<String> truthRequired = (List<String>) truth.get("required");
    List<String> ourRequired = (List<String>) ours.get("required");

    if (truthRequired == null || truthRequired.isEmpty()) {
      assertTrue(
          argsClass.getSimpleName() + ": expected no required fields",
          ourRequired == null || ourRequired.isEmpty());
    } else {
      assertNotNull(argsClass.getSimpleName() + ": required missing", ourRequired);
      assertEquals(
          argsClass.getSimpleName() + ": required fields mismatch",
          truthRequired.size(),
          ourRequired.size());
      assertTrue(
          argsClass.getSimpleName() + ": required contents mismatch",
          ourRequired.containsAll(truthRequired));
    }
  }

  /** Generate schema using victools as ground truth reference. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> generateWithVictools(Class<?> argsClass) {
    JacksonModule module = new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED);
    SchemaGenerator generator =
        new SchemaGenerator(
            new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON)
                .with(module)
                .build());
    JsonNode node = generator.generateSchema(argsClass);
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> schema = mapper.convertValue(node, Map.class);
    schema.remove("$schema");
    return schema;
  }

  // --- Helpers ---

  @SuppressWarnings("unchecked")
  private static String getType(Map<String, Object> props, String fieldName) {
    Map<String, Object> prop = (Map<String, Object>) props.get(fieldName);
    assertNotNull("Property " + fieldName + " should exist", prop);
    return (String) prop.get("type");
  }

  @SuppressWarnings("unchecked")
  private static String getDescription(Map<String, Object> props, String fieldName) {
    Map<String, Object> prop = (Map<String, Object>) props.get(fieldName);
    assertNotNull("Property " + fieldName + " should exist", prop);
    return (String) prop.get("description");
  }
}
