#!/usr/bin/env bash
# Reproducible Rust/WASM + Vue production build, required before Android assets.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
bash ui-core/build-web.sh
node ui-core/smoke.mjs
(
  cd web-ui
  npm ci --no-audit --no-fund
  npm test
  npm run build
)
python3 - <<'PY'
from pathlib import Path
import json
root = Path('web-ui/dist')
assert (root / 'index.html').is_file(), 'Vue production index missing'
wasm = list(root.rglob('*.wasm'))
assert wasm, 'Rust/WASM missing from production bundle'
assert all(p.stat().st_size > 8 for p in wasm), 'Empty WASM artifact'
print(json.dumps({'dist': str(root), 'wasm_files': [str(p) for p in wasm]}, ensure_ascii=False))
PY
