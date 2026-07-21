#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

tracked_environment_files="$(git ls-files | awk '
  /(^|\/)\.env($|\.)/ && $0 !~ /\.env\.example$/ { print }
')"
if [[ -n "$tracked_environment_files" ]]; then
  echo "Tracked environment files may contain credentials:" >&2
  printf '%s\n' "$tracked_environment_files" >&2
  exit 1
fi

# Keep output to file names only so a failed scan never copies a secret into CI logs.
high_confidence_pattern='BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY|AKIA[0-9A-Z]{16}|github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{30,}|glpat-[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{20,}|sk-[A-Za-z0-9_-]{32,}|[A-Za-z][A-Za-z0-9+.-]*://[^/@[:space:]]+:[^/@[:space:]]+@'
if matches="$(git grep -l -I -E "$high_confidence_pattern" -- . 2>/dev/null)"; then
  echo "Potential credentials detected in tracked files:" >&2
  printf '%s\n' "$matches" >&2
  exit 1
fi

echo "Credential scan passed for tracked files"
