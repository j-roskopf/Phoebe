#!/usr/bin/env bash
set -euo pipefail

repository="backend"
keep_count=10
dry_run=false
vercel_token="${VERCEL_TOKEN:-}"

usage() {
  cat <<'EOF'
Prune old Phoebe backend images from Vercel Container Registry.

Vercel Hobby projects allow 50 images per repository. Each backend deploy
creates a new image, so release CI prunes older images before deploying.

Usage:
  scripts/prune-phoebe-backend-vcr-images.sh [options]

Options:
  --keep <count>       Number of newest images to retain (default: 10)
  --repository <name>  VCR repository name (default: backend)
  --token <token>      Vercel access token (defaults to VERCEL_TOKEN)
  --dry-run            Print deletions without removing images
  -h, --help           Show this help
EOF
}

while (($# > 0)); do
  case "$1" in
    --keep)
      keep_count="${2:?--keep requires a count}"
      shift 2
      ;;
    --repository)
      repository="${2:?--repository requires a name}"
      shift 2
      ;;
    --token)
      vercel_token="${2:?--token requires a value}"
      shift 2
      ;;
    --dry-run)
      dry_run=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "::error::Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ "${keep_count}" -lt 1 ]]; then
  echo "::error::--keep must be at least 1" >&2
  exit 1
fi

vercel_cmd() {
  if [[ -n "${vercel_token}" ]]; then
    vercel "$@" --token "${vercel_token}"
  else
    vercel "$@"
  fi
}

list_images_json() {
  vercel_cmd vcr image ls "${repository}" --format json --limit 100 "$@"
}

list_images_json="$(list_images_json)"

node_output="$(
  LIST_IMAGES_JSON="${list_images_json}" \
  KEEP_COUNT="${keep_count}" \
  node <<'NODE'
const payload = JSON.parse(process.env.LIST_IMAGES_JSON || "{}");
const images = Array.isArray(payload.images) ? payload.images : [];
const keepCount = Number.parseInt(process.env.KEEP_COUNT || "10", 10);

images.sort((left, right) => new Date(right.createdAt) - new Date(left.createdAt));

if (images.length <= keepCount) {
  console.error(
    `Phoebe backend VCR repository has ${images.length} image(s); keeping all (limit ${keepCount}).`,
  );
  process.exit(0);
}

const toDelete = images.slice(keepCount);
console.error(
  `Phoebe backend VCR repository has ${images.length} image(s); deleting ${toDelete.length}, keeping ${keepCount}.`,
);

for (const image of toDelete) {
  process.stdout.write(`${image.id}\n`);
}
NODE
)"

image_ids_to_delete=()
if [[ -n "${node_output}" ]]; then
  while IFS= read -r image_id; do
    [[ -n "${image_id}" ]] || continue
    image_ids_to_delete+=("${image_id}")
  done <<< "${node_output}"
fi

if ((${#image_ids_to_delete[@]} == 0)); then
  exit 0
fi

for image_id in "${image_ids_to_delete[@]}"; do
  if [[ "${dry_run}" == "true" ]]; then
    echo "Would delete ${repository}/${image_id}"
    continue
  fi

  vercel_cmd vcr image rm "${repository}" "${image_id}" --yes
done

if [[ "${dry_run}" == "true" ]]; then
  echo "Dry run complete."
else
  echo "Pruned ${#image_ids_to_delete[@]} image(s) from ${repository}."
fi
