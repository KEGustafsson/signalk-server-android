// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Puts the Android builds of native addons in place after npm has installed packages
 * (docs/PLAN.md §9.2). npm-launch.js runs it once an install-like npm command has succeeded; it
 * walks the installed tree for packages with a `binding.gyp` that were left without their
 * addon, finds each one's build in the index the Prebuilds workflow publishes (prebuilds/,
 * docs/PLAN.md §9.1), and unpacks it into the package's `build/Release`, where both `bindings`
 * and `node-gyp-build` look first.
 *
 * Why after npm, not as npm's node-gyp: Signal K's App Store installs plugins with
 * `npm install --ignore-scripts`, so npm never calls node-gyp for them, and better-sqlite3 13 has
 * no install script at all. A package's own scripts are not needed either: what they would
 * build is what the Release holds, and nothing here is exec'd by name, which an app's data
 * directory forbids (W^X, §4.2).
 *
 * A build is used only when its tarball matches the SHA-256 the index gives, checked before a
 * byte is unpacked, and only the files the index lists are taken from it. The index and the
 * builds are kept in the cache directory, so a package installed again offline still gets its
 * addon. Each placed build leaves a marker naming the asset, so the next run knows the build is
 * its own and replaces it when the index names another (a C++ API build for a new runtime);
 * an addon the package brought or built itself is never touched.
 *
 * What is left without a build is recorded too, in [MISSING_FILE] in the cache directory, by the
 * tree it was installed into: the Dock reads it to name those packages and offer to request their
 * build (docs/PLAN.md §16 12.4), since the App Store's log, where [report]'s lines go, is gone
 * once the install is over. Each run replaces its tree's list, so a build placed later, or a tree
 * with nothing missing any more, clears its own; the Dock checks each package is still there.
 *
 * Plain CommonJS with no dependencies, like the preloads. Nothing here throws out of [place]:
 * whatever fails is reported, and npm's own result stands.
 */

const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const zlib = require("node:zlib");

/** Where the Prebuilds workflow publishes: the Release tagged `prebuilds` of this repository. */
const RELEASE_URL = "https://github.com/KEGustafsson/signalk-server-android/releases/download/prebuilds/";

/** The index format this file reads; prebuilds/lib.js writes it. */
const INDEX_FORMAT = 1;

/** An N-API build's ABI, which loads under every later Node; prebuilds/lib.js's NAPI. */
const NAPI = "napi";

/** Left beside a placed build: the asset it came from and that asset's SHA-256. */
const MARKER = ".pocketsk-prebuild";

/** The record of packages left without a build, in the cache directory; NodeRuntime names it too. */
const MISSING_FILE = "missing.json";
const MISSING_FORMAT = 1;

/** The only files a build may hold: addons straight under build/Release. */
const ADDON_FILE = /^build\/Release\/[A-Za-z0-9_.+-]+\.node$/;

/** An asset's name, as prebuilds/lib.js's assetName makes it: one plain file name, kept under that name in the cache. */
const ASSET_FILE = /^[A-Za-z0-9_+-][A-Za-z0-9@_.+-]*\.tar\.gz$/;

const INDEX_TIMEOUT_MS = 15000;
const ASSET_TIMEOUT_MS = 60000;

/**
 * npm's commands that install packages, with their aliases (npm 11): install, ci, update,
 * install-test, install-ci-test and rebuild. Anything else (`--version`, `view`, `uninstall`)
 * leaves no new package without its addon.
 */
const INSTALL_COMMANDS = new Set([
  "install", "add", "i", "in", "ins", "inst", "insta", "instal", "isnt", "isnta", "isntal", "isntall",
  "ci", "clean-install", "ic", "install-clean", "isntall-clean",
  "update", "up", "upgrade", "udpate",
  "install-test", "it", "install-ci-test", "cit",
  "rebuild", "rb",
]);

/**
 * The node_modules directory an npm run with [args] installs into, or null when it installs
 * nothing. Arguments after `--` are package names, never the command. A global run installs
 * into `<prefix>/lib/node_modules`, the prefix from `--prefix` or npm's environment (NodeRuntime
 * sets `npm_config_prefix`); a local one into `<prefix or cwd>/node_modules`.
 */
