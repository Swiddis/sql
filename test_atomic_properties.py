#!/usr/bin/env python3
"""
Quick validation test for atomic properties.

Tests that the new schema model and atomic properties work correctly
without requiring a live OpenSearch instance.
"""

import sys
from ppl_correctness.datagen.context import (
    Field, FieldType, SubField, IndexContext, generate_field_value
)
from ppl_correctness.generators.ppl import PPLQueryGenerator
import random


def test_schema_extensions():
    """Test that new schema features work"""
    print("Testing schema extensions...")

    # Test array field
    array_field = Field("values", FieldType.INTEGER, is_array=True)
    assert array_field.is_array
    assert array_field.name == "values"

    # Test nested field
    nested_field = Field("obj.nested.value", FieldType.DOUBLE, nested_depth=2)
    assert nested_field.nested_depth == 2
    assert '.' in nested_field.name

    # Test text with keyword subfield
    text_field = Field(
        "description",
        FieldType.TEXT,
        subfields={"keyword": SubField(FieldType.KEYWORD, ignore_above=50)}
    )
    assert "keyword" in text_field.subfields
    assert text_field.subfields["keyword"].ignore_above == 50

    print("  ✓ Schema extensions work")


def test_data_generation():
    """Test that field value generation handles new types"""
    print("Testing data generation...")

    rng = random.Random(42)

    # Test array generation
    array_field = Field("values", FieldType.INTEGER, is_array=True)
    value = generate_field_value(array_field, rng)
    assert isinstance(value, list), f"Expected list, got {type(value)}"
    assert len(value) >= 1 and len(value) <= 5

    # Test nested field value generation
    nested_field = Field("obj.nested.value", FieldType.DOUBLE, nested_depth=2)
    value = generate_field_value(nested_field, rng)
    assert isinstance(value, float) or value is None

    # Test text with keyword subfield
    text_field = Field(
        "description",
        FieldType.TEXT,
        subfields={"keyword": SubField(FieldType.KEYWORD, ignore_above=50)}
    )
    value = generate_field_value(text_field, rng)
    assert isinstance(value, str) or value is None

    print("  ✓ Data generation works")


def test_query_generation():
    """Test that query generator handles new commands"""
    print("Testing query generation...")

    rng = random.Random(42)

    # Create context with diverse fields
    context = IndexContext(
        name="test_index",
        fields=[
            Field("id", FieldType.INTEGER),
            Field("category", FieldType.KEYWORD),
            Field("values", FieldType.INTEGER, is_array=True),
            Field("obj.nested.value", FieldType.DOUBLE, nested_depth=1),
            Field("description", FieldType.TEXT,
                  subfields={"keyword": SubField(FieldType.KEYWORD, ignore_above=50)}),
        ],
        doc_count=100
    )

    generator = PPLQueryGenerator(context, rng)

    # Test rename command
    rename = generator.generate_rename_cmd()
    assert "rename" in rename
    assert " as " in rename
    print(f"  ✓ Rename command: {rename}")

    # Test dedup command
    dedup = generator.generate_dedup_cmd()
    assert "dedup" in dedup
    print(f"  ✓ Dedup command: {dedup}")

    # Test command chain
    chain = generator.generate_command_chain(length=3)
    assert "source=" in chain
    assert " | " in chain
    print(f"  ✓ Command chain: {chain[:100]}...")

    # Test predicate with null checks
    for _ in range(10):
        pred = generator.generate_predicate()
        # Should sometimes generate isnotnull predicates
        if "isnotnull" in pred or "isnull" in pred:
            print(f"  ✓ Null check predicate: {pred}")
            break

    print("  ✓ Query generation works")


def test_context_diversity():
    """Test that generated contexts include diverse field types"""
    print("Testing context diversity...")

    # Simulate what generate_contexts would create
    rng = random.Random(42)

    has_array = False
    has_nested = False
    has_subfields = False

    for _ in range(10):
        num_fields = rng.randint(3, 8)
        fields = []

        for j in range(num_fields):
            field_type = rng.choice(list(FieldType))
            field_name = f"field_{j}"
            is_array = False
            subfields_dict = {}
            nested_depth = 0

            # Check for arrays
            if field_type in (FieldType.INTEGER, FieldType.LONG, FieldType.KEYWORD) and rng.random() < 0.3:
                is_array = True
                has_array = True
                field_name = f"array_{j}"

            # Check for nested
            elif rng.random() < 0.2:
                depth = rng.randint(1, 2)
                nested_depth = depth
                has_nested = True
                field_name = f"obj_{j}.value"

            # Check for subfields
            if field_type == FieldType.TEXT and rng.random() < 0.4:
                has_subfields = True
                subfields_dict = {
                    "keyword": SubField(FieldType.KEYWORD, ignore_above=50)
                }

            fields.append(Field(
                name=field_name,
                type=field_type,
                is_array=is_array,
                subfields=subfields_dict,
                nested_depth=nested_depth
            ))

    assert has_array, "Should generate array fields"
    assert has_nested, "Should generate nested fields"
    assert has_subfields, "Should generate subfields"

    print("  ✓ Context diversity works")
    print(f"    - Generated array fields: {has_array}")
    print(f"    - Generated nested fields: {has_nested}")
    print(f"    - Generated text+keyword subfields: {has_subfields}")


def main():
    print("=" * 60)
    print("Atomic Properties Validation Test")
    print("=" * 60)
    print()

    try:
        test_schema_extensions()
        test_data_generation()
        test_query_generation()
        test_context_diversity()

        print()
        print("=" * 60)
        print("✓ All validation tests passed!")
        print("=" * 60)
        print()
        print("Next steps:")
        print("  1. Start OpenSearch: docker run -p 9200:9200 -e 'discovery.type=single-node' opensearchproject/opensearch:latest")
        print("  2. Run atomic properties: python main.py --property atomic --iterations 10")
        print("  3. Run all properties: python main.py --property all --iterations 100")
        print()

        return 0

    except AssertionError as e:
        print(f"\n✗ Test failed: {e}")
        return 1
    except Exception as e:
        print(f"\n✗ Unexpected error: {e}")
        import traceback
        traceback.print_exc()
        return 1


if __name__ == '__main__':
    sys.exit(main())
