// libsignal comes from Signal's own Maven repository, pinned exactly, and only for Signal's group.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const catalog = readFileSync("gradle/libs.versions.toml", "utf8");
const settings = readFileSync("settings.gradle.kts", "utf8");
const app = readFileSync("app/build.gradle.kts", "utf8");

test("libsignal is pinned to an exact release", () => {
  assert.match(catalog, /^libsignal = "\d+\.\d+\.\d+"$/m);
  assert.match(catalog, /module = "org\.signal:libsignal-android", version\.ref = "libsignal"/);
});

test("Signal's Maven repository serves only org.signal, and org.signal comes only from there", () => {
  assert.match(settings, /exclusiveContent \{/);
  assert.match(settings, /maven\("https:\/\/build-artifacts\.signal\.org\/libraries\/maven\/"\)/);
  assert.match(settings, /includeGroup\("org\.signal"\)/);
});

test("libsignal's test-only native libraries are kept out of the app", () => {
  assert.match(app, /"\*\*\/libsignal_jni_testing\.so"/);
});

test("libsignal's desktop natives are kept out of the app", () => {
  for (const pattern of ["libsignal_jni*.dylib", "libsignal_jni*.so", "signal_jni*.dll"]) {
    assert.ok(app.includes(`"${pattern}"`), pattern);
  }
});

test("Dependabot can see libsignal releases", () => {
  const dependabot = readFileSync(".github/dependabot.yml", "utf8");
  assert.match(dependabot, /url: https:\/\/build-artifacts\.signal\.org\/libraries\/maven\//);
});