function installRoot(args, cwd, env) {
  const end = args.indexOf("--");
  const opts = end < 0 ? args : args.slice(0, end);
  if (!opts.some((a) => INSTALL_COMMANDS.has(a))) return null;
  let prefix = null;
  for (let i = 0; i < opts.length; i++) {
    const a = opts[i];
    if (a.startsWith("--prefix=")) prefix = a.slice("--prefix=".length);
    else if ((a === "--prefix" || a === "-C") && i + 1 < opts.length) prefix = opts[++i];
  }
  const global =
    opts.includes("-g") || opts.includes("--global") || opts.includes("--location=global") ||
    env.npm_config_global === "true" || env.npm_config_location === "global";
  if (global) {
    const base = prefix ?? env.npm_config_prefix;
    return base ? path.resolve(cwd, base, "lib", "node_modules") : null;
  }
  return path.resolve(cwd, prefix ?? ".", "node_modules");
}

/** Whether a build of ABI [abi] loads under a runtime whose C++ API ABI is [runtimeAbi]. */
const loadsUnder = (abi, runtimeAbi) => abi === NAPI || abi === runtimeAbi;

/** This runtime's C++ API ABI, as prebuilds/lib.js names it: `node-<NODE_MODULE_VERSION>`. */
const runtimeAbi = () => `node-${process.versions.modules}`;

/**
 * [index] if it is one this file can read, else an Error saying why: the checks prebuilds/lib.js
 * makes of a published index, and two of this file's own, that an asset's name is a plain file
 * name and that its files are addons in build/Release, since those name what is written here.
 * A build is never fetched on the word of a malformed entry.
 */
function checkIndex(index) {
  if (index === null || typeof index !== "object") return new Error("the index is not an object");
  if (index.format !== INDEX_FORMAT) return new Error(`the index is format ${JSON.stringify(index.format)}, this app reads ${INDEX_FORMAT}`);
  if (!Array.isArray(index.entries)) return new Error("the index has no entries list");
  const bad = index.entries.findIndex(
    (e) =>
      !e ||
      ["name", "version", "platform", "arch", "file"].some((k) => typeof e[k] !== "string" || e[k] === "") ||
      !ASSET_FILE.test(e.file) ||
      !/^[0-9a-f]{64}$/.test(e.sha256) ||
      !(e.abi === NAPI || /^node-\d+$/.test(e.abi)) ||
      !Array.isArray(e.files) ||
      e.files.length === 0 ||
      !e.files.every((f) => typeof f === "string" && ADDON_FILE.test(f)),
  );
  if (bad >= 0) return new Error(`entry ${bad} of the index is not a build`);
  return index;
}

/**
 * The index's build of [name]@[version] for this platform that loads under [abi], an N-API one
 * before a C++ API one (it keeps loading when the runtime moves), or null.
 */
function pick(index, name, version, platform, arch, abi) {
  const fits = (index?.entries ?? []).filter(
    (e) => e.name === name && e.version === version && e.platform === platform && e.arch === arch && loadsUnder(e.abi, abi),
  );
  return fits.find((e) => e.abi === NAPI) ?? fits[0] ?? null;
}

/** The directory's entries, or none when it cannot be read. */
function list(dir) {
  try {
    return fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return [];
  }
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch {
    return null;
  }
}

/**
 * The packages under [root] (a node_modules directory) that build a native addon: those with a
 * `binding.gyp`, with their names and versions, nested node_modules and scopes included.
 * Links are not followed (npm links `file:` and workspace packages, which are someone's own
 * checkout), and neither are dot directories (`.bin`, npm's `.package-lock.json` and staging).
 */
function findAddons(root) {
  const found = [];
  const walk = (modules) => {
    for (const d of list(modules)) {
      if (!d.isDirectory() || d.name.startsWith(".")) continue;
      const dir = path.join(modules, d.name);
      if (d.name.startsWith("@")) {
        for (const s of list(dir)) {
          if (s.isDirectory() && !s.name.startsWith(".")) visit(path.join(dir, s.name));
        }
      } else {
        visit(dir);
      }
    }
  };
  const visit = (dir) => {
    if (fs.existsSync(path.join(dir, "binding.gyp"))) {
      const pkg = readJson(path.join(dir, "package.json"));
      if (pkg && typeof pkg.name === "string" && typeof pkg.version === "string") found.push({ dir, name: pkg.name, version: pkg.version });
    }
    walk(path.join(dir, "node_modules"));
  };
  walk(root);
  return found;
}

/** The marker a placed build left in [dir], or null. */
const markerOf = (dir) => readJson(path.join(dir, "build", "Release", MARKER));

/**
 * Whether the package in [dir] has an addon this file did not put there: one in build/Release
 * without a marker (built, or shipped, by the package), or one in its `prebuilds/<platform>-<arch>`
 * for node-gyp-build. Such a package is left as it is.
 */
