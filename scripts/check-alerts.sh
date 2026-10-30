#!/usr/bin/env bash
# Validates the Prometheus config and runs the alert rule unit tests with promtool,
# from the same image docker compose uses, so nothing needs installing.
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE=prom/prometheus:v3.15.0
run() { docker run --rm -v "$PWD/ops/prometheus:/etc/prometheus:ro" -w /etc/prometheus --entrypoint promtool "$IMAGE" "$@"; }

run check config prometheus.yml
run test rules alerts.test.yml
