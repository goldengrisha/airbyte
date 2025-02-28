/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.source.mssql;

import static io.airbyte.cdk.db.jdbc.JdbcConstants.INTERNAL_COLUMN_NAME;
import static io.airbyte.cdk.db.jdbc.JdbcConstants.INTERNAL_COLUMN_TYPE;
import static io.airbyte.cdk.db.jdbc.JdbcConstants.INTERNAL_COLUMN_TYPE_NAME;
import static io.airbyte.cdk.db.jdbc.JdbcConstants.INTERNAL_SCHEMA_NAME;
import static io.airbyte.cdk.db.jdbc.JdbcConstants.INTERNAL_TABLE_NAME;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.sqlserver.jdbc.Geography;
import com.microsoft.sqlserver.jdbc.Geometry;
import com.microsoft.sqlserver.jdbc.SQLServerResultSetMetaData;
import io.airbyte.cdk.db.jdbc.JdbcSourceOperations;
import io.airbyte.protocol.models.JsonSchemaType;
import java.nio.charset.Charset;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MssqlSourceOperations extends JdbcSourceOperations {

  private static final Logger LOGGER = LoggerFactory.getLogger(MssqlSourceOperations.class);

  /**
   * The method is used to set json value by type. Need to be overridden as MSSQL has some its own
   * specific types (ex. Geometry, Geography, Hierarchyid, etc)
   *
   * @throws SQLException
   */
  @Override
  public void copyToJsonField(final ResultSet resultSet, final int colIndex, final ObjectNode json)
      throws SQLException {

    final SQLServerResultSetMetaData metadata = (SQLServerResultSetMetaData) resultSet
        .getMetaData();
    final String columnName = metadata.getColumnName(colIndex);
    final String columnTypeName = metadata.getColumnTypeName(colIndex);
    final JDBCType columnType = safeGetJdbcType(metadata.getColumnType(colIndex));

    if (columnTypeName.equalsIgnoreCase("time")) {
      putTime(json, columnName, resultSet, colIndex);
    } else if (columnTypeName.equalsIgnoreCase("geometry")) {
      putGeometry(json, columnName, resultSet, colIndex);
    } else if (columnTypeName.equalsIgnoreCase("geography")) {
      putGeography(json, columnName, resultSet, colIndex);
    } else {
      putValue(columnType, resultSet, columnName, colIndex, json);
    }
  }

  private void putValue(final JDBCType columnType,
                        final ResultSet resultSet,
                        final String columnName,
                        final int colIndex,
                        final ObjectNode json)
      throws SQLException {
    switch (columnType) {
      case BIT, BOOLEAN -> putBoolean(json, columnName, resultSet, colIndex);
      case TINYINT, SMALLINT -> putShortInt(json, columnName, resultSet, colIndex);
      case INTEGER -> putInteger(json, columnName, resultSet, colIndex);
      case BIGINT -> putBigInt(json, columnName, resultSet, colIndex);
      case FLOAT, DOUBLE -> putDouble(json, columnName, resultSet, colIndex);
      case REAL -> putFloat(json, columnName, resultSet, colIndex);
      case NUMERIC, DECIMAL -> putBigDecimal(json, columnName, resultSet, colIndex);
      case CHAR, NVARCHAR, VARCHAR, LONGVARCHAR -> putString(json, columnName, resultSet, colIndex);
      case DATE -> putDate(json, columnName, resultSet, colIndex);
      case TIME -> putTime(json, columnName, resultSet, colIndex);
      case TIMESTAMP -> putTimestamp(json, columnName, resultSet, colIndex);
      case BLOB, BINARY, VARBINARY, LONGVARBINARY -> putBinary(json, columnName, resultSet,
          colIndex);
      case ARRAY -> putArray(json, columnName, resultSet, colIndex);
      default -> putDefault(json, columnName, resultSet, colIndex);
    }
  }

  @Override
  public JDBCType getDatabaseFieldType(final JsonNode field) {
    try {
      final String typeName = field.get(INTERNAL_COLUMN_TYPE_NAME).asText();
      if (typeName.equalsIgnoreCase("geography")
          || typeName.equalsIgnoreCase("geometry")
          || typeName.equalsIgnoreCase("hierarchyid")) {
        return JDBCType.VARCHAR;
      }

      if (typeName.equalsIgnoreCase("datetime")) {
        return JDBCType.TIMESTAMP;
      }

      if (typeName.equalsIgnoreCase("datetimeoffset")) {
        return JDBCType.TIMESTAMP_WITH_TIMEZONE;
      }

      if (typeName.equalsIgnoreCase("real")) {
        return JDBCType.REAL;
      }

      return JDBCType.valueOf(field.get(INTERNAL_COLUMN_TYPE).asInt());
    } catch (final IllegalArgumentException ex) {
      LOGGER.warn(String.format("Could not convert column: %s from table: %s.%s with type: %s. Casting to VARCHAR.",
          field.get(INTERNAL_COLUMN_NAME),
          field.get(INTERNAL_SCHEMA_NAME),
          field.get(INTERNAL_TABLE_NAME),
          field.get(INTERNAL_COLUMN_TYPE)));
      return JDBCType.VARCHAR;
    }
  }

  @Override
  protected void putBinary(final ObjectNode node,
                           final String columnName,
                           final ResultSet resultSet,
                           final int index)
      throws SQLException {
    final byte[] bytes = resultSet.getBytes(index);
    final String value = new String(bytes, Charset.defaultCharset());
    node.put(columnName, value);
  }

  protected void putGeometry(final ObjectNode node,
                             final String columnName,
                             final ResultSet resultSet,
                             final int index)
      throws SQLException {
    node.put(columnName, Geometry.deserialize(resultSet.getBytes(index)).toString());
  }

  protected void putGeography(final ObjectNode node,
                              final String columnName,
                              final ResultSet resultSet,
                              final int index)
      throws SQLException {
    node.put(columnName, Geography.deserialize(resultSet.getBytes(index)).toString());
  }

  @Override
  protected void putTimestamp(final ObjectNode node, final String columnName, final ResultSet resultSet, final int index) throws SQLException {
    String pattern = "[yyyy][yy]['-']['/']['.'][' '][MMM][MM][M]['-']['/']['.'][' '][dd][d]" +
          "[[' ']['T']HH:mm[':'ss[.][SSSSSSS][SSSSSS][SSSSS][SSSS][SSS][SS][S][' '][z][zzz][Z][O][x][XXX][XX][X]]]";
    final DateTimeFormatter microsecondsFormatter = DateTimeFormatter.ofPattern(pattern);
    node.put(columnName, getObject(resultSet, index, LocalDateTime.class).format(microsecondsFormatter));
  }

  @Override
  public JsonSchemaType getAirbyteType(final JDBCType jdbcType) {
    return switch (jdbcType) {
      case TINYINT, SMALLINT, INTEGER, BIGINT -> JsonSchemaType.INTEGER;
      case DOUBLE, DECIMAL, FLOAT, NUMERIC, REAL -> JsonSchemaType.NUMBER;
      case BOOLEAN, BIT -> JsonSchemaType.BOOLEAN;
      case NULL -> JsonSchemaType.NULL;
      case BLOB, BINARY, VARBINARY, LONGVARBINARY -> JsonSchemaType.STRING_BASE_64;
      case TIME -> JsonSchemaType.STRING_TIME_WITHOUT_TIMEZONE;
      case TIMESTAMP_WITH_TIMEZONE -> JsonSchemaType.STRING_TIMESTAMP_WITH_TIMEZONE;
      case TIMESTAMP -> JsonSchemaType.STRING_TIMESTAMP_WITHOUT_TIMEZONE;
      case DATE -> JsonSchemaType.STRING_DATE;
      default -> JsonSchemaType.STRING;
    };
  }

}
