#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/chesscheat-pro-3.0.0-smoke"
mkdir -p "$OUT"
kotlinc \
  "$ROOT/app/src/main/java/com/chesscheatpro/mobile/ChessImport.kt" \
  "$ROOT/app/src/main/java/com/chesscheatpro/mobile/BoardPositionCodec.kt" \
  "$ROOT/app/src/main/java/com/chesscheatpro/mobile/BoardOccupancyClassifier.kt" \
  "$ROOT/app/src/main/java/com/chesscheatpro/mobile/BoardGeometryDetector.kt" \
  "$ROOT/app/src/main/java/com/chesscheatpro/mobile/BoardMoveTracker.kt" \
  "$ROOT/app/src/main/java/com/chesscheatpro/mobile/BoardSquareTensorizer.kt" \
  "$ROOT/app/src/main/java/com/chesscheatpro/mobile/PiecePredictionDecoder.kt" \
  "$ROOT/verification/Smoke.kt" -include-runtime -d "$OUT/core-smoke.jar"
java -jar "$OUT/core-smoke.jar"
kotlinc \
  "$ROOT/app/src/main/java/com/chesscheatpro/mobile/ElfBinaryValidator.kt" \
  "$ROOT/verification/ElfSmoke.kt" -include-runtime -d "$OUT/elf-smoke.jar"
java -jar "$OUT/elf-smoke.jar"
