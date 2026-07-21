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
TOPOLOGY_FAILOVER="${XUANTONG_LOAD_TOPOLOGY_FAILOVER:-true}"
SPLIT_CHILD_MAX_HEAP_MB="${XUANTONG_LOAD_SPLIT_CHILD_MAX_HEAP_MB:-512}"
SPLIT_STARTUP_TIMEOUT_SECONDS="${XUANTONG_LOAD_SPLIT_STARTUP_TIMEOUT_SECONDS:-60}"
SPLIT_CRASH_TARGET="${XUANTONG_LOAD_SPLIT_CRASH_TARGET:-follower}"

if [[ "$SPLIT_CRASH_TARGET" != "leader" && "$SPLIT_CRASH_TARGET" != "follower" ]]; then
  echo "XUANTONG_LOAD_SPLIT_CRASH_TARGET must be leader or follower" >&2
  exit 2
fi

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

preserve_failed_split_report() {
  local name="$1"
  local temporary_report="$2"
  if [[ ! -s "$temporary_report" ]]; then
    rm -f -- "$temporary_report"
    return
  fi
  local failed_report
  failed_report="$REPORT_DIR/${name}.failed-$(date -u +%Y%m%dT%H%M%SZ)-$$-${RANDOM}.jsonl"
  mv -f -- "$temporary_report" "$failed_report"
  echo "Preserved failed split-topology report: $failed_report" >&2
}

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

run_topology_profile() {
  local name="$1"
  local duration="$2"
  local clients="$3"
  local watchers="$4"
  local concurrency="$5"
  local report="$REPORT_DIR/${name}.jsonl"
  local mvn_args=(
    -pl xuantong-server
    -am
    -Dtest=ControlPlaneProductionTopologyLoadTest
    -Dsurefire.failIfNoSpecifiedTests=false
    -Dxuantong.topology.enabled=true
    "-Dxuantong.topology.durationSeconds=$duration"
    "-Dxuantong.topology.clients=$clients"
    "-Dxuantong.topology.watchers=$watchers"
    "-Dxuantong.topology.fetchConcurrency=$concurrency"
    "-Dxuantong.topology.fetchRatePerSecond=$FETCH_RATE"
    "-Dxuantong.topology.publishRatePerMinute=$PUBLISH_RATE"
    "-Dxuantong.topology.payloadBytes=$PAYLOAD_BYTES"
    "-Dxuantong.topology.failoverEnabled=$TOPOLOGY_FAILOVER"
    "-Dxuantong.topology.runLabel=$RUN_LABEL"
    "-Dxuantong.topology.buildRevision=$BUILD_REVISION"
    "-Dxuantong.topology.buildState=$BUILD_STATE"
    "-Dxuantong.topology.reportPath=$report"
    test
  )

  (cd "$REPO_ROOT" && "$MVN_BIN" "${mvn_args[@]}")
}

