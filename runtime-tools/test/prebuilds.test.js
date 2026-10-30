// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const zlib = require("node:zlib");

const prebuilds = require("../npm/prebuilds.js");

/** A tar archive of [files] (name to contents), as GNU tar writes one: ustar headers, two zero blocks. */
function tar(files) {
  const parts = [];
  for (const [name, data] of Object.entries(files)) {
    const h = Buffer.alloc(512);
    h.write(name, 0, "utf8");
    h.write("0000644\0", 100);
    h.write("0000000\0", 108);
    h.write("0000000\0", 116);
    h.write(data.length.toString(8).padStart(11, "0") + "\0", 124);
    h.write("00000000000\0", 136);
    h.write("        ", 148);
    h.write("0", 156);
    h.write("ustar  \0", 257);
    let sum = 0;
    for (const b of h) sum += b;
    h.write(sum.toString(8).padStart(6, "0") + "\0 ", 148);
    parts.push(h, data, Buffer.alloc((512 - (data.length % 512)) % 512));
  }
  parts.push(Buffer.alloc(1024));
  return Buffer.concat(parts);
}

const sha = (b) => crypto.createHash("sha256").update(b).digest("hex");

function tmp() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "pocketsk-prebuilds-"));
}

/** A package in [modules]: its package.json, and a binding.gyp unless [gyp] is false. */
function pkg(modules, name, version, { gyp = true } = {}) {
  const dir = path.join(modules, ...name.split("/"));
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, "package.json"), JSON.stringify({ name, version }));
  if (gyp) fs.writeFileSync(path.join(dir, "binding.gyp"), "{}");
  return dir;
}

/** An index entry and its tarball for [name]@[version]. */
function build(name, version, { abi = "napi", files = { "build/Release/addon.node": Buffer.from(`${name}@${version}/${abi}`) } } = {}) {
  const tarball = zlib.gzipSync(tar(files));
  const file = `${name.replace(/^@/, "").replace(/\//g, "-")}@${version}-android-arm64${abi === "napi" ? "" : "-" + abi.replace("-", "")}.tar.gz`;
  return { entry: { name, version, platform: "android", arch: "arm64", abi, file, sha256: sha(tarball), size: tarball.length, files: Object.keys(files) }, tarball };
}

/** A fetch that serves [assets] (file name to bytes) under BASE and counts what it was asked for. */
function server(assets) {
  const asked = [];
  const fetch = async (url) => {
    asked.push(url);
    const name = decodeURIComponent(url.slice(BASE.length));
    const body = assets[name];
    if (body === undefined) return { ok: false, status: 404 };
    return { ok: true, status: 200, arrayBuffer: async () => body };
  };
  return { fetch, asked };
}

const BASE = "https://example.test/prebuilds/";
const ANDROID = { platform: "android", arch: "arm64", abi: "node-137" };
const indexOf = (...builds) => Buffer.from(JSON.stringify({ format: 1, nodejsMobile: "24.20.0-0", entries: builds.map((b) => b.entry) }));

test("an install-like command names where it installs; anything else installs nothing", () => {
  const env = { npm_config_prefix: "/f/sk/current" };
  assert.equal(prebuilds.installRoot(["--save", "--ignore-scripts", "install", "--", "signalk-x@1.0.0"], "/h/.signalk", env), "/h/.signalk/node_modules");
  assert.equal(prebuilds.installRoot(["install", "--ignore-scripts", "--omit=dev"], "/h/.signalk", env), "/h/.signalk/node_modules");
  assert.equal(prebuilds.installRoot(["install", "-g", "--prefix", "/f/sk/slots/B", "signalk-server@2.35.1"], "/h", env), "/f/sk/slots/B/lib/node_modules");
  assert.equal(prebuilds.installRoot(["i", "-g", "--prefix=/f/sk/slots/A"], "/h", env), "/f/sk/slots/A/lib/node_modules");
  assert.equal(prebuilds.installRoot(["update", "--global"], "/h", env), "/f/sk/current/lib/node_modules");
  assert.equal(prebuilds.installRoot(["ci", "--prefix", "sub"], "/h", env), "/h/sub/node_modules");
  assert.equal(prebuilds.installRoot(["--version"], "/h", env), null);
  assert.equal(prebuilds.installRoot(["view", "signalk-server", "versions", "--json"], "/h", env), null);
  assert.equal(prebuilds.installRoot(["uninstall", "--", "install"], "/h", env), null, "a package named install is not the command");
  assert.equal(prebuilds.installRoot(["install", "-g"], "/h", {}), null, "a global install with no prefix to find");
});

