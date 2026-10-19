// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { Link } = require("../lib/link");
const { MAX_LINE } = require("../lib/protocol");
const { socketPath, freeSocketPath, listen, until } = require("./helpers");

test("lines arrive whole, however the bytes are split, and sends reach the app", async (t) => {
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
  t.after(async () => {
    link.stop();
    await server.closeAll();
  });
  assert.equal(link.send({ early: true }), false);
  link.start();
  await until(() => lines.length === 2);
  assert.deepEqual(lines, ['{"a":1}', '{"b":2}']);
  assert.deepEqual(states, [[true, "connected"]]);
  assert.equal(link.send({ t: "subscribe" }), true);
  await until(() => received.length > 0);
  assert.equal(received, '{"t":"subscribe"}\n');
});

test("the link connects again after the app goes away and comes back", async (t) => {
  const p = socketPath();
  const states = [];
  const servers = [];
  const link = new Link(p, { onLine: () => {}, onState: (c, d) => states.push([c, d]), firstDelayMs: 20, maxDelayMs: 40 });
  t.after(async () => {
    link.stop();
    await Promise.all(servers.map((s) => s.closeAll()));
  });
  link.start();
  await until(() => states.length >= 2);
  assert.match(states[0][1], /^cannot connect: /);
  const first = await listen(p, (s) => setTimeout(() => s.destroy(), 20));
  servers.push(first);
  await until(() => states.some(([c]) => c));
  await until(() => !link.connected);
  await first.closeAll();
  freeSocketPath(p);
  servers.push(await listen(p));
  await until(() => link.connected);
  link.stop();
  assert.equal(link.connected, false);
});

test("a line longer than the limit closes the connection", async (t) => {
  const p = socketPath();
  const server = await listen(p, (s) => s.write("x".repeat(MAX_LINE + 10)));
  const states = [];
  const lines = [];
  const link = new Link(p, { onLine: (l) => lines.push(l), onState: (c, d) => states.push([c, d]), firstDelayMs: 5000 });
  t.after(async () => {
    link.stop();
    await server.closeAll();
  });
  link.start();
  await until(() => states.length === 2);
  assert.deepEqual(states[1], [false, "line too long"]);
  assert.equal(lines.length, 0);
});
