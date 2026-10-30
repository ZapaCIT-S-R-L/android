// Repository rules for the GitHub workflows.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";

const dir = ".github/workflows";
const workflows = Object.fromEntries(readdirSync(dir).map((f) => [f, readFileSync(`${dir}/${f}`, "utf8")]));

test("every action is pinned by a full commit SHA", () => {
  for (const [file, text] of Object.entries(workflows)) {
    for (const [, ref] of text.matchAll(/^\s*-?\s*uses:\s*(\S+)/gm)) {
      if (ref.startsWith("./")) continue;
      assert.match(ref, /@[0-9a-f]{40}$/, `${file}: ${ref}`);
    }
  }
});

test("the Play upload runs only in the protected play environment, by hand, and skips without secrets", () => {
  const play = workflows["play-internal.yml"];
  assert.ok(play, "play-internal.yml exists");
  assert.match(play, /workflow_dispatch:/);
  assert.doesNotMatch(play, /pull_request/);
  assert.match(play, /environment:\s*\n\s*name: play/);
  assert.match(play, /secrets\.PLAY_UPLOAD_KEYSTORE_BASE64/);
  // Keyless publishing: Workload Identity Federation, no service account key anywhere.
  assert.match(play, /workload_identity_provider: \$\{\{ vars\.PLAY_WIF_PROVIDER \}\}/);
  assert.match(play, /service_account: \$\{\{ vars\.PLAY_SERVICE_ACCOUNT \}\}/);
  assert.doesNotMatch(play, /credentials_json|PLAY_SERVICE_ACCOUNT_JSON/);
  assert.match(play, /::notice::.*skipped/i);
});

test("no other workflow can see the Play secrets", () => {
  for (const [file, text] of Object.entries(workflows)) {
    if (file === "play-internal.yml") continue;
    assert.doesNotMatch(text, /PLAY_(UPLOAD|SERVICE)/, file);
  }
});

test("only the Play job may request a GitHub OIDC token", () => {
  for (const [file, text] of Object.entries(workflows)) {
    if (file === "play-internal.yml") assert.match(text, /id-token: write/, file);
    else assert.doesNotMatch(text, /id-token: write/, file);
  }
});
