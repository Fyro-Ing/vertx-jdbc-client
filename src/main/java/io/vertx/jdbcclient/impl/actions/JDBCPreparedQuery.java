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

package io.vertx.jdbcclient.impl.actions;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.jdbc.impl.actions.CallableOutParams;
import io.vertx.ext.jdbc.impl.actions.JDBCStatementHelper;
import io.vertx.ext.jdbc.impl.actions.JDBCTypeWrapper;
import io.vertx.ext.jdbc.spi.JDBCColumnDescriptorProvider;
import io.vertx.ext.sql.SQLOptions;
import io.vertx.jdbcclient.SqlOutParam;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import io.vertx.sqlclient.impl.command.ExtendedQueryCommand;

import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.stream.Collector;

/**
 * @author <a href="mailto:nscavell@redhat.com">Nick Scavelli</a>
 */
public class JDBCPreparedQuery<C, R> extends JDBCQueryAction<C, R> {

  private final ExtendedQueryCommand<R> query;
  private final Tuple params = Tuple.tuple();
  private final CallableOutParams outParams = CallableOutParams.create();

  public JDBCPreparedQuery(JDBCStatementHelper helper, SQLOptions options, ExtendedQueryCommand<R> query, Collector<Row, C, R> collector, Tuple params) {
    super(helper, options, collector);
    this.query = query;
    this.normalizeParams(params);
  }

  @Override
  public JDBCResponse<R> execute(Connection conn) throws SQLException {
    // if there are registered output parameters we need to disable the auto generates key
    // extraction as it will interfere with the expectations on some jdbc drivers (such as MSSQL)
    boolean returnAutoGeneratedKeys = outParams.size() == 0 && returnAutoGeneratedKeys(conn);

    try (PreparedStatement ps = prepare(conn, returnAutoGeneratedKeys)) {
      fillStatement(ps, conn);
      return decode(ps, ps.execute(), returnAutoGeneratedKeys, outParams);
    }
  }

  private PreparedStatement prepare(Connection conn, boolean returnAutoGeneratedKeys) throws SQLException {

    final String sql = query.sql();

    if (!outParams.isEmpty()) {
      return conn.prepareCall(sql);
    } else {

      boolean autoGeneratedIndexes = options != null && options.getAutoGeneratedKeysIndexes() != null && options.getAutoGeneratedKeysIndexes().size() > 0;

      if (returnAutoGeneratedKeys && !autoGeneratedIndexes) {
        return conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
      } else if (autoGeneratedIndexes) {
        // convert json array to int or string array
        JsonArray indexes = options.getAutoGeneratedKeysIndexes();
        try {
          if (indexes.getValue(0) instanceof Number) {
            int[] keys = new int[indexes.size()];
            for (int i = 0; i < keys.length; i++) {
              keys[i] = indexes.getInteger(i);
            }
            return conn.prepareStatement(sql, keys);
          } else if (indexes.getValue(0) instanceof String) {
            String[] keys = new String[indexes.size()];
            for (int i = 0; i < keys.length; i++) {
              keys[i] = indexes.getString(i);
            }
            return conn.prepareStatement(sql, keys);
          } else {
            throw new SQLException("Invalid type of index, only [int, String] allowed");
          }
        } catch (RuntimeException e) {
          // any exception due to type conversion
          throw new SQLException(e);
        }
      } else {
        return conn.prepareStatement(sql);
      }
    }
  }

  private void normalizeParams(Tuple tuple) {
    if (tuple == null) {
      return;
    }
    for (int i = 0; i < tuple.size(); i++) {
      final Object param = tuple.getValue(i);
      if (param instanceof SqlOutParam) {
        final SqlOutParam out = (SqlOutParam) param;
        outParams.put(i + 1, out.type());
        params.addValue(out.in() ? out.value() : out);
      } else {
        params.addValue(param);
      }
    }
  }

  private void fillStatement(PreparedStatement ps, Connection conn) throws SQLException {
    // Need to register out then able to get parameter metadata in postgresql
    // https://www.postgresql.org/message-id/flat/556A1477.2050506%40ttc-cmc.net#f16a74c2e386993934626dc0c9aa22c3
    // Other driver seems fine with this way
    if (!outParams.isEmpty()) {
      final CallableStatement cs = (CallableStatement) ps;
      for (Map.Entry<Integer, JDBCTypeWrapper> entry : outParams.entrySet()) {
        cs.registerOutParameter(entry.getKey(), entry.getValue().vendorTypeNumber());
      }
    }
    final ParameterMetaData metaData = ps.getParameterMetaData();
    final JDBCColumnDescriptorProvider provider = JDBCColumnDescriptorProvider.fromParameterMetaData(metaData);
    for (int idx = 1; idx <= params.size(); idx++) {
      Object value = params.getValue(idx - 1);
      if (value instanceof SqlOutParam) {
        continue;
      }
      ps.setObject(idx, adaptType(conn, helper.getEncoder().encode(params, idx, provider)));
    }
  }

  private Object adaptType(Connection conn, Object value) throws SQLException {
    if (value instanceof Buffer) {
      // -> java.sql.Blob
      Buffer buffer = (Buffer) value;
      Blob blob = conn.createBlob();
      blob.setBytes(1, buffer.getBytes());
      return blob;
    }

    return value;
  }

}
