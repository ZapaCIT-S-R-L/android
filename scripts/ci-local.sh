#!/usr/bin/env bash
# The CI checks on this machine, before every push (Stefan's rule): build, JVM unit tests, lint/ktlint/detekt,
# actionlint, npm test, OSV (dependencies + libsignal's Rust crates), gitleaks, and the emulator UI tests on the local
# emulator (scripts/emulator.sh: headless, 2 GB, refused while xcodebuild or an iOS simulator runs or a phone is
# attached, shut down after the run). SKIP_UI=1 skips them (say so in the PR). Gradle stays throttled by
# ~/.gradle/gradle.properties (2 workers) and stops its daemon at the end. Needs JDK 21, osv-scanner, gitleaks,
# actionlint, Node 24, and the emulator + system-images;android-37.0;google_apis;arm64-v8a (sdkmanager).
set -euo pipefail
cd "$(dirname "$0")/.."
trap './gradlew --stop -q || true' EXIT
if [ "${SKIP_UI:-}" != 1 ]; then node scripts/emulator-guard.js; fi
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
echo "== Contract (api's openapi.yaml at contract/api-commit)"
npm ci --ignore-scripts --no-audit --no-fund >/dev/null
./scripts/fetch-openapi.sh
npm run -s contract

if [ "${SKIP_UI:-}" = 1 ]; then
  echo "== Emulator UI tests: SKIPPED (SKIP_UI=1); say so in the PR, CI's ui job runs them"
else
  echo "== Emulator UI tests (local emulator, headless, 2 GB)"
  trap './scripts/emulator.sh stop; ./gradlew --stop -q || true' EXIT
  ./scripts/emulator.sh start
  ANDROID_SERIAL=emulator-5584 ./gradlew connectedDebugAndroidTest
  ./scripts/emulator.sh stop
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
