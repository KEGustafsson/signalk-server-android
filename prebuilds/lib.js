// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * The pure part of the prebuild job (docs/PLAN.md §9.1), kept apart from build.js so the rules
 * are tested without a compiler: which versions are built, what each built file is called, what
 * passes as an Android arm64 library, and how a run's results join the published index.
 *
 * The index is what the phone's node-gyp shim (§9.2) reads to find a package's build: one entry
 * per package, version and platform, naming the release asset and its SHA-256, which the shim
 * checks before unpacking a byte.
 */

/** The index's own version, which the shim checks before reading the rest. */
const INDEX_FORMAT = 1;

/** The ELF machine number of AArch64 (e_machine, at byte 18). */
const EM_AARCH64 = 183;

/**
 * A version as numbers, for sorting: "13.0.1" is [13, 0, 1]. A prerelease ("13.1.0-beta.1")
 * gives null, since the registry's prereleases are never what a plugin's range resolves to.
 */
function parseVersion(v) {
  const m = /^(\d+)\.(\d+)\.(\d+)$/.exec(String(v));
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null;
}

function compareVersions(a, b) {
  const x = parseVersion(a);
  const y = parseVersion(b);
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] - y[i];
  return 0;
}

/**
 * The versions of one range to build: the newest [keep] of those the registry gave for it
 * (`npm view <name>@<range> version --json`, a string for one, an array for several). The newest
 * is what an App Store install resolves a plugin's range to; the one before it covers a plugin
 * whose lockfile is a release behind.
 */
function pickVersions(viewed, keep) {
  const all = (Array.isArray(viewed) ? viewed : viewed == null ? [] : [viewed]).filter((v) => parseVersion(v));
  if (!(keep > 0)) return [];
  return [...new Set(all)].sort(compareVersions).slice(-keep).reverse();
}

/**
 * The release asset of one build: npm pack's name for the package ("@serialport/bindings-cpp"
 * packs as "serialport-bindings-cpp"), then the version and the platform. A release asset's name
 * may hold neither "/" nor a leading "@".
 */
function assetName(name, version, platform, arch) {
  const base = name.replace(/^@/, "").replace(/\//g, "-");
  return `${base}@${version}-${platform}-${arch}.tar.gz`;
}

/** Whether [bytes] begin an ELF 64-bit little-endian shared object for AArch64. */
function isAarch64SharedObject(bytes) {
  if (!bytes || bytes.length < 20) return false;
  const elf = bytes[0] === 0x7f && bytes[1] === 0x45 && bytes[2] === 0x4c && bytes[3] === 0x46;
  const is64 = bytes[4] === 2;
  const little = bytes[5] === 1;
  const shared = bytes.readUInt16LE(16) === 3; // ET_DYN
  return elf && is64 && little && shared && bytes.readUInt16LE(18) === EM_AARCH64;
}

/**
 * The gyp variables a node-pre-gyp package's binding.gyp expects (sqlite3 5.1.6 and before):
 * node-pre-gyp passes them to node-gyp from package.json's `binary`, and gyp stops at the first
 * it lacks. The module is built as usual into build/Release, where the asset takes it from;
 * [modulePath] is only where binding.gyp's own copy step puts a second copy. The highest N-API
 * version the package names, which nodejs-mobile's Node has. None for any other package.
 */
function preGypArgs(packageJson, modulePath) {
  const binary = packageJson?.binary;
  if (!binary?.module_name) return [];
  const napi = Math.max(3, ...(Array.isArray(binary.napi_versions) ? binary.napi_versions.filter(Number.isInteger) : []));
  return [`--module_name=${binary.module_name}`, `--module_path=${modulePath}`, `--napi_build_version=${napi}`];
}

const key = (e) => `${e.name}@${e.version}/${e.platform}-${e.arch}`;

/** Whether [index] already has a build of that package, version and platform. */
function has(index, name, version, platform, arch) {
  return (index?.entries ?? []).some((e) => key(e) === key({ name, version, platform, arch }));
}

/**
 * [index] as an index this script can extend, or an Error saying why not: another format, or
 * entries that do not each name a package, version, platform, arch, asset and SHA-256. Such an
 * index is refused rather than replaced, since a run that started afresh from it would publish
 * an index that has lost every earlier build.
 */
function checkIndex(index) {
  if (index === null || typeof index !== "object") return new Error("the published index is not an object");
  if (index.format !== INDEX_FORMAT) return new Error(`the published index is format ${JSON.stringify(index.format)}, this script writes ${INDEX_FORMAT}`);
  if (!Array.isArray(index.entries)) return new Error("the published index has no entries list");
  const bad = index.entries.findIndex(
    (e) => !e || ["name", "version", "platform", "arch", "file"].some((k) => typeof e[k] !== "string" || e[k] === "") || !/^[0-9a-f]{64}$/.test(e.sha256),
  );
  if (bad >= 0) return new Error(`entry ${bad} of the published index is not a build`);
  return index;
}

/**
 * The published index with this run's [built] entries joined in: a new build of a package,
 * version and platform already listed replaces the old entry; everything else is kept, sorted
 * so the file changes only where a build did. An index [checkIndex] refuses throws. [nodejsMobile] is the runtime the run built
 * against, recorded for whoever reads the index; N-API keeps older builds loading under it.
 */
function mergeIndex(published, built, nodejsMobile) {
  const byKey = new Map();
  if (published != null) {
    const checked = checkIndex(published);
    if (checked instanceof Error) throw checked;
    for (const e of checked.entries) byKey.set(key(e), e);
  }
  for (const e of built) byKey.set(key(e), e);
  const entries = [...byKey.values()].sort((a, b) =>
    a.name !== b.name ? (a.name < b.name ? -1 : 1) : a.version !== b.version ? compareVersions(a.version, b.version) : key(a) < key(b) ? -1 : 1,
  );
  return { format: INDEX_FORMAT, nodejsMobile, entries };
}

module.exports = { INDEX_FORMAT, parseVersion, compareVersions, pickVersions, assetName, isAarch64SharedObject, preGypArgs, has, checkIndex, mergeIndex };
