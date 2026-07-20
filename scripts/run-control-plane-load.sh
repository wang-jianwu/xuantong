#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-staircase}"
MVN_BIN="${MVN_BIN:-mvn}"
REPORT_DIR="${XUANTONG_LOAD_REPORT_DIR:-output/load-reports}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CLIENTS="${XUANTONG_LOAD_CLIENTS:-64}"
WATCHERS="${XUANTONG_LOAD_WATCHERS:-$CLIENTS}"
FETCH_CONCURRENCY="${XUANTONG_LOAD_FETCH_CONCURRENCY:-32}"
FETCH_RATE="${XUANTONG_LOAD_FETCH_RATE_PER_SECOND:-0}"
PUBLISH_RATE="${XUANTONG_LOAD_PUBLISH_RATE_PER_MINUTE:-6}"
PAYLOAD_BYTES="${XUANTONG_LOAD_PAYLOAD_BYTES:-1024}"
SAMPLE_INTERVAL="${XUANTONG_LOAD_SAMPLE_INTERVAL_SECONDS:-10}"
GROWTH_WARMUP="${XUANTONG_LOAD_GROWTH_WARMUP_SECONDS:-}"
TENANT_REQUEST_RATE="${XUANTONG_LOAD_TENANT_REQUEST_RATE_PER_SECOND:-}"
TENANT_REQUEST_BURST="${XUANTONG_LOAD_TENANT_REQUEST_BURST:-}"
RUN_LABEL="${XUANTONG_LOAD_RUN_LABEL:-}"
BUILD_REVISION="${XUANTONG_LOAD_BUILD_REVISION:-}"
BUILD_STATE="${XUANTONG_LOAD_BUILD_STATE:-}"

if [[ "$REPORT_DIR" != /* ]]; then
  REPORT_DIR="$REPO_ROOT/$REPORT_DIR"
fi
if [[ -z "$BUILD_REVISION" ]]; then
  BUILD_REVISION="$(git -C "$REPO_ROOT" rev-parse --verify HEAD 2>/dev/null || true)"
  BUILD_REVISION="${BUILD_REVISION:-unknown}"
fi
if [[ -z "$BUILD_STATE" ]]; then
  if git -C "$REPO_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    if [[ -n "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=normal)" ]]; then
      BUILD_STATE="dirty"
    else
      BUILD_STATE="clean"
    fi
  else
    BUILD_STATE="unknown"
  fi
fi

mkdir -p "$REPORT_DIR"

run_profile() {
  local name="$1"
  local duration="$2"
  local clients="$3"
  local watchers="$4"
  local concurrency="$5"
  local report="$REPORT_DIR/${name}.jsonl"
  local tenant_rate="$TENANT_REQUEST_RATE"
  local tenant_burst="$TENANT_REQUEST_BURST"
  local mvn_args=(
    -pl xuantong-server
    -am
    -Dtest=ControlPlaneSoakTest
    -Dsurefire.failIfNoSpecifiedTests=false
    -Dxuantong.soak.enabled=true
    "-Dxuantong.soak.durationSeconds=$duration"
    "-Dxuantong.soak.clients=$clients"
    "-Dxuantong.soak.watchers=$watchers"
    "-Dxuantong.soak.fetchConcurrency=$concurrency"
    "-Dxuantong.soak.fetchRatePerSecond=$FETCH_RATE"
  )

  if [[ -z "$tenant_rate" ]]; then
    if (( FETCH_RATE == 0 )); then
      tenant_rate=100000000
    else
      local rate_headroom=$(( FETCH_RATE / 5 ))
      if (( rate_headroom < 100 )); then
        rate_headroom=100
      fi
      tenant_rate=$(( FETCH_RATE + rate_headroom ))
    fi
  fi
  if [[ -z "$tenant_burst" ]]; then
    if (( FETCH_RATE == 0 )); then
      tenant_burst=100000000
    else
      tenant_burst=$(( tenant_rate * 2 ))
      local setup_floor=$(( clients + watchers ))
      if (( tenant_burst < setup_floor )); then
        tenant_burst="$setup_floor"
      fi
    fi
  fi

  mvn_args+=(
    "-Dxuantong.soak.tenantRequestRatePerSecond=$tenant_rate"
    "-Dxuantong.soak.tenantRequestBurst=$tenant_burst"
    "-Dxuantong.soak.publishRatePerMinute=$PUBLISH_RATE"
    "-Dxuantong.soak.payloadBytes=$PAYLOAD_BYTES"
    "-Dxuantong.soak.sampleIntervalSeconds=$SAMPLE_INTERVAL"
  )
  if [[ -n "$GROWTH_WARMUP" ]]; then
    mvn_args+=("-Dxuantong.soak.growthWarmupSeconds=$GROWTH_WARMUP")
  fi
  mvn_args+=(
    "-Dxuantong.soak.runLabel=$RUN_LABEL"
    "-Dxuantong.soak.buildRevision=$BUILD_REVISION"
    "-Dxuantong.soak.buildState=$BUILD_STATE"
    "-Dxuantong.soak.reportPath=$report"
    test
  )

  (cd "$REPO_ROOT" && "$MVN_BIN" "${mvn_args[@]}")
}

case "$MODE" in
  staircase)
    IFS=',' read -r -a stages <<< "${XUANTONG_LOAD_STAGES:-16,32,64,128}"
    duration="${XUANTONG_LOAD_STAGE_DURATION_SECONDS:-300}"
    for clients in "${stages[@]}"; do
      watchers="$clients"
      concurrency="$clients"
      if (( concurrency > 64 )); then
        concurrency=64
      fi
      run_profile "staircase-${clients}" "$duration" "$clients" "$watchers" "$concurrency"
    done
    ;;
  soak24)
    run_profile "soak-24h" 86400 "$CLIENTS" "$WATCHERS" "$FETCH_CONCURRENCY"
    ;;
  soak72)
    run_profile "soak-72h" 259200 "$CLIENTS" "$WATCHERS" "$FETCH_CONCURRENCY"
    ;;
  *)
    echo "Usage: $0 {staircase|soak24|soak72}" >&2
    exit 2
    ;;
esac
