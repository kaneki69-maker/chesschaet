#!/usr/bin/env bash
# Compiles every pure-JVM source + all unit tests that don't need Android classes, then runs them
# with a minimal JUnit shim. Usage: tools/run_jvm_tests.sh [TestClassNameFilter]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GL="$(dirname "$(readlink -f "$(which gradle)")")/../lib"
OUT="${OUT:-/tmp/chesscheat-jvm-tests}"; mkdir -p "$OUT"
SRC="$ROOT/app/src/main/java/com/chesscheatpro/mobile"
# Files that import android.* / compose / TFLite / chesslib are not JVM-testable.
PURE=$(cd "$SRC" && grep -L -E "^import (android|androidx|org\.tensorflow|org\.json|com\.github)" *.kt | sed "s|^|$SRC/|")
TESTS=$(ls "$ROOT"/app/src/test/java/com/chesscheatpro/mobile/*.kt "$ROOT"/verification/v3/tests/*.kt 2>/dev/null || true)
rm -rf "$OUT/shim"; mkdir -p "$OUT/shim"
javac -nowarn -d "$OUT/shim" "$ROOT"/verification/v3/shim/org/junit/*.java 2>&1 | grep -v "^Picked up" || true
"$ROOT/tools/kotlinc.sh" -cp "$GL/kotlin-stdlib-2.0.21.jar:$GL/kotlinx-coroutines-core-jvm-1.6.4.jar:$OUT/shim" -d "$OUT/tests.jar" $PURE $TESTS "$ROOT/verification/v3/RunTests.kt" 2>&1 | grep -v -E "^(warning|Picked up)" || true
java -cp "$OUT/shim:$OUT/tests.jar:$GL/kotlin-stdlib-2.0.21.jar:$GL/kotlinx-coroutines-core-jvm-1.6.4.jar" RunTestsKt "$OUT/tests.jar" "${1:-}" 2>&1 | grep -v "^Picked up"
