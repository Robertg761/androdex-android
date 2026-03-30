// FILE: workspace-browser.js
// Purpose: Enumerates host directories and formats workspace browse data for remote clients.
// Layer: Bridge helper
// Exports: workspace browsing helpers
// Depends on: fs, os, path

const fs = require("fs");
const os = require("os");
const path = require("path");

function createWorkspaceBrowser({
  platform = process.platform,
  fsModule = fs,
  osModule = os,
  pathModule = path,
} = {}) {
  function buildRecentWorkspaceSummaries({ activeCwd = "", recentWorkspaces = [] } = {}) {
    return recentWorkspaces.map((workspacePath) => ({
      path: workspacePath,
      name: displayNameForPath(workspacePath),
      isActive: arePathsEqual(workspacePath, activeCwd, platform),
    }));
  }

  function listDirectory({ requestedPath = null, activeCwd = "", recentWorkspaces = [] } = {}) {
    const recentSummaries = buildRecentWorkspaceSummaries({ activeCwd, recentWorkspaces });

    if (!requestedPath) {
      return {
        requestedPath: null,
        parentPath: null,
        entries: [],
        rootEntries: buildRootEntries({ activeCwd, recentSummaries }),
        activeCwd: activeCwd || null,
        recentWorkspaces: recentSummaries,
      };
    }

    const canonicalPath = normalizeAbsoluteDirectoryPath(requestedPath);
    return {
      requestedPath: canonicalPath,
      parentPath: resolveParentPath(canonicalPath),
      entries: readChildDirectories(canonicalPath).map((entryPath) => ({
        path: entryPath,
        name: displayNameForPath(entryPath),
        isDirectory: true,
        isActive: arePathsEqual(entryPath, activeCwd, platform),
        source: "browse",
      })),
      rootEntries: [],
      activeCwd: activeCwd || null,
      recentWorkspaces: recentSummaries,
    };
  }

  function buildRootEntries({ activeCwd = "", recentSummaries = [] } = {}) {
    const results = [];
    const seen = new Set();

    const addEntry = (entryPath, source) => {
      const normalized = typeof entryPath === "string" ? entryPath.trim() : "";
      if (!normalized) {
        return;
      }

      const dedupeKey = normalized;
      if (seen.has(dedupeKey)) {
        return;
      }
      seen.add(dedupeKey);

      results.push({
        path: normalized,
        name: displayNameForPath(normalized),
        isDirectory: true,
        isActive: arePathsEqual(normalized, activeCwd, platform),
        source,
      });
    };

    for (const drivePath of listRootDirectories()) {
      addEntry(drivePath, "root");
    }

    const homeDirectory = normalizeAbsolutePath(osModule.homedir());
    if (homeDirectory) {
      addEntry(homeDirectory, "root");
    }

    for (const workspace of recentSummaries) {
      addEntry(workspace.path, "recent");
    }

    return results;
  }

  function listRootDirectories() {
    const root = pathModule.parse(osModule.homedir()).root || pathModule.sep;
    return [normalizeAbsolutePath(root)].filter(Boolean);
  }

  function readChildDirectories(directoryPath) {
    let entries;
    try {
      entries = fsModule.readdirSync(directoryPath, { withFileTypes: true });
    } catch {
      throw workspaceBrowseError(
        "workspace_directory_unreadable",
        "The selected folder could not be opened."
      );
    }

    return entries
      .filter((entry) => {
        try {
          return entry.isDirectory();
        } catch {
          return false;
        }
      })
      .map((entry) => pathModule.join(directoryPath, entry.name))
      .sort((left, right) => left.localeCompare(right, undefined, { sensitivity: "base" }));
  }

  function normalizeAbsoluteDirectoryPath(candidatePath) {
    const normalized = normalizeAbsolutePath(candidatePath);
    if (!normalized) {
      throw workspaceBrowseError(
        "workspace_path_not_absolute",
        "Choose an absolute folder path on the host machine."
      );
    }

    let stat;
    try {
      stat = fsModule.statSync(normalized);
    } catch {
      throw workspaceBrowseError(
        "workspace_directory_missing",
        "The selected folder does not exist on the host machine."
      );
    }

    if (!stat.isDirectory()) {
      throw workspaceBrowseError(
        "workspace_path_not_directory",
        "The selected path is not a folder."
      );
    }

    return normalized;
  }

  function normalizeAbsolutePath(candidatePath) {
    const trimmed = typeof candidatePath === "string" ? candidatePath.trim() : "";
    if (!trimmed || !pathModule.isAbsolute(trimmed)) {
      return "";
    }
    return pathModule.normalize(trimmed);
  }

  function resolveParentPath(candidatePath) {
    const parsed = pathModule.parse(candidatePath);
    const parentPath = pathModule.dirname(candidatePath);
    if (!parentPath || arePathsEqual(parentPath, candidatePath, platform)) {
      return null;
    }
    if (arePathsEqual(candidatePath, parsed.root, platform)) {
      return null;
    }
    return parentPath;
  }

  function displayNameForPath(candidatePath) {
    const normalized = normalizeAbsolutePath(candidatePath);
    if (!normalized) {
      return "";
    }

    const parsed = pathModule.parse(normalized);
    if (arePathsEqual(normalized, parsed.root, platform)) {
      return normalized;
    }

    return pathModule.basename(normalized) || normalized;
  }

  return {
    buildRecentWorkspaceSummaries,
    displayNameForPath,
    listDirectory,
    normalizeAbsoluteDirectoryPath,
  };
}

function arePathsEqual(left, right, platform = process.platform) {
  const normalizedLeft = typeof left === "string" ? left.trim() : "";
  const normalizedRight = typeof right === "string" ? right.trim() : "";
  if (!normalizedLeft || !normalizedRight) {
    return false;
  }
  return normalizedLeft === normalizedRight;
}

function workspaceBrowseError(errorCode, userMessage) {
  const error = new Error(userMessage);
  error.errorCode = errorCode;
  error.userMessage = userMessage;
  return error;
}

module.exports = {
  arePathsEqual,
  createWorkspaceBrowser,
  workspaceBrowseError,
};
