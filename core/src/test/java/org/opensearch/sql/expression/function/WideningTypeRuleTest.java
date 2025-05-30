/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.expression.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.opensearch.sql.data.type.ExprCoreType.BOOLEAN;
import static org.opensearch.sql.data.type.ExprCoreType.BYTE;
import static org.opensearch.sql.data.type.ExprCoreType.DATE;
import static org.opensearch.sql.data.type.ExprCoreType.DOUBLE;
import static org.opensearch.sql.data.type.ExprCoreType.FLOAT;
import static org.opensearch.sql.data.type.ExprCoreType.INTEGER;
import static org.opensearch.sql.data.type.ExprCoreType.IP;
import static org.opensearch.sql.data.type.ExprCoreType.LONG;
import static org.opensearch.sql.data.type.ExprCoreType.SHORT;
import static org.opensearch.sql.data.type.ExprCoreType.STRING;
import static org.opensearch.sql.data.type.ExprCoreType.TIME;
import static org.opensearch.sql.data.type.ExprCoreType.TIMESTAMP;
import static org.opensearch.sql.data.type.ExprCoreType.UNDEFINED;
import static org.opensearch.sql.data.type.WideningTypeRule.IMPOSSIBLE_WIDENING;
import static org.opensearch.sql.data.type.WideningTypeRule.TYPE_EQUAL;

import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Table;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opensearch.sql.data.type.ExprCoreType;
import org.opensearch.sql.data.type.WideningTypeRule;
import org.opensearch.sql.exception.ExpressionEvaluationException;

class WideningTypeRuleTest {
  private static final Table<ExprCoreType, ExprCoreType, Integer> numberWidenRule =
      new ImmutableTable.Builder<ExprCoreType, ExprCoreType, Integer>()
          .put(BYTE, SHORT, 1)
          .put(BYTE, INTEGER, 2)
          .put(BYTE, LONG, 3)
          .put(BYTE, FLOAT, 4)
          .put(BYTE, DOUBLE, 5)
          .put(SHORT, INTEGER, 1)
          .put(SHORT, LONG, 2)
          .put(SHORT, FLOAT, 3)
          .put(SHORT, DOUBLE, 4)
          .put(INTEGER, LONG, 1)
          .put(INTEGER, FLOAT, 2)
          .put(INTEGER, DOUBLE, 3)
          .put(LONG, FLOAT, 1)
          .put(LONG, DOUBLE, 2)
          .put(FLOAT, DOUBLE, 1)
          .put(STRING, BOOLEAN, 1)
          .put(STRING, TIMESTAMP, 1)
          .put(STRING, DATE, 1)
          .put(STRING, TIME, 1)
          .put(STRING, IP, 1)
          .put(DATE, TIMESTAMP, 1)
          .put(TIME, TIMESTAMP, 1)
          .put(UNDEFINED, BYTE, 1)
          .put(UNDEFINED, SHORT, 2)
          .put(UNDEFINED, INTEGER, 3)
          .put(UNDEFINED, LONG, 4)
          .put(UNDEFINED, FLOAT, 5)
          .put(UNDEFINED, DOUBLE, 6)
          .build();

  private static Stream<Arguments> distanceArguments() {
    List<ExprCoreType> exprTypes = ExprCoreType.coreTypes();
    return Lists.cartesianProduct(exprTypes, exprTypes).stream()
        .map(
            list -> {
              ExprCoreType type1 = list.get(0);
              ExprCoreType type2 = list.get(1);
              if (type1 == type2) {
                return Arguments.of(type1, type2, TYPE_EQUAL);
              } else if (numberWidenRule.contains(type1, type2)) {
                return Arguments.of(type1, type2, numberWidenRule.get(type1, type2));
              } else {
                return Arguments.of(type1, type2, IMPOSSIBLE_WIDENING);
              }
            });
  }

  private static Stream<Arguments> validMaxTypes() {
    List<ExprCoreType> exprTypes = ExprCoreType.coreTypes();
    return Lists.cartesianProduct(exprTypes, exprTypes).stream()
        .map(
            list -> {
              ExprCoreType type1 = list.get(0);
              ExprCoreType type2 = list.get(1);
              if (type1 == type2) {
                return Arguments.of(type1, type2, type1);
              } else if (numberWidenRule.contains(type1, type2)) {
                return Arguments.of(type1, type2, type2);
              } else if (numberWidenRule.contains(type2, type1)) {
                return Arguments.of(type1, type2, type1);
              } else {
                return Arguments.of(type1, type2, null);
              }
            });
  }

  @ParameterizedTest
  @MethodSource("distanceArguments")
  public void distance(ExprCoreType v1, ExprCoreType v2, Integer expected) {
    assertEquals(expected, WideningTypeRule.distance(v1, v2));
  }

  @ParameterizedTest
  @MethodSource("validMaxTypes")
  public void max(ExprCoreType v1, ExprCoreType v2, ExprCoreType expected) {
    if (null == expected) {
      ExpressionEvaluationException exception =
          assertThrows(ExpressionEvaluationException.class, () -> WideningTypeRule.max(v1, v2));
      assertEquals(String.format("no max type of %s and %s ", v1, v2), exception.getMessage());
    } else {
      assertEquals(expected, WideningTypeRule.max(v1, v2));
    }
  }

  @Test
  public void maxOfUndefinedAndOthersShouldBeTheOtherType() {
    ExprCoreType.coreTypes()
        .forEach(type -> assertEquals(type, WideningTypeRule.max(type, UNDEFINED)));
    ExprCoreType.coreTypes()
        .forEach(type -> assertEquals(type, WideningTypeRule.max(UNDEFINED, type)));
  }
}
