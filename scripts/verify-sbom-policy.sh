#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <cyclonedx-sbom.json>" >&2
  exit 2
fi

sbom="$1"
[[ -s "$sbom" ]] || { echo "SBOM is missing or empty: $sbom" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "jq is required" >&2; exit 1; }

jq -e '
  .bomFormat == "CycloneDX" and
  (.specVersion | test("^1\\.[4-9]$")) and
  (.metadata.component.name == "xuantong-parent") and
  ((.components // []) | length > 0)
' "$sbom" >/dev/null || {
  echo "SBOM does not contain the expected CycloneDX aggregate metadata" >&2
  exit 1
}

prohibited="$(jq -r '
  (.components // [])[]
  | . as $component
  | (.licenses // [])[]?
  | ((.license.id // .license.name // .expression // "") | ascii_downcase) as $license
  | select($license | test("affero|agpl|server side public|sspl|business source|busl|commons clause"))
  | "\($component.group // ""):\($component.name):\($component.version) => \($license)"
' "$sbom")"
if [[ -n "$prohibited" ]]; then
  echo "SBOM contains dependencies with prohibited release licenses:" >&2
  printf '%s\n' "$prohibited" >&2
  exit 1
fi

missing_license_count="$(jq '[.components[] | select(((.licenses // []) | length) == 0)] | length' "$sbom")"
echo "SBOM policy passed: components=$(jq '.components | length' "$sbom"), missingLicenseMetadata=$missing_license_count"
if [[ "$missing_license_count" -gt 0 ]]; then
  echo "Warning: missing license metadata requires review in the GitHub dependency scan artifact" >&2
fi
