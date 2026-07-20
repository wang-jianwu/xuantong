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

ensure_jdk
cd "$REPOSITORY_ROOT"

"$MVN_BIN" -pl xuantong-server -am \
  -Dtest=FullClusterRecoveryDrillTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dxuantong.recovery.drill=true \
  test

report="$REPOSITORY_ROOT/xuantong-server/target/surefire-reports/TEST-cloud.xuantong.server.state.management.FullClusterRecoveryDrillTest.xml"
if [[ ! -f "$report" ]]; then
  echo "Recovery drill report was not generated: $report" >&2
  exit 1
fi
suite="$(grep -m1 '<testsuite ' "$report" || true)"
expected_skipped=1
mysql_status="skipped"
if [[ -n "${XUANTONG_RECOVERY_MYSQL_HOST:-}" ]]; then
  expected_skipped=0
  mysql_status="passed"
fi
if [[ "$suite" != *'tests="2"'* \
      || "$suite" != *'errors="0"'* \
      || "$suite" != *'failures="0"'* \
      || "$suite" != *"skipped=\"$expected_skipped\""* ]]; then
  echo "Recovery drill did not execute the required H2/MySQL matrix" >&2
  echo "$suite" >&2
  exit 1
fi
echo "Full recovery drill verified: h2=passed mysql=$mysql_status failures=0 errors=0"
