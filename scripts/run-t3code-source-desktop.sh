#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
T3_REPO="${T3CODE_SOURCE_REPO:-$HOME/Documents/Projects/t3code}"
ELECTRON_BIN="${ANDRODEX_ELECTRON_BIN:-$ROOT_DIR/desktop/node_modules/electron/dist/Electron.app/Contents/MacOS/Electron}"
MAIN_JS="$T3_REPO/apps/desktop/dist-electron/main.js"
DESKTOP_DIR="$T3_REPO/apps/desktop"

if [[ ! -x "$ELECTRON_BIN" ]]; then
  echo "Missing Electron runtime at $ELECTRON_BIN" >&2
  echo "Set ANDRODEX_ELECTRON_BIN or run npm install in $ROOT_DIR/desktop." >&2
  exit 1
fi

if [[ ! -f "$MAIN_JS" ]]; then
  echo "Missing built T3 desktop entry at $MAIN_JS" >&2
  echo "Run '~/.bun/bin/bun run build:desktop' in $T3_REPO first." >&2
  exit 1
fi

cd "$DESKTOP_DIR"
exec "$ELECTRON_BIN" "$MAIN_JS"
