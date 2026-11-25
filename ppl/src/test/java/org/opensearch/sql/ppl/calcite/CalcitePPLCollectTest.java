/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.ppl.calcite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;

import java.util.List;
import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.test.CalciteAssert;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Programs;
import org.junit.Test;
import org.opensearch.sql.calcite.plan.rel.CollectTarget;
import org.opensearch.sql.calcite.plan.rel.LogicalCollectSink;
import org.opensearch.sql.common.antlr.SyntaxCheckException;
import org.opensearch.sql.common.setting.Settings;
import org.opensearch.sql.exception.SemanticCheckException;

/**
 * Plan-shape tests for the {@code collect} command (no cluster): {@code visitCollect} builds a
 * {@link LogicalCollectSink} write sink returning (rows_written, rows_failed), testmode is a
 * no-write dry run with the same checks, and bad destinations, options and positions are refused at
 * plan time.
 */
public class CalcitePPLCollectTest extends CalcitePPLAbstractTest {

  public CalcitePPLCollectTest() {
    super(CalciteAssert.SchemaSpec.SCOTT_WITH_TEMPORAL);
  }

  /** A writable destination stub, and one whose name resolves to a hidden index. */
  @Override
  protected Frameworks.ConfigBuilder config(CalciteAssert.SchemaSpec... schemaSpecs) {
    final SchemaPlus rootSchema = Frameworks.createRootSchema(true);
    final SchemaPlus schema = CalciteAssert.addSchema(rootSchema, schemaSpecs);
    schema.add("DEST", new DestTable(false));
    schema.add("HIDDEN_ALIAS", new DestTable(true));
    return Frameworks.newConfigBuilder()
        .parserConfig(SqlParser.Config.DEFAULT)
        .defaultSchema(schema)
        .traitDefs((List<RelTraitDef>) null)
        .programs(Programs.heuristicJoinOrder(Programs.RULE_SET, true, 2));
  }

  private static class DestTable extends AbstractTable implements CollectTarget {
    private final boolean resolvesToHidden;

    DestTable(boolean resolvesToHidden) {
      this.resolvesToHidden = resolvesToHidden;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
      return typeFactory.builder().add("x", SqlTypeName.INTEGER).build();
    }

    @Override
    public void checkCollectTarget() {
      if (resolvesToHidden) {
        throw new SemanticCheckException("collect cannot write to system or hidden index [.h]");
      }
    }
  }

  private void assertSemanticError(String ppl, String expected) {
    SemanticCheckException e = assertThrows(SemanticCheckException.class, () -> getRelNode(ppl));
    assertTrue(e.getMessage(), e.getMessage().contains(expected));
  }

  private void assertSyntaxError(String ppl, String expected) {
    SyntaxCheckException e = assertThrows(SyntaxCheckException.class, () -> getRelNode(ppl));
    assertTrue(e.getMessage(), e.getMessage().contains(expected));
  }

  @Test
  public void testCollectBuildsLogicalCollectWithCountsRow() {
    RelNode root = getRelNode("source=EMP | collect index=DEST");

    assertTrue(root instanceof LogicalCollectSink);
    assertEquals(
        "[scott, DEST]", ((LogicalCollectSink) root).getTable().getQualifiedName().toString());
    assertEquals(List.of("rows_written", "rows_failed"), root.getRowType().getFieldNames());
    verifyLogical(
        root,
        "LogicalCollectSink(table=[[scott, DEST]])\n"
            + "  LogicalTableScan(table=[[scott, EMP]])\n");
  }

  @Test
  public void testCollectAfterStats() {
    RelNode root = getRelNode("source=EMP | stats count() by DEPTNO | collect index=DEST");
    assertTrue(root instanceof LogicalCollectSink);
    assertTrue(root.explain().contains("LogicalAggregate"));
  }

