#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: backup-xuantong-node.sh \
  --state-dir DIR \
  --database-backup FILE \
  --snapshot-result FILE \
  --node-id NODE \
  --output FILE.tar.gz \
  --offline-confirmed \
  [--expected-groups 2]

Before running this script:
  1. POST /api/v2/state-cluster/snapshot for this target node.
  2. Create a consistent management DB dump.
  3. Stop the target State node and verify it is offline.

The archive contains the complete local Ratis directory, not just Snapshot
files. It is only valid for restoring the same State node identity.
USAGE
}

state_dir=""
database_backup=""
snapshot_result=""
node_id=""
output=""
expected_groups=2
offline_confirmed=false

while (($#)); do
  case "$1" in
    --state-dir) state_dir="${2:-}"; shift 2 ;;
    --database-backup) database_backup="${2:-}"; shift 2 ;;
    --snapshot-result) snapshot_result="${2:-}"; shift 2 ;;
    --node-id) node_id="${2:-}"; shift 2 ;;
    --output) output="${2:-}"; shift 2 ;;
    --expected-groups) expected_groups="${2:-}"; shift 2 ;;
    --offline-confirmed) offline_confirmed=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ "$offline_confirmed" != true ]]; then
  echo "Refusing live Ratis copy; pass --offline-confirmed after stopping the node" >&2
  exit 1
fi
if [[ -z "$state_dir" || -z "$database_backup" || -z "$snapshot_result"
      || -z "$node_id" || -z "$output" ]]; then
  usage >&2
  exit 2
fi
if [[ ! "$expected_groups" =~ ^[1-9][0-9]*$ ]]; then
  echo "--expected-groups must be positive" >&2
  exit 2
fi

state_dir="$(cd "$state_dir" 2>/dev/null && pwd -P)" || {
  echo "State directory does not exist: $state_dir" >&2
  exit 1
}
if [[ "$state_dir" == "/" || "$state_dir" == "$HOME" ]]; then
  echo "Unsafe State directory: $state_dir" >&2
  exit 1
fi
if [[ ! -s "$database_backup" || ! -s "$snapshot_result" ]]; then
  echo "Database backup and Snapshot result must both be non-empty" >&2
  exit 1
fi
snapshot_compact="$(tr -d '[:space:]' <"$snapshot_result")"
if [[ "$snapshot_compact" != *"\"targetNodeId\":\"$node_id\""* ]]; then
  echo "Snapshot result does not belong to target node $node_id" >&2
  exit 1
fi
snapshot_result_groups="$(awk -v RS='\"groupType\"' 'END {print NR - 1}' "$snapshot_result")"
if [[ "$snapshot_result_groups" -lt "$expected_groups" ]]; then
  echo "Snapshot result covers only $snapshot_result_groups Groups" >&2
  exit 1
fi
if [[ -e "$output" || -e "$output.sha256" ]]; then
  echo "Refusing to overwrite backup output: $output" >&2
  exit 1
fi
if find "$state_dir" -type l -print -quit | grep -q .; then
  echo "State directory must not contain symbolic links" >&2
  exit 1
fi

group_count="$(find "$state_dir" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
if [[ "$group_count" -lt "$expected_groups" ]]; then
  echo "State directory has $group_count Group directories, expected at least $expected_groups" >&2
  exit 1
fi

for group_dir in "$state_dir"/*; do
  [[ -d "$group_dir" ]] || continue
  if [[ ! -f "$group_dir/current/raft-meta" || ! -d "$group_dir/sm" ]]; then
    echo "Incomplete Ratis Group directory: $group_dir" >&2
    exit 1
  fi
done

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
done < <(find "$state_dir" -type f -name 'snapshot.*_*.md5' -print)
if [[ "$snapshot_count" -lt "$expected_groups" ]]; then
  echo "Only $snapshot_count verified Snapshots found" >&2
  exit 1
fi

mkdir -p "$(dirname "$output")"
work="$(mktemp -d "${TMPDIR:-/tmp}/xuantong-backup.XXXXXX")"
trap 'rm -rf "$work"' EXIT
tar -C "$state_dir" -cf "$work/state.tar" .
cp -p "$database_backup" "$work/database.backup"
cp -p "$snapshot_result" "$work/snapshot-result.json"

created_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
state_files="$(find "$state_dir" -type f | wc -l | tr -d ' ')"
cat >"$work/manifest.properties" <<EOF
format=1
node_id=$node_id
created_at_utc=$created_at
expected_groups=$expected_groups
group_directories=$group_count
state_files=$state_files
verified_snapshots=$snapshot_count
state_sha256=$(sha256_file "$work/state.tar")
database_sha256=$(sha256_file "$work/database.backup")
snapshot_result_sha256=$(sha256_file "$work/snapshot-result.json")
EOF

tar -C "$work" -czf "$output" \
  manifest.properties state.tar database.backup snapshot-result.json
sha256_file "$output" >"$output.sha256"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
"$script_dir/verify-xuantong-backup.sh" \
  --archive "$output" \
  --expected-node-id "$node_id"
echo "Node backup created: $output"
