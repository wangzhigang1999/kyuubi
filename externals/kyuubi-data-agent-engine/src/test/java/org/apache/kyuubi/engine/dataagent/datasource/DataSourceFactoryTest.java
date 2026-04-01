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

package org.apache.kyuubi.engine.dataagent.datasource;

import static org.junit.Assert.*;

import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.Test;

public class DataSourceFactoryTest {

  @Test
  public void testCreateReturnsHikariDataSource() throws Exception {
    File tmp = File.createTempFile("kyuubi-ds-test-", ".db");
    tmp.deleteOnExit();
    DataSource ds = DataSourceFactory.create("jdbc:sqlite:" + tmp.getAbsolutePath());
    assertTrue(ds instanceof HikariDataSource);
    try (Connection conn = ds.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE test (id INTEGER)");
      stmt.execute("INSERT INTO test VALUES (1)");
    } finally {
      ((HikariDataSource) ds).close();
      tmp.delete();
    }
  }
}
