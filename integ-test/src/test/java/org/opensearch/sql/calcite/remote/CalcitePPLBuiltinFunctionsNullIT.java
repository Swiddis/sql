/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.remote;

import static org.opensearch.sql.legacy.TestsConstants.TEST_INDEX_DATE_FORMATS_WITH_NULL;
import static org.opensearch.sql.legacy.TestsConstants.TEST_INDEX_NULL_MISSING;
import static org.opensearch.sql.legacy.TestsConstants.TEST_INDEX_STATE_COUNTRY_WITH_NULL;
import static org.opensearch.sql.util.MatcherUtils.*;
import static org.opensearch.sql.util.MatcherUtils.rows;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.opensearch.sql.exception.ExpressionEvaluationException;
import org.opensearch.sql.ppl.PPLIntegTestCase;

public class CalcitePPLBuiltinFunctionsNullIT extends PPLIntegTestCase {
  @Override
  public void init() throws Exception {
    super.init();
    enableCalcite();
    disallowCalciteFallback();

    loadIndex(Index.STATE_COUNTRY);
    loadIndex(Index.STATE_COUNTRY_WITH_NULL);
    loadIndex(Index.DATE_FORMATS);
    loadIndex(Index.DATE_FORMATS_WITH_NULL);
    loadIndex(Index.NULL_MISSING);
  }

  @Test
  public void testYearWeekInvalid() {
    assertThrows(
        Exception.class,
        () -> {
          executeQuery(
              String.format(
                  "source=%s  | eval `YEARWEEK('2020-08-26')` = YEARWEEK('2020-15-26')",
                  TEST_INDEX_DATE_FORMATS_WITH_NULL));
        });
  }

  @Test
  public void testYearWeekNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s  |  eval NullValue = YEARWEEK(date) | fields NullValue",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));

