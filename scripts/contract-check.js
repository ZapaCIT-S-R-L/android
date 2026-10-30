// Contract test: every HTTP exchange the app's JVM tests made (the requests the app built, the responses the fakes
// returned) must match the API's openapi.yaml at the commit pinned in contract/api-commit. Any drift fails.
//   - the path (with {templates}) and method exist;
//   - the request body is present when required and matches the requestBody schema;
//   - the status is declared for the operation, and a JSON response matches its schema.
// Exchanges a test marks off-spec (response header X-Contract-Off-Spec: <reason>) are robustness tests against
// answers the contract doesn't define (yet): they are listed, not checked.
// CLI: node scripts/contract-check.js <openapi.yaml> <exchanges.jsonl>
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { parse } from "yaml";

const SPEC_ID = "openapi";

const escapePointer = (s) => s.replaceAll("~", "~0").replaceAll("/", "~1");

function findOperation(spec, method, path) {
  for (const [template, item] of Object.entries(spec.paths ?? {})) {
    const pattern = new RegExp(`^${template.replace(/[.*+?^$()|[\]\\]/g, "\\$&").replace(/\{[^}]+\}/g, "[^/]+")}$`);
    const op = item?.[method.toLowerCase()];
    if (pattern.test(path) && op) return { template, op };
  }
  return null;
}

function resolve(spec, node) {
  let current = node;
  while (current?.$ref) {
    const parts = current.$ref.replace(/^#\//, "").split("/").map((p) => p.replaceAll("~1", "/").replaceAll("~0", "~"));
    current = parts.reduce((o, k) => o?.[k], spec);
  }
  return current;
}

export function checkExchanges(spec, exchanges) {
  const ajv = new Ajv2020({ strict: false, allErrors: true });
  addFormats(ajv);
  ajv.addSchema(spec, SPEC_ID);
  const errors = [];
  const notes = [];
  if (exchanges.length === 0) errors.push("no exchanges recorded: did the JVM tests run with the contract recorder?");

  for (const ex of exchanges) {
    const where = `${ex.method} ${ex.path} -> ${ex.status}`;
    if (ex.offSpec) {
      notes.push(`${where}: off-spec on purpose (${ex.offSpec})`);
      continue;
    }
    const found = findOperation(spec, ex.method, ex.path);
    if (!found) {
      errors.push(`${where}: ${ex.method} ${ex.path} is not in openapi.yaml`);
      continue;
    }
    const base = `${SPEC_ID}#/paths/${escapePointer(found.template)}/${ex.method.toLowerCase()}`;
    const requestBody = resolve(spec, found.op.requestBody);
    const requestSchema = requestBody?.content?.["application/json"]?.schema;
    if (requestBody?.required && (ex.requestBody == null || ex.requestBody === "")) {
      errors.push(`${where}: request body is required`);
    } else if (requestSchema && ex.requestBody) {
      const validate = ajv.getSchema(`${base}/requestBody/content/application~1json/schema`) ??
        ajv.compile({ $ref: `${base}/requestBody/content/application~1json/schema` });
      if (!validate(JSON.parse(ex.requestBody))) errors.push(`${where}: request ${ajv.errorsText(validate.errors)}`);
    }
    const responses = found.op.responses ?? {};
    const declared = responses[String(ex.status)] ?? responses.default;
    if (!declared) {
      errors.push(`${where}: status ${ex.status} is not declared for ${ex.method} ${found.template}`);
      continue;
    }
    const response = resolve(spec, declared);
    const schema = response?.content?.["application/json"]?.schema;
    if (schema && ex.responseBody) {
      const key = responses[String(ex.status)] ? String(ex.status) : "default";
      const pointer = declared.$ref
        ? `${SPEC_ID}${declared.$ref}/content/application~1json/schema`
        : `${base}/responses/${key}/content/application~1json/schema`;
      const validate = ajv.compile({ $ref: pointer });
      let body;
      try {
        body = JSON.parse(ex.responseBody);
      } catch {
        errors.push(`${where}: response is not JSON`);
        continue;
      }
      if (!validate(body)) errors.push(`${where}: response ${ajv.errorsText(validate.errors)}`);
    }
  }
  return { errors, notes };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [specPath, exchangesPath] = process.argv.slice(2);
  const spec = parse(readFileSync(specPath, "utf8"));
  const exchanges = readFileSync(exchangesPath, "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l));
  const { errors, notes } = checkExchanges(spec, exchanges);
  for (const n of notes) console.log(`contract note: ${n}`);
  for (const e of errors) console.error(`contract drift: ${e}`);
  if (errors.length > 0) process.exit(1);
  console.log(`contract: ${exchanges.length - notes.length} exchanges match openapi.yaml (${notes.length} off-spec)`);
}
