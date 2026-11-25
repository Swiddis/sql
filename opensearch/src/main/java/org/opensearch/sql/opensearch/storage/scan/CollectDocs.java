/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.storage.scan;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.type.SqlTypeName;
import org.locationtech.jts.geom.Point;
import org.opensearch.sql.calcite.type.AbstractExprRelDataType;
import org.opensearch.sql.calcite.utils.datetime.DateTimeConversionUtils;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.data.type.ExprCoreType;
import org.opensearch.sql.opensearch.storage.OpenSearchIndex;

/** Row to document conversion and deterministic {@code _id} for the collect write sink. */
final class CollectDocs {

  private CollectDocs() {}

  /**
   * The column values of one input row. With Calcite's {@code JavaRowFormat.SCALAR} (a single
   * column) the row object is the column value itself, even when that value is an {@code Object[]}
   * (a ROW); in every other format the row is the {@code Object[]} of column values.
   */
  static Object[] rowValues(Object row, boolean scalarRow) {
    return scalarRow ? new Object[] {row} : (Object[]) row;
  }

  /**
   * Converts one row to a JSON-writable document, or returns null when every non-metadata column is
   * null (such a row is not written).
   *
   * <p>The index row type lists each object field twice: the struct column {@code a} and one column
   * per leaf ({@code a.b}). Only the struct column is written, because writing both makes the field
   * hold the value twice, and a dotted key cannot be written into a {@code nested} mapping. A
   * dotted column whose parent column is absent (an explicit field list) is written as given.
   */
  static Map<String, Object> toDocument(
      List<String> names, List<RelDataType> types, Object[] values) {
    Map<String, RelDataType> typeByPath = new HashMap<>();
    for (int i = 0; i < names.size(); i++) {
      typeByPath.put(names.get(i), types.get(i));
    }
    Map<String, Object> doc = new LinkedHashMap<>();
    for (int i = 0; i < names.size() && i < values.length; i++) {
      String name = names.get(i);
      if (values[i] == null
          || OpenSearchIndex.METADATAFIELD_TYPE_MAP.containsKey(name)
          || hasParentColumn(name, typeByPath)) {
        continue;
      }
      Object v = toJson(values[i], types.get(i), name, typeByPath);
      if (v != null) {
        doc.put(name, v);
      }
    }
    return doc.isEmpty() ? null : doc;
  }

  private static boolean hasParentColumn(String name, Map<String, RelDataType> columns) {
    for (int dot = name.indexOf('.'); dot > 0; dot = name.indexOf('.', dot + 1)) {
      if (columns.containsKey(name.substring(0, dot))) {
        return true;
      }
    }
    return false;
  }

  /** Converts a runtime value to something XContent can write. */
  static Object toJson(Object value, RelDataType type) {
    return toJson(value, type, null, Map.of());
  }

  /**
   * @param path the dotted field path of {@code value}, or null when unknown
   * @param typeByPath column types by dotted path; the types of leaves inside a struct value come
   *     from here, since the struct's own type carries no field types
   */
  @SuppressWarnings("unchecked")
  private static Object toJson(
      Object value, RelDataType type, String path, Map<String, RelDataType> typeByPath) {
    if (value == null) {
      return null;
    }
    if (value instanceof ExprValue ev) {
      if (ev.isNull() || ev.isMissing()) {
        return null;
      }
      return toJson(ev.value(), type, path, typeByPath);
    }
    if (value instanceof Point p) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("lat", p.getY());
      m.put("lon", p.getX());
      return m;
    }
    if (value instanceof byte[] b) {
      return Base64.getEncoder().encodeToString(b);
    }
    if (value instanceof TemporalAmount t) {
      return t.toString();
    }
    if (value instanceof Number n && isInterval(type)) {
      // PPL holds an INTERVAL value as the count of its qualifier's unit (INTERVAL 5 MINUTE is 5);
      // the unit is only in the type. Same conversion as date_add.
      return DateTimeConversionUtils.convertToTemporalAmount(
              n.longValue(), type.getIntervalQualifier().getUnit())
          .toString();
    }
    if (value instanceof String s && isTimestamp(type)) {
      return toIsoUtc(s);
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
        String childPath = path == null ? null : path + "." + e.getKey();
        out.put(
            e.getKey(),
            toJson(
                e.getValue(),
                childPath == null ? null : typeByPath.get(childPath),
                childPath,
                typeByPath));
      }
      return out;
    }
    if (value instanceof Object[] arr) {
      if (type != null && type.getSqlTypeName() == SqlTypeName.ROW) {
        List<RelDataTypeField> fields = type.getFieldList();
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < fields.size() && i < arr.length; i++) {
          out.put(
              fields.get(i).getName(), toJson(arr[i], fields.get(i).getType(), null, typeByPath));
        }
        return out;
      }
      value = Arrays.asList(arr);
    }
    if (value instanceof List<?> list) {
      RelDataType component = type != null ? type.getComponentType() : null;
      List<Object> out = new ArrayList<>(list.size());
      for (Object o : list) {
        // Elements of an array of objects keep the array's path: n.ts is the leaf of every n[i].
        out.add(toJson(o, component, path, typeByPath));
      }
      return out;
    }
    return value;
  }

  private static boolean isInterval(RelDataType type) {
    return type != null && SqlTypeName.INTERVAL_TYPES.contains(type.getSqlTypeName());
  }

  private static boolean isTimestamp(RelDataType type) {
    if (type == null) {
      return false;
    }
    if (type instanceof AbstractExprRelDataType<?> t) {
      return t.getExprType() == ExprCoreType.TIMESTAMP;
    }
    return type.getSqlTypeName() == SqlTypeName.TIMESTAMP;
  }

  /** PPL timestamps are UTC strings {@code yyyy-MM-dd HH:mm:ss[.fraction]}. */
  private static String toIsoUtc(String s) {
    String iso = s.indexOf('T') >= 0 ? s : s.replace(' ', 'T');
    return iso.endsWith("Z") ? iso : iso + "Z";
  }

  /**
   * Deterministic document id: the first 128 bits of SHA-256, base64url without padding. Each value
   * contributes 0x00 if null, else a type tag (S string, I integral, F floating point, B boolean, O
   * other), a 4-byte big-endian length and its UTF-8 text ({@link String#valueOf}). This is a
   * stable format: changing it makes every re-run of a summary duplicate its rows.
   */
  static String documentId(List<?> values) {
    MessageDigest md;
    try {
      md = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    for (Object v : values) {
      if (v == null) {
        md.update((byte) 0);
        continue;
      }
      byte[] text = String.valueOf(v).getBytes(StandardCharsets.UTF_8);
      md.update(typeTag(v));
      md.update(ByteBuffer.allocate(4).putInt(text.length).array());
      md.update(text);
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(md.digest(), 16));
  }

  private static byte typeTag(Object v) {
    if (v instanceof String) {
      return 'S';
    }
    if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
      return 'I';
    }
    if (v instanceof Double || v instanceof Float) {
      return 'F';
    }
    if (v instanceof Boolean) {
      return 'B';
    }
    return 'O';
  }
}
