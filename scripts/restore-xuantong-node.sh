#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: restore-xuantong-node.sh \
  --archive FILE.tar.gz \
  --state-dir EMPTY_OR_MISSING_DIR \
  --database-output MISSING_FILE \
  --expected-node-id NODE \
  --offline-confirmed \
  --confirm-restore

The script restores files only. Import database-output with the matching H2,
MySQL, or PostgreSQL restore command before starting Xuantong.
USAGE
}

archive=""
state_dir=""
database_output=""
expected_node_id=""
offline_confirmed=false
confirm_restore=false

while (($#)); do
  case "$1" in
    --archive) archive="${2:-}"; shift 2 ;;
    --state-dir) state_dir="${2:-}"; shift 2 ;;
    --database-output) database_output="${2:-}"; shift 2 ;;
    --expected-node-id) expected_node_id="${2:-}"; shift 2 ;;
    --offline-confirmed) offline_confirmed=true; shift ;;
    --confirm-restore) confirm_restore=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ "$offline_confirmed" != true || "$confirm_restore" != true ]]; then
  echo "Restore requires --offline-confirmed and --confirm-restore" >&2
  exit 1
fi
if [[ -z "$archive" || -z "$state_dir" || -z "$database_output"
      || -z "$expected_node_id" ]]; then
  usage >&2
  exit 2
fi
if [[ -e "$database_output" ]]; then
  echo "Refusing to overwrite database output: $database_output" >&2
  exit 1
fi
if [[ -e "$state_dir" ]] && find "$state_dir" -mindepth 1 -print -quit | grep -q .; then
  echo "State restore target must be empty: $state_dir" >&2
  exit 1
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
"$script_dir/verify-xuantong-backup.sh" \
  --archive "$archive" \
  --expected-node-id "$expected_node_id"

work="$(mktemp -d "${TMPDIR:-/tmp}/xuantong-restore.XXXXXX")"
state_stage="${state_dir}.restore.$$"
database_stage="${database_output}.restore.$$"
cleanup() {
  rm -rf "$work" "$state_stage"
  rm -f "$database_stage"
}
trap cleanup EXIT

tar -xzf "$archive" -C "$work"
mkdir -p "$state_stage"
tar -xf "$work/state.tar" -C "$state_stage"
mkdir -p "$(dirname "$database_output")" "$(dirname "$state_dir")"
cp -p "$work/database.backup" "$database_stage"

if [[ -d "$state_dir" ]]; then
  rmdir "$state_dir"
fi
mv "$state_stage" "$state_dir"
mv "$database_stage" "$database_output"

trap - EXIT
rm -rf "$work"
echo "State restored for $expected_node_id: $state_dir"
echo "Database backup material restored to: $database_output"
echo "Import the management database, validate data, then start this node."
