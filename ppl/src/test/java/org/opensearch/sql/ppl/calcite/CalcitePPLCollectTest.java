/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.ppl.calcite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.test.CalciteAssert;
import org.junit.Test;
import org.opensearch.sql.calcite.plan.rel.LogicalCollectSink;
import org.opensearch.sql.common.antlr.SyntaxCheckException;
import org.opensearch.sql.exception.SemanticCheckException;

/**
 * Plan-shape tests for the {@code collect} command (no cluster): {@code visitCollect} builds a
 * {@link LogicalCollectSink} write sink returning (rows_written, rows_failed), testmode is a
 * no-write dry run, and bad destinations, options and positions are refused at plan time.
 */
public class CalcitePPLCollectTest extends CalcitePPLAbstractTest {

  public CalcitePPLCollectTest() {
    super(CalciteAssert.SchemaSpec.SCOTT_WITH_TEMPORAL);
  }

  @Test
  public void testCollectBuildsLogicalCollectWithCountsRow() {
    RelNode root = getRelNode("source=EMP | collect index=DEPT");

    assertTrue(root instanceof LogicalCollectSink);
    assertEquals(
        "[scott, DEPT]", ((LogicalCollectSink) root).getTable().getQualifiedName().toString());
    assertEquals(List.of("rows_written", "rows_failed"), root.getRowType().getFieldNames());
    verifyLogical(
        root,
        "LogicalCollectSink(table=[[scott, DEPT]])\n"
            + "  LogicalTableScan(table=[[scott, EMP]])\n");
  }

  @Test
  public void testCollectAfterStats() {
    RelNode root = getRelNode("source=EMP | stats count() by DEPTNO | collect index=DEPT");
    assertTrue(root instanceof LogicalCollectSink);
    assertTrue(root.explain().contains("LogicalAggregate"));
  }

  @Test
  public void testCollectIdOption() {
    RelNode root =
        getRelNode(
            "source=EMP | stats count() as c by DEPTNO, JOB | collect index=DEPT id=DEPTNO,JOB");
    assertEquals(List.of("DEPTNO", "JOB"), ((LogicalCollectSink) root).getIdFields());
    verifyLogical(
        root,
        "LogicalCollectSink(table=[[scott, DEPT]], id=[[DEPTNO, JOB]])\n"
            + "  LogicalProject(c=[$2], DEPTNO=[$0], JOB=[$1])\n"
            + "    LogicalAggregate(group=[{0, 1}], c=[COUNT()])\n"
            + "      LogicalProject(DEPTNO=[$7], JOB=[$2])\n"
            + "        LogicalTableScan(table=[[scott, EMP]])\n");
  }

  @Test
  public void testCollectIdFieldMustBeAnInputColumn() {
    SemanticCheckException e =
        assertThrows(
            SemanticCheckException.class,
            () -> getRelNode("source=EMP | fields ENAME | collect index=DEPT id=EMPNO"));
    assertTrue(e.getMessage(), e.getMessage().contains("EMPNO"));
  }

  @Test
  public void testCollectUnknownOptionRefused() {
    assertThrows(
        SyntaxCheckException.class, () -> getRelNode("source=EMP | collect index=DEPT marker='m'"));
  }

  /** Would have caught T7: `collect | head 2` wrote the whole input but returned 2 rows. */
  @Test
  public void testCollectNotLastRefused() {
    SyntaxCheckException e =
        assertThrows(
            SyntaxCheckException.class,
            () -> getRelNode("source=EMP | collect index=DEPT | head 2"));
    assertTrue(e.getMessage(), e.getMessage().contains("last command"));
  }

  /** Would have caught T7: collect inside append [...] wrote silently. */
  @Test
  public void testCollectInSubsearchRefused() {
    assertThrows(
        SyntaxCheckException.class,
        () -> getRelNode("source=EMP | append [ source=EMP | collect index=DEPT ]"));
  }

  /** The dropped stamp options must not leave reserved words behind. */
  @Test
  public void testHostAndMarkerAreOrdinaryFieldNames() {
    RelNode root =
        getRelNode("source=EMP | eval host = ENAME, marker = JOB | stats count() by host, marker");
    assertTrue(root.getRowType().getFieldNames().contains("host"));
    assertTrue(root.getRowType().getFieldNames().contains("marker"));
  }

  @Test
  public void testCollectMissingDestinationFailsAtPlanTime() {
    SemanticCheckException e =
        assertThrows(
            SemanticCheckException.class,
            () -> getRelNode("source=EMP | collect index=NONEXISTENT_DEST"));
    assertTrue(e.getMessage(), e.getMessage().contains("NONEXISTENT_DEST"));
  }

  @Test
  public void testCollectDotIndexRefusedAtPlanTime() {
    SemanticCheckException e =
        assertThrows(
            SemanticCheckException.class,
            () -> getRelNode("source=EMP | collect index=.system_index"));
    assertTrue(e.getMessage(), e.getMessage().contains("system or hidden"));
  }

  @Test
  public void testCollectTestmodeSkipsWriteAndPreExistenceCheck() {
    RelNode root = getRelNode("source=EMP | collect index=NONEXISTENT_DEST testmode=true");
    assertFalse(root.explain(), root instanceof LogicalCollectSink);
    assertTrue(root.getRowType().getFieldNames().contains("ENAME"));
  }
}
