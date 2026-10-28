// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

const lib = require("../lib.js");

test("versions: the newest of a range first, prereleases and repeats left out", () => {
  assert.deepEqual(lib.pickVersions(["12.0.0", "12.0.1", "12.1.0-beta.1", "12.0.10", "12.0.1"], 2), ["12.0.10", "12.0.1"]);
  assert.deepEqual(lib.pickVersions("4.1.0", 2), ["4.1.0"]);
  assert.deepEqual(lib.pickVersions([], 2), []);
  assert.deepEqual(lib.pickVersions(null, 2), []);
  assert.deepEqual(lib.pickVersions(["1.0.0", "2.0.0"], 0), []);
});

test("an asset is named as npm packs the package, with its version, platform and a C++ API's ABI", () => {
  assert.equal(lib.assetName("@serialport/bindings-cpp", "13.0.1", "android", "arm64"), "serialport-bindings-cpp@13.0.1-android-arm64.tar.gz");
  assert.equal(lib.assetName("@serialport/bindings-cpp", "13.0.1", "android", "arm64", lib.NAPI), "serialport-bindings-cpp@13.0.1-android-arm64.tar.gz");
  assert.equal(lib.assetName("better-sqlite3", "12.4.1", "android", "arm64", lib.nodeAbi(137)), "better-sqlite3@12.4.1-android-arm64-node137.tar.gz");
});

test("abiOf: N-API from napi_register_module_v1, a C++ API build from its NODE_MODULE_VERSION", () => {
  assert.equal(lib.abiOf([["napi_register_module_v1", "other"]]), lib.NAPI);
  assert.equal(lib.abiOf([["node_register_module_v137"], ["sqlite3_extension_init"]]), "node-137");
  assert.equal(lib.abiOf([["napi_register_module_v1"], ["node_register_module_v137"]]), "node-137", "the stricter wins");
  assert.equal(lib.abiOf([["sqlite3_extension_init"], [""]]), null);
  assert.equal(lib.abiOf([]), null);
});

test("loadsUnder: N-API under any runtime, a C++ API build only under its own", () => {
  assert.equal(lib.loadsUnder(lib.NAPI, "node-141"), true);
  assert.equal(lib.loadsUnder("node-137", "node-137"), true);
  assert.equal(lib.loadsUnder("node-137", "node-141"), false);
});

/** The first 20 bytes of an ELF header: class, data, type and machine as given. */
function elf({ cls = 2, data = 1, type = 3, machine = 183 } = {}) {
  const b = Buffer.alloc(20);
  Buffer.from([0x7f, 0x45, 0x4c, 0x46, cls, data]).copy(b);
  b.writeUInt16LE(type, 16);
  b.writeUInt16LE(machine, 18);
  return b;
}

test("only a 64-bit little-endian AArch64 shared object passes", () => {
  assert.equal(lib.isAarch64SharedObject(elf()), true);
  assert.equal(lib.isAarch64SharedObject(elf({ machine: 62 })), false, "x86-64");
  assert.equal(lib.isAarch64SharedObject(elf({ machine: 40, cls: 1 })), false, "32-bit ARM");
  assert.equal(lib.isAarch64SharedObject(elf({ type: 2 })), false, "an executable");
  assert.equal(lib.isAarch64SharedObject(Buffer.from("#!/bin/sh\necho no\n")), false);
  assert.equal(lib.isAarch64SharedObject(Buffer.alloc(4)), false);
});

const entry = (name, version, sha256 = "0".repeat(64), abi = lib.NAPI) => ({ name, version, platform: "android", arch: "arm64", abi, file: lib.assetName(name, version, "android", "arm64", abi), sha256, size: 1, files: [] });

test("a run's builds join the published index, sorted, a rebuild replacing its entry", () => {
  const published = { format: lib.INDEX_FORMAT, nodejsMobile: "24.19.0-0", entries: [entry("sqlite3", "5.1.7"), entry("better-sqlite3", "12.0.10", "1".repeat(64))] };
  const merged = lib.mergeIndex(published, [entry("better-sqlite3", "12.0.10", "2".repeat(64)), entry("better-sqlite3", "12.0.9")], "24.20.0-0");
  assert.equal(merged.format, lib.INDEX_FORMAT);
  assert.equal(merged.nodejsMobile, "24.20.0-0");
  assert.deepEqual(merged.entries.map((e) => `${e.name}@${e.version}:${e.sha256[0]}`), ["better-sqlite3@12.0.9:0", "better-sqlite3@12.0.10:2", "sqlite3@5.1.7:0"]);
});

test("no index starts afresh; an index of another format or shape is refused, never replaced", () => {
  assert.equal(lib.mergeIndex(null, [entry("x", "1.0.0")], "v").entries.length, 1);
  assert.throws(() => lib.mergeIndex({ format: 99, entries: [entry("x", "1.0.0")] }, [], "v"), /format 99/);
  assert.throws(() => lib.mergeIndex({ format: lib.INDEX_FORMAT }, [], "v"), /no entries/);
  assert.throws(() => lib.mergeIndex({ format: lib.INDEX_FORMAT, entries: [{ name: "x" }] }, [], "v"), /entry 0/);
});

