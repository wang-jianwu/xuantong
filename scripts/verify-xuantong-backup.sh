#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: verify-xuantong-backup.sh --archive FILE [--expected-node-id NODE]" >&2
}

archive=""
expected_node_id=""
while (($#)); do
  case "$1" in
    --archive) archive="${2:-}"; shift 2 ;;
    --expected-node-id) expected_node_id="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ -z "$archive" || ! -f "$archive" ]]; then
  usage
  exit 2
fi

sha256_file() {
  if command -v shasum >/dev/null; then
    shasum -a 256 "$1" | awk '{print $1}'
  elif command -v sha256sum >/dev/null; then
    sha256sum "$1" | awk '{print $1}'
  else
    echo "shasum or sha256sum is required" >&2
    return 1
  fi
}

md5_file() {
  if command -v md5 >/dev/null; then
    md5 -q "$1"
  elif command -v md5sum >/dev/null; then
    md5sum "$1" | awk '{print $1}'
  else
    echo "md5 or md5sum is required" >&2
    return 1
  fi
}

manifest_value() {
  local key="$1"
  awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$work/manifest.properties"
}

entries="$(tar -tzf "$archive")"
if printf '%s\n' "$entries" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
  echo "Backup archive contains an unsafe path" >&2
  exit 1
fi
for required in manifest.properties state.tar database.backup snapshot-result.json; do
  if ! printf '%s\n' "$entries" | grep -Fxq "$required"; then
    echo "Backup archive is missing $required" >&2
    exit 1
  fi
done

work="$(mktemp -d "${TMPDIR:-/tmp}/xuantong-verify.XXXXXX")"
trap 'rm -rf "$work"' EXIT
tar -xzf "$archive" -C "$work"

if [[ "$(manifest_value format)" != "1" ]]; then
  echo "Unsupported backup manifest format" >&2
  exit 1
fi
node_id="$(manifest_value node_id)"
if [[ -z "$node_id" ]]; then
  echo "Backup manifest has no node_id" >&2
  exit 1
fi
if [[ -n "$expected_node_id" && "$node_id" != "$expected_node_id" ]]; then
  echo "Backup belongs to node $node_id, expected $expected_node_id" >&2
  exit 1
fi

for item in state database snapshot_result; do
  case "$item" in
    state) file="$work/state.tar" ;;
    database) file="$work/database.backup" ;;
    snapshot_result) file="$work/snapshot-result.json" ;;
  esac
  expected="$(manifest_value "${item}_sha256")"
  actual="$(sha256_file "$file")"
  if [[ -z "$expected" || "$expected" != "$actual" ]]; then
    echo "Backup checksum mismatch for $item" >&2
    exit 1
  fi
done

state_entries="$(tar -tf "$work/state.tar")"
if printf '%s\n' "$state_entries" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
  echo "State archive contains an unsafe path" >&2
  exit 1
fi
mkdir "$work/state"
tar -xf "$work/state.tar" -C "$work/state"

group_count="$(find "$work/state" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
expected_groups="$(manifest_value expected_groups)"
if [[ -z "$expected_groups" || "$group_count" -lt "$expected_groups" ]]; then
  echo "State archive has $group_count Group directories, expected at least $expected_groups" >&2
  exit 1
fi

snapshot_count=0
while IFS= read -r checksum_file; do
  [[ -n "$checksum_file" ]] || continue
  snapshot="${checksum_file%.md5}"
  if [[ ! -f "$snapshot" ]]; then
    echo "Snapshot payload is missing for $checksum_file" >&2
    exit 1
  fi
  expected="$(awk '{print $1; exit}' "$checksum_file")"
  actual="$(md5_file "$snapshot")"
  if [[ "$expected" != "$actual" ]]; then
    echo "Snapshot checksum mismatch: $snapshot" >&2
    exit 1
  fi
  snapshot_count=$((snapshot_count + 1))
done < <(find "$work/state" -type f -name 'snapshot.*_*.md5' -print)

if [[ "$snapshot_count" -lt "$expected_groups" ]]; then
  echo "State archive has only $snapshot_count verified Snapshots" >&2
  exit 1
fi
echo "Backup verified: node=$node_id groups=$group_count snapshots=$snapshot_count archive=$archive"
