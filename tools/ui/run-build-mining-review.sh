#!/usr/bin/env bash
# Compile only the review tool against an already-built current project; never starts a display.
# Prerequisite: JDK25 and ./gradlew :bootstrap:installDist. Outputs contain authored game data.
set -euo pipefail
if [[ $# != 5 ]]; then
  printf '%s\n' 'Usage: run-build-mining-review.sh :DISPLAY OUTPUT WIDTH HEIGHT TEXT_SCALE' >&2
  exit 2
fi
review_display=$1
if [[ ${DISPLAY:-} != "$review_display" ]]; then
  printf '%s\n' 'DISPLAY must match the explicitly supplied isolated review display.' >&2
  exit 2
fi
review_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
mkdir -p "$2"
review_output=$(cd "$2" && pwd)
review_classes="$review_output/tool-classes"
mkdir -p "$review_classes"
review_java=${JAVA_HOME:+$JAVA_HOME/bin/}java
review_javac=${JAVA_HOME:+$JAVA_HOME/bin/}javac
review_cp="$review_root/desktop/build/classes/java/main:$review_root/bootstrap/build/classes/java/main:$review_root/application/build/classes/java/main:$review_root/domain/build/classes/java/main:$review_root/infrastructure/build/classes/java/main:$review_root/bootstrap/build/install/bootstrap/lib/*"
"$review_javac" -cp "$review_cp" -d "$review_classes" \
  "$review_root/tools/ui/fixtures/BuildOpeningReviewData.java" \
  "$review_root/tools/ui/BuildMiningReviewApp.java"
for review_phase in seed restart; do
  "$review_java" --enable-native-access=ALL-UNNAMED -Dprism.order=sw \
    -cp "$review_classes:$review_root/desktop/src/main/resources:$review_cp" \
    io.vibeminer.bootstrap.BuildMiningReviewApp \
    --isolated-display "$review_display" --output "$review_output/$review_phase" \
    --data-dir "$review_output/authored-data" --phase "$review_phase" \
    --width "$3" --height "$4" --scale "$5" 2>&1 | tee "$review_output/$review_phase.log"
done
