/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.storage.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.calcite.avatica.util.TimeUnit;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.sql.calcite.utils.OpenSearchTypeFactory;
import org.opensearch.sql.calcite.utils.OpenSearchTypeFactory.ExprUDT;
import org.opensearch.sql.data.model.ExprIntervalValue;
import org.opensearch.sql.data.model.ExprIpValue;

/** Unit tests for the collect row to document conversion and the deterministic {@code _id}. */
class CollectDocsTest {

  private static final OpenSearchTypeFactory TF = OpenSearchTypeFactory.TYPE_FACTORY;
  private static final RelDataType VARCHAR = TF.createSqlType(SqlTypeName.VARCHAR);
  private static final RelDataType BIGINT = TF.createSqlType(SqlTypeName.BIGINT);
  private static final RelDataType ANY = TF.createSqlType(SqlTypeName.ANY);

  private static Map<String, Object> doc(String name, RelDataType type, Object value) {
    return CollectDocs.toDocument(List.of(name), List.of(type), new Object[] {value});
  }

  /** The doc must be writable by the same XContent path IndexRequest.source(Map) uses. */
  private static void assertSerializable(Map<String, Object> doc) throws Exception {
    XContentFactory.jsonBuilder().map(doc).close();
  }

  /** Would have caught T3 (ip): HTTP 500 "cannot write xcontent ... ExprIpValue". */
  @Test
  void ipValueIsWrittenAsString() throws Exception {
    Map<String, Object> d = doc("ip", TF.createUDT(ExprUDT.EXPR_IP), new ExprIpValue("10.0.0.1"));
    assertEquals("10.0.0.1", d.get("ip"));
    assertSerializable(d);
  }

  /** Would have caught T3 (geo_point): HTTP 500 "cannot write xcontent ... jts Point". */
  @Test
  void geoPointIsWrittenAsLatLonObject() throws Exception {
    var point = new GeometryFactory().createPoint(new Coordinate(-122.4, 37.7)); // x=lon, y=lat
    Map<String, Object> d = doc("loc", ANY, point);
    assertEquals(Map.of("lat", 37.7, "lon", -122.4), d.get("loc"));
    assertSerializable(d);
  }

  @Test
  void binaryIsWrittenAsBase64() throws Exception {
    Map<String, Object> d = doc("b", TF.createUDT(ExprUDT.EXPR_BINARY), new byte[] {1, 2, 3});
    assertEquals("AQID", d.get("b"));
    assertSerializable(d);
  }

  @Test
  void intervalIsWrittenAsIso8601Duration() throws Exception {
    Map<String, Object> d = doc("i", ANY, new ExprIntervalValue(Duration.ofMinutes(5)));
    assertEquals("PT5M", d.get("i"));
    assertSerializable(d);
  }

  @Test
  void structAndArrayAreConvertedRecursively() throws Exception {
    var point = new GeometryFactory().createPoint(new Coordinate(1, 2));
    Map<String, Object> d =
        doc("o", ANY, Map.of("ip", new ExprIpValue("::1"), "pts", Arrays.asList(point, null)));
    @SuppressWarnings("unchecked")
    Map<String, Object> o = (Map<String, Object>) d.get("o");
    assertEquals("::1", o.get("ip"));
    assertEquals(Arrays.asList(Map.of("lat", 2.0, "lon", 1.0), null), o.get("pts"));
    assertSerializable(d);
  }

  @Test
  void rowValueIsWrittenAsObjectWithFieldNames() throws Exception {
    RelDataType row = TF.builder().add("a", VARCHAR).add("b", BIGINT).build();
    Map<String, Object> d = doc("r", row, new Object[] {"x", 2L});
    assertEquals(Map.of("a", "x", "b", 2L), d.get("r"));
    assertEquals(List.of("x", 2L), doc("l", ANY, new Object[] {"x", 2L}).get("l"));
    assertSerializable(d);
  }

  @Test
  void decimalIsWrittenUnchanged() throws Exception {
    Map<String, Object> d = doc("d", ANY, new java.math.BigDecimal("12345678901234567890.123"));
    assertEquals(new java.math.BigDecimal("12345678901234567890.123"), d.get("d"));
    assertSerializable(d);
  }

  /** Would have caught T1: 'yyyy-MM-dd HH:mm:ss' is rejected by a default date mapping. */
  @Test
  void timestampIsWrittenAsIso8601Utc() {
    RelDataType ts = TF.createUDT(ExprUDT.EXPR_TIMESTAMP);
    assertEquals("2026-11-23T10:00:00Z", doc("ts", ts, "2026-11-23 10:00:00").get("ts"));
    assertEquals(
        "2026-11-23T10:00:00.123456Z", doc("ts", ts, "2026-11-23 10:00:00.123456").get("ts"));
  }

  /** Would have caught T4: an all-null row was indexed as an empty document. */
  @Test
  void allNullRowProducesNoDocument() {
    assertNull(
        CollectDocs.toDocument(
            List.of("a", "b"), List.of(VARCHAR, BIGINT), new Object[] {null, null}));
  }

  @Test
  void metadataFieldsAreNotWritten() {
    Map<String, Object> d =
        CollectDocs.toDocument(
            List.of("_id", "a"), List.of(VARCHAR, BIGINT), new Object[] {"x", 1L});
    assertEquals(Map.of("a", 1L), d);
  }

