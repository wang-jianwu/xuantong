#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

allow_snapshot=false
if [[ "${1:-}" == "--allow-snapshot" ]]; then
  allow_snapshot=true
elif [[ $# -gt 0 ]]; then
  echo "Usage: $0 [--allow-snapshot]" >&2
  exit 2
fi

project_version="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' pom.xml | head -n 1)"
[[ -n "$project_version" ]] || { echo "Unable to read project version" >&2; exit 1; }
if [[ "$allow_snapshot" != true && "$project_version" == *-SNAPSHOT ]]; then
  echo "Release version must not be a SNAPSHOT: $project_version" >&2
  exit 1
fi

for required in LICENSE README.md SECURITY.md CONTRIBUTING.md CHANGELOG.md; do
  [[ -s "$required" ]] || { echo "Required release file is missing or empty: $required" >&2; exit 1; }
done

for element in name description url licenses developers scm; do
  rg -q "<$element(?:>| )" pom.xml || {
    echo "Root POM is missing Central metadata: <$element>" >&2
    exit 1
  }
done

public_modules=(
  xuantong-protocol
  xuantong-client-core
  xuantong-solon-plugin
  xuantong-solon-cloud-plugin
  xuantong-spring-boot-starter
  xuantong-spring-cloud-starter
)

while IFS= read -r pom; do
  parent_block="$(sed -n '/<parent>/,/<\/parent>/p' "$pom")"
  [[ "$parent_block" == *'<artifactId>xuantong-parent</artifactId>'* ]] || continue
  [[ "$parent_block" == *"<version>$project_version</version>"* ]] || {
    echo "Reactor module parent version differs from $project_version: $pom" >&2
    exit 1
  }
done < <(find . -mindepth 2 -maxdepth 2 -name pom.xml -type f | LC_ALL=C sort)

for module in "${public_modules[@]}"; do
  pom="$module/pom.xml"
  [[ -s "$pom" ]] || { echo "Public module POM is missing: $pom" >&2; exit 1; }
  rg -q '<artifactId>xuantong-parent</artifactId>' "$pom" || {
    echo "Public module does not inherit release metadata: $pom" >&2
    exit 1
  }
  rg -q "<version>${project_version//./\.}</version>" "$pom" || {
    echo "Public module parent version differs from $project_version: $pom" >&2
    exit 1
  }
done

duplicate_release_plugins="$(
  rg -l 'central-publishing-maven-plugin|maven-gpg-plugin' --glob 'pom.xml' . \
    | grep -v '^\./pom\.xml$' || true
)"
if [[ -n "$duplicate_release_plugins" ]]; then
  echo "Central/GPG configuration must exist only in the root POM:" >&2
  printf '%s\n' "$duplicate_release_plugins" >&2
  exit 1
fi

rg -q '<id>release-artifacts</id>' pom.xml
rg -q '<id>sbom</id>' pom.xml
rg -q '<id>central-publish</id>' pom.xml

echo "Release metadata passed: version=$project_version, publicCoordinates=7"
