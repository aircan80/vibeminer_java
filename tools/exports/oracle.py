#!/usr/bin/env python3
"""Independent Python integer, Unicode, schema and hostile-document export oracle."""

import argparse
import base64
import copy
import hashlib
import json
from pathlib import Path

from jsonschema.exceptions import ValidationError

from validate import MAXIMUM_BYTES, parse_document, validate_file, validator


def rejected(action):
    try:
        action()
    except (ValueError, UnicodeError, ValidationError):
        return
    raise AssertionError("hostile document accepted")


def object_paths(value, path=()):
    if isinstance(value, dict):
        yield path
        for key, child in value.items():
            yield from object_paths(child, path + (key,))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from object_paths(child, path + (index,))


def at(value, path):
    for key in path:
        value = value[key]
    return value


def run(directory, schema):
    check = validator(schema)
    documents = {p.stem: validate_file(p, check) for p in directory.glob("*.json")}
    assert len(documents) == 269, f"incomplete corpus: {len(documents)}"
    for stem, document in documents.items():
        if stem.startswith("raw-"):
            _, width, number = stem.split("-")
            width, number = int(width), int(number)
            integer = document["row"]["integers"][0]
            # Python arbitrary precision is independent of Java long formatting.
            assert 0 <= number < 2 ** width
            assert integer["rawHex"] == format(number, f"0{(width + 3) // 4}x")
            assert integer["candidateDecimal"] == str(number)
            assert integer["rawWidth"] == integer["candidateBits"] == width
            assert integer["candidateSign"] == "UNSIGNED"
        elif stem.startswith("signed-"):
            number = int(stem[len("signed-"):])
            integer = document["row"]["integers"][0]
            assert integer["rawHex"] == format(number % 2 ** 64, "016x")
            assert integer["candidateDecimal"] == str(number)
            assert integer["candidateSign"] == "SIGNED"
            assert document["documentCreatedAt"] == "+1000000000-12-31T23:59:59.999999999Z"

    text = 'e\u0301é😀"\\\t\b\f'
    unicode_document = documents["unicode"]
    assert unicode_document["itemSource"]["evidence"]["association"]["build"]["editionLabel"] == text
    assert unicode_document["documentCreatedAt"] == "-1000000000-01-01T00:00:00Z"
    assert documents["raw-1-0"]["name"] == {
        "state": "NOT_INCLUDED", "candidate": None, "copySourceId": None}
    assert documents["raw-1-0"]["sparseSource"] is None
    for state in ("PHYSICAL_NAME", "COPY_UNINDEXED", "NO_PHYSICAL_MATCH"):
        document = documents[f"name-{state}"]
        name = document["name"]
        assert name["state"] == state
        assert document["sparseTableFileDataId"] == 1572924
        evidence = document["sparseSource"]["evidence"]
        chunks = evidence["chunks"]
        assert len(chunks) == 3780 and [c["ordinal"] for c in chunks] == list(range(3780))
        assert all(c["verifierRevision"] == "chunk-v1" for c in chunks)
        assert evidence["localVerification"]["verifiedAt"] == "1970-01-01T00:00:00Z"
        assert document["verification"]["startedAt"] == "1970-01-01T00:00:01Z"
        assert document["verification"]["completedAt"] == "1970-01-01T00:00:02Z"
        assert evidence["fullDecoded"] is None
        if state == "PHYSICAL_NAME":
            assert name["candidate"]["value"] == "" and name["candidate"]["utf8Bytes"] == 0
            assert name["copySourceId"] is None
        else:
            assert name["candidate"] is None
            assert name["copySourceId"] == ("202" if state == "COPY_UNINDEXED" else None)

    canary = b"PUBLIC-AUTHORED-PAYLOAD-CANARY"
    data = (directory / "canary.json").read_bytes()
    assert canary not in data and base64.b64encode(canary) not in data
    assert hashlib.sha256(canary).hexdigest().encode("ascii") in data
    for delta in (-1, 0):
        assert (directory / f"ceiling-{delta}.json").stat().st_size == MAXIMUM_BYTES + delta

    # Each object in a real export must be closed, including deeply nested proof objects.
    baseline = documents["raw-1-0"]
    mutations = 0
    for path in object_paths(baseline):
        hostile = copy.deepcopy(baseline)
        at(hostile, path)["undeclaredPayload"] = "AUTHORED-LEAK-CANARY"
        rejected(lambda: check.validate(hostile))
        mutations += 1
        for key in at(baseline, path):
            hostile = copy.deepcopy(baseline)
            del at(hostile, path)[key]
            rejected(lambda: check.validate(hostile))
            mutations += 1
    for state in ("PHYSICAL_NAME", "COPY_UNINDEXED", "NO_PHYSICAL_MATCH"):
        hostile = copy.deepcopy(documents[f"name-{state}"])
        hostile["name"]["candidate"] = None if state == "PHYSICAL_NAME" else {
            "value": "inherited copy name"}
        rejected(lambda: check.validate(hostile))
    for replacement in (False, "101", -1, 2 ** 32):
        hostile = copy.deepcopy(baseline)
        hostile["itemRecordId"] = replacement
        rejected(lambda: check.validate(hostile))
    for hostile in (b'{"x":1,"x":2}', b'{"x":NaN}', b'{"x":Infinity}', b'{"x":1e9999}',
                    b'{"x":"\\ud800"}', b'{"x":"\\udc00"}', b'{}{}',
                    b'\xef\xbb\xbf{}', b'{"x":"\xff"}', b' ' * (MAXIMUM_BYTES + 1)):
        rejected(lambda: parse_document(hostile))
    print(f"PASS {len(documents)} encoder documents; {mutations} recursively closed-object probes; "
          "integer/Unicode/name/full-chunk/byte-ceiling and hostile JSON oracles")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    parser.add_argument("--schema", type=Path, default=Path(__file__).resolve().parents[2] /
                        "docs/schema/item-evidence-v1.schema.json")
    args = parser.parse_args()
    run(args.corpus, args.schema)


if __name__ == "__main__":
    main()
