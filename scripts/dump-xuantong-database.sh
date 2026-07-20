#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  dump-xuantong-database.sh --dialect h2 --h2-file FILE --output FILE --offline-confirmed
  dump-xuantong-database.sh --dialect mysql --host HOST --port PORT --database DB --user USER --output FILE
  dump-xuantong-database.sh --dialect pgsql --host HOST --port PORT --database DB --user USER --output FILE

Passwords are read from XUANTONG_DB_PASSWORD. They are never placed in the
process arguments. H2 copying is allowed only while every Xuantong Server that
can open the file is stopped.
USAGE
}

dialect=""
host=""
port=""
database=""
user=""
h2_file=""
output=""
offline_confirmed=false

while (($#)); do
  case "$1" in
    --dialect) dialect="${2:-}"; shift 2 ;;
    --host) host="${2:-}"; shift 2 ;;
    --port) port="${2:-}"; shift 2 ;;
    --database) database="${2:-}"; shift 2 ;;
    --user) user="${2:-}"; shift 2 ;;
    --h2-file) h2_file="${2:-}"; shift 2 ;;
    --output) output="${2:-}"; shift 2 ;;
    --offline-confirmed) offline_confirmed=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ -z "$dialect" || -z "$output" ]]; then
  usage >&2
  exit 2
fi
if [[ -e "$output" || -L "$output" ]]; then
  echo "Refusing to overwrite database backup: $output" >&2
  exit 1
fi
mkdir -p "$(dirname "$output")"
output_dir="$(cd "$(dirname "$output")" && pwd -P)"
output_name="$(basename "$output")"
stage="$(mktemp "$output_dir/.${output_name}.partial.XXXXXX")"
child_pid=""
cleanup() {
  if [[ -n "$child_pid" ]] && kill -0 "$child_pid" 2>/dev/null; then
    kill -TERM "$child_pid" 2>/dev/null || true
    wait "$child_pid" 2>/dev/null || true
  fi
  rm -f "$stage"
}
trap cleanup EXIT
on_signal() {
  local status="$1"
  cleanup
  trap - EXIT
  exit "$status"
}
trap 'on_signal 143' TERM
trap 'on_signal 130' INT
trap 'on_signal 129' HUP

run_child() {
  "$@" &
  child_pid=$!
  local status=0
  if wait "$child_pid"; then
    status=0
  else
    status=$?
  fi
  child_pid=""
  return "$status"
}

case "$dialect" in
  h2)
    if [[ "$offline_confirmed" != true ]]; then
      echo "H2 backup requires --offline-confirmed" >&2
      exit 1
    fi
    if [[ -z "$h2_file" || ! -f "$h2_file" ]]; then
      echo "H2 database file does not exist: $h2_file" >&2
      exit 1
    fi
    cp -p "$h2_file" "$stage"
    ;;
  mysql)
    : "${XUANTONG_DB_PASSWORD:?XUANTONG_DB_PASSWORD is required for MySQL dump}"
    if [[ -z "$host" || -z "$port" || -z "$database" || -z "$user" ]]; then
      usage >&2
      exit 2
    fi
    command -v mysqldump >/dev/null || {
      echo "mysqldump is required" >&2
      exit 1
    }
    run_child env MYSQL_PWD="$XUANTONG_DB_PASSWORD" mysqldump \
      --host="$host" \
      --port="$port" \
      --user="$user" \
      --single-transaction \
      --quick \
      --routines \
      --triggers \
      --events \
      --hex-blob \
      --set-gtid-purged=OFF \
      "$database" >"$stage"
    ;;
  pgsql|postgres|postgresql)
    : "${XUANTONG_DB_PASSWORD:?XUANTONG_DB_PASSWORD is required for PostgreSQL dump}"
    if [[ -z "$host" || -z "$port" || -z "$database" || -z "$user" ]]; then
      usage >&2
      exit 2
    fi
    command -v pg_dump >/dev/null || {
      echo "pg_dump is required" >&2
      exit 1
    }
    run_child env PGPASSWORD="$XUANTONG_DB_PASSWORD" pg_dump \
      --host="$host" \
      --port="$port" \
      --username="$user" \
      --format=custom \
      --no-owner \
      --no-privileges \
      --file="$stage" \
      "$database"
    ;;
  *)
    echo "Unsupported database dialect: $dialect" >&2
    exit 2
    ;;
esac

if [[ ! -s "$stage" ]]; then
  echo "Database backup is empty: $output" >&2
  exit 1
fi
if ! ln "$stage" "$output"; then
  echo "Refusing to overwrite database backup created concurrently: $output" >&2
  exit 1
fi
rm -f "$stage"
trap - EXIT TERM INT HUP
echo "Database backup created: $output"
