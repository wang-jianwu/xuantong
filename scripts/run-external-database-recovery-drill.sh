#!/usr/bin/env bash
set -euo pipefail

MVN_BIN="${MVN_BIN:-mvn}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPOSITORY_ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"

ensure_jdk() {
  if [[ -x "${JAVA_HOME:-}/bin/javac" ]]; then
    return
  fi
  if [[ "$(uname -s)" == "Darwin" ]]; then
    local detected
    detected="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [[ -x "$detected/bin/javac" ]]; then
      export JAVA_HOME="$detected"
      export PATH="$JAVA_HOME/bin:$PATH"
      return
    fi
  fi
  echo "A full JDK 21 is required. Set JAVA_HOME to a JDK, not a JRE." >&2
  exit 2
}

configured_dialects=0
if [[ -n "${XUANTONG_RECOVERY_MYSQL_HOST:-}" ]]; then
  configured_dialects=$((configured_dialects + 1))
fi
if [[ -n "${XUANTONG_RECOVERY_PGSQL_HOST:-}" ]]; then
  configured_dialects=$((configured_dialects + 1))
fi
if [[ "$configured_dialects" -eq 0 ]]; then
  echo "Configure at least one isolated recovery database server:" >&2
  echo "  XUANTONG_RECOVERY_MYSQL_HOST/PORT/USER/PASSWORD" >&2
  echo "  XUANTONG_RECOVERY_PGSQL_HOST/PORT/USER/PASSWORD" >&2
  exit 2
fi

ensure_jdk
cd "$REPOSITORY_ROOT"

"$MVN_BIN" -pl xuantong-server -am \
  -Dtest=ExternalDatabaseBackupRestoreDrillTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test

report="$REPOSITORY_ROOT/xuantong-server/target/surefire-reports/TEST-cloud.xuantong.server.config.ExternalDatabaseBackupRestoreDrillTest.xml"
if [[ ! -f "$report" ]]; then
  echo "External database recovery report was not generated: $report" >&2
  exit 1
fi
expected_skipped=$((2 - configured_dialects))
suite="$(grep -m1 '<testsuite ' "$report" || true)"
if [[ "$suite" != *'tests="2"'* \
      || "$suite" != *'errors="0"'* \
      || "$suite" != *'failures="0"'* \
      || "$suite" != *"skipped=\"$expected_skipped\""* ]]; then
  echo "Configured external database recovery drills did not all execute" >&2
  echo "$suite" >&2
  exit 1
fi
echo "External database recovery verified: configured=$configured_dialects skipped=$expected_skipped"
