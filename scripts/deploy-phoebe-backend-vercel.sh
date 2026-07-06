#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
context_root="$(mktemp -d "${TMPDIR:-/tmp}/phoebe-backend-vercel.XXXXXX")"
# Vercel may infer a project name from the deploy directory before the project is linked.
# Keep the visible basename stable and lowercase even when mktemp adds mixed-case suffixes.
context_dir="${context_root}/phoebe-backend"
context_only=false
args=()

mkdir -p "${context_dir}"

for arg in "$@"; do
  case "${arg}" in
    --context-only)
      context_only=true
      ;;
    *)
      args+=("${arg}")
      ;;
  esac
done

cleanup() {
  if [[ "${context_only}" != "true" ]]; then
    rm -rf "${context_root}"
  fi
}
trap cleanup EXIT

copy_path() {
  local path="$1"
  rsync -a \
    --exclude build \
    --exclude .gradle \
    --exclude .kotlin \
    --exclude node_modules \
    "${repo_root}/${path}" \
    "${context_dir}/${path}"
}

for file in \
  Dockerfile.vercel \
  vercel.json \
  settings.backend.gradle.kts \
  build.gradle.kts \
  gradle.properties \
  gradlew
do
  mkdir -p "${context_dir}/$(dirname "${file}")"
  cp "${repo_root}/${file}" "${context_dir}/${file}"
done

chmod +x "${context_dir}/gradlew"

for dir in \
  gradle \
  build-logic \
  backend \
  domain
do
  mkdir -p "${context_dir}/$(dirname "${dir}")"
  copy_path "${dir}/"
done

if [[ -d "${repo_root}/.vercel" ]]; then
  cp -R "${repo_root}/.vercel" "${context_dir}/.vercel"
fi

file_count="$(find "${context_dir}" -type f | wc -l | tr -d '[:space:]')"
context_size="$(du -sh "${context_dir}" | awk '{print $1}')"
echo "Prepared Phoebe backend Vercel context at ${context_dir} (${file_count} files, ${context_size})."

if [[ "${context_only}" == "true" ]]; then
  echo "Context kept for inspection because --context-only was passed."
  exit 0
fi

vercel deploy "${context_dir}" --archive=tgz "${args[@]}"
