package org.example.calcite.poc.table;

import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;

import java.util.List;

/**
 * A simple RelOptSchema implementation for the POC.
 */
public class SimpleSchema implements RelOptSchema {

    @Override
    public RelOptTable getTableForMember(List<String> names) {
        return null;
    }

    @Override
    public RelDataTypeFactory getTypeFactory() {
        return null;
    }

    @Override
    public void registerRules(org.apache.calcite.plan.RelOptPlanner planner) {
        // No rules to register
    }
}
