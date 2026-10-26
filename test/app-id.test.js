// The app id is ro.zapakid.app (decided 2026-10-26). The old id must not come back anywhere in the repository:
// not in Gradle, the manifest, Kotlin packages, tests or docs.
import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

// Built from parts so this file doesn't contain the id it forbids.
const OLD_ID = ["com", "zapacit", "cuzaai"];
const OLD = new RegExp(`${OLD_ID.join("[./]")}|\\b${OLD_ID[2]}\\b`, "i");

const files = execFileSync("git", ["ls-files", "-z"], { encoding: "utf8" })
  .split("\0")
  .filter(Boolean)
  .filter((f) => !/\.(jar|png|webp|jpg|ico)$/.test(f));

test("the old app id appears nowhere in the repository", () => {
  const hits = [];
  for (const file of files) {
    if (OLD.test(file)) hits.push(`${file} (path)`);
    const text = readFileSync(file, "utf8");
    text.split("\n").forEach((line, i) => {
      if (OLD.test(line)) hits.push(`${file}:${i + 1}`);
    });
  }
  assert.deepEqual(hits, []);
});

test("Gradle uses ro.zapakid.app as application id and namespace", () => {
  const gradle = readFileSync("app/build.gradle.kts", "utf8");
  assert.match(gradle, /applicationId = "ro\.zapakid\.app"/);
  assert.match(gradle, /namespace = "ro\.zapakid\.app"/);
});
