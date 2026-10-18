#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 4 ]; then
  echo 'Usage: run-encoding-inventory-benchmark.sh NEW_OUTPUT_DIRECTORY PAGES BATCH_ROWS CANDIDATES_PER_CONTENT' >&2
  exit 2
fi
repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
javac_bin="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
"$repo_dir/gradlew" -p "$repo_dir" :bootstrap:installDist --console=plain
bench_classes="$(mktemp -d)"
trap 'rm -rf "$bench_classes"' EXIT
bench_cp="$repo_dir/bootstrap/build/install/bootstrap/lib/*"
"$javac_bin" -cp "$bench_cp" -d "$bench_classes" "$repo_dir/tools/performance/EncodingInventoryBenchmark.java"
"$java_bin" --enable-native-access=ALL-UNNAMED -Xmx256m -cp "$bench_classes:$bench_cp" \
  io.vibeminer.infrastructure.encoding.EncodingInventoryBenchmark "$@"
