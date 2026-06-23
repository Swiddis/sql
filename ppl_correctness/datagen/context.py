"""
Index context and data generation for PPL testing.

An IndexContext contains:
- Index name
- Schema (field names and types)
- Sample data
"""

from dataclasses import dataclass, field
from typing import List, Dict, Any, Optional
from enum import Enum
import random
from opensearchpy import OpenSearch


class FieldType(Enum):
    """PPL-supported field types"""
    INTEGER = "integer"
    LONG = "long"
    FLOAT = "float"
    DOUBLE = "double"
    BOOLEAN = "boolean"
    TEXT = "text"
    KEYWORD = "keyword"
    DATE = "date"
    IP = "ip"


@dataclass
class SubField:
    """Subfield definition for multi-field mappings (e.g., text with keyword)"""
    type: FieldType
    ignore_above: Optional[int] = None


@dataclass
class Field:
    """Schema field definition"""
    name: str
    type: FieldType
    nullable: bool = True
    is_array: bool = False  # Whether field holds array values
    subfields: Dict[str, SubField] = None  # e.g., {"keyword": SubField(KEYWORD, ignore_above=50)}
    nested_depth: int = 0  # 0 = flat field, >0 = nested (name uses dot notation)

    def __post_init__(self):
        if self.subfields is None:
            self.subfields = {}


@dataclass
class IndexContext:
    """Test index with schema and data"""
    name: str
    fields: List[Field]
    doc_count: int = 100

    def get_numeric_fields(self) -> List[Field]:
        """Return fields that support arithmetic operations"""
        return [f for f in self.fields if f.type in
                (FieldType.INTEGER, FieldType.LONG, FieldType.FLOAT, FieldType.DOUBLE)]

    def get_groupable_fields(self) -> List[Field]:
        """Return fields suitable for grouping (low cardinality)"""
        return [f for f in self.fields if f.type in
                (FieldType.KEYWORD, FieldType.BOOLEAN, FieldType.INTEGER)]

    def get_comparable_fields(self) -> List[Field]:
        """Return fields that support comparison operators"""
        return [f for f in self.fields if f.type != FieldType.TEXT]


def generate_field_value(field: Field, rng: random.Random) -> Any:
    """Generate a random value for a field"""
    if field.nullable and rng.random() < 0.1:
        return None

    # Generate base scalar value
    base_value = None
    if field.type == FieldType.INTEGER:
        base_value = rng.randint(-1000, 1000)
    elif field.type == FieldType.LONG:
        base_value = rng.randint(-10000, 10000)
    elif field.type in (FieldType.FLOAT, FieldType.DOUBLE):
        base_value = rng.uniform(-100.0, 100.0)
    elif field.type == FieldType.BOOLEAN:
        base_value = rng.choice([True, False])
    elif field.type == FieldType.TEXT:
        words = ['apple', 'banana', 'cherry', 'date', 'elderberry']
        text = ' '.join(rng.choices(words, k=rng.randint(1, 3)))
        # For text fields with keyword subfields, sometimes generate long text
        if field.subfields and 'keyword' in field.subfields:
            subfield = field.subfields['keyword']
            if subfield.ignore_above and rng.random() < 0.3:
                # Generate text longer than ignore_above to trigger the bug
                text = text + " " + "x" * (subfield.ignore_above + 10)
        base_value = text
    elif field.type == FieldType.KEYWORD:
        base_value = rng.choice(['red', 'green', 'blue', 'yellow', 'purple'])
    elif field.type == FieldType.DATE:
        # Generate ISO date string
        year = rng.randint(2020, 2024)
        month = rng.randint(1, 12)
        day = rng.randint(1, 28)
        base_value = f"{year}-{month:02d}-{day:02d}"
    elif field.type == FieldType.IP:
        base_value = f"{rng.randint(1,255)}.{rng.randint(0,255)}.{rng.randint(0,255)}.{rng.randint(0,255)}"

    # Convert to array if needed
    if field.is_array and base_value is not None:
        # Generate array of 1-5 elements
        array_size = rng.randint(1, 5)
        if field.type in (FieldType.INTEGER, FieldType.LONG):
            return [rng.randint(-1000, 1000) for _ in range(array_size)]
        elif field.type in (FieldType.FLOAT, FieldType.DOUBLE):
            return [rng.uniform(-100.0, 100.0) for _ in range(array_size)]
        elif field.type == FieldType.KEYWORD:
            return rng.sample(['red', 'green', 'blue', 'yellow', 'purple', 'orange'], min(array_size, 6))
        else:
            # For other types, repeat the base value with variation
            return [base_value for _ in range(array_size)]

    return base_value


