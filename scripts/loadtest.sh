#!/usr/bin/env bash
# Runs loadtest/ingest.js with the k6 image on the compose network, against the app
# container. Start the stack first: docker compose up -d --build --wait
# Options as environment variables: VUS (20), BATCH (50 records per request), DURATION (60s).
#   VUS=40 DURATION=2m scripts/loadtest.sh
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p target/loadtest
docker run --rm --network hr_roster_sync_default \
  -v "$PWD/loadtest:/scripts:ro" -v "$PWD/target/loadtest:/results" \
  -e BASE_URL=http://app:8080 -e VUS="${VUS:-20}" -e BATCH="${BATCH:-50}" -e DURATION="${DURATION:-60s}" \
  grafana/k6:2.3.0 run --quiet --no-color --summary-export=/results/summary.json /scripts/ingest.js
