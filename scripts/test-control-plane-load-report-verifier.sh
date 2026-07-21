#!/usr/bin/env bash
set -euo pipefail

REPORT="${1:-}"
EXPECTED_CRASH_TARGET="${2:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFIER="$SCRIPT_DIR/verify-control-plane-load-report.sh"

if [[ -z "$REPORT" \
      || ("$EXPECTED_CRASH_TARGET" != "follower" \
        && "$EXPECTED_CRASH_TARGET" != "leader") ]]; then
  echo "Usage: $0 <report.jsonl> <follower|leader>" >&2
  exit 2
fi
if [[ ! -s "$REPORT" ]]; then
  echo "Control-plane load report is missing or empty: $REPORT" >&2
  exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to test the report verifier" >&2
  exit 1
fi

TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/xuantong-report-verifier.XXXXXX")"
cleanup() {
  rm -rf -- "$TEMP_DIR"
}
trap cleanup EXIT

assert_rejected() {
  local name="$1"
  local filter="$2"
  local candidate="$TEMP_DIR/$name.jsonl"
  jq -c "$filter" "$REPORT" > "$candidate"
  if "$VERIFIER" split-topology "$candidate" \
      "$EXPECTED_CRASH_TARGET" >/dev/null 2>&1; then
    echo "Verifier accepted invalid report mutation: $name" >&2
    exit 1
  fi
}

"$VERIFIER" split-topology "$REPORT" \
  "$EXPECTED_CRASH_TARGET" >/dev/null

assert_rejected "missing-header" '
  select(.type != "header")
'
assert_rejected "runtime-mismatch" '
  if .type == "header" then
    .runtime.javaVersion = "tampered"
  else
    .
  end
'
assert_rejected "multiple-active-sessions" '
  if .type == "sample" and .phase == "pre-crash" then
    .clientMaxActiveSessions = 2
    | .clientsWithMultipleActiveSessions = 1
  else
    .
  end
'
assert_rejected "watch-residue-after-close" '
  if .type == "summary" then
    .clientAfterClose.registeredWatches = 1
  else
    .
  end
'

if [[ "$EXPECTED_CRASH_TARGET" == "follower" ]]; then
  wrong_target="leader"
else
  wrong_target="follower"
fi
if "$VERIFIER" split-topology "$REPORT" \
    "$wrong_target" >/dev/null 2>&1; then
  echo "Verifier accepted the wrong expected crash role" >&2
  exit 1
fi

echo "Control-plane load report verifier contract passed: target=$EXPECTED_CRASH_TARGET"
