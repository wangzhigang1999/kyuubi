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

package org.apache.spark.kyuubi.shutdown.watchdog;

import static org.junit.Assert.*;

import org.apache.spark.api.plugin.DriverPlugin;
import org.apache.spark.api.plugin.SparkPlugin;
import org.junit.After;
import org.junit.Test;

/** Unit tests for {@link SparkShutdownWatchdogPlugin}. */
public class SparkShutdownWatchdogPluginTest {

  @After
  public void tearDown() {
    ShutdownWatchdog.resetForTests();
  }

  @Test
  public void testDriverPluginIsNotNull() {
    SparkPlugin plugin = new SparkShutdownWatchdogPlugin();
    DriverPlugin driverPlugin = plugin.driverPlugin();
    assertNotNull("driverPlugin() should return a non-null instance", driverPlugin);
  }

  @Test
  public void testExecutorPluginIsNull() {
    SparkPlugin plugin = new SparkShutdownWatchdogPlugin();
    assertNull("executorPlugin() should return null (driver-only plugin)", plugin.executorPlugin());
  }

  @Test
  public void testShutdownWithoutInitLogsWarning() {
    // When shutdown() is called without a prior init(), sparkConf is null.
    // This should not throw — it logs a warning and returns gracefully.
    SparkPlugin plugin = new SparkShutdownWatchdogPlugin();
    DriverPlugin driverPlugin = plugin.driverPlugin();
    driverPlugin.shutdown(); // should not throw
  }
}