test("checkIndex: a well-formed index passes, anything else names what is wrong", () => {
  const good = { format: lib.INDEX_FORMAT, entries: [entry("sqlite3", "5.1.7", "a".repeat(64))] };
  assert.equal(lib.checkIndex(good), good);
  assert.equal(lib.checkIndex({ format: lib.INDEX_FORMAT, entries: [] }).entries.length, 0);
  assert.ok(lib.checkIndex([]) instanceof Error);
  assert.ok(lib.checkIndex("index") instanceof Error);
  assert.ok(lib.checkIndex({ format: lib.INDEX_FORMAT, entries: [entry("sqlite3", "5.1.7", "short")] }) instanceof Error);
  assert.ok(lib.checkIndex({ format: lib.INDEX_FORMAT, entries: [{ ...entry("sqlite3", "5.1.7", "a".repeat(64)), file: "" }] }) instanceof Error);
  assert.ok(lib.checkIndex({ format: lib.INDEX_FORMAT, entries: [{ ...entry("sqlite3", "5.1.7"), abi: undefined }] }) instanceof Error, "no ABI");
  assert.ok(lib.checkIndex({ format: lib.INDEX_FORMAT, entries: [entry("sqlite3", "5.1.7", undefined, "v8")] }) instanceof Error, "an unknown ABI");
});

test("has: a build already published that loads under this runtime", () => {
  const index = { format: lib.INDEX_FORMAT, entries: [entry("sqlite3", "5.1.7"), entry("better-sqlite3", "12.4.1", undefined, "node-137")] };
  assert.equal(lib.has(index, "sqlite3", "5.1.7", "android", "arm64", "node-137"), true);
  assert.equal(lib.has(index, "sqlite3", "5.1.7", "android", "arm64", "node-141"), true, "N-API loads under a new runtime");
  assert.equal(lib.has(index, "sqlite3", "5.1.6", "android", "arm64", "node-137"), false);
  assert.equal(lib.has(index, "sqlite3", "5.1.7", "android", "arm", "node-137"), false);
  assert.equal(lib.has(index, "better-sqlite3", "12.4.1", "android", "arm64", "node-137"), true);
  assert.equal(lib.has(index, "better-sqlite3", "12.4.1", "android", "arm64", "node-141"), false, "a new runtime builds it again");
  assert.equal(lib.has(null, "sqlite3", "5.1.7", "android", "arm64", "node-137"), false);
});

test("a rebuild for a new runtime's C++ API joins the index beside the old one", () => {
  const published = { format: lib.INDEX_FORMAT, entries: [entry("better-sqlite3", "12.4.1", "1".repeat(64), "node-137")] };
  const merged = lib.mergeIndex(published, [entry("better-sqlite3", "12.4.1", "2".repeat(64), "node-141")], "v");
  assert.deepEqual(merged.entries.map((e) => `${e.abi}:${e.file}`), [
    "node-137:better-sqlite3@12.4.1-android-arm64-node137.tar.gz",
    "node-141:better-sqlite3@12.4.1-android-arm64-node141.tar.gz",
  ]);
});

test("a node-pre-gyp package gets the variables its binding.gyp expects, any other none", () => {
  const sqlite = { binary: { module_name: "node_sqlite3", module_path: "./lib/binding/napi-v{napi_build_version}", napi_versions: [3, 6] } };
  assert.deepEqual(lib.preGypArgs(sqlite, "/w/build/binding"), ["--module_name=node_sqlite3", "--module_path=/w/build/binding", "--napi_build_version=6"]);
  assert.deepEqual(lib.preGypArgs({ binary: { module_name: "m" } }, "/b"), ["--module_name=m", "--module_path=/b", "--napi_build_version=3"]);
  assert.deepEqual(lib.preGypArgs({ name: "better-sqlite3" }, "/b"), []);
  assert.deepEqual(lib.preGypArgs(null, "/b"), []);
});

test("parseIndex: a file holding null, or anything but an index, is refused, never taken for none", () => {
  assert.throws(() => lib.parseIndex("null"), /not an object/);
  assert.throws(() => lib.parseIndex("[]"), /format/);
  assert.throws(() => lib.parseIndex("{"), SyntaxError);
  const text = JSON.stringify({ format: lib.INDEX_FORMAT, nodejsMobile: "v", entries: [entry("sqlite3", "5.1.7")] });
  assert.equal(lib.parseIndex(text).entries.length, 1);
});

test("gypArgs: a package's own gyp variables, --name=value only", () => {
  assert.deepEqual(lib.gypArgs({ name: "better-sqlite3", gyp: ["--force_build=1"] }), ["--force_build=1"]);
  assert.deepEqual(lib.gypArgs({ name: "sqlite3" }), []);
  assert.throws(() => lib.gypArgs({ name: "x", gyp: "--force_build=1" }), /not a list/);
  assert.throws(() => lib.gypArgs({ name: "x", gyp: ["--force_build=1; rm -rf /"] }), /not a --name=value/);
  assert.throws(() => lib.gypArgs({ name: "x", gyp: ["rebuild"] }), /not a --name=value/);
});
