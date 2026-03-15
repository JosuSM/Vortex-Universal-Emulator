#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <owner/repo>" >&2
  exit 1
fi

repo="$1"
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
properties_file="$root_dir/keystore.properties"

if [[ ! -f "$properties_file" ]]; then
  echo "Missing $properties_file" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
. "$properties_file"
set +a

keystore_path="$root_dir/$storeFile"
if [[ ! -f "$keystore_path" ]]; then
  echo "Missing keystore at $keystore_path" >&2
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI is required." >&2
  exit 1
fi

gh secret set VORTEX_SIGNING_STORE_BASE64 --repo "$repo" < <(base64 -w0 "$keystore_path")
printf '%s' "$storePassword" | gh secret set VORTEX_SIGNING_STORE_PASSWORD --repo "$repo" --body -
printf '%s' "$keyAlias" | gh secret set VORTEX_SIGNING_KEY_ALIAS --repo "$repo" --body -
printf '%s' "$keyPassword" | gh secret set VORTEX_SIGNING_KEY_PASSWORD --repo "$repo" --body -

echo "Configured signing secrets for $repo"
