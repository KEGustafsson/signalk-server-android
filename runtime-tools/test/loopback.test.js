// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const PRELOAD = path.join(__dirname, "..", "preload", "loopback.js");
const m = require(PRELOAD);

function tmp() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "pocketsk-loopback-"));
}

/** Runs [script] in a node that loads the preload as the app's do, [loopback] or not; its stdout. */
function run(script, { loopback, cwd } = {}) {
  const env = { ...process.env, NODE_OPTIONS: `--require="${PRELOAD}"` };
  if (loopback) env[m.ENV] = "1";
  else delete env[m.ENV];
  return new Promise((ok, fail) => {
    const child = spawn(process.execPath, ["-e", script], { env, cwd, stdio: ["ignore", "pipe", "inherit"] });
    let out = "";
    child.stdout.on("data", (d) => (out += d));
    child.on("error", fail);
    child.on("exit", (code) => (code === 0 ? ok(out.trim()) : fail(new Error(`exit ${code}: ${out}`))));
  });
}

test("loopback addresses are told from the rest", () => {
  for (const h of ["localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3", "::1", "[::1]", "0:0:0:0:0:0:0:1"]) {
    assert.ok(m.isLoopback(h), h);
  }
  for (const h of ["0.0.0.0", "::", "192.168.1.9", "10.0.0.2", "fe80::1", "128.0.0.1", "127.0.0", "", undefined, null, 3000]) {
    assert.ok(!m.isLoopback(h), String(h));
  }
});

test("a TCP port about to be bound beyond the loopback moves to it, nothing else does", () => {
  const to = (address, port, addressType, fd, hasHandle = false) => m.loopbackAddress(address, port, addressType, fd, hasHandle);
  const loop = { address: "127.0.0.1", addressType: 4 };
  assert.deepEqual(to(null, 3000, 4, undefined), loop, "every interface");
  assert.deepEqual(to("0.0.0.0", 3000, 4, undefined), loop);
  assert.deepEqual(to("::", 0, 6, undefined), loop);
  assert.deepEqual(to("192.168.1.9", 3000, 4, undefined), loop);
  assert.deepEqual(to("127.0.0.1", 3000, 4, undefined), { address: "127.0.0.1", addressType: 4 });
  assert.deepEqual(to("::1", 3000, 6, undefined), { address: "::1", addressType: 6 });
  assert.deepEqual(to("/data/x.sock", -1, -1, undefined), { address: "/data/x.sock", addressType: -1 }, "a Unix socket");
  assert.deepEqual(to(null, null, null, 3), { address: null, addressType: null }, "a descriptor");
  assert.deepEqual(to(null, -1, -1, undefined, true), { address: null, addressType: -1 }, "a handle given");
  assert.deepEqual(to(null, 3000, 4, undefined, true), { address: null, addressType: 4 }, "a cluster primary's handle");
});

/**
 * Every form of `listen` that opens a TCP port, each one's bound address in order. The forms
 * reviews found past an argument parser are among them: a port with a path, a null `_handle`,
 * options inside `handle`.
 */
const FORMS = `
  const http = require("node:http"), net = require("node:net");
  const sock = ${JSON.stringify(path.join(os.tmpdir(), "pocketsk-unused.sock"))};
  const forms = [
    (s, cb) => s.listen(0, cb),
    (s, cb) => s.listen(cb),
    (s, cb) => s.listen({ port: 0 }, cb),
    (s, cb) => s.listen(0, "0.0.0.0", cb),
    (s, cb) => s.listen(0, "0.0.0.0", 511, cb),
    (s, cb) => s.listen({ port: 0, path: sock }, cb),
    (s, cb) => s.listen({ port: 0, _handle: null }, cb),
    (s, cb) => s.listen({ handle: { port: 0 } }, cb),
  ];
  (async () => {
    const out = [];
    for (const [i, form] of forms.entries()) {
      const s = i === 0 ? http.createServer() : net.createServer();
      await new Promise((ok, fail) => { s.once("error", fail); form(s, ok); });
      out.push(s.address().address);
      await new Promise((ok) => s.close(ok));
    }
    console.log(JSON.stringify(out));
  })().catch((e) => { console.error(e); process.exit(1); });`;

