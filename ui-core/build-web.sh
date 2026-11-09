#!/usr/bin/env bash
# Build the Rust frontend domain core into web-ui/src/wasm for the Vue app.
#
# Contract: docs/UI_CORE_CONTRACT.md §构建
#   cargo test --manifest-path ui-core/Cargo.toml
#   cargo build --manifest-path ui-core/Cargo.toml --target wasm32-unknown-unknown --release
#   wasm-bindgen --target web --out-dir web-ui/src/wasm \
#       ui-core/target/wasm32-unknown-unknown/release/studio_ui_core.wasm
#
# Usage:
#   ui-core/build-web.sh            # test + release build + wasm-bindgen
#   ui-core/build-web.sh --no-test  # skip cargo test (faster iteration)
#
# Only generated files under web-ui/src/wasm are written; no Vue source is
# touched. Requires: rustup target wasm32-unknown-unknown, wasm-bindgen CLI
# matching the wasm-bindgen crate version in Cargo.lock.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT/ui-core/Cargo.toml"
WASM="$ROOT/ui-core/target/wasm32-unknown-unknown/release/studio_ui_core.wasm"
OUT_DIR="$ROOT/web-ui/src/wasm"
RUN_TESTS=1

for arg in "$@"; do
  case "$arg" in
    --no-test) RUN_TESTS=0 ;;
    -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# Pick up a cargo installation that is not on PATH for non-login shells.
if ! command -v cargo >/dev/null 2>&1 && [ -f "$HOME/.cargo/env" ]; then
  # shellcheck disable=SC1091
  . "$HOME/.cargo/env"
fi

command -v cargo >/dev/null 2>&1 || { echo "cargo not found on PATH" >&2; exit 1; }
command -v wasm-bindgen >/dev/null 2>&1 || {
  echo "wasm-bindgen CLI not found. Install the version pinned in Cargo.lock:" >&2
  echo "  cargo install wasm-bindgen-cli --version <version>" >&2
  exit 1
}

if [ "$RUN_TESTS" = 1 ]; then
  echo "==> cargo test"
  cargo test --manifest-path "$MANIFEST"
fi

echo "==> cargo build --target wasm32-unknown-unknown --release"
cargo build --manifest-path "$MANIFEST" --target wasm32-unknown-unknown --release

echo "==> wasm-bindgen --target web --out-dir web-ui/src/wasm"
mkdir -p "$OUT_DIR"
wasm-bindgen --target web --out-dir "$OUT_DIR" "$WASM"

echo "==> done"
ls -l "$OUT_DIR"
