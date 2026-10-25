// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { Readable } = require("node:stream");
const { Files, FilesError, validFolder, validName, bytes, MAX_PIECE, UPLOADS } = require("../lib/files");

function config() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "files-"));
}

const body = (s) => Readable.from([Buffer.from(s)]);
const plenty = async () => 1024 ** 4;

async function rejects(p, status) {
  await assert.rejects(p, (e) => e instanceof FilesError && e.status === status);
}

test("data folders are plain names, never the plugins or their settings", () => {
  for (const ok of ["charts", "applicationData", "my-tiles_2", "v1.2"]) assert.ok(validFolder(ok), ok);
  for (const bad of ["node_modules", "plugin-config-data", ".hidden", "../x", "a/b", "", "charts.partial", "charts.moved", "x".repeat(65), null]) {
    assert.ok(!validFolder(bad), String(bad));
  }
  for (const ok of ["bay.mbtiles", "Coast 2026.mbtiles", "ä.pmtiles"]) assert.ok(validName(ok), ok);
  for (const bad of ["", ".bay.mbtiles", "a/b", "a\\b", " lead", "x\0y", "x".repeat(201), 3]) assert.ok(!validName(bad), String(bad));
  assert.equal(bytes("123"), 123);
  for (const bad of [undefined, "", "-1", "1.5", "1e3", "9".repeat(16)]) assert.equal(bytes(bad), null);
});

test("a file sent in pieces is whole in the folder at the end, not before", async () => {
  const dir = config();
  const files = new Files({ configDir: dir, free: plenty });
  assert.deepEqual(await files.put("charts", "bay.mbtiles", 0, 10, 4, body("0123")), { received: 4, done: false });
  assert.ok(!fs.existsSync(path.join(dir, "charts", "bay.mbtiles")), "half a chart is not in the folder");
  const listed = await files.list("charts");
  assert.deepEqual(listed.files, []);
  assert.deepEqual(listed.uploads, [{ name: "bay.mbtiles", size: 10, received: 4 }]);
  assert.deepEqual(await files.put("charts", "bay.mbtiles", 4, 10, 6, body("456789")), { received: 10, done: true });
  assert.equal(fs.readFileSync(path.join(dir, "charts", "bay.mbtiles"), "utf8"), "0123456789");
  const after = await files.list("charts");
  assert.deepEqual(after.uploads, []);
  assert.equal(after.files[0].name, "bay.mbtiles");
  assert.equal(after.files[0].size, 10);
  assert.ok(fs.existsSync(path.join(dir, UPLOADS, "charts")), "the pieces gather beside the folder");
});

test("a piece that does not follow on is told where the upload is", async () => {
  const files = new Files({ configDir: config(), free: plenty });
  await files.put("charts", "bay.mbtiles", 0, 10, 4, body("0123"));
  await assert.rejects(files.put("charts", "bay.mbtiles", 8, 10, 2, body("89")), (e) => e.status === 409 && e.extra.received === 4);
  // Offset 0 starts the file again.
  assert.deepEqual(await files.put("charts", "bay.mbtiles", 0, 10, 10, body("abcdefghij")), { received: 10, done: true });
});

test("a piece longer or shorter than it said is refused, what arrived kept", async () => {
  const files = new Files({ configDir: config(), free: plenty });
  await rejects(files.put("charts", "a.mbtiles", 0, 10, 2, body("0123")), 400);
  await assert.rejects(files.put("charts", "b.mbtiles", 0, 10, 4, body("01")), (e) => e.status === 400 && e.extra.received === 2);
  assert.deepEqual((await files.list("charts")).uploads.find((u) => u.name === "b.mbtiles"), { name: "b.mbtiles", size: 10, received: 2 });
});

test("pieces, names and folders out of bounds are refused before anything is written", async () => {
  const dir = config();
  const files = new Files({ configDir: dir, free: plenty });
  await rejects(files.put("charts", "../x", 0, 1, 1, body("x")), 400);
  await rejects(files.put("node_modules", "x", 0, 1, 1, body("x")), 400);
  await rejects(files.put("charts", "x", null, 1, 1, body("x")), 400);
  await rejects(files.put("charts", "x", 2, 1, 0, body("")), 400);
  await rejects(files.put("charts", "x", 0, 1, null, body("x")), 400);
  await rejects(files.put("charts", "x", 0, MAX_PIECE * 2, MAX_PIECE + 1, body("x")), 400);
  await rejects(files.put("charts", "x", 0, 2, 3, body("xxx")), 400);
  assert.ok(!fs.existsSync(path.join(dir, "node_modules")));
});