    verifySchema(actual, schema("NullValue", "int"));
    JSONArray ret = actual.getJSONArray("datarows");
    for (int i = 0; i < ret.length(); i++) {
      Object o = ((JSONArray) ret.get(i)).get(0);
      assertEquals(JSONObject.NULL, o);
    }
  }

  @Test
  public void testYearInvalid() {
    assertThrows(
        Exception.class,
        () -> {
          executeQuery(
              String.format(
                  "source=%s  | eval a = YEAR('2020-15-26')", TEST_INDEX_DATE_FORMATS_WITH_NULL));
        });
    assertThrows(
        Exception.class,
        () -> {
          executeQuery(
              String.format(
                  "source=%s  | eval a = YEAR('2020-12-26 25:00:00')",
                  TEST_INDEX_DATE_FORMATS_WITH_NULL));
        });
  }

  @Test
  public void testWeekInvalid() {
    assertThrows(
        Exception.class,
        () -> {
          executeQuery(
              String.format(
                  "source=%s  | eval a = WEEK('2020-15-26')", TEST_INDEX_DATE_FORMATS_WITH_NULL));
        });
    assertThrows(
        Exception.class,
        () -> {
          executeQuery(
              String.format(
                  "source=%s  | eval a = WEEK('2020-12-26 25:00:00')",
                  TEST_INDEX_DATE_FORMATS_WITH_NULL));
        });
  }

  @Test
  public void testWeekDayInvalid() {
    assertThrows(
        Exception.class,
        () -> {
          executeQuery(
              String.format(
                  "source=%s  | eval a = WEEKDAY('2020-15-26')",
                  TEST_INDEX_DATE_FORMATS_WITH_NULL));
        });
    assertThrows(
        Exception.class,
        () -> {
          executeQuery(
              String.format(
                  "source=%s  | eval a = WEEKDAY('2020-12-26 25:00:00')",
                  TEST_INDEX_DATE_FORMATS_WITH_NULL));
        });

    assertThrows(
        Exception.class,
        () -> {
          executeQuery(
              String.format(
                  "source=%s  | eval a = WEEKDAY('25:00:00')", TEST_INDEX_DATE_FORMATS_WITH_NULL));
        });
  }

  @Test
  public void testWeekDayNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s  |  eval timestamp = WEEKDAY(strict_date_optional_time),"
                    + " date=WEEKDAY(date), time=WEEKDAY(time) | fields timestamp, date, time",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));

    verifySchema(actual, schema("timestamp", "int"), schema("date", "int"), schema("time", "int"));
    JSONArray ret = (JSONArray) actual.getJSONArray("datarows").get(0);
    for (int i = 0; i < ret.length(); i++) {
      assertEquals(JSONObject.NULL, ret.get(i));
    }
  }

  @Test
  public void testUnixTimestampInvalid() {
    assertThrows(
        Exception.class,
        () -> {
          executeQuery(
              String.format(
                  "source=%s  | eval a = UNIX_TIMESTAMP('2020-15-26')",
                  TEST_INDEX_DATE_FORMATS_WITH_NULL));
        });
    assertThrows(
        Exception.class,
        () -> {
          executeQuery(
              String.format(
                  "source=%s  | eval a = UNIX_TIMESTAMP('2020-12-26 25:00:00')",
                  TEST_INDEX_DATE_FORMATS_WITH_NULL));
        });
  }

  @Test
  public void testUnixTimestampNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s  |  eval timestamp = UNIX_TIMESTAMP(strict_date_optional_time),"
                    + " date=UNIX_TIMESTAMP(date), time=UNIX_TIMESTAMP(time) | fields timestamp,"
                    + " date, time",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));

    verifySchema(
        actual, schema("timestamp", "double"), schema("date", "double"), schema("time", "double"));
    JSONArray ret = (JSONArray) actual.getJSONArray("datarows").get(0);
    for (int i = 0; i < ret.length(); i++) {
      assertEquals(JSONObject.NULL, ret.get(i));
    }
  }

  @Test
  public void testToSecondsInvalid() {
    assertThrows(
        Exception.class,
        () -> {
          executeQuery(
              String.format(
                  "source=%s  | eval a = UNIX_TIMESTAMP('2020-15-26')",
                  TEST_INDEX_DATE_FORMATS_WITH_NULL));
        });
    assertThrows(
        Exception.class,
        () -> {
          executeQuery(
              String.format(
                  "source=%s  | eval a = UNIX_TIMESTAMP('2020-12-26 25:00:00')",
                  TEST_INDEX_DATE_FORMATS_WITH_NULL));
        });
  }

  @Test
  public void testToSecondsNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s  |  eval timestamp = SECOND(strict_date_optional_time),"
                    + " date=SECOND(date) | fields timestamp, date",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));

    verifySchema(actual, schema("timestamp", "int"), schema("date", "int"));
    JSONArray ret = (JSONArray) actual.getJSONArray("datarows").get(0);
    for (int i = 0; i < ret.length(); i++) {
      assertEquals(JSONObject.NULL, ret.get(i));
    }
  }

  @Test
  public void testDatetimeInvalid() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s  |  eval timestamp = DATETIME('2025-12-01 15:02:61'),"
                    + " date=DATETIME('2025-12-02'), time=DATETIME('16:00:61'), convert1="
                    + " DATETIME('2025-12-01 12:02:61') | fields timestamp, date, time, convert1",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));

    verifySchema(
        actual,
        schema("timestamp", "timestamp"),
        schema("date", "timestamp"),
        schema("time", "timestamp"),
        schema("convert1", "timestamp"));
    JSONArray ret = (JSONArray) actual.getJSONArray("datarows").get(0);
    for (int i = 0; i < ret.length(); i++) {
      assertEquals(JSONObject.NULL, ret.get(i));
    }
  }

  @Test
  public void testStrTDateInvalid1() {
    assertThrows(
        Exception.class,
        () -> {
          executeQuery(
              String.format(
                  "source=%s  | eval a = str_to_date('01,13,2013', '%%d,%%m,%%Y')",
                  TEST_INDEX_DATE_FORMATS_WITH_NULL));
        });
  }

  @Test
  public void testStrTDateInvalid2() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s  |  eval timestamp = STR_TO_DATE('2025-13-02', '2025-13-02')"
                    + "| fields timestamp",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));

    verifySchema(actual, schema("timestamp", "timestamp"));
    JSONArray ret = (JSONArray) actual.getJSONArray("datarows").get(0);
    for (int i = 0; i < ret.length(); i++) {
      assertEquals(JSONObject.NULL, ret.get(i));
    }
  }

  @Test
  public void testConvertTZInvalid() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s  |  eval  a =CONVERT_TZ('2025-13-02', '+10:00', '-10:00'), b"
                    + " =CONVERT_TZ('2025-10-02', '+10:00', '-10:00'), c =CONVERT_TZ('2025-12-02"
                    + " 10:61:61', '+10:00', '-10:00'), d = CONVERT_TZ('2025-12-02 12:61:61',"
                    + " '+10:00:00', '-10:00')| fields a, b, c, d",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));

    verifySchema(
        actual,
        schema("a", "timestamp"),
        schema("b", "timestamp"),
        schema("c", "timestamp"),
        schema("d", "timestamp"));
    JSONArray ret = (JSONArray) actual.getJSONArray("datarows").get(0);
    for (int i = 0; i < ret.length(); i++) {
      assertEquals(JSONObject.NULL, ret.get(i));
    }
  }

  @Test
  public void testAddSubDateNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s  |  eval n1 = ADDDATE(date_time, INTERVAL 1 DAY), "
                    + "n2 = ADDDATE(date, 1), n3 = SUBDATE(time, 1) | fields n1, n2, n3",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));

    verifySchema(
        actual, schema("n1", "timestamp"), schema("n2", "date"), schema("n3", "timestamp"));
    verifyDataRows(actual, rows(null, null, null));
  }

  /**
   * (DATE/TIMESTAMP, DATE/TIMESTAMP/TIME) -> TIMESTAMP
   *
   * <p>(TIME, DATE/TIMESTAMP/TIME) -> TIME
   */
  @Test
  public void testAddTimeNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s  |  eval n1 = ADDTIME(date_time, date_time), "
                    + "n2 = ADDTIME(date, date), n3 = ADDTIME(time, time) | fields n1, n2, n3",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(
        actual, schema("n1", "timestamp"), schema("n2", "timestamp"), schema("n3", "time"));
    verifyDataRows(actual, rows(null, null, null));
  }

  /** (DATE/TIMESTAMP/TIME, INTERVAL) -> TIMESTAMP */
  @Test
  public void testDateAddSubNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s  |  eval n1 = DATE_ADD(date_time, INTERVAL 1 DAY), n2 = DATE_ADD(date,"
                    + " INTERVAL 1 DAY), n3 = DATE_SUB(time, INTERVAL 1 DAY) | fields n1, n2, n3",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(
        actual, schema("n1", "timestamp"), schema("n2", "timestamp"), schema("n3", "timestamp"));
    verifyDataRows(actual, rows(null, null, null));
  }

  /*
  STRING/DATE/TIMESTAMP
   */
  @Test
  public void testDateNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s  |  eval d1 = DATE(date), d2 = DATE(date_time) | fields d1, d2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));

    verifySchema(actual, schema("d1", "date"), schema("d2", "date"));

    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testDateInvalid() {
    Throwable t =
        assertThrowsWithReplace(
            ExpressionEvaluationException.class,
            () ->
                executeQuery(
                    String.format(
                        "source=%s  | eval d1 = DATE('2020-08-26'), d2 = DATE('2020-15-26') |"
                            + " fields d1, d2",
                        TEST_INDEX_DATE_FORMATS_WITH_NULL)));
    verifyErrorMessageContains(t, "date:2020-15-26 in unsupported format, please use 'yyyy-MM-dd'");
  }

  /** STRING/TIME/TIMESTAMP -> INTEGER */
  @Test
  public void testHourNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s  |  eval h2 = HOUR(date_time), h3 = HOUR(time) | fields h2, h3",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("h2", "int"), schema("h3", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testHourInvalid() {
    Throwable t =
        assertThrowsWithReplace(
            ExpressionEvaluationException.class,
            () ->
                executeQuery(
                    String.format(
                        "source=%s  | eval h1 = HOUR('2020-08-26') | fields h1",
                        TEST_INDEX_DATE_FORMATS_WITH_NULL)));
    verifyErrorMessageContains(
        t, "time:2020-08-26 in unsupported format, please use 'HH:mm:ss[.SSSSSSSSS]'");
  }

  @Test
  public void testDayInvalid() {
    Throwable malformMonthException =
        assertThrowsWithReplace(
            ExpressionEvaluationException.class,
            () ->
                executeQuery(
                    String.format(
                        "source=%s  | eval d1 = DAY('2020-13-26') | fields d1",
                        TEST_INDEX_DATE_FORMATS_WITH_NULL)));
    verifyErrorMessageContains(
        malformMonthException, "date:2020-13-26 in unsupported format, please use 'yyyy-MM-dd'");

    Throwable timeAsDateException =
        assertThrowsWithReplace(
            ExpressionEvaluationException.class,
            () ->
                executeQuery(
                    String.format(
                        "source=%s  | eval d2 = DAY('12:00:00') | fields d2",
                        TEST_INDEX_DATE_FORMATS_WITH_NULL)));
    verifyErrorMessageContains(
        timeAsDateException, "date:12:00:00 in unsupported format, please use 'yyyy-MM-dd'");
  }

  @Test
  public void testTimeInvalid() {
    assertThrowsWithReplace(
        ExpressionEvaluationException.class,
        () ->
            executeQuery(
                String.format(
                    "source=%s  | eval t1 = TIME('13:69:00') | fields t1",
                    TEST_INDEX_DATE_FORMATS_WITH_NULL)));
  }

  @Test
  public void testDayOfWeekNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval d1 = DAY_OF_WEEK(date), d2 = DAYOFWEEK(date_time) | fields d1,"
                    + " d2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("d1", "int"), schema("d2", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testDayOfYearNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval d1 = DAY_OF_YEAR(date), d2 = DAYOFYEAR(date_time) | fields d1,"
                    + " d2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("d1", "int"), schema("d2", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testExtractNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval e1 = EXTRACT(YEAR FROM date), e2 = EXTRACT(MONTH FROM date_time),"
                    + " e3 = EXTRACT(HOUR FROM time) | fields e1, e2, e3",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("e1", "bigint"), schema("e2", "bigint"), schema("e3", "bigint"));
    verifyDataRows(actual, rows(null, null, null));
  }

  @Test
  public void testFromDaysNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval from1 = FROM_DAYS(TO_DAYS(date)), from2 ="
                    + " FROM_DAYS(TO_DAYS(date_time)) | fields from1, from2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("from1", "date"), schema("from2", "date"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testFromUnixtimeNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval f1 = FROM_UNIXTIME(UNIX_TIMESTAMP(date_time)), f2 ="
                    + " FROM_UNIXTIME(UNIX_TIMESTAMP(date)) | fields f1, f2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("f1", "timestamp"), schema("f2", "timestamp"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testHourOfDayNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval h1 = HOUR_OF_DAY(time), h2 = HOUR_OF_DAY(date_time) | fields h1,"
                    + " h2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("h1", "int"), schema("h2", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testLastDayNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval l1 = LAST_DAY(date), l2 = LAST_DAY(date_time) | fields l1, l2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("l1", "date"), schema("l2", "date"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testMakedateNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval mk1 = MAKEDATE(YEAR(date), DAYOFYEAR(date)), mk2 ="
                    + " MAKEDATE(YEAR(date_time), DAYOFYEAR(date_time)) | fields mk1, mk2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("mk1", "date"), schema("mk2", "date"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testMaketimeNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval mt1 = MAKETIME(HOUR(date_time), MINUTE(date_time),"
                    + " SECOND(date_time)), mt2 = MAKETIME(HOUR(time), MINUTE(time), SECOND(time))"
                    + " | fields mt1, mt2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("mt1", "time"), schema("mt2", "time"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testAdddateNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval a1 = ADDDATE(date, 3), a2 = ADDDATE(date_time, 3) | fields a1,"
                    + " a2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("a1", "date"), schema("a2", "timestamp"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testAddtimeNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval n1 = ADDTIME(date_time, date_time), n2 = ADDTIME(date, date), n3"
                    + " = ADDTIME(time, time) | fields n1, n2, n3",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(
        actual, schema("n1", "timestamp"), schema("n2", "timestamp"), schema("n3", "time"));
    verifyDataRows(actual, rows(null, null, null));
  }

  @Test
  public void testConvertTzNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval c1 = CONVERT_TZ(date, '+00:00', '+08:00'), c2 = CONVERT_TZ(date,"
                    + " '-03:00', '+01:30') | fields c1, c2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("c1", "timestamp"), schema("c2", "timestamp"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testDateAddNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval da1 = DATE_ADD(date, INTERVAL 1 DAY), da2 = DATE_ADD(date_time,"
                    + " interval 5 month) | fields da1, da2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("da1", "timestamp"), schema("da2", "timestamp"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testDateFormatNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval df1 = DATE_FORMAT(date, 'yyyy-MM-dd'), df2 ="
                    + " DATE_FORMAT(date_time, 'yyyy-MM-dd HH:mm:ss') | fields df1, df2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("df1", "string"), schema("df2", "string"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testDateSubNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval ds1 = DATE_SUB(date, INTERVAL 1 DAY), ds2 = DATE_SUB(date_time,"
                    + " interval 5 month) | fields ds1, ds2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("ds1", "timestamp"), schema("ds2", "timestamp"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testDatediffNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval diff1 = DATEDIFF(date, date), diff2 = DATEDIFF(date_time,"
                    + " date_time) | fields diff1, diff2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("diff1", "bigint"), schema("diff2", "bigint"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testDatetimeNullString() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | where age = 10 | eval d1 = DATETIME(name, '+10:00'), d2 ="
                    + " datetime('2004-02-28 23:00:00-10:00', state)| fields d1, d2",
                TEST_INDEX_STATE_COUNTRY_WITH_NULL));
    verifySchema(actual, schema("d1", "timestamp"), schema("d2", "timestamp"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testDatetimeNullTimestamp() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval d1 = DATETIME(date_time) | fields d1",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("d1", "timestamp"));
    verifyDataRows(actual, rows((Object) null));
  }

  @Test
  public void testDayNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval d1 = DAY(date), d2 = DAY(date_time) | fields d1, d2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("d1", "int"), schema("d2", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testDaynameNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval d1 = DAYNAME(date), d2 = DAYNAME(date_time) | fields d1, d2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("d1", "string"), schema("d2", "string"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testDayOfMonthNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval d1 = DAY_OF_MONTH(date), d2 = DAYOFMONTH(date_time) | fields d1,"
                    + " d2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("d1", "int"), schema("d2", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testMicrosecondNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval m1 = MICROSECOND(time), m2 = MICROSECOND(date_time) | fields m1,"
                    + " m2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("m1", "int"), schema("m2", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testMinuteNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval m1 = MINUTE(time), m2 = MINUTE(date_time) | fields m1, m2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("m1", "int"), schema("m2", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testMinuteOfDayNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval md1 = MINUTE_OF_DAY(time), md2 = MINUTE_OF_DAY(date_time) |"
                    + " fields md1, md2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("md1", "int"), schema("md2", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testMinuteOfHourNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval mh1 = MINUTE_OF_HOUR(time), mh2 = MINUTE_OF_HOUR(date_time) |"
                    + " fields mh1, mh2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("mh1", "int"), schema("mh2", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testMonthNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval mo1 = MONTH(date), mo2 = MONTH(date_time) | fields mo1, mo2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("mo1", "int"), schema("mo2", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testMonthOfYearNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval mo1 = MONTH_OF_YEAR(date), mo2 = MONTH_OF_YEAR(date_time) |"
                    + " fields mo1, mo2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("mo1", "int"), schema("mo2", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testMonthnameNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval mn1 = MONTHNAME(date), mn2 = MONTHNAME(date_time) | fields mn1,"
                    + " mn2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("mn1", "string"), schema("mn2", "string"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testPeriodAddDiffNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | where key='null' | head 1 | eval pa1 = PERIOD_ADD(`int`, 3), pa2 ="
                    + " PERIOD_DIFF(`int`, `int`) | fields pa1, pa2",
                TEST_INDEX_NULL_MISSING));

    verifySchema(actual, schema("pa1", "int"), schema("pa2", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testQuarterNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval q1 = QUARTER(date), q2 = QUARTER(date_time) | fields q1, q2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("q1", "int"), schema("q2", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testSecToTimeNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval st1 = SEC_TO_TIME(UNIX_TIMESTAMP(date_time)), st2 ="
                    + " SEC_TO_TIME(UNIX_TIMESTAMP(date)) | fields st1, st2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("st1", "time"), schema("st2", "time"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testSecondNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval s1 = SECOND(time), s2 = SECOND(date_time) | fields s1, s2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("s1", "int"), schema("s2", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testSecondOfMinuteNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval s1 = SECOND_OF_MINUTE(time), s2 = SECOND_OF_MINUTE(date_time) |"
                    + " fields s1, s2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("s1", "int"), schema("s2", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testStrToDateNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval s = STR_TO_DATE(MONTHNAME(date_time), '%%M') | fields s",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("s", "timestamp"));
    verifyDataRows(actual, rows((Object) null));
  }

  @Test
  public void testSubdateNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval sd1 = SUBDATE(date, 3), sd2 = SUBDATE(date_time, 5) | fields sd1,"
                    + " sd2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("sd1", "date"), schema("sd2", "timestamp"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testSubtimeNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval s1 = SUBTIME(date_time, date_time), s2 = SUBTIME(date, date), s3"
                    + " = SUBTIME(time, time) | fields s1, s2, s3",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(
        actual, schema("s1", "timestamp"), schema("s2", "timestamp"), schema("s3", "time"));
    verifyDataRows(actual, rows(null, null, null));
  }

  @Test
  public void testTimeNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval t1 = TIME(date_time) | fields t1",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("t1", "time"));
    verifyDataRows(actual, rows((Object) null));
  }

  @Test
  public void testTimeFormatNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval tf1 = TIME_FORMAT(time, '%%H:%%i:%%s') | fields tf1",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("tf1", "string"));
    verifyDataRows(actual, rows((Object) null));
  }

  @Test
  public void testTimeToSecNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval ts1 = TIME_TO_SEC(time) | fields ts1",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("ts1", "bigint"));
    verifyDataRows(actual, rows((Object) null));
  }

  @Test
  public void testTimediffNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval td1 = TIMEDIFF(time, time) | fields td1",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("td1", "time"));
    verifyDataRows(actual, rows((Object) null));
  }

  @Test
  public void testTimestampNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval t1 = TIMESTAMP(date, time), t2 = TIMESTAMP(date_time) | fields"
                    + " t1, t2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("t1", "timestamp"), schema("t2", "timestamp"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testTimestampaddNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval ta1 = TIMESTAMPADD(MONTH, 2, date), ta2 = TIMESTAMPADD(HOUR, 3,"
                    + " date_time) | fields ta1, ta2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("ta1", "timestamp"), schema("ta2", "timestamp"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testTimestampdiffNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval td1 = TIMESTAMPDIFF(DAY, date, date_time), td2 ="
                    + " TIMESTAMPDIFF(HOUR, date_time, date_time) | fields td1, td2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("td1", "bigint"), schema("td2", "bigint"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testToDaysNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval td1 = TO_DAYS(date), td2 = TO_DAYS(date_time) | fields td1, td2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("td1", "bigint"), schema("td2", "bigint"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testWeekNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval w1 = WEEK(date), w2 = WEEK(date_time) | fields w1, w2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("w1", "int"), schema("w2", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testWeekdayNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval wd1 = WEEKDAY(date), wd2 = WEEKDAY(date_time) | fields wd1, wd2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("wd1", "int"), schema("wd2", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testWeekOfYearNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval wy1 = WEEK_OF_YEAR(date), wy2 = WEEK_OF_YEAR(date_time) | fields"
                    + " wy1, wy2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("wy1", "int"), schema("wy2", "int"));
    verifyDataRows(actual, rows(null, null));
  }

  @Test
  public void testYearNull() throws IOException {
    JSONObject actual =
        executeQuery(
            String.format(
                "source=%s | eval y1 = YEAR(date), y2 = YEAR(date_time) | fields y1, y2",
                TEST_INDEX_DATE_FORMATS_WITH_NULL));
    verifySchema(actual, schema("y1", "int"), schema("y2", "int"));
    verifyDataRows(actual, rows(null, null));
  }
}
