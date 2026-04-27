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
class Field:
    """Schema field definition"""
    name: str
    type: FieldType
    nullable: bool = True


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

    if field.type == FieldType.INTEGER:
        return rng.randint(-1000, 1000)
    elif field.type == FieldType.LONG:
        return rng.randint(-10000, 10000)
    elif field.type in (FieldType.FLOAT, FieldType.DOUBLE):
        return rng.uniform(-100.0, 100.0)
    elif field.type == FieldType.BOOLEAN:
        return rng.choice([True, False])
    elif field.type == FieldType.TEXT:
        words = ['apple', 'banana', 'cherry', 'date', 'elderberry']
        return ' '.join(rng.choices(words, k=rng.randint(1, 3)))
    elif field.type == FieldType.KEYWORD:
        return rng.choice(['red', 'green', 'blue', 'yellow', 'purple'])
    elif field.type == FieldType.DATE:
        # Generate ISO date string
        year = rng.randint(2020, 2024)
        month = rng.randint(1, 12)
        day = rng.randint(1, 28)
        return f"{year}-{month:02d}-{day:02d}"
    elif field.type == FieldType.IP:
        return f"{rng.randint(1,255)}.{rng.randint(0,255)}.{rng.randint(0,255)}.{rng.randint(0,255)}"

    return None


def create_test_index(client: OpenSearch, context: IndexContext, rng: random.Random):
    """Create and populate a test index"""
    # Create index with mapping
    mapping = {
        "mappings": {
            "properties": {
                f.name: {"type": f.type.value}
                for f in context.fields
            }
        }
    }

    if client.indices.exists(index=context.name):
        client.indices.delete(index=context.name)

    client.indices.create(index=context.name, body=mapping)

    # Bulk insert documents
    documents = []
    for i in range(context.doc_count):
        doc = {
            "_index": context.name,
            "_id": i,
            "_source": {
                f.name: generate_field_value(f, rng)
                for f in context.fields
            }
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
    """Generate random test index contexts"""
    rng = random.Random(seed)

    client = OpenSearch([host])
    contexts = []

    for i in range(num_contexts):
        # Generate random schema
        num_fields = rng.randint(3, 8)
        fields = []

        for j in range(num_fields):
            field_type = rng.choice(list(FieldType))
            fields.append(Field(
                name=f"field_{j}",
                type=field_type,
                nullable=rng.choice([True, False])
            ))

        context = IndexContext(
            name=f"test_index_{i}",
            fields=fields,
            doc_count=rng.randint(50, 200)
        )

        create_test_index(client, context, rng)
        contexts.append(context)

    return contexts
