#!/usr/bin/env bash
# Upload Android NDK debug symbols for projectM / PhoebeProjectM to Sentry.
# Requires SENTRY_AUTH_TOKEN, and native/projectm/android-* built with RelWithDebInfo or .so.debug.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ORG="${SENTRY_ORG:-personal-0mr}"
PROJECT="${SENTRY_PROJECT:-phoebe}"

if [[ -z "${SENTRY_AUTH_TOKEN:-}" ]]; then
  echo "SENTRY_AUTH_TOKEN required"
  exit 1
fi

if ! command -v sentry-cli >/dev/null 2>&1; then
  curl -sL https://sentry.io/get-cli/ | bash
  export PATH="$HOME/.local/bin:$PATH"
fi

shopt -s nullglob
for abi_dir in "${ROOT}"/native/projectm/android-*/lib; do
  echo "Uploading symbols from ${abi_dir}"
  sentry-cli debug-files upload \
    --org "${ORG}" \
    --project "${PROJECT}" \
    --include-sources \
    "${abi_dir}" || true
done

echo "NDK symbol upload finished"
