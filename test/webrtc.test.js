// WebRTC for voice calls: io.github.webrtc-sdk:android (BSD-3-Clause, compatible with AGPL-3.0), because it exposes
// FrameCryptor for end-to-end encrypted frames. Pinned exactly; its JNI classes survive R8.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const catalog = readFileSync("gradle/libs.versions.toml", "utf8");
const calls = readFileSync("calls/build.gradle.kts", "utf8");

test("WebRTC is pinned to an exact release", () => {
  assert.match(catalog, /^webrtc = "\d+\.\d+\.\d+"$/m);
  assert.match(catalog, /module = "io\.github\.webrtc-sdk:android", version\.ref = "webrtc"/);
});

test("only :calls uses WebRTC, and it keeps WebRTC's JNI classes for R8", () => {
  assert.match(calls, /implementation\(libs\.webrtc\)/);
  assert.doesNotMatch(readFileSync("app/build.gradle.kts", "utf8"), /libs\.webrtc/);
  assert.match(calls, /consumerProguardFiles\("consumer-rules\.pro"\)/);
  assert.match(readFileSync("calls/consumer-rules.pro", "utf8"), /-keep class org\.webrtc\.\*\* \{ \*; \}/);
});

test("WebRTC's checksums are verified", () => {
  const meta = readFileSync("gradle/verification-metadata.xml", "utf8");
  const version = catalog.match(/^webrtc = "([^"]+)"$/m)[1];
  assert.ok(meta.includes(`<component group="io.github.webrtc-sdk" name="android" version="${version}">`));
});
