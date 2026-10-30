#!/usr/bin/env bash
# The local Android emulator for scripts/ci-local.sh: headless, 2 GB RAM, arm64, API 37 (the app's target SDK).
#   scripts/emulator.sh start   create the AVD if needed, check the guard, boot, wait, disable animations
#   scripts/emulator.sh stop    shut it down (ci-local.sh always does, also on failure)
# The guard (scripts/emulator-guard.js) refuses while xcodebuild or an iOS simulator runs, or a phone is attached.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}
SDK=${SDK:-/opt/homebrew/share/android-commandlinetools}
AVD=zapakid-ci-37
IMAGE="system-images;android-37.0;google_apis;arm64-v8a"
PORT=5584
export ANDROID_SERIAL="emulator-$PORT"
ADB="$SDK/platform-tools/adb"

case "${1:-}" in
  start)
    node scripts/emulator-guard.js
    [ -d "$SDK/system-images/android-37.0/google_apis/arm64-v8a" ] ||
      { echo "Missing $IMAGE: sdkmanager \"emulator\" \"$IMAGE\"" >&2; exit 1; }
    if ! "$SDK/emulator/emulator" -list-avds | grep -qx "$AVD"; then
      echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d pixel_7 >/dev/null 2>&1 || true
    fi
    nohup "$SDK/emulator/emulator" -avd "$AVD" -port "$PORT" -no-window -no-audio -no-boot-anim -no-snapshot \
      -memory 2048 -cores 2 -gpu swiftshader_indirect >"${TMPDIR:-/tmp}/zapakid-emulator.log" 2>&1 &
    "$ADB" -s "$ANDROID_SERIAL" wait-for-device
    for _ in $(seq 1 180); do
      [ "$("$ADB" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
      sleep 2
    done
    [ "$("$ADB" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed | tr -d '\r')" = 1 ] ||
      { echo "Emulator didn't boot; see ${TMPDIR:-/tmp}/zapakid-emulator.log" >&2; exit 1; }
    for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
      "$ADB" -s "$ANDROID_SERIAL" shell settings put global "$setting" 0
    done
    echo "Emulator $ANDROID_SERIAL ready"
    ;;
  stop)
    "$ADB" -s "$ANDROID_SERIAL" emu kill >/dev/null 2>&1 || true
    for _ in $(seq 1 30); do pgrep -f "qemu.*-avd $AVD" >/dev/null || break; sleep 1; done
    pkill -f "qemu.*-avd $AVD" 2>/dev/null || true
    ;;
  *)
    echo "usage: $0 start|stop" >&2
    exit 2
    ;;
esac