run_split_topology_profile() {
  local name="$1"
  local duration="$2"
  local clients="$3"
  local watchers="$4"
  local concurrency="$5"
  local crash_target="${6:-$SPLIT_CRASH_TARGET}"
  local report="$REPORT_DIR/${name}.jsonl"
  local temporary_report=
  temporary_report="$REPORT_DIR/.${name}.tmp-$$-${RANDOM}.jsonl"
  local mvn_args=(
    -pl xuantong-server
    -am
    -Dtest=ControlPlaneSplitProcessTopologyLoadTest
    -Dsurefire.failIfNoSpecifiedTests=false
    -Dxuantong.splitTopology.enabled=true
    "-Dxuantong.splitTopology.durationSeconds=$duration"
    "-Dxuantong.splitTopology.clients=$clients"
    "-Dxuantong.splitTopology.watchers=$watchers"
    "-Dxuantong.splitTopology.fetchConcurrency=$concurrency"
    "-Dxuantong.splitTopology.fetchRatePerSecond=$FETCH_RATE"
    "-Dxuantong.splitTopology.publishRatePerMinute=$PUBLISH_RATE"
    "-Dxuantong.splitTopology.payloadBytes=$PAYLOAD_BYTES"
    "-Dxuantong.splitTopology.childMaxHeapMb=$SPLIT_CHILD_MAX_HEAP_MB"
    "-Dxuantong.splitTopology.startupTimeoutSeconds=$SPLIT_STARTUP_TIMEOUT_SECONDS"
    "-Dxuantong.splitTopology.sampleIntervalSeconds=$SAMPLE_INTERVAL"
    "-Dxuantong.splitTopology.crashTarget=$crash_target"
    "-Dxuantong.splitTopology.runLabel=$RUN_LABEL"
    "-Dxuantong.splitTopology.buildRevision=$BUILD_REVISION"
    "-Dxuantong.splitTopology.buildState=$BUILD_STATE"
    "-Dxuantong.splitTopology.reportPath=$temporary_report"
  )

  if [[ -n "$GROWTH_WARMUP" ]]; then
    mvn_args+=("-Dxuantong.splitTopology.growthWarmupSeconds=$GROWTH_WARMUP")
  fi
  mvn_args+=(test)

  if ! (cd "$REPO_ROOT" && "$MVN_BIN" "${mvn_args[@]}"); then
    preserve_failed_split_report "$name" "$temporary_report"
    return 1
  fi
  if ! "$REPO_ROOT/scripts/verify-control-plane-load-report.sh" \
      split-topology "$temporary_report" "$crash_target"; then
    preserve_failed_split_report "$name" "$temporary_report"
    return 1
  fi
  mv -f -- "$temporary_report" "$report"
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
  topology)
    run_topology_profile \
      "production-topology" \
      "${XUANTONG_LOAD_TOPOLOGY_DURATION_SECONDS:-300}" \
      "$CLIENTS" \
      "$WATCHERS" \
      "$FETCH_CONCURRENCY"
    ;;
  topology-staircase)
    IFS=',' read -r -a stages <<< "${XUANTONG_LOAD_TOPOLOGY_STAGES:-12,24,48}"
    duration="${XUANTONG_LOAD_TOPOLOGY_STAGE_DURATION_SECONDS:-300}"
    for clients in "${stages[@]}"; do
      watchers="$clients"
      concurrency="$clients"
      if (( concurrency > 64 )); then
        concurrency=64
      fi
      run_topology_profile \
        "production-topology-${clients}" \
        "$duration" \
        "$clients" \
        "$watchers" \
        "$concurrency"
    done
    ;;
  split-topology)
    run_split_topology_profile \
      "split-process-topology" \
      "${XUANTONG_LOAD_SPLIT_DURATION_SECONDS:-300}" \
      "$CLIENTS" \
      "$WATCHERS" \
      "$FETCH_CONCURRENCY"
    ;;
  split-topology-staircase)
    IFS=',' read -r -a stages <<< "${XUANTONG_LOAD_SPLIT_STAGES:-6,12,24}"
    duration="${XUANTONG_LOAD_SPLIT_STAGE_DURATION_SECONDS:-300}"
    for clients in "${stages[@]}"; do
      watchers="$clients"
      concurrency="$clients"
      if (( concurrency > 64 )); then
        concurrency=64
      fi
      run_split_topology_profile \
        "split-process-topology-${clients}" \
        "$duration" \
        "$clients" \
        "$watchers" \
        "$concurrency"
    done
    ;;
  split-topology-matrix)
    for crash_target in follower leader; do
      run_split_topology_profile \
        "split-process-topology-${crash_target}" \
        "${XUANTONG_LOAD_SPLIT_DURATION_SECONDS:-300}" \
        "$CLIENTS" \
        "$WATCHERS" \
        "$FETCH_CONCURRENCY" \
        "$crash_target"
    done
    ;;
  split-topology-matrix-staircase)
    IFS=',' read -r -a stages <<< "${XUANTONG_LOAD_SPLIT_STAGES:-6,12,24}"
    duration="${XUANTONG_LOAD_SPLIT_STAGE_DURATION_SECONDS:-300}"
    for clients in "${stages[@]}"; do
      watchers="$clients"
      concurrency="$clients"
      if (( concurrency > 64 )); then
        concurrency=64
      fi
      for crash_target in follower leader; do
        run_split_topology_profile \
          "split-process-topology-${crash_target}-${clients}" \
          "$duration" \
          "$clients" \
          "$watchers" \
          "$concurrency" \
          "$crash_target"
      done
    done
    ;;
  split-topology-soak24)
    run_split_topology_profile \
      "split-process-topology-${SPLIT_CRASH_TARGET}-soak-24h" \
      86400 \
      "$CLIENTS" \
      "$WATCHERS" \
      "$FETCH_CONCURRENCY" \
      "$SPLIT_CRASH_TARGET"
    ;;
  split-topology-soak72)
    run_split_topology_profile \
      "split-process-topology-${SPLIT_CRASH_TARGET}-soak-72h" \
      259200 \
      "$CLIENTS" \
      "$WATCHERS" \
      "$FETCH_CONCURRENCY" \
      "$SPLIT_CRASH_TARGET"
    ;;
  *)
    echo "Usage: $0 {staircase|soak24|soak72|topology|topology-staircase|split-topology|split-topology-staircase|split-topology-matrix|split-topology-matrix-staircase|split-topology-soak24|split-topology-soak72}" >&2
    exit 2
    ;;
esac
