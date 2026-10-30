// Uploads a signed release AAB to the Google Play internal testing track (Google Play Developer API v3, edits).
// Used only by .github/workflows/play-internal.yml, in the protected `play` environment.
// CLI: PLAY_ACCESS_TOKEN=... node scripts/play-upload.js <app.aab> <release name> [draft|completed]
// The access token comes from google-github-actions/auth (androidpublisher scope); it is never printed.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const PACKAGE = "ro.zapakid.app";
const API = "https://androidpublisher.googleapis.com";

export async function uploadToInternal({ fetch, token, packageName, aab, releaseName, status, log = console.log }) {
  if (packageName !== PACKAGE) throw new Error(`only ${PACKAGE} is published from this repository`);
  if (!["draft", "completed"].includes(status)) throw new Error("status must be draft or completed");
  const app = `${API}/androidpublisher/v3/applications/${packageName}`;
  const auth = { Authorization: `Bearer ${token}` };

  async function call(step, url, init) {
    const response = await fetch(url, { ...init, headers: { ...auth, ...(init.headers ?? {}) } });
    const text = await response.text();
    if (!response.ok) {
      let message = text.slice(0, 300);
      try {
        message = JSON.parse(text).error?.message ?? message;
      } catch {
        // not JSON: keep the start of the body
      }
      throw new Error(`Play API ${step} failed: HTTP ${response.status}: ${message}`);
    }
    return text ? JSON.parse(text) : {};
  }

  const edit = await call("open edit", `${app}/edits`, { method: "POST" });
  log(`edit ${edit.id} opened`);
  const bundle = await call(
    "upload bundle",
    `${API}/upload/androidpublisher/v3/applications/${packageName}/edits/${edit.id}/bundles?uploadType=media`,
    { method: "POST", headers: { "Content-Type": "application/octet-stream" }, body: aab },
  );
  log(`bundle uploaded: versionCode ${bundle.versionCode}`);
  await call("set internal track", `${app}/edits/${edit.id}/tracks/internal`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      track: "internal",
      releases: [{ name: releaseName, versionCodes: [String(bundle.versionCode)], status }],
    }),
  });
  await call("commit", `${app}/edits/${edit.id}:commit`, { method: "POST" });
  log(`internal track: ${releaseName} (versionCode ${bundle.versionCode}, ${status})`);
  return { versionCode: bundle.versionCode, editId: edit.id };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [aabPath, releaseName, status = "draft"] = process.argv.slice(2);
  const token = process.env.PLAY_ACCESS_TOKEN;
  if (!token || !aabPath || !releaseName) {
    console.error("usage: PLAY_ACCESS_TOKEN=... node scripts/play-upload.js <app.aab> <release name> [draft|completed]");
    process.exit(2);
  }
  uploadToInternal({ fetch, token, packageName: PACKAGE, aab: readFileSync(aabPath), releaseName, status }).catch((e) => {
    console.error(e.message);
    process.exit(1);
  });
}
