// Tests for scripts/contract-check.js: does a recorded HTTP exchange match the API's openapi.yaml?
import { test } from "node:test";
import assert from "node:assert/strict";
import { checkExchanges } from "../scripts/contract-check.js";

const spec = {
  openapi: "3.1.0",
  paths: {
    "/v1/things": {
      post: {
        requestBody: {
          required: true,
          content: { "application/json": { schema: { $ref: "#/components/schemas/Thing" } } },
        },
        responses: {
          201: { content: { "application/json": { schema: { type: "object", required: ["id"], properties: { id: { type: "integer", maximum: 127 } } } } } },
          401: { $ref: "#/components/responses/Unauthorized" },
        },
      },
    },
    "/v1/things/{thingId}": {
      get: { responses: { 200: { description: "No schema yet (draft)." } } },
    },
  },
  components: {
    schemas: {
      Thing: {
        type: "object",
        required: ["name", "at"],
        additionalProperties: false,
        properties: { name: { type: "string" }, at: { type: "string", format: "date-time" } },
      },
      Error: { type: "object", required: ["error"], properties: { error: { type: "object", required: ["code", "message"] } } },
    },
    responses: { Unauthorized: { content: { "application/json": { schema: { $ref: "#/components/schemas/Error" } } } } },
  },
};

const ok = {
  method: "POST",
  path: "/v1/things",
  requestBody: JSON.stringify({ name: "x", at: "2026-10-27T10:00:00Z" }),
  status: 201,
  responseBody: '{"id":3}',
};

test("a request and response that follow the spec pass", () => {
  assert.deepEqual(checkExchanges(spec, [ok]).errors, []);
});

test("a path or method the spec doesn't have is drift", () => {
  assert.match(checkExchanges(spec, [{ ...ok, path: "/v1/other" }]).errors[0], /not in openapi/);
  assert.match(checkExchanges(spec, [{ ...ok, method: "DELETE" }]).errors[0], /not in openapi/);
});

test("path parameters match their templates", () => {
  assert.deepEqual(checkExchanges(spec, [{ method: "GET", path: "/v1/things/42", status: 200, responseBody: "{}" }]).errors, []);
});

test("a request body that breaks its schema is drift", () => {
  const extra = { ...ok, requestBody: JSON.stringify({ name: "x", at: "2026-10-27T10:00:00Z", more: 1 }) };
  assert.match(checkExchanges(spec, [extra]).errors[0], /request/);
  const badDate = { ...ok, requestBody: JSON.stringify({ name: "x", at: "yesterday" }) };
  assert.match(checkExchanges(spec, [badDate]).errors[0], /request/);
  assert.match(checkExchanges(spec, [{ ...ok, requestBody: null }]).errors[0], /request body is required/);
});

test("a status the operation doesn't declare is drift", () => {
  assert.match(checkExchanges(spec, [{ ...ok, status: 409, responseBody: "{}" }]).errors[0], /409/);
});

test("a response body that breaks its schema is drift, also through $ref responses", () => {
  assert.match(checkExchanges(spec, [{ ...ok, responseBody: '{"id":128}' }]).errors[0], /response/);
  assert.match(checkExchanges(spec, [{ ...ok, status: 401, responseBody: '{"error":{}}' }]).errors[0], /response/);
  assert.deepEqual(checkExchanges(spec, [{ ...ok, status: 401, responseBody: '{"error":{"code":"unauthorized","message":"x"}}' }]).errors, []);
});

test("exchanges marked off-spec are listed, not checked", () => {
  const offSpec = { ...ok, status: 409, responseBody: "{}", offSpec: "API gap: no 409 in the contract yet" };
  const result = checkExchanges(spec, [offSpec]);
  assert.deepEqual(result.errors, []);
  assert.match(result.notes[0], /API gap/);
});

test("no recorded exchanges at all fails (the recorder didn't run)", () => {
  assert.match(checkExchanges(spec, []).errors[0], /no exchanges/);
});
