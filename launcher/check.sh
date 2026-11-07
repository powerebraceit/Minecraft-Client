#!/usr/bin/env bash
set -euo pipefail

launcher_root="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$launcher_root/.." && pwd)"
no_build=false
require_gui=false
for option in "$@"; do
  case "$option" in
    --no-build) no_build=true ;;
    --gui) require_gui=true ;;
    --help|-h)
      cat <<'USAGE'
Usage: launcher/check.sh [--no-build] [--gui]

Build the standalone launcher, compile its Java 17-compatible tests, and run the
JSON, settings, bundled-mod installer, Java runtime, authentication and Minecraft
installer checks, plus process checks and graphical checks when a display exists.

  --no-build  Reuse launcher/build/classes from a preceding launcher/build.sh.
  --gui       Require the UI check; use DISPLAY or start xvfb-run automatically.

When a graphical display is available, UI checks save fresh screenshots under
launcher/build/smoke. Tests use temporary data and fake accounts/transports;
no real Microsoft account or Minecraft game is launched.
USAGE
      exit 0 ;;
    *) echo "Unknown option: $option" >&2; exit 2 ;;
  esac
done

if [[ -n "${JAVA_HOME:-}" ]]; then
  task_java="$JAVA_HOME/bin/java"
  task_javac="$JAVA_HOME/bin/javac"
else
  task_java="$(command -v java || true)"
  task_javac="$(command -v javac || true)"
fi
if [[ -z "$task_java" || -z "$task_javac" || ! -x "$task_java" || ! -x "$task_javac" ]]; then
  echo "Java 17+ JDK is required. Set JAVA_HOME to an installed JDK." >&2
  exit 1
fi

if [[ "$no_build" == false ]]; then
  bash "$launcher_root/build.sh"
fi
classes="$launcher_root/build/classes"
test_classes="$launcher_root/build/test-classes"
if [[ ! -d "$classes" ]]; then
  echo "Launcher classes are missing. Run bash launcher/build.sh before --no-build." >&2
  exit 1
fi
mkdir -p "$test_classes" "$launcher_root/build/smoke"
find "$test_classes" -type f -delete
find "$launcher_root/build/smoke" -maxdepth 1 -name 'aurora-launcher-*.png' -type f -delete
for required_test in \
  auth/BrowserLoginCheck.java \
  service/GameProcessCheck.java \
  LauncherUiCheck.java \
  LauncherBrowserEntryCheck.java \
  LauncherEntryCheck.java; do
  if [[ ! -f "$launcher_root/src/test/java/dev/aurora/launcher/$required_test" ]]; then
    echo "Required launcher check source is missing: $required_test" >&2
    exit 1
  fi
done
sources=()
while IFS= read -r file; do sources+=("$file"); done < <(find "$launcher_root/src/test/java" -name '*.java' -type f | sort)
if [[ ${#sources[@]} -eq 0 ]]; then
  echo "No launcher test sources were found." >&2
  exit 1
fi
"$task_javac" --release 17 -encoding UTF-8 -cp "$classes" -d "$test_classes" "${sources[@]}"
host_os="$(uname -s 2>/dev/null || true)"
classpath_separator=:
case "$host_os" in
  CYGWIN*|MINGW*|MSYS*) classpath_separator=';' ;;
esac
classpath="$classes$classpath_separator$test_classes"
cd "$repo_root"

run_check() {
  echo "Checking $1"
  "$task_java" -Djava.awt.headless=true -cp "$classpath" "$@"
}
run_check dev.aurora.launcher.net.JsonCheck
run_check dev.aurora.launcher.service.LauncherSettingsCheck
run_check dev.aurora.launcher.service.ModInstallerCheck --bundled
run_check dev.aurora.launcher.service.JavaRuntimeCheck
run_check dev.aurora.launcher.auth.AuthServiceCheck
run_check dev.aurora.launcher.auth.BrowserLoginCheck
run_check dev.aurora.launcher.minecraft.MinecraftInstallerCheck
run_check dev.aurora.launcher.service.GameProcessCheck

gui_checks=(dev.aurora.launcher.LauncherUiCheck dev.aurora.launcher.LauncherEntryCheck dev.aurora.launcher.LauncherBrowserEntryCheck)
run_gui_checks() {
  for main_class in "${gui_checks[@]}"; do
    echo "Checking $main_class"
    "$task_java" -Djava.awt.headless=false -cp "$classpath" "$main_class" --screenshots
  done
}
gui_ran=false
if [[ -n "${DISPLAY:-}" ]]; then
  echo "Checking the graphical interface and application entry point on DISPLAY=$DISPLAY"
  run_gui_checks
  gui_ran=true
elif [[ "$host_os" == Darwin* || "$host_os" == MINGW* || "$host_os" == MSYS* || "$host_os" == CYGWIN* ]]; then
  echo "Checking the graphical interface and application entry point on the native desktop"
  run_gui_checks
  gui_ran=true
elif command -v xvfb-run >/dev/null 2>&1 && command -v xauth >/dev/null 2>&1; then
  echo "Checking the graphical interface and application entry point on a temporary Xvfb display"
  xvfb-run --auto-servernum --server-args='-screen 0 1600x1000x24 -nolisten tcp' \
    bash -c '
      set -euo pipefail
      task_java=$1
      classpath=$2
      shift 2
      for main_class in "$@"; do
        echo "Checking $main_class"
        "$task_java" -Djava.awt.headless=false -cp "$classpath" "$main_class" --screenshots
      done
    ' _ "$task_java" "$classpath" "${gui_checks[@]}"
  gui_ran=true
elif [[ "$require_gui" == true ]]; then
  echo "GUI checks require a working DISPLAY or xvfb-run. Install xvfb and xauth, then retry --gui." >&2
  exit 1
else
  echo "SKIP: GUI display unavailable. Run --gui with DISPLAY or xvfb-run to require graphical checks."
fi

if [[ "$gui_ran" == true ]]; then
  echo "PASS: Aurora Launcher backend, process and graphical checks completed."
else
  echo "PASS: Available Aurora Launcher backend/process checks completed; GUI check skipped."
fi