test("every TCP listener started with the variable is on 127.0.0.1, and without it everywhere", async () => {
  const kept = JSON.parse(await run(FORMS, { loopback: true }));
  assert.deepEqual(kept, Array(kept.length).fill("127.0.0.1"));
  const open = JSON.parse(await run(FORMS, { loopback: false }));
  assert.equal(open.length, kept.length);
  for (const address of open) assert.ok(address === "::" || address === "0.0.0.0", address);
});

test("the IPv6 wildcards are kept to 127.0.0.1 too", async () => {
  // Only with the variable: without it they need IPv6, which not every machine the tests run on has.
  const script = `
    const net = require("node:net");
    const a = net.createServer(), b = net.createServer();
    a.listen(0, "::", () => b.listen({ port: 0, host: "::", ipv6Only: true }, () => {
      console.log(JSON.stringify([a.address().address, b.address().address])); a.close(); b.close();
    }));`;
  assert.deepEqual(JSON.parse(await run(script, { loopback: true })), ["127.0.0.1", "127.0.0.1"]);
});

test("a loopback name or address asked for stays as it was", async () => {
  const script = `
    const net = require("node:net");
    const a = net.createServer(), b = net.createServer();
    a.listen(0, "localhost", () => b.listen(0, "127.0.0.2", () => {
      console.log(JSON.stringify([a.address().address, b.address().address])); a.close(); b.close();
    }));`;
  const [a, b] = JSON.parse(await run(script, { loopback: true }));
  assert.ok(a === "127.0.0.1" || a === "::1", a);
  assert.equal(b, "127.0.0.2");
});

test("a Unix socket still listens where it was asked to", async () => {
  const sock = path.join(tmp(), "s.sock");
  const script = `
    const s = require("node:net").createServer();
    s.listen(${JSON.stringify(sock)}, () => { console.log(s.address()); s.close(); });`;
  assert.equal(await run(script, { loopback: true }), sock);
});

test("signalk-server's mDNS advertisement is stopped, and nothing else that is called mdns", async () => {
  const root = tmp();
  const dist = path.join(root, "node_modules", "signalk-server", "dist");
  fs.mkdirSync(dist, { recursive: true });
  fs.writeFileSync(path.join(dist, "mdns.js"), 'module.exports = () => "advertising";');
  fs.writeFileSync(path.join(dist, "index.js"), 'module.exports = String(require("./mdns")({}));');
  const other = path.join(root, "other");
  fs.mkdirSync(other);
  fs.writeFileSync(path.join(other, "mdns.js"), 'module.exports = () => "a plugin\'s own";');
  fs.writeFileSync(path.join(other, "index.js"), 'module.exports = String(require("./mdns")({}));');
  const script = `console.log(JSON.stringify([require(${JSON.stringify(path.join(dist, "index.js"))}), require(${JSON.stringify(path.join(other, "index.js"))})]))`;
  assert.deepEqual(JSON.parse(await run(script, { loopback: true })), ["undefined", "a plugin's own"]);
  assert.deepEqual(JSON.parse(await run(script, { loopback: false })), ["advertising", "a plugin's own"]);
});

test("installing twice wraps once", () => {
  const net = require("node:net");
  const Module = require("node:module");
  const before = net.Server.prototype._listen2;
  const load = Module._load;
  try {
    assert.equal(m.install(), true);
    const wrapped = net.Server.prototype._listen2;
    assert.notEqual(wrapped, before);
    assert.equal(m.install(), false);
    assert.equal(net.Server.prototype._listen2, wrapped);
  } finally {
    net.Server.prototype._listen2 = before;
    Module._load = load;
    delete net.Server.prototype[Symbol.for("pocketsk.loopback")];
  }
});
