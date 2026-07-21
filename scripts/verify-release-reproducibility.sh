#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

for command in git java mvn shasum; do
  command -v "$command" >/dev/null 2>&1 || { echo "$command is required" >&2; exit 1; }
done

java_major="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.specification.version = //p' | head -n 1)"
[[ "$java_major" == "21" ]] || { echo "JDK 21 is required; found $java_major" >&2; exit 1; }

version="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' pom.xml | head -n 1)"
build_timestamp="$(git show -s --format=%cI HEAD)"
public_projects=":xuantong-protocol,:xuantong-client-core,:xuantong-solon-plugin,:xuantong-solon-cloud-plugin,:xuantong-spring-boot-starter,:xuantong-spring-cloud-starter"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/xuantong-reproducible.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

collect() {
  destination="$1"
  mkdir -p "$destination"
  cp xuantong-server/target/xuantong-server.jar "$destination/"
  cp xuantong-probe/target/xuantong-probe.jar "$destination/"
  cp target/xuantong-sbom.json "$destination/"
  for module in xuantong-protocol xuantong-client-core xuantong-solon-plugin \
      xuantong-solon-cloud-plugin xuantong-spring-boot-starter xuantong-spring-cloud-starter; do
    cp "$module/target/$module-$version.jar" "$destination/"
    cp "$module/target/$module-$version-sources.jar" "$destination/"
    cp "$module/target/$module-$version-javadoc.jar" "$destination/"
  done
  (
    cd "$destination"
    LC_ALL=C
    export LC_ALL
    for file in ./*; do
      shasum -a 256 "$file"
    done > SHA256SUMS
  )
}

for round in 1 2; do
  mvn --batch-mode --no-transfer-progress \
    "-Dxuantong.build.outputTimestamp=$build_timestamp" -Psbom clean verify -DskipTests
  mvn --batch-mode --no-transfer-progress \
    "-Dxuantong.build.outputTimestamp=$build_timestamp" -Prelease-artifacts \
    -pl "$public_projects" -am package -DskipTests
  collect "$work_dir/round-$round"
done

if ! diff -u "$work_dir/round-1/SHA256SUMS" "$work_dir/round-2/SHA256SUMS"; then
  echo "Release artifacts are not reproducible" >&2
  exit 1
fi

echo "Reproducible build passed for executable JARs, public artifacts, and aggregate SBOM"
