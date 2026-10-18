#!/usr/bin/env python3
"""Independent strict parsing and Draft 2020-12 validation of Item evidence exports.

This checks the document contract, not the authenticity of source commitments.
It never downloads a schema or reads game installations.
"""

import argparse
import json
import math
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry

MAXIMUM_BYTES = 2 * 1024 * 1024


def _pairs(pairs):
    value = {}
    for key, child in pairs:
        if key in value:
            raise ValueError(f"duplicate object key: {key!r}")
        value[key] = child
    return value


def _constant(value):
    raise ValueError(f"non-JSON numeric constant: {value}")


def _scalars(value):
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError("number exceeds the finite parser range")
    if isinstance(value, str):
        value.encode("utf-8", errors="strict")
    elif isinstance(value, dict):
        for key, child in value.items():
            _scalars(key)
            _scalars(child)
    elif isinstance(value, list):
        for child in value:
            _scalars(child)


def parse_document(data):
    if not 0 < len(data) <= MAXIMUM_BYTES:
        raise ValueError("document exceeds the 2097152-byte ceiling or is empty")
    # utf-8 (not utf-8-sig) deliberately rejects a BOM through json.loads.
    value = json.loads(data.decode("utf-8", errors="strict"),
                       object_pairs_hook=_pairs, parse_constant=_constant)
    _scalars(value)  # JSON escape sequences must also encode valid Unicode scalars.
    return value


def validator(schema_path):
    schema = json.loads(Path(schema_path).read_text(encoding="utf-8"),
                        object_pairs_hook=_pairs, parse_constant=_constant)
    Draft202012Validator.check_schema(schema)
    # An empty registry prevents implicit network retrieval of external references.
    return Draft202012Validator(schema, registry=Registry())


def validate_file(path, check):
    with Path(path).open("rb") as stream:
        data = stream.read(MAXIMUM_BYTES + 1)
    document = parse_document(data)
    check.validate(document)
    return document


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("documents", nargs="+", type=Path)
    parser.add_argument("--schema", type=Path,
                        default=Path(__file__).resolve().parents[2] /
                        "docs/schema/item-evidence-v1.schema.json")
    args = parser.parse_args()
    check = validator(args.schema)
    for path in args.documents:
        validate_file(path, check)
        print(f"PASS {path}")


if __name__ == "__main__":
    main()
