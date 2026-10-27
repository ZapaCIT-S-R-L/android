// Tests for scripts/play-upload.js: the Google Play Developer API calls that put a signed AAB on the internal track.
import { test } from "node:test";
import assert from "node:assert/strict";
import { uploadToInternal } from "../scripts/play-upload.js";

const API = "https://androidpublisher.googleapis.com";

function fakePlay({ failAt } = {}) {
  const calls = [];
  const fetch = async (url, init = {}) => {
    calls.push({ url, method: init.method ?? "GET", headers: init.headers ?? {}, body: init.body });
    const step = calls.length;
    if (step === failAt) return new Response(JSON.stringify({ error: { message: "The caller does not have permission" } }), { status: 403 });
    if (url.endsWith("/edits")) return Response.json({ id: "edit-1" });
    if (url.includes("/bundles?uploadType=media")) return Response.json({ versionCode: 42 });
    if (url.includes("/tracks/internal")) return Response.json({ track: "internal" });
    if (url.endsWith(":commit")) return Response.json({ id: "edit-1" });
    return new Response("unexpected", { status: 500 });
  };
  return { calls, fetch };
}

const options = (fetch) => ({
  fetch,
  token: "ya29.secret-token",
  packageName: "ro.zapakid.app",
  aab: new Uint8Array([1, 2, 3]),
  releaseName: "v1.2.3",
  status: "draft",
  log: () => {},
});

test("opens an edit, uploads the bundle, puts it on internal and commits", async () => {
  const play = fakePlay();
  const result = await uploadToInternal(options(play.fetch));

  assert.deepEqual(result, { versionCode: 42, editId: "edit-1" });
  assert.deepEqual(
    play.calls.map((c) => `${c.method} ${c.url.replace(API, "")}`),
    [
      "POST /androidpublisher/v3/applications/ro.zapakid.app/edits",
      "POST /upload/androidpublisher/v3/applications/ro.zapakid.app/edits/edit-1/bundles?uploadType=media",
      "PUT /androidpublisher/v3/applications/ro.zapakid.app/edits/edit-1/tracks/internal",
      "POST /androidpublisher/v3/applications/ro.zapakid.app/edits/edit-1:commit",
    ],
  );
  for (const call of play.calls) assert.equal(call.headers.Authorization, "Bearer ya29.secret-token");
  assert.equal(play.calls[1].headers["Content-Type"], "application/octet-stream");
  assert.deepEqual(JSON.parse(play.calls[2].body), {
    track: "internal",
    releases: [{ name: "v1.2.3", versionCodes: ["42"], status: "draft" }],
  });
});

test("only the internal track, only ro.zapakid.app, only draft or completed", async () => {
  const play = fakePlay();
  await assert.rejects(uploadToInternal({ ...options(play.fetch), packageName: "com.example" }), /ro\.zapakid\.app/);
  await assert.rejects(uploadToInternal({ ...options(play.fetch), status: "inProgress" }), /draft or completed/);
  assert.equal(play.calls.length, 0);
});

test("a Play error stops the upload and never prints the token", async () => {
  const play = fakePlay({ failAt: 2 });
  const lines = [];
  await assert.rejects(uploadToInternal({ ...options(play.fetch), log: (l) => lines.push(l) }), (error) => {
    assert.match(error.message, /403/);
    assert.match(error.message, /permission/);
    assert.doesNotMatch(error.message, /ya29/);
    return true;
  });
  assert.equal(play.calls.length, 2, "nothing after the failed step");
  assert.doesNotMatch(lines.join("\n"), /ya29/);
});
