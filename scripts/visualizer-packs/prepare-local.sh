#!/usr/bin/env bash
# Prepare local contents for the future phoebe-visualizer-packs GitHub repo (Phase 7).
# Does NOT create or push a remote — ask before doing that.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${ROOT}/packs/local"
MILK_SRC="${ROOT}/composeApp/src/commonMain/resources/projectm-presets"
BUTTER_OUT="${OUT}/butterchurn-presets"
MILK_OUT="${OUT}/projectm-presets"

mkdir -p "${MILK_OUT}" "${BUTTER_OUT}" "${OUT}/packs/cream-of-the-crop" \
  "${OUT}/packs/projectm-classic" "${OUT}/packs/milkdrop-original" \
  "${OUT}/textures"

cp -R "${MILK_SRC}/." "${MILK_OUT}/"

# Minimal Butterchurn JSON stubs for web (wasmJsMain only — not commonMain).
mkdir -p "${ROOT}/composeApp/src/wasmJsMain/resources/butterchurn-presets"
for milk in "${MILK_OUT}"/*.milk; do
  base="$(basename "${milk}" .milk)"
  cat > "${BUTTER_OUT}/${base}.json" <<EOF
{
  "name": "${base}",
  "author": "Phoebe",
  "frame": "",
  "pixel": "",
  "shapes": [],
  "waves": []
}
EOF
  cp -f "${BUTTER_OUT}/${base}.json" \
    "${ROOT}/composeApp/src/wasmJsMain/resources/butterchurn-presets/${base}.json"
done

cat > "${OUT}/catalog.json" <<'EOF'
{
  "packs": [
    {
      "id": "bundled",
      "name": "Phoebe bundled",
      "version": "1",
      "presets": [
        {
          "packId": "bundled",
          "presetId": "geiss-swirlie-1",
          "displayName": "Geiss — Swirlie 1",
          "milkRelativePath": "projectm-presets/geiss-swirlie-1.milk",
          "butterchurnRelativePath": "butterchurn-presets/geiss-swirlie-1.json"
        },
        {
          "packId": "bundled",
          "presetId": "geiss-swirlie-4",
          "displayName": "Geiss — Swirlie 4",
          "milkRelativePath": "projectm-presets/geiss-swirlie-4.milk",
          "butterchurnRelativePath": "butterchurn-presets/geiss-swirlie-4.json"
        },
        {
          "packId": "bundled",
          "presetId": "aderrasi-agitator",
          "displayName": "Aderrasi — Agitator",
          "milkRelativePath": "projectm-presets/aderrasi-agitator.milk",
          "butterchurnRelativePath": "butterchurn-presets/aderrasi-agitator.json"
        },
        {
          "packId": "bundled",
          "presetId": "aderrasi-blender",
          "displayName": "Aderrasi — Blender",
          "milkRelativePath": "projectm-presets/aderrasi-blender.milk",
          "butterchurnRelativePath": "butterchurn-presets/aderrasi-blender.json"
        },
        {
          "packId": "bundled",
          "presetId": "phoebe-pulse",
          "displayName": "Phoebe Pulse",
          "milkRelativePath": "projectm-presets/phoebe-pulse.milk",
          "butterchurnRelativePath": "butterchurn-presets/phoebe-pulse.json"
        }
      ]
    }
  ]
}
EOF

cp "${OUT}/catalog.json" "${OUT}/packs/bundled/manifest.json" 2>/dev/null || {
  mkdir -p "${OUT}/packs/bundled"
  python3 - <<'PY'
import json, pathlib
root = pathlib.Path("packs/local")
catalog = json.loads((root / "catalog.json").read_text())
bundled = next(p for p in catalog["packs"] if p["id"] == "bundled")
(root / "packs" / "bundled" / "manifest.json").write_text(json.dumps(bundled, indent=2) + "\n")
PY
}

cat > "${OUT}/README.md" <<'EOF'
# phoebe-visualizer-packs (local staging)

Local staging tree for the future public GitHub repo served via jsDelivr.

## Layout
- `catalog.json` — pack index consumed by `VisualizerPackDownloader`
- `projectm-presets/` — `.milk` for native projectM
- `butterchurn-presets/` — JSON for Butterchurn (stubs until converter runs)
- `textures/` — shared milkdrop textures
- `packs/<id>/manifest.json` — per-pack manifests

## Do not publish yet
Creating `j-roskopf/phoebe-visualizer-packs` is an outward-facing action.
Ask before `gh repo create` / push.
EOF

echo "Prepared pack staging at ${OUT}"
echo "ASK before creating GitHub repo phoebe-visualizer-packs"
