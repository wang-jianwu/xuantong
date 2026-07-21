#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

allow_dirty=false
allow_snapshot=false
skip_tests=false
output_dir=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --allow-dirty) allow_dirty=true ;;
    --allow-snapshot) allow_snapshot=true ;;
    --skip-tests) skip_tests=true ;;
    --output)
      shift
      [[ $# -gt 0 ]] || { echo "--output requires a path" >&2; exit 2; }
      output_dir="$1"
      ;;
    *)
      echo "Usage: $0 [--allow-dirty] [--allow-snapshot] [--skip-tests] [--output <directory>]" >&2
      exit 2
      ;;
  esac
  shift
done

for command in git java mvn jq unzip; do
  command -v "$command" >/dev/null 2>&1 || { echo "$command is required" >&2; exit 1; }
done

java_major="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.specification.version = //p' | head -n 1)"
[[ "$java_major" == "21" ]] || { echo "JDK 21 is required; found $java_major" >&2; exit 1; }

version="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' pom.xml | head -n 1)"
[[ -n "$version" ]] || { echo "Unable to read project version" >&2; exit 1; }
if [[ "$allow_snapshot" != true && "$version" == *-SNAPSHOT ]]; then
  echo "Refusing to build a release candidate from SNAPSHOT version $version" >&2
  exit 1
fi

dirty=false
if ! git diff --quiet || ! git diff --cached --quiet || [[ -n "$(git ls-files --others --exclude-standard)" ]]; then
  dirty=true
fi
if [[ "$allow_dirty" != true && "$dirty" == true ]]; then
  echo "Release candidates require a clean Git worktree" >&2
  exit 1
fi

if [[ -z "$output_dir" ]]; then
  output_dir="$ROOT_DIR/output/release-candidate/$version"
elif [[ "$output_dir" != /* ]]; then
  output_dir="$ROOT_DIR/$output_dir"
fi
if [[ -e "$output_dir" ]]; then
  echo "Output path already exists: $output_dir" >&2
  exit 1
fi

metadata_args=()
[[ "$allow_snapshot" == true ]] && metadata_args+=(--allow-snapshot)
./scripts/verify-release-metadata.sh "${metadata_args[@]}"
./scripts/verify-no-secrets.sh

revision="$(git rev-parse HEAD)"
build_timestamp="$(git show -s --format=%cI HEAD)"
maven_common=(--batch-mode --no-transfer-progress "-Dxuantong.build.outputTimestamp=$build_timestamp")
test_args=()
[[ "$skip_tests" == true ]] && test_args+=(-DskipTests)

mvn "${maven_common[@]}" -Psbom clean verify "${test_args[@]}"

public_projects=":xuantong-protocol,:xuantong-client-core,:xuantong-solon-plugin,:xuantong-solon-cloud-plugin,:xuantong-spring-boot-starter,:xuantong-spring-cloud-starter"
mvn "${maven_common[@]}" -Prelease-artifacts -pl "$public_projects" -am package -DskipTests

server_jar="xuantong-server/target/xuantong-server.jar"
probe_jar="xuantong-probe/target/xuantong-probe.jar"
sbom="target/xuantong-sbom.json"
for artifact in "$server_jar" "$probe_jar" "$sbom"; do
  [[ -s "$artifact" ]] || { echo "Required release artifact is missing: $artifact" >&2; exit 1; }
done
unzip -p "$server_jar" META-INF/MANIFEST.MF | tr -d '\r' | rg -q '^Main-Class: ' || {
  echo "Server JAR is not executable" >&2
  exit 1
}
unzip -p "$probe_jar" META-INF/MANIFEST.MF | tr -d '\r' | rg -q '^Main-Class: cloud.xuantong.probe.XuantongProbeApplication$' || {
  echo "Probe JAR has an unexpected Main-Class" >&2
  exit 1
}
./scripts/verify-sbom-policy.sh "$sbom"

staging_dir="$(mktemp -d "${TMPDIR:-/tmp}/xuantong-rc.XXXXXX")"
cleanup() {
  rm -rf "$staging_dir"
}
trap cleanup EXIT
mkdir -p "$staging_dir/maven-central"
cp "$server_jar" "$staging_dir/xuantong-server-$version.jar"
cp "$probe_jar" "$staging_dir/xuantong-probe-$version.jar"
cp "$sbom" "$staging_dir/xuantong-$version.cdx.json"
cp LICENSE "$staging_dir/LICENSE"

public_modules=(
  xuantong-protocol
  xuantong-client-core
  xuantong-solon-plugin
  xuantong-solon-cloud-plugin
  xuantong-spring-boot-starter
  xuantong-spring-cloud-starter
)
cp pom.xml "$staging_dir/maven-central/xuantong-parent-$version.pom"
for module in "${public_modules[@]}"; do
  for classifier in "" -sources -javadoc; do
    artifact="$module/target/$module-$version$classifier.jar"
    [[ -s "$artifact" ]] || { echo "Public artifact is missing: $artifact" >&2; exit 1; }
    cp "$artifact" "$staging_dir/maven-central/"
  done
  cp "$module/pom.xml" "$staging_dir/maven-central/$module-$version.pom"
done

jq -n \
  --arg schemaVersion "1" \
  --arg version "$version" \
  --arg revision "$revision" \
  --arg buildTimestamp "$build_timestamp" \
  --arg javaVersion "$(java -version 2>&1 | head -n 1)" \
  --arg mavenVersion "$(mvn -version | head -n 1)" \
  --argjson dirty "$dirty" \
  --argjson testsSkipped "$skip_tests" \
  '{schemaVersion: ($schemaVersion | tonumber), version: $version, revision: $revision,
    buildTimestamp: $buildTimestamp, dirty: $dirty, testsSkipped: $testsSkipped,
    java: $javaVersion, maven: $mavenVersion,
    publicCoordinates: ["xuantong-parent", "xuantong-protocol", "xuantong-client-core",
      "xuantong-solon-plugin", "xuantong-solon-cloud-plugin",
      "xuantong-spring-boot-starter", "xuantong-spring-cloud-starter"]}' \
  > "$staging_dir/release-manifest.json"

checksum_command=(shasum -a 256)
command -v sha256sum >/dev/null 2>&1 && checksum_command=(sha256sum)
(
  cd "$staging_dir"
  find . -type f ! -name SHA256SUMS -print | LC_ALL=C sort | while IFS= read -r file; do
    "${checksum_command[@]}" "$file"
  done > SHA256SUMS
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum --check SHA256SUMS
  else
    shasum -a 256 -c SHA256SUMS
  fi
)

mkdir -p "$(dirname "$output_dir")"
mv "$staging_dir" "$output_dir"
trap - EXIT
echo "Release candidate created: $output_dir"
