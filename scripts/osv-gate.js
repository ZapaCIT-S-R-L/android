// Fails the build when OSV-Scanner found a high or critical vulnerability (CVSS 7.0 or more), or one without a rating.
// Accepted exceptions go in osv-scanner.toml ([[IgnoredVulns]] with a reason and an ignoreUntil date).
// CLI (used by .github/workflows/ci.yml): node scripts/osv-gate.js osv-results.json
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const BLOCKING_SCORE = 7.0;

export function blockingVulnerabilities(report) {
  if (!report || !Array.isArray(report.results)) throw new Error("not an OSV-Scanner report");
  const found = [];
  for (const result of report.results) {
    for (const pkg of result.packages ?? []) {
      const name = `${pkg.package?.name}@${pkg.package?.version}`;
      for (const group of pkg.groups ?? []) {
        const score = Number.parseFloat(group.max_severity);
        if (Number.isNaN(score) || score >= BLOCKING_SCORE) {
          found.push({ id: group.ids?.[0] ?? "unknown", package: name, severity: group.max_severity || "unrated" });
        }
      }
    }
  }
  return found;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const path = process.argv[2] ?? "osv-results.json";
  let report;
  try {
    report = JSON.parse(readFileSync(path, "utf8"));
  } catch (error) {
    console.error(`OSV: no readable report at ${path} (${error.message}); the scan didn't run.`);
    process.exit(1);
  }
  const found = blockingVulnerabilities(report);
  for (const v of found) console.error(`OSV: ${v.id} in ${v.package} (severity ${v.severity})`);
  if (found.length > 0) process.exit(1);
  console.log("OSV: no high or critical vulnerabilities.");
}