  /**
   * Golden vectors. The summary _id must be identical across versions or a re-run duplicates every
   * summary row: changing these values is a breaking change. Cross-checked with an independent
   * Python implementation of the same format: sha256 over, per value, 0x00 for null, else a type
   * tag (S string, I integral, F floating point, B boolean, O other) + 4-byte big-endian length +
   * UTF-8 text (Java String.valueOf; for doubles that is Double.toString); first 16 bytes,
   * base64url without padding.
   */
  @Test
  void documentIdGoldenVectors() {
    assertEquals("ZBd4n3kj52K_57MT4x6ofQ", CollectDocs.documentId(List.of("a", 1L)));
    assertEquals("kNC6OCPFPv65f6TZzYdlnA", CollectDocs.documentId(List.of("a")));
    assertEquals(
        "J4qf43_7oG0GmsMHbz4JWQ", CollectDocs.documentId(Arrays.asList("", null, 1.5, true)));
    // Type-tagged: the string "1" and the number 1 differ; empty string and null differ.
    assertNotEquals(CollectDocs.documentId(List.of("1")), CollectDocs.documentId(List.of(1L)));
    assertNotEquals(
        CollectDocs.documentId(List.of("")), CollectDocs.documentId(Arrays.asList((Object) null)));
    assertNotEquals(
        CollectDocs.documentId(List.of("ab", "c")), CollectDocs.documentId(List.of("a", "bc")));
    assertNotEquals(
        CollectDocs.documentId(Arrays.asList((Object) null)),
        CollectDocs.documentId(List.of("null")));
    assertEquals(CollectDocs.documentId(List.of(1)), CollectDocs.documentId(List.of(1L)));
  }

  /**
   * Would have caught review bug 2: a struct column and its flattened dotted columns both written.
   */
  @Test
  void structColumnIsNotAlsoWrittenAsDottedKeys() {
    Map<String, Object> d =
        CollectDocs.toDocument(
            List.of("o", "o.b", "o.c.d", "x"),
            List.of(ANY, VARCHAR, VARCHAR, VARCHAR),
            new Object[] {Map.of("b", "v", "c", Map.of("d", "w")), "v", "w", "y"});
    assertEquals(List.of("o", "x"), List.copyOf(d.keySet()));
    assertEquals(Map.of("b", "v", "c", Map.of("d", "w")), d.get("o"));
  }

  /** A dotted column without its parent column (explicit field list) is still written. */
  @Test
  void dottedColumnWithoutParentIsWritten() {
    Map<String, Object> d =
        CollectDocs.toDocument(List.of("o.b"), List.of(VARCHAR), new Object[] {"v"});
    assertEquals(Map.of("o.b", "v"), d);
  }

  /** Would have caught review bug 3: a timestamp nested in a struct was not converted to ISO. */
  @Test
  void timestampNestedInStructIsWrittenAsIso8601Utc() {
    RelDataType ts = TF.createUDT(ExprUDT.EXPR_TIMESTAMP);
    Map<String, Object> d =
        CollectDocs.toDocument(
            List.of("evt", "evt.ts", "evt.in.ts"),
            List.of(ANY, ts, ts),
            new Object[] {
              Map.of("ts", "2026-11-23 10:00:00", "in", Map.of("ts", "2026-11-23 10:00:01")),
              "2026-11-23 10:00:00",
              "2026-11-23 10:00:01"
            });
    assertEquals(
        Map.of("ts", "2026-11-23T10:00:00Z", "in", Map.of("ts", "2026-11-23T10:00:01Z")),
        d.get("evt"));
  }

  /** Same for an array of objects (nested type): the element path stays the field path. */
  @Test
  void timestampInArrayOfStructsIsWrittenAsIso8601Utc() {
    RelDataType ts = TF.createUDT(ExprUDT.EXPR_TIMESTAMP);
    Map<String, Object> d =
        CollectDocs.toDocument(
            List.of("n", "n.ts"),
            List.of(ANY, ts),
            new Object[] {
              List.of(Map.of("ts", "2026-11-23 10:00:00")), List.of("2026-11-23 10:00:00")
            });
    assertEquals(List.of(Map.of("ts", "2026-11-23T10:00:00Z")), d.get("n"));
  }

  /** Would have caught review bug 5: intervals were written as bare numbers (unit lost). */
  @Test
  void dayTimeIntervalIsWrittenAsIso8601Duration() {
    RelDataType t =
        TF.createSqlIntervalType(
            new SqlIntervalQualifier(TimeUnit.MINUTE, null, SqlParserPos.ZERO));
    assertEquals("PT5M", doc("i", t, 5L).get("i"));
    RelDataType day =
        TF.createSqlIntervalType(new SqlIntervalQualifier(TimeUnit.DAY, null, SqlParserPos.ZERO));
    assertEquals("P2D", doc("i", day, 2L).get("i"));
  }

  @Test
  void yearMonthIntervalIsWrittenAsIso8601Period() {
    RelDataType t =
        TF.createSqlIntervalType(new SqlIntervalQualifier(TimeUnit.MONTH, null, SqlParserPos.ZERO));
    assertEquals("P1M", doc("i", t, 1).get("i"));
    assertEquals("P14M", doc("i", t, 14L).get("i"));
  }

  /** Would have caught review bug 11: a ROW value in a single-column scalar row read as the row. */
  @Test
  void rowValueIsNotMistakenForTheWholeRowWhenFormatIsScalar() {
    RelDataType row = TF.builder().add("a", VARCHAR).add("b", BIGINT).build();
    Object[] rowValue = {"x", 2L};
    assertEquals(1, CollectDocs.rowValues(rowValue, true).length);
    assertSame(rowValue, CollectDocs.rowValues(rowValue, true)[0]);
    assertSame(rowValue, CollectDocs.rowValues(rowValue, false));
    assertEquals(Map.of("a", "x", "b", 2L), doc("r", row, rowValue).get("r"));
  }
}
