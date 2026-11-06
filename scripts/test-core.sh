#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/classes
mapfile -t sources < <(find src/main/java src/test/java -name '*.java' -print | sort)
if command -v javac >/dev/null 2>&1; then
    javac --release 21 -d build/classes "${sources[@]}"
elif [[ -n "${ECJ_JAR:-}" && -f "$ECJ_JAR" ]]; then
    java -jar "$ECJ_JAR" -21 -d build/classes "${sources[@]}"
else
    echo 'A JDK 21+ is required; alternatively set ECJ_JAR to a verified Eclipse ECJ 3.42+ compiler.' >&2
    exit 1
fi
java -ea -cp build/classes dev.aurora.CoreTest
