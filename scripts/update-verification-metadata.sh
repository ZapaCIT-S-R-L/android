#!/usr/bin/env bash
# Rewrites gradle/verification-metadata.xml (SHA-256 of every dependency, plugin and build tool) from scratch,
# by running every task CI runs. The emulator test runner (UTP) is only resolved with a device attached: run with
# WITH_DEVICE=1 and an emulator, or use the verification-metadata workflow (Actions tab), which does this on Linux.
# Then review `git diff gradle/verification-metadata.xml`.
set -euo pipefail
cd "$(dirname "$0")/.."
rm -f gradle/verification-metadata.xml
./gradlew --no-configuration-cache --write-verification-metadata sha256 \
  --init-script gradle/aapt2-all-platforms.init.gradle.kts \
  resolveAapt2AllPlatforms \
  assembleDebug assembleRelease bundleRelease testDebugUnitTest assembleDebugAndroidTest \
  lintDebug ktlintCheck detekt cyclonedxBom \
  ${WITH_DEVICE:+connectedDebugAndroidTest}
# IDE downloads (sources, javadoc) aren't verified; they never reach a build.
python3 - <<'PY'
import re
path = "gradle/verification-metadata.xml"
xml = open(path, encoding="utf-8").read()
trust = (
    "      <trusted-artifacts>\n"
    '         <trust file=".*-javadoc[.]jar" regex="true"/>\n'
    '         <trust file=".*-sources[.]jar" regex="true"/>\n'
    "      </trusted-artifacts>\n"
)
if "<trusted-artifacts>" not in xml:
    xml = re.sub(r"(\s*<verify-signatures>false</verify-signatures>\n)", r"\1" + trust, xml, count=1)
open(path, "w", encoding="utf-8").write(xml)
PY
echo "Updated gradle/verification-metadata.xml"
