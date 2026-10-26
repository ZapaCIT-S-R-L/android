// Tests for scripts/osv-gate.js: does an OSV-Scanner JSON report block the build?
import { test } from "node:test";
import assert from "node:assert/strict";
import { blockingVulnerabilities } from "../scripts/osv-gate.js";

const report = (groups) => ({
  results: [
    {
      source: { path: "gradle/verification-metadata.xml", type: "lockfile" },
      packages: [{ package: { name: "com.example:lib", version: "1.0.0", ecosystem: "Maven" }, groups }],
    },
  ],
});

test("no results means nothing blocks", () => {
  assert.deepEqual(blockingVulnerabilities({ results: [] }), []);
});

test("low and medium (below 7.0) don't block", () => {
  assert.deepEqual(blockingVulnerabilities(report([{ ids: ["GHSA-low"], max_severity: "3.1" }, { ids: ["GHSA-mid"], max_severity: "6.9" }])), []);
});

test("high (7.0) and critical block", () => {
  const found = blockingVulnerabilities(report([{ ids: ["GHSA-high"], max_severity: "7.0" }, { ids: ["CVE-crit"], max_severity: "9.8" }]));
  assert.deepEqual(
    found.map((v) => v.id),
    ["GHSA-high", "CVE-crit"],
  );
  assert.equal(found[0].package, "com.example:lib@1.0.0");
});

test("an unknown severity blocks until someone rates it (security first)", () => {
  assert.deepEqual(
    blockingVulnerabilities(report([{ ids: ["GHSA-unrated"], max_severity: "" }])).map((v) => v.id),
    ["GHSA-unrated"],
  );
});

test("a report without the results list is rejected, not treated as clean", () => {
  assert.throws(() => blockingVulnerabilities({}), /not an OSV-Scanner report/);
});
