// Fails the build when OSV-Scanner found a high or critical vulnerability (CVSS 7.0 or more). Findings without a
// rating block too (security first), except with --unrated=warn: used for libsignal's Rust crates, where we can't
// patch anything ourselves (the fix is a libsignal bump) and the unrated ones are "unmaintained" notices.
// Accepted exceptions go in osv-scanner.toml ([[IgnoredVulns]] with a reason and an ignoreUntil date).
// CLI (used by .github/workflows/ci.yml): node scripts/osv-gate.js osv-results.json [--unrated=warn]
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const BLOCKING_SCORE = 7.0;

function findings(report) {
  if (!report || !Array.isArray(report.results)) throw new Error("not an OSV-Scanner report");
  const all = [];
  for (const result of report.results) {
    for (const pkg of result.packages ?? []) {
      const name = `${pkg.package?.name}@${pkg.package?.version}`;
      for (const group of pkg.groups ?? []) {
        const score = Number.parseFloat(group.max_severity);
        all.push({
          id: group.ids?.[0] ?? "unknown",
          package: name,
          severity: group.max_severity || "unrated",
          score: Number.isNaN(score) ? null : score,
        });
      }
    }
  }
  return all;
}

const blocks = (finding, { unrated = "block" } = {}) =>
  finding.score === null ? unrated !== "warn" : finding.score >= BLOCKING_SCORE;

export function blockingVulnerabilities(report, options = {}) {
  return findings(report)
    .filter((f) => blocks(f, options))
    .map(({ id, package: pkg, severity }) => ({ id, package: pkg, severity }));
}

export function warnings(report, options = {}) {
  return findings(report)
    .filter((f) => !blocks(f, options))
    .map(({ id, package: pkg, severity }) => ({ id, package: pkg, severity }));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const path = args.find((a) => !a.startsWith("--")) ?? "osv-results.json";
  const options = args.includes("--unrated=warn") ? { unrated: "warn" } : {};
  let report;
  try {
    report = JSON.parse(readFileSync(path, "utf8"));
  } catch (error) {
    console.error(`OSV: no readable report at ${path} (${error.message}); the scan didn't run.`);
    process.exit(1);
  }
  for (const v of warnings(report, options)) console.log(`OSV note: ${v.id} in ${v.package} (severity ${v.severity})`);
  const found = blockingVulnerabilities(report, options);
  for (const v of found) console.error(`OSV: ${v.id} in ${v.package} (severity ${v.severity})`);
  if (found.length > 0) process.exit(1);
  console.log(`OSV: no ${options.unrated === "warn" ? "high or critical" : "high, critical or unrated"} vulnerabilities.`);
}