test("checkIndex: a build is fetched only on the word of a well-formed entry", () => {
  const good = build("sqlite3", "5.1.7").entry;
  const index = (e) => ({ format: 1, entries: [e] });
  assert.equal(prebuilds.checkIndex(index(good)).entries.length, 1);
  assert.ok(prebuilds.checkIndex(null) instanceof Error);
  assert.ok(prebuilds.checkIndex({ format: 2, entries: [] }) instanceof Error);
  assert.ok(prebuilds.checkIndex(index({ ...good, file: "../index.json" })) instanceof Error, "a name that leaves the cache");
  assert.ok(prebuilds.checkIndex(index({ ...good, file: "a/b.tar.gz" })) instanceof Error);
  assert.ok(prebuilds.checkIndex(index({ ...good, files: ["../../package.json"] })) instanceof Error, "a file outside build/Release");
  assert.ok(prebuilds.checkIndex(index({ ...good, files: ["build/Release/sub/x.node"] })) instanceof Error);
  assert.ok(prebuilds.checkIndex(index({ ...good, files: [] })) instanceof Error);
  assert.ok(prebuilds.checkIndex(index({ ...good, sha256: "00" })) instanceof Error);
  assert.ok(prebuilds.checkIndex(index({ ...good, abi: "v8" })) instanceof Error);
});

test("pick: this platform's build that loads under this runtime, N-API first", () => {
  const napi = build("x", "1.0.0").entry;
  const cpp = build("x", "1.0.0", { abi: "node-137" }).entry;
  const old = build("y", "1.0.0", { abi: "node-127" }).entry;
  const index = { entries: [cpp, napi, old, { ...napi, name: "z", arch: "arm" }] };
  assert.equal(prebuilds.pick(index, "x", "1.0.0", "android", "arm64", "node-137"), napi);
  assert.equal(prebuilds.pick({ entries: [cpp] }, "x", "1.0.0", "android", "arm64", "node-137"), cpp);
  assert.equal(prebuilds.pick(index, "y", "1.0.0", "android", "arm64", "node-137"), null, "a C++ API build for another runtime");
  assert.equal(prebuilds.pick(index, "z", "1.0.0", "android", "arm64", "node-137"), null, "another arch");
  assert.equal(prebuilds.pick(index, "x", "1.0.1", "android", "arm64", "node-137"), null);
});

test("untar: the regular files of an archive, by name", () => {
  const files = prebuilds.untar(tar({ "build/Release/a.node": Buffer.from("aa"), "build/Release/b.node": Buffer.alloc(700, 1) }));
  assert.deepEqual([...files.keys()], ["build/Release/a.node", "build/Release/b.node"]);
  assert.equal(files.get("build/Release/a.node").toString(), "aa");
  assert.equal(files.get("build/Release/b.node").length, 700);
  assert.throws(() => prebuilds.untar(tar({ "x.node": Buffer.alloc(600) }).subarray(0, 700)), /past the end/);
});

test("findAddons: packages with a binding.gyp, scoped and nested, links and dot directories left out", () => {
  const root = path.join(tmp(), "node_modules");
  pkg(root, "plain", "1.0.0", { gyp: false });
  pkg(root, "@serialport/bindings-cpp", "13.0.1");
  const parent = pkg(root, "signalk-x", "1.0.0", { gyp: false });
  pkg(path.join(parent, "node_modules"), "better-sqlite3", "12.11.1");
  pkg(path.join(root, ".staging"), "hidden", "1.0.0");
  fs.symlinkSync(pkg(path.join(tmp(), "elsewhere"), "linked", "1.0.0"), path.join(root, "linked"));
  const found = prebuilds.findAddons(root).map((a) => `${a.name}@${a.version}`).sort();
  assert.deepEqual(found, ["@serialport/bindings-cpp@13.0.1", "better-sqlite3@12.11.1"]);
  assert.deepEqual(prebuilds.findAddons(path.join(root, "absent")), []);
});

