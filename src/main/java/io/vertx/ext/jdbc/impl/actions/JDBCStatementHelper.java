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

package io.vertx.ext.jdbc.impl.actions;

import io.vertx.core.ServiceHelper;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.jdbc.spi.JDBCDecoder;
import io.vertx.ext.jdbc.spi.JDBCEncoder;
import io.vertx.ext.jdbc.spi.impl.JDBCDecoderImpl;
import io.vertx.ext.jdbc.spi.impl.JDBCEncoderImpl;

import java.sql.CallableStatement;
import java.sql.JDBCType;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;

/**
 * @author <a href="mailto:nscavell@redhat.com">Nick Scavelli</a>
 * @author <a href="mailto:plopes@redhat.com">Paulo Lopes</a>
 */
public final class JDBCStatementHelper {

  private static final JsonArray EMPTY = new JsonArray(Collections.unmodifiableList(new ArrayList<>()));

  private final JDBCEncoder encoder;
  private final JDBCDecoder decoder;

  public JDBCStatementHelper() {
    this(new JsonObject());
  }

  public JDBCStatementHelper(JsonObject config) {
    this.encoder = Optional.ofNullable(ServiceHelper.loadFactoryOrNull(JDBCEncoder.class)).orElseGet(JDBCEncoderImpl::new)
      .setup(config.getBoolean("castUUID", false), config.getBoolean("castDate", true),
        config.getBoolean("castTime", true), config.getBoolean("castDatetime", true));
    this.decoder = Optional.ofNullable(ServiceHelper.loadFactoryOrNull(JDBCDecoder.class)).orElseGet(JDBCDecoderImpl::new);
  }

  public JDBCEncoder getEncoder() {
    return encoder;
  }

  public JDBCDecoder getDecoder() {
    return decoder;
  }

  public void fillStatement(PreparedStatement statement, JsonArray in) throws SQLException {
    if (in == null) {
      in = EMPTY;
    }

    ParameterMetaData metaData = statement.getParameterMetaData();
    for (int pos = 1; pos <= in.size(); pos++) {
      statement.setObject(pos, encoder.convert(JDBCType.valueOf(metaData.getParameterType(pos)), in.getValue(pos)));
    }
  }

  public void fillStatement(CallableStatement statement, JsonArray in, JsonArray out) throws SQLException {
    if (in == null) {
      in = EMPTY;
    }

    if (out == null) {
      out = EMPTY;
    }

    int max = Math.max(in.size(), out.size());
    ParameterMetaData metaData = statement.getParameterMetaData();
    for (int i = 0; i < max; i++) {
      Object value = null;
      boolean set = false;

      if (i < in.size()) {
        value = in.getValue(i);
      }

      // found a in value, use it as a input parameter
      if (value != null) {
        statement.setObject(i + 1, encoder.convert(JDBCType.valueOf(metaData.getParameterType(i + 1)), value));
        set = true;
      }

      // reset
      value = null;

      if (i < out.size()) {
        value = out.getValue(i);
      }

      // found a out value, use it as a output parameter
      if (value != null) {
        // We're using the int from the enum instead of the enum itself to allow working with Drivers
        // that have not been upgraded to Java8 yet.
        if (value instanceof String) {
          statement.registerOutParameter(i + 1, JDBCType.valueOf((String) value).getVendorTypeNumber());
        } else if (value instanceof Number) {
          // for cases where vendors have special codes (e.g.: Oracle)
          statement.registerOutParameter(i + 1, ((Number) value).intValue());
        }
        set = true;
      }

      if (!set) {
        // assume null input
        statement.setNull(i + 1, Types.NULL);
      }
    }
  }

}
