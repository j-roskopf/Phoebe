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