test("place: each addon without a build gets this phone's from the index, checked and kept for next time", async () => {
  const root = path.join(tmp(), "node_modules");
  const cacheDir = tmp();
  const serial = build("@serialport/bindings-cpp", "13.0.1", { files: { "build/Release/bindings.node": Buffer.from("serial") } });
  const sqlite = build("better-sqlite3", "12.11.1", { abi: "node-137", files: { "build/Release/better_sqlite3.node": Buffer.from("db"), "build/Release/test_extension.node": Buffer.from("ext") } });
  const serialDir = pkg(root, "@serialport/bindings-cpp", "13.0.1");
  const sqliteDir = pkg(path.join(pkg(root, "signalk-x", "1.0.0", { gyp: false }), "node_modules"), "better-sqlite3", "12.11.1");
  pkg(root, "@canboat/socketcan", "1.0.0");
  const own = pkg(root, "own-build", "1.0.0");
  fs.mkdirSync(path.join(own, "build", "Release"), { recursive: true });
  fs.writeFileSync(path.join(own, "build", "Release", "own.node"), "mine");

  const s = server({ "index.json": indexOf(serial, sqlite), [serial.entry.file]: serial.tarball, [sqlite.entry.file]: sqlite.tarball });
  const result = await prebuilds.place(root, { ...ANDROID, baseUrl: BASE, cacheDir, fetch: s.fetch });
  assert.deepEqual(result, { placed: ["@serialport/bindings-cpp@13.0.1", "better-sqlite3@12.11.1"], missing: ["@canboat/socketcan@1.0.0"], failed: [] });
  assert.equal(fs.readFileSync(path.join(serialDir, "build/Release/bindings.node"), "utf8"), "serial");
  assert.equal(fs.readFileSync(path.join(sqliteDir, "build/Release/better_sqlite3.node"), "utf8"), "db");
  assert.equal(fs.readFileSync(path.join(sqliteDir, "build/Release/test_extension.node"), "utf8"), "ext");
  assert.equal(fs.readFileSync(path.join(own, "build", "Release", "own.node"), "utf8"), "mine", "a package's own build is left alone");
  assert.ok(fs.existsSync(path.join(cacheDir, serial.entry.file)) && fs.existsSync(path.join(cacheDir, "index.json")));

  // Run again offline: the builds in place are known by their markers, and nothing is fetched
  // but the index, which the kept copy stands in for.
  const offline = server({});
  const again = await prebuilds.place(root, { ...ANDROID, baseUrl: BASE, cacheDir, fetch: offline.fetch });
  assert.deepEqual(again, { placed: [], missing: ["@canboat/socketcan@1.0.0"], failed: [] });
  assert.deepEqual(offline.asked, [BASE + "index.json"]);

  // A package installed again (its build/Release gone) gets its build back offline, from the kept tarball.
  fs.rmSync(path.join(serialDir, "build"), { recursive: true });
  const reinstalled = await prebuilds.place(root, { ...ANDROID, baseUrl: BASE, cacheDir, fetch: offline.fetch });
  assert.deepEqual(reinstalled.placed, ["@serialport/bindings-cpp@13.0.1"]);
  assert.equal(fs.readFileSync(path.join(serialDir, "build/Release/bindings.node"), "utf8"), "serial");
});

