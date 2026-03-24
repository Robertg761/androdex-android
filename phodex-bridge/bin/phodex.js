#!/usr/bin/env node
// FILE: phodex.js
// Purpose: Backward-compatible wrapper that forwards legacy `phodex` usage to `androdex`.
// Layer: CLI binary
// Exports: none
// Depends on: ./androdex

require("./androdex");
