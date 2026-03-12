#!/usr/bin/env node
// FILE: phodex.js
// Purpose: Backward-compatible wrapper that forwards legacy `phodex up` usage to `relaydex up`.
// Layer: CLI binary
// Exports: none
// Depends on: ./remodex

require("./remodex");