test("place: what is left without a build is recorded in its tree's own file, and cleared when it is not", async () => {
  const base = tmp();
  const root = path.join(base, "a", "node_modules");
  const other = path.join(base, "b", "node_modules");
  const cacheDir = tmp();
  const records = () => Object.fromEntries(
    (fs.existsSync(path.join(cacheDir, prebuilds.MISSING_DIR)) ? fs.readdirSync(path.join(cacheDir, prebuilds.MISSING_DIR)) : [])
      .map((f) => [f, JSON.parse(fs.readFileSync(path.join(cacheDir, prebuilds.MISSING_DIR, f), "utf8"))]),
  );
  const fileOf = (r) => path.basename(prebuilds.missingFile(cacheDir, r));
  const serial = build("@serialport/bindings-cpp", "13.0.1", { files: { "build/Release/bindings.node": Buffer.from("serial") } });
  const s = server({ "index.json": indexOf(serial), [serial.entry.file]: serial.tarball });
  const can = pkg(root, "@canboat/socketcan", "1.0.0");
  pkg(root, "@serialport/bindings-cpp", "13.0.1");
  const elsewhere = pkg(other, "usb", "2.9.0");

  await prebuilds.place(root, { ...ANDROID, baseUrl: BASE, cacheDir, fetch: s.fetch });
  await prebuilds.place(other, { ...ANDROID, baseUrl: BASE, cacheDir, fetch: s.fetch });
  assert.deepEqual(records(), {
    [fileOf(root)]: { format: 1, root, missing: [{ name: "@canboat/socketcan", version: "1.0.0", dir: can }] },
    [fileOf(other)]: { format: 1, root: other, missing: [{ name: "usb", version: "2.9.0", dir: elsewhere }] },
  }, "the placed build is not in it; each tree has a file of its own");
  assert.match(fileOf(root), /^[0-9a-f]{32}\.json$/);

  // Offline, with no kept index either: nothing is known, so the record stands.
  fs.rmSync(path.join(cacheDir, "index.json"));
  const before = records();
  await prebuilds.place(root, { ...ANDROID, baseUrl: BASE, cacheDir, fetch: server({}).fetch });
  assert.deepEqual(records(), before);

  // The package builds its own addon (or is removed): its tree's file goes, the other's stays.
  fs.mkdirSync(path.join(can, "build", "Release"), { recursive: true });
  fs.writeFileSync(path.join(can, "build", "Release", "can.node"), "own");
  await prebuilds.place(root, { ...ANDROID, baseUrl: BASE, cacheDir, fetch: s.fetch });
  assert.deepEqual(Object.keys(records()), [fileOf(other)]);
});

test("place: a tree's record that is not one is written again, and nothing is left staged", async () => {
  const root = path.join(tmp(), "node_modules");
  const cacheDir = tmp();
  const file = prebuilds.missingFile(cacheDir, root);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, "[1, 2");
  const dir = pkg(root, "usb", "2.9.0");
  await prebuilds.place(root, { ...ANDROID, baseUrl: BASE, cacheDir, fetch: server({ "index.json": indexOf() }).fetch });
  assert.deepEqual(JSON.parse(fs.readFileSync(file, "utf8")), { format: 1, root, missing: [{ name: "usb", version: "2.9.0", dir }] });
  assert.deepEqual(fs.readdirSync(path.dirname(file)), [path.basename(file)], "no staging file left beside it");
});

test("place: a build for a new runtime replaces the one this file put in place for the old", async () => {
  const root = path.join(tmp(), "node_modules");
  const cacheDir = tmp();
  const dir = pkg(root, "better-sqlite3", "12.11.1");
  const old = build("better-sqlite3", "12.11.1", { abi: "node-137", files: { "build/Release/better_sqlite3.node": Buffer.from("137") } });
  const now = build("better-sqlite3", "12.11.1", { abi: "node-141", files: { "build/Release/better_sqlite3.node": Buffer.from("141") } });
  const s = server({ "index.json": indexOf(old, now), [old.entry.file]: old.tarball, [now.entry.file]: now.tarball });
  await prebuilds.place(root, { ...ANDROID, baseUrl: BASE, cacheDir, fetch: s.fetch });
  assert.equal(fs.readFileSync(path.join(dir, "build/Release/better_sqlite3.node"), "utf8"), "137");
  const result = await prebuilds.place(root, { ...ANDROID, abi: "node-141", baseUrl: BASE, cacheDir, fetch: s.fetch });
  assert.deepEqual(result.placed, ["better-sqlite3@12.11.1"]);
  assert.equal(fs.readFileSync(path.join(dir, "build/Release/better_sqlite3.node"), "utf8"), "141");
});

