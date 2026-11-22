#!/usr/bin/env bash
# Runs the Kotlin compiler bundled with Gradle (no Android SDK required) for pure-JVM sources.
# Usage: tools/kotlinc.sh -d out.jar src1.kt src2.kt ...   (adds the Kotlin runtime to the jar)
set -euo pipefail
GL="${GRADLE_LIB:-$(dirname "$(readlink -f "$(which gradle)")")/../lib}"
CP="$GL/kotlin-compiler-embeddable-2.0.21.jar:$GL/kotlin-stdlib-2.0.21.jar:$GL/kotlin-script-runtime-2.0.21.jar:$GL/kotlin-reflect-2.0.21.jar:$GL/kotlin-daemon-embeddable-2.0.21.jar:$GL/trove4j-1.0.20200330.jar:$GL/annotations-24.0.1.jar:$GL/kotlinx-coroutines-core-jvm-1.6.4.jar"
DEFCP="$GL/kotlin-stdlib-2.0.21.jar:$GL/kotlinx-coroutines-core-jvm-1.6.4.jar"
HASCP=0; for a in "$@"; do [ "$a" = "-cp" ] && HASCP=1; done
[ $HASCP = 0 ] && set -- -cp "$DEFCP" "$@"
exec java -Xmx1g -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
  -jvm-target 17 "$@"
