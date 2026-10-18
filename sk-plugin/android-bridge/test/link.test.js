// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const { Link } = require("../lib/link");
const { MAX_LINE } = require("../lib/protocol");

function socketPath() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bridge-"));
  return path.join(dir, "bridge.sock");
}

function listen(p, onConnection) {
  return new Promise((resolve) => {
    const server = net.createServer(onConnection);
    server.listen(p, () => resolve(server));
  });
}

function until(check, ms = 3000) {
  return new Promise((resolve, reject) => {
    const end = Date.now() + ms;
    (function poll() {
      if (check()) return resolve();
      if (Date.now() > end) return reject(new Error("timed out"));
      setTimeout(poll, 10);
    })();
  });
}

test("lines arrive whole, however the bytes are split, and sends reach the app", async () => {
  const p = socketPath();
  let received = "";
  const server = await listen(p, (s) => {
    s.setEncoding("utf8");
    s.on("data", (d) => (received += d));
    s.write('{"a":');
    setTimeout(() => s.write('1}\n\n{"b":2}\n{"c"'), 20);
  });
  const lines = [];
  const states = [];
  const link = new Link(p, { onLine: (l) => lines.push(l), onState: (c, d) => states.push([c, d]) });
  assert.equal(link.send({ early: true }), false);
  link.start();
  await until(() => lines.length === 2);
  assert.deepEqual(lines, ['{"a":1}', '{"b":2}']);
  assert.deepEqual(states, [[true, "connected"]]);
  assert.equal(link.send({ t: "subscribe" }), true);
  await until(() => received.length > 0);
  assert.equal(received, '{"t":"subscribe"}\n');
  link.stop();
  server.close();
});

test("the link connects again after the app goes away and comes back", async () => {
  const p = socketPath();
  const states = [];
  const link = new Link(p, { onLine: () => {}, onState: (c, d) => states.push([c, d]), firstDelayMs: 20, maxDelayMs: 40 });
  link.start();
  await until(() => states.length >= 2);
  assert.match(states[0][1], /^cannot connect: /);
  let server = await listen(p, (s) => setTimeout(() => s.destroy(), 20));
  await until(() => states.some(([c]) => c));
  await until(() => states.filter(([c]) => c).length >= 1 && !link.connected);
  server.close();
  fs.rmSync(p, { force: true });
  server = await listen(p, () => {});
  await until(() => link.connected);
  link.stop();
  assert.equal(link.connected, false);
  server.close();
});

test("a line longer than the limit closes the connection", async () => {
  const p = socketPath();
  const server = await listen(p, (s) => s.write("x".repeat(MAX_LINE + 10)));
  const states = [];
  const lines = [];
  const link = new Link(p, { onLine: (l) => lines.push(l), onState: (c, d) => states.push([c, d]), firstDelayMs: 5000 });
  link.start();
  await until(() => states.length === 2);
  assert.deepEqual(states[1], [false, "line too long"]);
  assert.equal(lines.length, 0);
  link.stop();
  server.close();
});
