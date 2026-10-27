#!/usr/bin/env bash
# Downloads libsignal's Cargo.lock for the version pinned in gradle/libs.versions.toml, so OSV-Scanner can check the
# Rust crates compiled into libsignal's native library (the AAR itself has no lock file to scan).
# Output: build/libsignal/Cargo.lock
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(sed -n 's/^libsignal = "\([0-9.]*\)"$/\1/p' gradle/libs.versions.toml)
[ -n "$version" ] || { echo "libsignal version not found in gradle/libs.versions.toml" >&2; exit 1; }
mkdir -p build/libsignal
curl -fsSL --proto '=https' "https://raw.githubusercontent.com/signalapp/libsignal/v${version}/Cargo.lock" \
  -o build/libsignal/Cargo.lock
echo "libsignal v${version}: build/libsignal/Cargo.lock"