def create_test_index(client: OpenSearch, context: IndexContext, rng: random.Random):
    """Create and populate a test index"""
    # Build mapping with support for nested fields and subfields
    properties = {}

    for f in context.fields:
        # Skip nested subpaths (handled by parent)
        if '.' in f.name and f.nested_depth > 0:
            continue

        field_mapping = {"type": f.type.value}

        # Add subfields (e.g., text with keyword)
        if f.subfields:
            field_mapping["fields"] = {}
            for subfield_name, subfield in f.subfields.items():
                subfield_mapping = {"type": subfield.type.value}
                if subfield.ignore_above is not None:
                    subfield_mapping["ignore_above"] = subfield.ignore_above
                field_mapping["fields"][subfield_name] = subfield_mapping

        properties[f.name] = field_mapping

    mapping = {"mappings": {"properties": properties}}

    if client.indices.exists(index=context.name):
        client.indices.delete(index=context.name)

    client.indices.create(index=context.name, body=mapping)

    # Bulk insert documents
    documents = []
    for i in range(context.doc_count):
        source = {}

        for f in context.fields:
            value = generate_field_value(f, rng)

            # Handle nested fields (dot notation like "obj.nested.value")
            if '.' in f.name:
                parts = f.name.split('.')
                current = source
                for part in parts[:-1]:
                    if part not in current:
                        current[part] = {}
                    current = current[part]
                current[parts[-1]] = value
            else:
                source[f.name] = value

        doc = {
            "_index": context.name,
            "_id": i,
            "_source": source
        }
        documents.append(doc)

    from opensearchpy.helpers import bulk
    bulk(client, documents)
    client.indices.refresh(index=context.name)


def generate_contexts(
    host: str,
    num_contexts: int = 5,
    seed: Optional[int] = None
) -> List[IndexContext]:
    """Generate random test index contexts with diverse field types"""
    rng = random.Random(seed)

    client = OpenSearch([host])
    contexts = []

    for i in range(num_contexts):
        # Generate random schema with mix of field types
        num_fields = rng.randint(3, 8)
        fields = []

        for j in range(num_fields):
            field_type = rng.choice(list(FieldType))
            field_name = f"field_{j}"
            is_array = False
            subfields_dict = {}
            nested_depth = 0

            # ponytail: array generation disabled - bugs #5333 (GROUP BY explosion), other issues TBD
            # if field_type in (FieldType.INTEGER, FieldType.LONG, FieldType.KEYWORD) and rng.random() < 0.3:
            #     is_array = True
            #     field_name = f"array_{j}"

            # 20% chance of nested field
            if rng.random() < 0.2:
                depth = rng.randint(1, 2)
                nested_depth = depth
                if depth == 1:
                    field_name = f"obj_{j}.value"
                else:
                    field_name = f"obj_{j}.nested.value"

            # For text fields, 40% chance of keyword subfield with ignore_above
            if field_type == FieldType.TEXT and rng.random() < 0.4:
                ignore_above = rng.choice([50, 100, 256])
                subfields_dict = {
                    "keyword": SubField(FieldType.KEYWORD, ignore_above=ignore_above)
                }

            fields.append(Field(
                name=field_name,
                type=field_type,
                nullable=rng.choice([True, False]),
                is_array=is_array,
                subfields=subfields_dict,
                nested_depth=nested_depth
            ))

        context = IndexContext(
            name=f"test_index_{i}",
            fields=fields,
            doc_count=rng.randint(50, 200)
        )

        create_test_index(client, context, rng)
        contexts.append(context)

    return contexts