function hasOwnBuild(dir, platform, arch) {
  if (markerOf(dir)) return false;
  const nodeIn = (d) => list(d).some((e) => e.isFile() && e.name.endsWith(".node"));
  return nodeIn(path.join(dir, "build", "Release")) || nodeIn(path.join(dir, "prebuilds", `${platform}-${arch}`));
}

/** Whether [entry]'s build is the one in place in [dir], all its files there. */
function placed(dir, entry) {
  const m = markerOf(dir);
  return m?.file === entry.file && m?.sha256 === entry.sha256 && m?.pending !== true && entry.files.every((f) => fs.existsSync(path.join(dir, f)));
}

/**
 * The regular files of a tar archive, by name. Only what GNU tar writes for the Prebuilds
 * workflow's assets is needed: ustar headers, short names, no extended headers. Anything else
 * (a directory, a link, a pax header) is skipped rather than followed.
 */
function untar(buffer) {
  const files = new Map();
  let at = 0;
  while (at + 512 <= buffer.length) {
    const header = buffer.subarray(at, at + 512);
    if (header.every((b) => b === 0)) break;
    const field = (start, length) => {
      const raw = header.subarray(start, start + length);
      const end = raw.indexOf(0);
      return raw.subarray(0, end < 0 ? length : end).toString("utf8");
    };
    const size = parseInt(field(124, 12).trim() || "0", 8);
    if (!Number.isFinite(size) || size < 0) throw new Error("a tar header has no size");
    const prefix = field(345, 155);
    const name = prefix ? `${prefix}/${field(0, 100)}` : field(0, 100);
    const type = String.fromCharCode(header[156]);
    const start = at + 512;
    if (start + size > buffer.length) throw new Error(`${name} runs past the end of the archive`);
    if (type === "0" || type === "\0") files.set(name.replace(/^\.\//, ""), buffer.subarray(start, start + size));
    at = start + Math.ceil(size / 512) * 512;
  }
  return files;
}

const sha256 = (bytes) => crypto.createHash("sha256").update(bytes).digest("hex");

/** Writes [bytes] to [file] beside it first, then renamed, so a reader sees the old file or the new one. */
function writeWhole(file, bytes) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const partial = path.join(path.dirname(file), `.${path.basename(file)}.part`);
  fs.writeFileSync(partial, bytes);
  fs.renameSync(partial, file);
}

async function download(url, timeoutMs, fetchFn) {
  const res = await fetchFn(url, { signal: AbortSignal.timeout(timeoutMs) });
  if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
  return Buffer.from(await res.arrayBuffer());
}

/**
 * The published index, fetched now and kept in [cacheDir]; the kept one when it cannot be
 * fetched (offline, at sea); null when there is neither, with the reason in [problems].
 */
async function loadIndex(o, problems) {
  const cached = path.join(o.cacheDir, "index.json");
  try {
    const index = JSON.parse((await download(`${o.baseUrl}index.json`, INDEX_TIMEOUT_MS, o.fetch)).toString("utf8"));
    const checked = checkIndex(index);
    if (checked instanceof Error) throw checked;
    writeWhole(cached, JSON.stringify(checked));
    return checked;
  } catch (e) {
    const kept = checkIndex(readJson(cached));
    if (!(kept instanceof Error)) return kept;
    problems.push(`no index of Android builds: ${e.message}`);
    return null;
  }
}

/** [entry]'s tarball, from [cacheDir] or the Release, checked against its SHA-256 and kept. */
async function asset(entry, o) {
  const cached = path.join(o.cacheDir, entry.file);
  try {
    const bytes = fs.readFileSync(cached);
    if (sha256(bytes) === entry.sha256) return bytes;
  } catch {
    // not kept yet
  }
  const bytes = await download(o.baseUrl + encodeURIComponent(entry.file), ASSET_TIMEOUT_MS, o.fetch);
  const sum = sha256(bytes);
  if (sum !== entry.sha256) throw new Error(`${entry.file} has SHA-256 ${sum}, the index says ${entry.sha256}`);
  writeWhole(cached, bytes);
  return bytes;
}

/**
 * Unpacks [entry]'s files from [tarball] into the package in [dir]. The marker is written twice:
 * pending before the first file, so a run cut short leaves files that are known to be this
 * file's and are put in place again next time (not taken for the package's own build), and
 * whole after the last.
 */
function unpack(dir, entry, tarball) {
  const files = untar(zlib.gunzipSync(tarball));
  const missing = entry.files.find((f) => !files.has(f));
  if (missing) throw new Error(`${entry.file} holds no ${missing}`);
  const marker = path.join(dir, "build", "Release", MARKER);
  const stamp = { file: entry.file, sha256: entry.sha256 };
  writeWhole(marker, JSON.stringify({ ...stamp, pending: true }) + "\n");
  for (const f of entry.files) writeWhole(path.join(dir, f), files.get(f));
  writeWhole(marker, JSON.stringify(stamp) + "\n");
}

/**
 * Records [absent], the packages under [root] left without a build (`name`, `version`, `dir`), in
 * [MISSING_FILE] in [cacheDir] in place of what an earlier run recorded for [root]: a root left
 * with none is dropped, and the file is written only when that changes something (so a phone
 * with nothing missing has no file). Another root's entries are left as they were; a file that is
 * no record at all is started again.
 */
function remember(cacheDir, root, absent) {
  const file = path.join(cacheDir, MISSING_FILE);
  const old = readJson(file);
  const roots = old?.format === MISSING_FORMAT && old.roots && typeof old.roots === "object" && !Array.isArray(old.roots) ? { ...old.roots } : {};
  const before = JSON.stringify(roots[root] ?? []);
  if (absent.length === 0) delete roots[root];
  else roots[root] = absent;
  const valid = old?.format === MISSING_FORMAT;
  if (JSON.stringify(roots[root] ?? []) === before && (valid || Object.keys(roots).length === 0)) return;
  writeWhole(file, JSON.stringify({ format: MISSING_FORMAT, roots }) + "\n");
}

/**
 * Puts in place, under [root], every build the index has for a package installed without its
 * addon, and records the packages it has none for ([remember]). Returns what it did: `placed`
 * and `missing` (no build for this phone) as `name@version`, `failed` with the reason.
 * [options] are for tests: `baseUrl`, `cacheDir`, `fetch`, `platform`, `arch`, `abi`.
 */
async function place(root, options = {}) {
  const o = {
    baseUrl: options.baseUrl ?? process.env.POCKETSK_PREBUILDS_URL ?? RELEASE_URL,
    cacheDir: options.cacheDir ?? process.env.POCKETSK_PREBUILDS,
    fetch: options.fetch ?? globalThis.fetch,
    platform: options.platform ?? process.platform,
    arch: options.arch ?? process.arch,
    abi: options.abi ?? runtimeAbi(),
  };
  const result = { placed: [], missing: [], failed: [] };
  if (!o.cacheDir) {
    result.failed.push("POCKETSK_PREBUILDS is not set");
    return result;
  }
  const absent = [];
  const record = () => {
    try {
      remember(o.cacheDir, root, absent);
    } catch (e) {
      result.failed.push(`the list of packages without a build could not be written: ${e.message}`);
    }
  };
  const wanting = findAddons(root).filter((a) => !hasOwnBuild(a.dir, o.platform, o.arch));
  if (wanting.length === 0) {
    record();
    return result;
  }
  // Without an index nothing is known about what is missing, so the record stands as it was.
  const index = await loadIndex(o, result.failed);
  if (!index) return result;
  for (const a of wanting) {
    const what = `${a.name}@${a.version}`;
    const entry = pick(index, a.name, a.version, o.platform, o.arch, o.abi);
    if (!entry) {
      if (!result.missing.includes(what)) result.missing.push(what);
      absent.push({ name: a.name, version: a.version, dir: a.dir });
      continue;
    }
    if (placed(a.dir, entry)) continue;
    try {
      unpack(a.dir, entry, await asset(entry, o));
      result.placed.push(what);
    } catch (e) {
      result.failed.push(`${what}: ${e.message}`);
    }
  }
  record();
  return result;
}

/**
 * [result] as lines for the install's log: what was put in place for stdout, what was not for
 * stderr, where Signal K's App Store and the app's own log show npm's warnings.
 */
function report(result) {
  return {
    out: result.placed.map((p) => `pocketsk: put the Android build of ${p} in place`),
    err: [
      ...result.missing.map((m) => `pocketsk: no Android build of ${m} yet; it will not load until there is one`),
      ...result.failed.map((f) => `pocketsk: an Android build could not be put in place: ${f}`),
    ],
  };
}

module.exports = { RELEASE_URL, MARKER, MISSING_FILE, INSTALL_COMMANDS, installRoot, loadsUnder, runtimeAbi, checkIndex, pick, findAddons, hasOwnBuild, untar, place, report };