test("place: a tarball that does not match its SHA-256, or lacks a file, puts nothing in place", async () => {
  const root = path.join(tmp(), "node_modules");
  const cacheDir = tmp();
  const a = build("a", "1.0.0");
  const b = build("b", "1.0.0");
  const lying = { ...build("c", "1.0.0"), tarball: zlib.gzipSync(tar({ "build/Release/other.node": Buffer.from("x") })) };
  lying.entry.sha256 = sha(lying.tarball);
  for (const n of ["a", "b", "c"]) pkg(root, n, "1.0.0");
  const s = server({ "index.json": indexOf(a, b, lying), [a.entry.file]: Buffer.from("tampered"), [b.entry.file]: b.tarball, [lying.entry.file]: lying.tarball });
  const result = await prebuilds.place(root, { ...ANDROID, baseUrl: BASE, cacheDir, fetch: s.fetch });
  assert.deepEqual(result.placed, ["b@1.0.0"]);
  assert.equal(result.failed.length, 2);
  assert.match(result.failed[0], /^a@1\.0\.0: .* has SHA-256 [0-9a-f]{64}, the index says/);
  assert.match(result.failed[1], /^c@1\.0\.0: .* holds no build\/Release\/addon\.node/);
  assert.ok(!fs.existsSync(path.join(root, "a", "build")), "nothing unpacked from a tarball that failed its check");
  assert.ok(!fs.existsSync(path.join(cacheDir, a.entry.file)), "nor kept");
  assert.ok(!fs.existsSync(path.join(root, "c", "build")));
});

test("place: a run cut short is finished next time, not taken for the package's own build", async () => {
  const root = path.join(tmp(), "node_modules");
  const cacheDir = tmp();
  const two = build("two", "1.0.0", { files: { "build/Release/a.node": Buffer.from("a"), "build/Release/b.node": Buffer.from("b") } });
  const dir = pkg(root, "two", "1.0.0");
  fs.mkdirSync(path.join(dir, "build", "Release"), { recursive: true });
  // What unpack leaves when it is stopped after the first file.
  fs.writeFileSync(path.join(dir, "build", "Release", prebuilds.MARKER), JSON.stringify({ file: two.entry.file, sha256: two.entry.sha256, pending: true }));
  fs.writeFileSync(path.join(dir, "build", "Release", "a.node"), "a");
  const s = server({ "index.json": indexOf(two), [two.entry.file]: two.tarball });
  const result = await prebuilds.place(root, { ...ANDROID, baseUrl: BASE, cacheDir, fetch: s.fetch });
  assert.deepEqual(result.placed, ["two@1.0.0"]);
  assert.equal(fs.readFileSync(path.join(dir, "build/Release/b.node"), "utf8"), "b");
  assert.equal(JSON.parse(fs.readFileSync(path.join(dir, "build", "Release", prebuilds.MARKER), "utf8")).pending, undefined);
});

test("place: no index anywhere, or none needed, and nothing is fetched or written", async () => {
  const root = path.join(tmp(), "node_modules");
  const cacheDir = tmp();
  pkg(root, "plain", "1.0.0", { gyp: false });
  const idle = server({});
  assert.deepEqual(await prebuilds.place(root, { ...ANDROID, baseUrl: BASE, cacheDir, fetch: idle.fetch }), { placed: [], missing: [], failed: [] });
  assert.deepEqual(idle.asked, [], "no addon wanting a build, no network");
  pkg(root, "needs", "1.0.0");
  const result = await prebuilds.place(root, { ...ANDROID, baseUrl: BASE, cacheDir, fetch: idle.fetch });
  assert.equal(result.failed.length, 1);
  assert.match(result.failed[0], /^no index of Android builds: .*HTTP 404/);
  const bad = server({ "index.json": Buffer.from(JSON.stringify({ format: 1, entries: [{ name: "needs" }] })) });
  assert.match((await prebuilds.place(root, { ...ANDROID, baseUrl: BASE, cacheDir, fetch: bad.fetch })).failed[0], /entry 0 of the index is not a build/);
  assert.deepEqual(fs.readdirSync(cacheDir), [], "a malformed index is not kept");
  assert.deepEqual((await prebuilds.place(root, { ...ANDROID, baseUrl: BASE, cacheDir: undefined, fetch: idle.fetch })).failed, ["POCKETSK_PREBUILDS is not set"]);
});

test("report: what was put in place for stdout, the rest for stderr", () => {
  const lines = prebuilds.report({ placed: ["a@1.0.0"], missing: ["b@2.0.0"], failed: ["c@3.0.0: HTTP 500"] });
  assert.deepEqual(lines.out, ["pocketsk: put the Android build of a@1.0.0 in place"]);
  assert.equal(lines.err.length, 2);
  assert.match(lines.err[0], /no Android build of b@2\.0\.0 yet/);
  assert.match(lines.err[1], /c@3\.0\.0: HTTP 500$/);
});
