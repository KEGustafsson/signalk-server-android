// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { Satellite, LABEL } = require("../lib/satellite");
const { until } = require("./helpers");

/** A WebSocket the test drives: every one made is kept, with what it was opened with. */
function fakeWebSocket() {
  const made = [];
  class FakeSocket {
    constructor(url, options) {
      this.url = url;
      this.options = options;
      this.closed = false;
      made.push(this);
    }
    close() {
      this.closed = true;
    }
    hello(self = "vessels.urn:mrn:signalk:uuid:main") {
      this.onmessage({ data: JSON.stringify({ name: "signalk-server", version: "2.35.1", self }) });
    }
    send(msg) {
      this.onmessage({ data: typeof msg === "string" ? msg : JSON.stringify(msg) });
    }
    fail(message, code = 1006) {
      this.onerror?.({ message });
      this.onclose?.({ code });
    }
  }
  FakeSocket.made = made;
  return FakeSocket;
}

/** A login status answer, or a fetch that cannot reach the server. */
function fakeFetch(status) {
  const asked = [];
  const fetch = async (url, init) => {
    asked.push({ url, headers: init?.headers ?? {} });
    if (status === null) throw new Error("unreachable");
    return { ok: true, json: async () => status };
  };
  fetch.asked = asked;
  return fetch;
}

function setup({ status = { status: "loggedIn", authenticationRequired: true }, ...more } = {}) {
  const WebSocket = fakeWebSocket();
  const fetch = fakeFetch(status);
  const app = { deltas: [], handleMessage(id, delta) { this.deltas.push([id, delta]); } };
  const states = [];
  const s = new Satellite({
    app,
    pluginId: "pocketsk-android-bridge",
    WebSocket,
    fetch,
    onState: (state, detail) => states.push([state, detail]),
    firstDelayMs: 5,
    maxDelayMs: 20,
    silentMs: 1000,
    ...more,
  });
  return { s, app, states, WebSocket, fetch };
}

test("it opens the main server's stream with the token in the header and says when it is connected", async (t) => {
  const { s, states, WebSocket, fetch } = setup();
  t.after(() => s.stop());
  s.follow("http://192.168.1.9:3000", "a.b.c");
  const [ws] = WebSocket.made;
  assert.equal(ws.url, "ws://192.168.1.9:3000/signalk/v1/stream?subscribe=all");
  assert.deepEqual(ws.options, { headers: { Authorization: "Bearer a.b.c" } });
  ws.hello();
  assert.deepEqual(states, [["connecting", null], ["connected", null]]);
  await until(() => fetch.asked.length === 1);
  assert.deepEqual(fetch.asked[0], { url: "http://192.168.1.9:3000/skServer/loginStatus", headers: { Authorization: "Bearer a.b.c" } });
  assert.equal(ws.closed, false, "a token it takes keeps the stream");
  assert.deepEqual(s.status(), { state: "connected", detail: null, url: "http://192.168.1.9:3000", deltas: 0 });
});

test("a server without security is opened without a header", () => {
  const { s, WebSocket } = setup();
  s.follow("http://pi.local:3000", null);
  assert.equal(WebSocket.made[0].options, undefined);
  s.stop();
});

test("the main server's vessel becomes this one's, other vessels keep theirs, and every update says where it comes from", () => {
  const { s, app, WebSocket } = setup();
  s.follow("http://192.168.1.9:3000", "a.b.c");
  const ws = WebSocket.made[0];
  ws.hello();
  ws.send({
    context: "vessels.urn:mrn:signalk:uuid:main",
    updates: [
      { $source: "nmea0183-sample-data.GP", values: [{ path: "navigation.speedOverGround", value: 3.1 }] },
      { source: { label: "can0", src: "43" }, values: [{ path: "environment.depth.belowTransducer", value: 4.2 }] },
      { values: [{ path: "", value: { name: "Northstar", uuid: "urn:mrn:signalk:uuid:main" } }] },
      "junk",
    ],
  });
  ws.send({ context: "vessels.urn:mrn:imo:mmsi:230000001", updates: [{ $source: "ais.AI", values: [{ path: "navigation.position", value: { latitude: 60, longitude: 24 } }] }] });
  ws.send({ name: "not a delta" });
  ws.send("not JSON");
  ws.send({ context: "vessels.urn:mrn:signalk:uuid:main", updates: ["junk"] });
  assert.equal(app.deltas.length, 2);
  const [[id, self], [, ais]] = app.deltas;
  assert.equal(id, "pocketsk-android-bridge");
  assert.equal("context" in self, false, "the boat's own vessel is this server's self");
  assert.equal(self.updates.length, 3);
  assert.equal(self.updates[0].$source, "nmea0183-sample-data.GP");
  assert.equal(self.updates[1].$source, undefined, "a source with a label and a src names itself");
  assert.equal(self.updates[2].$source, `${LABEL}.192.168.1.9:3000`);
  assert.deepEqual(self.updates[2].values[0].value, { name: "Northstar" }, "this server keeps its own uuid");
  assert.equal(ais.context, "vessels.urn:mrn:imo:mmsi:230000001");
  assert.equal(s.status().deltas, 2);
  s.stop();
});

