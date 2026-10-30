#!/usr/bin/env bash
# Writes a synthetic nightly export (see ExportGenerator for the options).
#   scripts/generate-export.sh --rows 200000 --out data/generated/full.csv
#   scripts/generate-export.sh --rows 200000 --truncate-at 120000 --out data/generated/cut.csv
set -euo pipefail
cd "$(dirname "$0")/.."

./mvnw -q test-compile
java -cp target/test-classes com.moveinsync.rostersync.tools.ExportGenerator "$@"
