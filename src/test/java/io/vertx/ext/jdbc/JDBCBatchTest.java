/*
 * Copyright (c) 2011-2014 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.jdbc;

import io.vertx.core.json.JsonArray;
import io.vertx.ext.sql.SQLClient;
import io.vertx.ext.sql.SQLConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author <a href="mailto:pmlopes@gmail.com">Paulo Lopes</a>
 */
public class JDBCBatchTest extends JDBCClientTestBase {

  public static void proc() {
    System.out.println("Fake Proc called");
  }

  @Before
  public void setUp() throws Exception {
    super.setUp();
    client = JDBCClient.create(vertx, DBConfigs.h2(JDBCBatchTest.class));
  }

  @Test
  public void testBatchStatement() {
    List<String> sql = new ArrayList<>();

    sql.add("drop table if exists t");
    sql.add("create table t (u UUID)");
    sql.add("insert into t (u) values (random_uuid())");

    connection().batch(sql, onSuccess(batchResult -> {
      assertNotNull(batchResult);
      assertEquals(3, batchResult.size());
      testComplete();
    }));

    await();
  }

  @Test
  public void testBatchPreparedStatement() {
    List<String> sql = new ArrayList<>();

    sql.add("drop table if exists t");
    sql.add("create table t (u BIGINT)");

    final SQLConnection conn = connection();

    conn.batch(sql, onSuccess(batchResult -> {
      assertNotNull(batchResult);
      assertEquals(2, batchResult.size());

      List<JsonArray> args = new ArrayList<>();

      args.add(new JsonArray().add(System.currentTimeMillis()));
      args.add(new JsonArray().add(System.currentTimeMillis()));
      args.add(new JsonArray().add(System.currentTimeMillis()));

      conn.batchWithParams("insert into t (u) values (?)", args, onSuccess(batchResult2 -> {
        assertNotNull(batchResult2);
        assertEquals(3, batchResult2.size());
        testComplete();
      }));
    }));

    await();
  }

  @Test
  public void testBatchCallableStatement() {
    final SQLConnection conn = connection();

    conn.batch(Arrays.asList("CREATE ALIAS println FOR \"io.vertx.ext.jdbc.JDBCBatchTest.proc\""), onSuccess(batchResult -> {
      assertNotNull(batchResult);
      assertEquals(1, batchResult.size());

      conn.batchCallableWithParams("{ call println() }", Arrays.asList(new JsonArray(), new JsonArray(), new JsonArray()), Arrays.asList(new JsonArray(), new JsonArray(), new JsonArray()), onSuccess(batchResult2 -> {
        assertNotNull(batchResult2);
        assertEquals(3, batchResult2.size());
        testComplete();
      }));
    }));

    await();
  }

  private SQLConnection connection() {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<SQLConnection> ref = new AtomicReference<>();
    client.getConnection(onSuccess(conn -> {
      ref.set(conn);
      latch.countDown();
    }));

    try {
      latch.await();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    return ref.get();
  }
}