  @Test
  public void testCollectIdOption() {
    RelNode root =
        getRelNode(
            "source=EMP | stats count() as c by DEPTNO, JOB | collect index=DEST id=DEPTNO,JOB");
    assertEquals(List.of("DEPTNO", "JOB"), ((LogicalCollectSink) root).getIdFields());
    verifyLogical(
        root,
        "LogicalCollectSink(table=[[scott, DEST]], id=[[DEPTNO, JOB]])\n"
            + "  LogicalProject(c=[$2], DEPTNO=[$0], JOB=[$1])\n"
            + "    LogicalAggregate(group=[{0, 1}], c=[COUNT()])\n"
            + "      LogicalProject(DEPTNO=[$7], JOB=[$2])\n"
            + "        LogicalTableScan(table=[[scott, EMP]])\n");
  }

  /** Bug 1: the sink keeps the query's column names, so a planner rewrite cannot lose a rename. */
  @Test
  public void testCollectSinkCapturesQueryColumnNames() {
    LogicalCollectSink root =
        (LogicalCollectSink)
            getRelNode(
                "source=EMP | stats count() as c by DEPTNO | rename DEPTNO as dept"
                    + " | collect index=DEST id=dept");
    assertEquals(List.of("c", "dept"), root.getFieldNames());
    assertEquals(
        List.of("c", "dept"),
        ((LogicalCollectSink) root.copy(root.getTraitSet(), root.getInputs())).getFieldNames());
  }

  @Test
  public void testCollectIdFieldMustBeAnInputColumn() {
    assertSemanticError("source=EMP | fields ENAME | collect index=DEST id=EMPNO", "EMPNO");
  }

  @Test
  public void testCollectUnknownOptionRefused() {
    assertThrows(
        SyntaxCheckException.class, () -> getRelNode("source=EMP | collect index=DEST marker='m'"));
  }

  /** Would have caught T7: `collect | head 2` wrote the whole input but returned 2 rows. */
  @Test
  public void testCollectNotLastRefused() {
    assertSyntaxError("source=EMP | collect index=DEST | head 2", "last command");
  }

  /** Would have caught T7: collect inside append [...] wrote silently. */
  @Test
  public void testCollectInSubsearchRefused() {
    assertThrows(
        SyntaxCheckException.class,
        () -> getRelNode("source=EMP | append [ source=EMP | collect index=DEST ]"));
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
    assertSemanticError(
        "source=EMP | collect index=NONEXISTENT_DEST", "[NONEXISTENT_DEST] does not exist");
  }

  @Test
  public void testCollectDotIndexRefusedAtPlanTime() {
    assertSemanticError("source=EMP | collect index=.system_index", "system or hidden");
  }

  /** Bug 4: the destination is parsed like source=, so a quoted name resolves to the index. */
  @Test
  public void testCollectQuotedDestinationResolves() {
    LogicalCollectSink root = (LogicalCollectSink) getRelNode("source=EMP | collect index=`DEST`");
    assertEquals("[scott, DEST]", root.getTable().getQualifiedName().toString());
  }

  /** Bug 4: `index=d as x` used to be read as the index `dasx`. */
  @Test
  public void testCollectDestinationAliasRefused() {
    assertSyntaxError("source=EMP | collect index=DEST as x", "does not take an alias");
  }

  /** Bug 4: wildcards, comma lists and _all fan a write out; refuse them at analysis. */
  @Test
  public void testCollectMultiIndexDestinationsRefused() {
    for (String dest : List.of("DE*", "DEST, DEPT", "_all")) {
      assertSemanticError("source=EMP | collect index=" + dest, "must be a single index or alias");
    }
  }

  /** Bug 4: the resolved-target check (alias, date math, index.hidden) runs at analysis. */
  @Test
  public void testCollectDestinationResolvingToHiddenIndexRefused() {
    assertSemanticError("source=EMP | collect index=HIDDEN_ALIAS", "system or hidden");
    assertSemanticError(
        "source=EMP | collect index=HIDDEN_ALIAS testmode=true", "system or hidden");
  }

