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
  assert.equal(m.loopbackHost("::1"), "::1");
  assert.equal(m.loopbackHost("0.0.0.0"), "127.0.0.1");
});

test("every form of listen that opens a TCP port gets the loopback", () => {
  const cb = () => {};
  assert.deepEqual(m.loopbackArgs([3000]), [3000, "127.0.0.1"]);
  assert.deepEqual(m.loopbackArgs(["3000"]), ["3000", "127.0.0.1"]);
  assert.deepEqual(m.loopbackArgs([3000, cb]), [3000, "127.0.0.1", cb]);
  assert.deepEqual(m.loopbackArgs([3000, 511, cb]), [3000, "127.0.0.1", 511, cb]);
  assert.deepEqual(m.loopbackArgs([3000, "0.0.0.0", cb]), [3000, "127.0.0.1", cb]);
  assert.deepEqual(m.loopbackArgs([3000, "::", 511, cb]), [3000, "127.0.0.1", 511, cb]);
  assert.deepEqual(m.loopbackArgs([8375, undefined]), [8375, "127.0.0.1"], "signalk-server's tcp.js without TCPSTREAMADDRESS");
  assert.deepEqual(m.loopbackArgs([3000, null, cb]), [3000, "127.0.0.1", cb]);
  assert.deepEqual(m.loopbackArgs([3000, "localhost", cb]), [3000, "localhost", cb], "a loopback asked for stays");
  assert.deepEqual(m.loopbackArgs([]), [0, "127.0.0.1"]);
  assert.deepEqual(m.loopbackArgs([cb]), [0, "127.0.0.1", cb]);
  assert.deepEqual(m.loopbackArgs([undefined, cb]), [0, "127.0.0.1", cb]);
  assert.deepEqual(m.loopbackArgs([{ port: 3000 }, cb]), [{ port: 3000, host: "127.0.0.1" }, cb]);
  assert.deepEqual(m.loopbackArgs([{ port: 3000, host: "::", ipv6Only: true }]), [{ port: 3000, host: "127.0.0.1" }]);
  assert.deepEqual(m.loopbackArgs([{ port: 3000, host: "127.0.0.1" }]), [{ port: 3000, host: "127.0.0.1" }]);
});

test("Unix sockets, handles and descriptors are left alone", () => {
  const cb = () => {};
  const args = (...a) => a;
  for (const a of [
    args("/data/x.sock", cb),
    args({ path: "/data/x.sock" }),
    args({ fd: 3 }),
    args({ handle: {} }),
    args({ _handle: null }, cb),
  ]) {
    assert.deepEqual(m.loopbackArgs(a), a);
  }
});

test("a server started with the variable listens on 127.0.0.1 only, and without it everywhere", async () => {
  const script = `
    const http = require("node:http"), net = require("node:net");
    const a = http.createServer(), b = net.createServer();
    a.listen(0, () => b.listen({ port: 0 }, () => {
      console.log(JSON.stringify([a.address().address, b.address().address]));
      a.close(); b.close();
    }));`;
  assert.deepEqual(JSON.parse(await run(script, { loopback: true })), ["127.0.0.1", "127.0.0.1"]);
  const open = JSON.parse(await run(script, { loopback: false }));
  for (const address of open) assert.ok(address === "::" || address === "0.0.0.0", address);
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
  const before = net.Server.prototype.listen;
  const load = Module._load;
  try {
    assert.equal(m.install(), true);
    const wrapped = net.Server.prototype.listen;
    assert.equal(m.install(), false);
    assert.equal(net.Server.prototype.listen, wrapped);
  } finally {
    net.Server.prototype.listen = before;
    Module._load = load;
    delete net.Server.prototype[Symbol.for("pocketsk.loopback")];
  }
});