test("a token the main server does not take ends the stream as unauthorized, and it is tried again later", async (t) => {
  const { s, states, WebSocket } = setup({ status: { status: "notLoggedIn", authenticationRequired: true } });
  t.after(() => s.stop());
  s.follow("http://192.168.1.9:3000", "a.b.c");
  WebSocket.made[0].hello();
  await until(() => states.some(([state]) => state === "unauthorized"));
  assert.equal(WebSocket.made[0].closed, true);
  assert.match(states.find(([state]) => state === "unauthorized")[1], /token/);
  await until(() => WebSocket.made.length === 2);
});

test("a server that lets anyone read, or runs without security, is not unauthorized", async () => {
  for (const status of [{ status: "notLoggedIn", authenticationRequired: true, readOnlyAccess: true }, { authenticationRequired: false }]) {
    const { s, states, WebSocket, fetch } = setup({ status });
    s.follow("http://192.168.1.9:3000", null);
    WebSocket.made[0].hello();
    await until(() => fetch.asked.length === 1);
    await new Promise((r) => setImmediate(r));
    assert.equal(WebSocket.made[0].closed, false);
    assert.deepEqual(states.at(-1), ["connected", null]);
    s.stop();
  }
});

test("a lost connection is opened again after a wait that doubles, and a refused upgrade is told apart by the login status", async (t) => {
  const { s, states, WebSocket } = setup({ status: { status: "notLoggedIn", authenticationRequired: true } });
  t.after(() => s.stop());
  s.follow("http://192.168.1.9:3000", "a.b.c");
  WebSocket.made[0].fail("Received network error or non-101 status code.");
  assert.deepEqual(states.at(-1), ["unreachable", "Received network error or non-101 status code."]);
  await until(() => states.at(-1)?.[0] === "unauthorized" || WebSocket.made.length > 1);
  assert.ok(states.some(([state]) => state === "unauthorized"));
  await until(() => WebSocket.made.length === 3);
  WebSocket.made[2].onclose({ code: 1000 });
  await until(() => WebSocket.made.length === 4);
});

test("an error with no close after it, as Node's WebSocket gives a refused upgrade, ends the connection once", async (t) => {
  const { s, states, WebSocket } = setup({ status: null });
  t.after(() => s.stop());
  s.follow("http://192.168.1.9:3000", "a.b.c");
  WebSocket.made[0].onerror({ message: "Received network error or non-101 status code." });
  assert.deepEqual(states.at(-1), ["unreachable", "Received network error or non-101 status code."]);
  assert.equal(WebSocket.made[0].closed, true);
  WebSocket.made[0].onclose({ code: 1006 });
  assert.equal(states.filter(([state]) => state === "unreachable").length, 1, "the close after it changes nothing");
  await until(() => WebSocket.made.length === 2);
});

test("an unreachable server stays unreachable, not unauthorized", async (t) => {
  const { s, states, WebSocket } = setup({ status: null });
  t.after(() => s.stop());
  s.follow("http://192.168.1.9:3000", "a.b.c");
  WebSocket.made[0].fail("ECONNREFUSED");
  await until(() => WebSocket.made.length === 2);
  assert.ok(!states.some(([state]) => state === "unauthorized"));
  assert.equal(states.find(([state]) => state === "unreachable")[1], "ECONNREFUSED");
});

test("a stream that falls silent is opened again", async (t) => {
  const { s, states, WebSocket } = setup({ silentMs: 10 });
  t.after(() => s.stop());
  s.follow("http://192.168.1.9:3000", "a.b.c");
  WebSocket.made[0].hello();
  await until(() => WebSocket.made.length === 2);
  assert.equal(WebSocket.made[0].closed, true);
  assert.ok(states.some(([state, detail]) => state === "unreachable" && /silent/.test(detail)));
});

test("the same target again changes nothing, another replaces it, and none is off", () => {
  const { s, states, WebSocket } = setup();
  s.follow("http://192.168.1.9:3000", "a.b.c");
  s.follow("http://192.168.1.9:3000", "a.b.c");
  assert.equal(WebSocket.made.length, 1);
  s.follow("http://192.168.1.9:3000", "d.e.f");
  assert.equal(WebSocket.made.length, 2);
  assert.equal(WebSocket.made[0].closed, true);
  WebSocket.made[0].onmessage({ data: JSON.stringify({ self: "x" }) });
  WebSocket.made[0].onclose({ code: 1000 });
  assert.notEqual(states.at(-1)[0], "connected", "the old stream says nothing any more");
  s.follow(null, null);
  assert.equal(WebSocket.made[1].closed, true);
  assert.deepEqual(states.at(-1), ["off", null]);
  assert.deepEqual(s.status(), { state: "off", detail: null, url: null, deltas: 0 });
  s.stop();
});

test("a Node without WebSocket, or one whose constructor throws, says so", async (t) => {
  const none = setup({ WebSocket: null });
  none.s.follow("http://192.168.1.9:3000", null);
  assert.deepEqual(none.states.at(-1), ["unreachable", "this Node has no WebSocket"]);
  none.s.stop();
  const throwing = setup({ WebSocket: function () { throw new Error("bad url"); } });
  t.after(() => throwing.s.stop());
  throwing.s.follow("http://192.168.1.9:3000", null);
  assert.deepEqual(throwing.states.at(-1), ["unreachable", "cannot connect: bad url"]);
});
