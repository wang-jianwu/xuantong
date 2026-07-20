#!/usr/bin/env bash
set -euo pipefail

MVN_BIN="${MVN_BIN:-mvn}"
IMAGE_SIZE="${XUANTONG_ENOSPC_IMAGE_SIZE:-256m}"
MARKER_FILE=".xuantong-enospc-test-volume"
MARKER_CONTENT="xuantong-enospc-test-only"
TEMP_ROOT=""
MOUNT_POINT=""
ATTACHED=0

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

cleanup() {
  if (( ATTACHED == 1 )); then
    hdiutil detach "$MOUNT_POINT" -force >/dev/null 2>&1 || true
  fi
  if [[ -n "$TEMP_ROOT" && "$TEMP_ROOT" == *"/xuantong-enospc."* ]]; then
    rm -rf -- "$TEMP_ROOT"
  fi
}
trap cleanup EXIT INT TERM

ensure_jdk

run_test() {
  local volume="$1"
  XUANTONG_ENOSPC_VOLUME="$volume" \
    "$MVN_BIN" -pl xuantong-raft-core -am \
    -Dtest=RatisEnospcIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test
}

if [[ -n "${XUANTONG_ENOSPC_VOLUME:-}" ]]; then
  if [[ ! -f "$XUANTONG_ENOSPC_VOLUME/$MARKER_FILE" ]]; then
    echo "Refusing external volume without $MARKER_FILE" >&2
    exit 2
  fi
  run_test "$XUANTONG_ENOSPC_VOLUME"
  exit 0
fi

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "Set XUANTONG_ENOSPC_VOLUME to a dedicated, mounted volume no larger than 1 GiB." >&2
  echo "Create $MARKER_FILE in that volume with content: $MARKER_CONTENT" >&2
  exit 2
fi

if ! command -v hdiutil >/dev/null 2>&1; then
  echo "hdiutil is required to create the temporary constrained volume." >&2
  exit 2
fi

TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/xuantong-enospc.XXXXXX")"
MOUNT_POINT="$TEMP_ROOT/volume"
IMAGE_PATH="$TEMP_ROOT/enospc.dmg"
mkdir -p "$MOUNT_POINT"

hdiutil create \
  -size "$IMAGE_SIZE" \
  -fs APFS \
  -volname "XuantongEnospc" \
  "$IMAGE_PATH" >/dev/null
hdiutil attach \
  "$IMAGE_PATH" \
  -nobrowse \
  -mountpoint "$MOUNT_POINT" >/dev/null
ATTACHED=1
printf '%s\n' "$MARKER_CONTENT" > "$MOUNT_POINT/$MARKER_FILE"

run_test "$MOUNT_POINT"
