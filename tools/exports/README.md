# Independent Item evidence export checks

These development tools validate the public version 1 document with Python's
standards-compliant Draft 2020-12 validator. They do not authenticate Blizzard
source commitments. No game installation, CDN request, or game payload is used.
The Java corpus uses the explicitly authored `ItemExportFixture` from the test
sources; this dependency is confined to development tooling.

Use Python 3.11 or newer and the project's JDK. Create an isolated environment:

```sh
python3 -m venv /tmp/vibeminer-export-validator
/tmp/vibeminer-export-validator/bin/python -m pip install --require-hashes -r tools/exports/requirements.txt
```

The requirements pin the validator and every transitive dependency. Hashes cover
all artifacts published for those versions on PyPI, allowing native wheels on
the supported operating systems. Installation may need a Rust toolchain if a
platform has no matching `rpds-py` wheel. This is not an application dependency.

From the repository root, build coherent production and authored fixture classes,
then generate the corpus in an empty temporary directory:

```sh
./gradlew :bootstrap:installDist :infrastructure:testClasses
export_check_dir=$(mktemp -d)
mkdir "$export_check_dir/classes" "$export_check_dir/documents"
javac -cp 'bootstrap/build/install/bootstrap/lib/*:infrastructure/build/classes/java/test' -d "$export_check_dir/classes" tools/exports/ExportOracleCorpus.java
java -cp "$export_check_dir/classes:bootstrap/build/install/bootstrap/lib/*:infrastructure/build/classes/java/test" io.vibeminer.infrastructure.itemexport.ExportOracleCorpus "$export_check_dir/documents"
/tmp/vibeminer-export-validator/bin/python tools/exports/oracle.py "$export_check_dir/documents"
```

The classpath commands above use the Unix separator `:`; on Windows use `;` and
an equivalent temporary directory. The Java producer writes only authored JSON
documents beneath the explicitly supplied corpus directory. Use an empty
directory so unrelated JSON files cannot change the expected corpus count.

The independent oracle checks 269 documents: raw widths 1–64, zero/high/all bits,
signed and unsigned wide boundaries including values above 2^53, exact Unicode,
expanded-year timestamps, all four name states including an empty physical name,
full 3,780-chunk receipts, omitted source-payload canaries, and encoded documents
at 2 MiB and one byte below it. The producer also requires a one-byte-over-limit
document to fail. Recursive undeclared-field mutations, name-condition/type
mutations, duplicate keys, nonfinite numbers, malformed UTF-8, escaped unpaired
surrogates, BOMs and trailing documents must be rejected. These checks supplement
the application, source capture, cancellation and atomic publication tests.

To validate an existing export without generating a corpus:

```sh
/tmp/vibeminer-export-validator/bin/python tools/exports/validate.py /path/to/export.json
```

The validator reads at most 2 MiB plus one sentinel byte and never retrieves
external schema references. JSON Schema checks document shape; cross-source
authentication and capture authorization remain application responsibilities.
