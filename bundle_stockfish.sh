#!/usr/bin/env bash
# Copies an official Stockfish Android arm64 build into the app as jniLibs/arm64-v8a/libstockfish.so.
# Usage: tools/bundle_stockfish.sh /path/to/stockfish-android-armv8
# Get the binary from https://github.com/official-stockfish/Stockfish/releases (asset "stockfish-android-armv8.tar").
# Stockfish is GPL-3.0: if you distribute the APK you must also provide the Stockfish source (see THIRD_PARTY.md).
set -euo pipefail
SRC="${1:?path to the stockfish-android-armv8 binary}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$SRC" <<'PY'
import sys, struct
h = open(sys.argv[1], 'rb').read(64)
assert h[:4] == b'\x7fELF', 'not an ELF file'
assert h[4] == 2 and h[5] == 1, 'need 64-bit little-endian ELF'
machine = struct.unpack('<H', h[18:20])[0]
assert machine == 183, f'need AArch64 (EM_AARCH64=183), got {machine}'
print('ELF aarch64 OK')
PY
mkdir -p "$ROOT/app/src/main/jniLibs/arm64-v8a"
cp "$SRC" "$ROOT/app/src/main/jniLibs/arm64-v8a/libstockfish.so"
chmod 755 "$ROOT/app/src/main/jniLibs/arm64-v8a/libstockfish.so"
sha256sum "$ROOT/app/src/main/jniLibs/arm64-v8a/libstockfish.so"
