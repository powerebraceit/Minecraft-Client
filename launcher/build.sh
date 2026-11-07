#!/usr/bin/env bash
set -euo pipefail
launcher_root="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$launcher_root/.." && pwd)"
if [[ -n "${JAVA_HOME:-}" ]]; then
  task_javac="$JAVA_HOME/bin/javac"
  task_jar="$JAVA_HOME/bin/jar"
else
  task_javac="$(command -v javac)"
  task_jar="$(command -v jar)"
fi
mkdir -p "$launcher_root/build/classes" "$launcher_root/dist"
find "$launcher_root/build/classes" -type f -delete
sources=()
while IFS= read -r file; do sources+=("$file"); done < <(find "$launcher_root/src/main/java" -name '*.java' -type f | sort)
"$task_javac" --release 17 -encoding UTF-8 -d "$launcher_root/build/classes" "${sources[@]}"
cp -R "$launcher_root/src/main/resources/." "$launcher_root/build/classes/"
mkdir -p "$launcher_root/build/classes/bundled"
cp "$repo_root/releases/1.10.0/aurora-client-1.10.0.jar" "$launcher_root/build/classes/bundled/"
cp "$repo_root/releases/1.10.0/aurora-client-1.10.0.jar.sha256" "$launcher_root/build/classes/bundled/"
"$task_jar" --create --file "$launcher_root/dist/AuroraLauncher-1.0.1.jar" --main-class dev.aurora.launcher.AuroraLauncher -C "$launcher_root/build/classes" .
"$task_jar" --create --file "$launcher_root/dist/AuroraLauncher-1.0.1-sources.jar" -C "$launcher_root/src/main" .
(cd "$launcher_root/dist" && sha256sum AuroraLauncher-1.0.1.jar > AuroraLauncher-1.0.1.jar.sha256)
echo "Built AuroraLauncher-1.0.1.jar (Java 17+, game runtime Java 25)."