test("a file that will not fit is refused with the room there is", async () => {
  const files = new Files({ configDir: config(), free: async () => 10 * 1024 * 1024 });
  await assert.rejects(files.put("charts", "big.mbtiles", 0, 1024 ** 3, 1, body("x")), (e) => e.status === 507 && /MB free/.test(e.message));
});

test("one writer at a time for one upload", async () => {
  const files = new Files({ configDir: config(), free: plenty });
  const slow = new Readable({ read() {} });
  const first = files.put("charts", "bay.mbtiles", 0, 4, 4, slow);
  while (files.busy.size === 0) await new Promise((r) => setImmediate(r));
  await rejects(files.put("charts", "bay.mbtiles", 0, 4, 4, body("abcd")), 409);
  slow.push("abcd");
  slow.push(null);
  assert.deepEqual(await first, { received: 4, done: true });
});

test("a whole file replaces one of the same name", async () => {
  const dir = config();
  const files = new Files({ configDir: dir, free: plenty });
  await files.put("charts", "bay.mbtiles", 0, 3, 3, body("old"));
  await files.put("charts", "bay.mbtiles", 0, 3, 3, body("new"));
  assert.equal(fs.readFileSync(path.join(dir, "charts", "bay.mbtiles"), "utf8"), "new");
});

test("files and unfinished uploads can be deleted", async () => {
  const dir = config();
  const files = new Files({ configDir: dir, free: plenty });
  await files.put("charts", "done.mbtiles", 0, 1, 1, body("x"));
  await files.put("charts", "half.mbtiles", 0, 2, 1, body("x"));
  await files.remove("charts", "done.mbtiles", false);
  await files.remove("charts", "half.mbtiles", true);
  const listed = await files.list("charts");
  assert.deepEqual(listed.files, []);
  assert.deepEqual(listed.uploads, []);
  await rejects(files.remove("charts", "done.mbtiles", false), 404);
  await rejects(files.remove("charts", "half.mbtiles", true), 404);
  await rejects(files.remove("charts", "../settings.json", false), 400);
});

test("a folder the phone keeps on a card is written through the link, its pieces on the card", { skip: process.platform === "win32" }, async () => {
  const dir = config();
  const card = config();
  fs.mkdirSync(path.join(card, "signalk", "charts"), { recursive: true });
  fs.symlinkSync(path.join(card, "signalk", "charts"), path.join(dir, "charts"));
  const files = new Files({ configDir: dir, free: plenty });
  await files.put("charts", "bay.mbtiles", 0, 4, 2, body("ab"));
  assert.ok(fs.existsSync(path.join(card, "signalk", UPLOADS, "charts")), "staged on the card's volume");
  await files.put("charts", "bay.mbtiles", 2, 4, 2, body("cd"));
  assert.equal(fs.readFileSync(path.join(card, "signalk", "charts", "bay.mbtiles"), "utf8"), "abcd");
});

test("the folders are the data folders there are, and charts always", async () => {
  const dir = config();
  fs.mkdirSync(path.join(dir, "applicationData"));
  fs.mkdirSync(path.join(dir, "node_modules"));
  fs.mkdirSync(path.join(dir, "plugin-config-data"));
  fs.mkdirSync(path.join(dir, UPLOADS));
  fs.writeFileSync(path.join(dir, "settings.json"), "{}");
  const files = new Files({ configDir: dir, free: plenty });
  assert.deepEqual(await files.folders(), ["applicationData", "charts"]);
  await rejects(files.list("nothing-here"), 404);
  const none = new Files({ configDir: path.join(dir, "missing"), free: plenty });
  assert.deepEqual(await none.folders(), ["charts"]);
  // The folder offered is one that can be listed, and so uploaded into.
  assert.deepEqual((await none.list("charts")).files, []);
  assert.ok(fs.existsSync(path.join(dir, "missing", "charts")));
});

test("the room is the volume's own when none is given", async () => {
  const dir = config();
  fs.mkdirSync(path.join(dir, "charts"));
  const listed = await new Files({ configDir: dir }).list("charts");
  assert.ok(listed.free > 0);
});
