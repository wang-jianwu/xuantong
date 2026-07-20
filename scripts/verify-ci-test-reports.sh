#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPOSITORY_ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"

assert_suite() {
  local report="$REPOSITORY_ROOT/$1"
  local expected_tests="$2"
  local expected_skipped="$3"
  local label="$4"

  if [[ ! -f "$report" ]]; then
    echo "$label report was not generated: $report" >&2
    exit 1
  fi

  local suite
  suite="$(grep -m1 '<testsuite ' "$report" || true)"
  if [[ "$suite" != *"tests=\"$expected_tests\""* \
        || "$suite" != *'errors="0"'* \
        || "$suite" != *'failures="0"'* \
        || "$suite" != *"skipped=\"$expected_skipped\""* ]]; then
    echo "$label did not execute the required real-infrastructure matrix" >&2
    echo "$suite" >&2
    exit 1
  fi
}

# PostgreSQL is intentionally outside the 2.0 production matrix. With both
# MySQL URLs configured, exactly its single schema test may remain skipped.
assert_suite \
  "xuantong-server/target/surefire-reports/TEST-cloud.xuantong.server.config.DatabaseSchemaIntegrationTest.xml" \
  16 1 "MySQL schema"

assert_suite \
  "xuantong-gateway/target/surefire-reports/TEST-cloud.xuantong.gateway.socketd.ControlPlaneGatewayNetworkTest.xml" \
  4 0 "Gateway TCP"
assert_suite \
  "xuantong-gateway/target/surefire-reports/TEST-cloud.xuantong.gateway.socketd.ControlPlaneSlowConsumerNetworkTest.xml" \
  3 0 "Gateway slow-consumer TCP"
assert_suite \
  "xuantong-client-core/target/surefire-reports/TEST-cloud.xuantong.client.tls.SocketDTlsIntegrationTest.xml" \
  4 0 "Socket.D TLS/mTLS"
assert_suite \
  "xuantong-raft-core/target/surefire-reports/TEST-cloud.xuantong.raft.ratis.RatisThreeNodePoCTest.xml" \
  7 0 "Ratis three-voter"
assert_suite \
  "xuantong-spring-cloud-starter/target/surefire-reports/TEST-cloud.xuantong.gateway.socketd.SpringCloudDiscoveryCapacityIntegrationTest.xml" \
  1 0 "Spring Cloud discovery capacity"

echo "CI real-infrastructure reports verified: mysql=executed tcp=executed tls=executed ratis=executed spring-cloud=executed"