  /** Bug 9: a table that is not an OpenSearch index gets a clear error, not CannotPlanException. */
  @Test
  public void testCollectNonOpenSearchDestinationRefused() {
    assertSemanticError("source=EMP | collect index=DEPT", "is not an OpenSearch index");
  }

  @Test
  public void testCollectTestmodeDoesNotWrite() {
    RelNode root = getRelNode("source=EMP | collect index=DEST testmode=true");
    assertFalse(root.explain(), root instanceof LogicalCollectSink);
    assertTrue(root.getRowType().getFieldNames().contains("ENAME"));
  }

  /** Bug 8: a dry run must fail where the write would: missing destination. */
  @Test
  public void testCollectTestmodeChecksDestinationExists() {
    assertSemanticError(
        "source=EMP | collect index=NONEXISTENT_DEST testmode=true", "does not exist");
  }

  /** Bug 8: a dry run must fail where the write would: id field not in the input. */
  @Test
  public void testCollectTestmodeChecksIdFields() {
    assertSemanticError(
        "source=EMP | fields ENAME | collect index=DEST id=EMPNO testmode=true", "EMPNO");
  }

  /** Bug 8: the writer skips all-null rows, so the preview filters them out too. */
  @Test
  public void testCollectTestmodeSkipsAllNullRows() {
    RelNode root = getRelNode("source=EMP | fields ENAME, COMM | collect index=DEST testmode=true");
    verifyLogical(
        root,
        "LogicalFilter(condition=[OR(IS NOT NULL($0), IS NOT NULL($1))])\n"
            + "  LogicalProject(ENAME=[$1], COMM=[$6])\n"
            + "    LogicalTableScan(table=[[scott, EMP]])\n");
    verifyPPLToSparkSQL(
        root,
        "SELECT *\n"
            + "FROM (SELECT `ENAME`, `COMM`\n"
            + "FROM `scott`.`EMP`) `t`\n"
            + "WHERE `ENAME` IS NOT NULL OR `COMM` IS NOT NULL");
  }

  /** Bug 13: every duplicate option is refused the same way (index= already was). */
  @Test
  public void testCollectDuplicateOptionsRefused() {
    assertSyntaxError(
        "source=EMP | collect index=DEST testmode=true testmode=false", "more than once");
    assertSyntaxError("source=EMP | collect index=DEST id=EMPNO id=ENAME", "more than once");
    assertThrows(
        SyntaxCheckException.class, () -> getRelNode("source=EMP | collect index=DEST index=DEPT"));
  }

  /** Bug 14: the input of the sink translates to Spark SQL like any other pipeline. */
  @Test
  public void testCollectInputToSparkSql() {
    LogicalCollectSink root =
        (LogicalCollectSink)
            getRelNode("source=EMP | stats count() as c by DEPTNO | collect index=DEST");
    verifyPPLToSparkSQL(
        root.getInput(), "SELECT COUNT(*) `c`, `DEPTNO`\nFROM `scott`.`EMP`\nGROUP BY `DEPTNO`");
  }

  /** plugins.ppl.write_commands.enable=false refuses collect, dry run included. */
  @Test
  public void testCollectRefusedWhenWriteCommandsDisabled() {
    doReturn(false).when(settings).getSettingValue(Settings.Key.PPL_WRITE_COMMANDS_ENABLED);
    for (String ppl :
        List.of(
            "source=EMP | collect index=DEST", "source=EMP | collect index=DEST testmode=true")) {
      assertSemanticError(ppl, "plugins.ppl.write_commands.enable");
    }
  }

  @Test
  public void testCollectAllowedWhenWriteCommandsEnabled() {
    doReturn(true).when(settings).getSettingValue(Settings.Key.PPL_WRITE_COMMANDS_ENABLED);
    assertTrue(getRelNode("source=EMP | collect index=DEST") instanceof LogicalCollectSink);
  }
}
