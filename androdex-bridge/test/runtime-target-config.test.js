const test = require("node:test");
const assert = require("node:assert/strict");

const {
  DEFAULT_RUNTIME_TARGET,
  createRuntimeLaunchPlan,
  readRuntimeTargetKind,
  resolveRuntimeTargetConfig,
} = require("../src/runtime/target-config");
const {
  DEFAULT_RUNTIME_PROVIDER,
  normalizeRuntimeProviderKind,
  readRuntimeProviderKind,
  resolveRuntimeProviderConfig,
} = require("../src/runtime/provider-config");
const { createRuntimeAdapter } = require("../src/runtime/adapter");

test("readRuntimeTargetKind defaults to codex-native when no target is configured", () => {
  assert.equal(readRuntimeTargetKind({ env: {} }), DEFAULT_RUNTIME_TARGET);
});

test("readRuntimeTargetKind accepts the legacy provider env alias", () => {
  assert.equal(
    readRuntimeTargetKind({ env: { ANDRODEX_RUNTIME_PROVIDER: "codex" } }),
    "codex-native"
  );
});

test("readRuntimeTargetKind rejects unsupported configured target values", () => {
  assert.throws(
    () => readRuntimeTargetKind({ env: { ANDRODEX_RUNTIME_TARGET: "bogus" } }),
    /unsupported runtime target "bogus"/i
  );
});

test("resolveRuntimeTargetConfig returns the codex-native target defaults", () => {
  const target = resolveRuntimeTargetConfig({ kind: "codex-native" });

  assert.equal(target.kind, "codex-native");
  assert.equal(target.legacyProviderKind, "codex");
  assert.equal(target.backendProviderKind, "codex");
  assert.deepEqual(target.endpointEnvVars, ["ANDRODEX_CODEX_ENDPOINT"]);
  assert.deepEqual(target.desktopBundleIdEnvVars, ["ANDRODEX_CODEX_BUNDLE_ID"]);
});

test("createRuntimeLaunchPlan launches codex app-server in the selected workspace", () => {
  const launchPlan = createRuntimeLaunchPlan({
    kind: "codex-native",
    env: { PATH: "/usr/bin" },
    cwd: "/tmp/workspace",
  });

  assert.equal(launchPlan.command, "codex");
  assert.deepEqual(launchPlan.args, ["app-server"]);
  assert.equal(launchPlan.options.cwd, "/tmp/workspace");
  assert.equal(launchPlan.options.env.PATH, "/usr/bin");
});

test("legacy provider helpers still resolve codex for backwards compatibility", () => {
  assert.equal(DEFAULT_RUNTIME_PROVIDER, "codex");
  assert.equal(readRuntimeProviderKind({ env: {} }), "codex");
  assert.equal(normalizeRuntimeProviderKind("codex"), "codex");
  assert.equal(normalizeRuntimeProviderKind("codex-native"), "codex");

  const provider = resolveRuntimeProviderConfig({ kind: "codex" });
  assert.equal(provider.kind, "codex");
  assert.equal(provider.legacyProviderKind, "codex");
});

test("resolveRuntimeTargetConfig rejects unsupported explicit target values", () => {
  assert.throws(
    () => resolveRuntimeTargetConfig({ kind: "bogus" }),
    /unsupported runtime target "bogus"/i
  );
});

test("planned runtime targets fail fast with a clear message until the adapter lands", () => {
  assert.throws(
    () => resolveRuntimeTargetConfig({ kind: "t3-server" }),
    /planned but not implemented yet/i
  );
});

test("createRuntimeAdapter preserves the T3 fast-fail path", () => {
  assert.throws(
    () => createRuntimeAdapter({ targetKind: "t3-server" }),
    /planned but not implemented yet/i
  );
});
