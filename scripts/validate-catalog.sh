#!/usr/bin/env bash
# Checks a catalog.json with the same parser Portal uses, refusing any problem at all.
# Usage: scripts/validate-catalog.sh path/to/catalog.json
set -euo pipefail
[ $# -eq 1 ] && [ -f "$1" ] || { echo "usage: $0 path/to/catalog.json" >&2; exit 2; }
cd "$(dirname "$0")/.."
CATALOG_FILE="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")" \
  ./gradlew :app:testDebugUnitTest --tests 'com.portal.tv.extensions.CatalogFileTest' --rerun-tasks -q
echo "catalog ok: $1"
