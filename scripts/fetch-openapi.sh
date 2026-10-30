#!/usr/bin/env bash
# Downloads the API's openapi.yaml at the commit pinned in contract/api-commit to build/contract/openapi.yaml.
set -euo pipefail
cd "$(dirname "$0")/.."
sha=$(tr -d '[:space:]' < contract/api-commit)
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo "contract/api-commit must hold a full commit SHA" >&2; exit 1; }
mkdir -p build/contract
curl -fsSL --proto '=https' "https://raw.githubusercontent.com/ZapaCIT-S-R-L/api/$sha/openapi.yaml" -o build/contract/openapi.yaml
echo "openapi.yaml at api@${sha:0:12}"
