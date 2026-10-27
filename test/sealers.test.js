// Fake key and message sealers (CallKeySealer, MessageSealer) must never reach an app build: they live only in the
// JVM/emulator test source sets (test, androidTest, sharedTest). Shipped code may implement a sealer only in a
// Libsignal*.kt file (the real, libsignal-backed one).
import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

const files = execFileSync("git", ["ls-files", "-z", "*.kt"], { encoding: "utf8" }).split("\0").filter(Boolean);
// A supertype list: "class X(...) : A, CallKeySealer {" or "object X : MessageSealer". Parameters ("sealer: CallKeySealer")
// don't match, because the colon has to follow a class or object name or a closing parenthesis.
const IMPLEMENTS = /(?:\b(?:class|object)\s+\w+|\))\s*:\s*(?:[\w.]+(?:\([^)]*\))?\s*,\s*)*(CallKeySealer|MessageSealer)\b/;
const TEST_SETS = /\/src\/(test|androidTest|sharedTest)\//;

test("only test source sets hold fake sealers; shipped code only the libsignal one", () => {
  const offenders = [];
  for (const file of files) {
    const match = readFileSync(file, "utf8").match(IMPLEMENTS);
    if (!match || TEST_SETS.test(file)) continue;
    if (!/\/Libsignal\w*\.kt$/.test(file)) offenders.push(`${file}: implements ${match[1]}`);
  }
  assert.deepEqual(offenders, []);
});

test("the shared test fakes are not part of any shipped source set", () => {
  for (const gradle of execFileSync("git", ["ls-files", "*/build.gradle.kts"], { encoding: "utf8" }).split("\n").filter(Boolean)) {
    const text = readFileSync(gradle, "utf8");
    for (const m of text.matchAll(/getByName\("(\w+)"\)\.kotlin\.directories\.add\("src\/sharedTest\/java"\)/g)) {
      assert.ok(["test", "androidTest"].includes(m[1]), `${gradle} adds sharedTest to ${m[1]}`);
    }
  }
});
