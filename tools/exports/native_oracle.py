#!/usr/bin/env python3
"""Independent strict schema and authored-value oracle for native export workflow twelve.

Checks disposable synthetic fixture outputs, not Blizzard authenticity, native
chooser behavior, crash recovery or arbitrary filesystem provider guarantees.
"""

import argparse
import base64
from collections import Counter
from pathlib import Path

from validate import validate_file, validator


# These expectations come from the authored fixture and explicitly scripted UI
# scenarios, independently of the JSON encoder, exported documents and manifest.
CASES = {
    "item-only.json": (1001, "NOT_INCLUDED", ""),
    "paired-1001.json": (1001, "PHYSICAL_NAME", "Authored sword α"),
    "paired-1002.json": (1002, "PHYSICAL_NAME", ""),
    "paired-1003.json": (1003, "COPY_UNINDEXED", ""),
    "paired-1004.json": (1004, "NO_PHYSICAL_MATCH", ""),
    "destination-retry.json": (1001, "NOT_INCLUDED", ""),
    "lost-receipt.json": (1001, "NOT_INCLUDED", ""),
    "paired-200pct.json": (1001, "PHYSICAL_NAME", "Authored sword α"),
    "commit-wins.json": (1001, "PHYSICAL_NAME", "Authored sword α"),
    "restart-paired.json": (1001, "PHYSICAL_NAME", "Authored sword α"),
}
FIELDS = (
    ("CLASS_ID", 32, "SIGNED"),
    ("SUBCLASS_ID", 8, "UNSIGNED"),
    ("INVENTORY_TYPE", 8, "SIGNED"),
    ("ICON_FILE_DATA_ID", 32, "SIGNED"),
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(output, schema):
    exports = Path(output) / "exports"
    lines = (exports / "expectations.tsv").read_text(encoding="utf-8").splitlines()
    seen = Counter()
    for line in lines:
        filename, record_id, state, encoded_name = line.split("\t")
        require(filename in CASES, f"Unknown authored scenario: {filename!r}")
        name = base64.b64decode(encoded_name, validate=True).decode("utf-8", errors="strict")
        require((int(record_id), state, name) == CASES[filename],
                f"Manifest changed independently authored expectation: {filename}")
        seen[filename] += 1
    expected_counts = Counter({name: 1 for name in CASES})
    expected_counts["paired-1001.json"] = 2  # Original receipt bytes checked again after restart.
    require(seen == expected_counts, "Expected all 11 entries across both application JVMs")
    require({p.name for p in exports.glob("*.json")} == set(CASES),
            "Expected exactly ten authored JSON output files")
    check = validator(schema)
    for filename, (record_id, state, name) in CASES.items():
        document = validate_file(exports / filename, check)
        require(document["itemRecordId"] == record_id, f"Wrong Item ID: {filename}")
        require(document["itemTableFileDataId"] == 841626, f"Wrong Item source: {filename}")
        row = document["row"]
        require(row["physicalOrdinal"] == record_id - 1001 and row["sectionOrdinal"] == 0,
                f"Wrong physical position: {filename}")
        require(len(row["integers"]) == 4, f"Wrong integer count: {filename}")
        # All four authored immediate descriptors read the same first byte;
        # candidate signs/widths come from the independent pinned bindings.
        value = record_id - 1001 + 3
        for integer, (field, bits, sign) in zip(row["integers"], FIELDS):
            expected = {
                "field": field,
                "rawWidth": 8,
                "rawHex": format(value, "02x"),
                "physicalInterpretation": "UNSIGNED_BITS",
                "candidateDecimal": str(value),
                "candidateBits": bits,
                "candidateSign": sign,
            }
            require(integer == expected, f"Wrong raw/candidate integer: {filename}/{field}")
        match = document["name"]
        require(match["state"] == state, f"Wrong name state: {filename}")
        if state == "NOT_INCLUDED":
            require(document["sparseSource"] is None and document["sparseTableFileDataId"] is None
                    and match["candidate"] is None and match["copySourceId"] is None,
                    f"Item-only output silently includes names: {filename}")
        else:
            require(document["sparseSource"] is not None
                    and document["sparseTableFileDataId"] == 1572924,
                    f"Paired output lost Sparse source: {filename}")
            if state == "PHYSICAL_NAME":
                require(match["candidate"]["value"] == name and match["copySourceId"] is None,
                        f"Wrong exact Unicode/empty physical name: {filename}")
            else:
                require(match["candidate"] is None, f"Invented name candidate: {filename}")
                require(match["copySourceId"] == ("1001" if state == "COPY_UNINDEXED" else None),
                        f"Wrong scoped copy/nonmatch evidence: {filename}")
        print(f"Native export oracle PASS {filename} {state}")
    print("Native export oracle OK; 11 expectation entries; 10 JSON documents; all four name states")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="Native workflow output directory containing exports/")
    parser.add_argument("--schema", type=Path,
                        default=Path(__file__).resolve().parents[2] /
                        "docs/schema/item-evidence-v1.schema.json")
    args = parser.parse_args()
    run(args.output, args.schema)


if __name__ == "__main__":
    main()
