#!/usr/bin/env bash
# The CI checks on this machine, before every push (Stefan's rule): build, JVM unit tests, lint/ktlint/detekt,
# actionlint, npm test, OSV (dependencies + libsignal's Rust crates), gitleaks. Emulator UI tests run only when an
# emulator is attached (adb); otherwise CI's `ui` job runs them. Gradle stays throttled by ~/.gradle/gradle.properties
# (2 workers) and stops its daemon at the end. Needs JDK 21 (JAVA_HOME), osv-scanner, gitleaks, actionlint, Node 24.
set -euo pipefail
cd "$(dirname "$0")/.."
trap './gradlew --stop -q || true' EXIT
if pgrep -x xcodebuild >/dev/null; then echo "xcodebuild is running: one heavy build at a time. Try later." >&2; exit 1; fi
tmp=$(mktemp -d)

echo "== Gradle: build, unit tests, lint"
./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug ktlintCheck detekt
python3 - <<'PY'
import glob, re
t = f = 0
for x in glob.glob("app/build/test-results/testDebugUnitTest/*.xml"):
    m = re.search(r'tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"', open(x).read())
    t += int(m[1]); f += int(m[2]) + int(m[3])
print(f"unit tests: {t} run, {f} failed")
PY

if command -v adb >/dev/null && adb devices | grep -q "emulator-"; then
  echo "== Emulator UI tests"
  ./gradlew connectedDebugAndroidTest
else
  echo "== Emulator UI tests: no emulator attached, CI's ui job runs them"
fi

echo "== actionlint"; actionlint
echo "== npm test"; npm test
echo "== OSV"
osv-scanner scan source --format=json --output="$tmp/osv.json" --recursive ./ >/dev/null 2>&1 || true
node scripts/osv-gate.js "$tmp/osv.json"
./scripts/fetch-libsignal-cargo-lock.sh
osv-scanner scan source --format=json --lockfile "$PWD/build/libsignal/Cargo.lock" > "$tmp/osv-libsignal.json" 2>/dev/null || true
node scripts/osv-gate.js "$tmp/osv-libsignal.json" --unrated=warn
echo "== gitleaks"; gitleaks git . --no-banner --redact
echo "== all local checks passed"
