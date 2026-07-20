#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  import-xuantong-database.sh --dialect h2 --input FILE --h2-file TARGET \
    --offline-confirmed --confirm-restore
  import-xuantong-database.sh --dialect mysql --input FILE --host HOST \
    --port PORT --database DB --user USER --target-empty-confirmed --confirm-restore
  import-xuantong-database.sh --dialect pgsql --input FILE --host HOST \
    --port PORT --database DB --user USER --target-empty-confirmed --confirm-restore

Passwords are read from XUANTONG_DB_PASSWORD and never placed in process
arguments. MySQL/PostgreSQL targets are queried and must contain zero tables.
H2 restore requires every process that can open the target file to be stopped.
USAGE
}

dialect=""
input=""
host=""
port=""
database=""
user=""
h2_file=""
offline_confirmed=false
target_empty_confirmed=false
confirm_restore=false

while (($#)); do
  case "$1" in
    --dialect) dialect="${2:-}"; shift 2 ;;
    --input) input="${2:-}"; shift 2 ;;
    --host) host="${2:-}"; shift 2 ;;
    --port) port="${2:-}"; shift 2 ;;
    --database) database="${2:-}"; shift 2 ;;
    --user) user="${2:-}"; shift 2 ;;
    --h2-file) h2_file="${2:-}"; shift 2 ;;
    --offline-confirmed) offline_confirmed=true; shift ;;
    --target-empty-confirmed) target_empty_confirmed=true; shift ;;
    --confirm-restore) confirm_restore=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ -z "$dialect" || -z "$input" || "$confirm_restore" != true ]]; then
  usage >&2
  exit 2
fi
if [[ ! -f "$input" || ! -s "$input" || -L "$input" ]]; then
  echo "Database backup must be a non-empty regular file: $input" >&2
  exit 1
fi

require_remote_arguments() {
  if [[ -z "$host" || -z "$port" || -z "$database" || -z "$user" ]]; then
    usage >&2
    exit 2
  fi
  if [[ "$target_empty_confirmed" != true ]]; then
    echo "Remote restore requires --target-empty-confirmed" >&2
    exit 1
  fi
  : "${XUANTONG_DB_PASSWORD:?XUANTONG_DB_PASSWORD is required}"
}

case "$dialect" in
  h2)
    if [[ "$offline_confirmed" != true ]]; then
      echo "H2 restore requires --offline-confirmed" >&2
      exit 1
    fi
    if [[ -z "$h2_file" ]]; then
      usage >&2
      exit 2
    fi
    if [[ -e "$h2_file" ]]; then
      echo "Refusing to overwrite H2 database: $h2_file" >&2
      exit 1
    fi
    mkdir -p "$(dirname "$h2_file")"
    stage="${h2_file}.restore.$$"
    trap 'rm -f "$stage"' EXIT
    cp -p "$input" "$stage"
    mv "$stage" "$h2_file"
    trap - EXIT
    ;;
  mysql)
    require_remote_arguments
    command -v mysql >/dev/null || {
      echo "mysql client is required" >&2
      exit 1
    }
    table_count="$(MYSQL_PWD="$XUANTONG_DB_PASSWORD" mysql \
      --host="$host" --port="$port" --user="$user" \
      --connect-timeout=10 \
      --batch --skip-column-names "$database" \
      --execute='SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()')"
    if [[ "$table_count" != "0" ]]; then
      echo "Refusing to import into non-empty MySQL database: tables=$table_count" >&2
      exit 1
    fi
    MYSQL_PWD="$XUANTONG_DB_PASSWORD" mysql \
      --host="$host" --port="$port" --user="$user" \
      --connect-timeout=10 "$database" <"$input"
    ;;
  pgsql|postgres|postgresql)
    require_remote_arguments
    command -v psql >/dev/null || {
      echo "psql is required" >&2
      exit 1
    }
    command -v pg_restore >/dev/null || {
      echo "pg_restore is required" >&2
      exit 1
    }
    table_count="$(PGPASSWORD="$XUANTONG_DB_PASSWORD" psql \
      --host="$host" --port="$port" --username="$user" --dbname="$database" \
      --tuples-only --no-align --set=ON_ERROR_STOP=1 \
      --command='SELECT COUNT(*) FROM pg_catalog.pg_tables WHERE schemaname = current_schema()')"
    if [[ "$table_count" != "0" ]]; then
      echo "Refusing to import into non-empty PostgreSQL database: tables=$table_count" >&2
      exit 1
    fi
    PGPASSWORD="$XUANTONG_DB_PASSWORD" pg_restore \
      --host="$host" --port="$port" --username="$user" --dbname="$database" \
      --exit-on-error --single-transaction --no-owner --no-privileges "$input"
    ;;
  *)
    echo "Unsupported database dialect: $dialect" >&2
    exit 2
    ;;
esac

echo "Database restore completed for dialect=$dialect"
echo "Start Xuantong, wait for projection recovery, then call:"
echo "  GET /api/v2/state-cluster/consistency"
