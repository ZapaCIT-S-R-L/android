# API contract pin

`api-commit` holds the ZapaCIT-S-R-L/api commit whose `openapi.yaml` the app is checked against (CI `unit` job and
`scripts/ci-local.sh`). Every request the app's HTTP clients build in the JVM tests, and every response the test
fakes return, must match it; any drift fails.

To move to a newer contract: put the new api commit SHA (from main) in `api-commit`, run `./scripts/ci-local.sh`,
fix what drifted, and open a PR.

Tests that deliberately answer outside the contract (robustness, or a gap the API hasn't specified yet) mark the
response with the header `X-Contract-Off-Spec: <reason>`; they are listed in the output, not checked.
