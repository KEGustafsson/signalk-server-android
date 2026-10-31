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
    /** A message from the main server. */
    receive(msg) {
      this.onmessage({ data: typeof msg === "string" ? msg : JSON.stringify(msg) });
    }
    /** What the plugin sent the main server. */
    send(msg) {
      (this.sent ??= []).push(JSON.parse(msg));
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
  await until(() => s.status().write === false);
  assert.deepEqual(s.status(), { state: "connected", detail: null, url: "http://192.168.1.9:3000", deltas: 0, write: false, pushed: 0 });
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
  ws.receive({
    context: "vessels.urn:mrn:signalk:uuid:main",
    updates: [
      { $source: "nmea0183-sample-data.GP", values: [{ path: "navigation.speedOverGround", value: 3.1 }] },
      { source: { label: "can0", src: "43" }, values: [{ path: "environment.depth.belowTransducer", value: 4.2 }] },
      { values: [{ path: "", value: { name: "Northstar", uuid: "urn:mrn:signalk:uuid:main" } }] },
      "junk",
    ],
  });
  ws.receive({ context: "vessels.urn:mrn:imo:mmsi:230000001", updates: [{ $source: "ais.AI", values: [{ path: "navigation.position", value: { latitude: 60, longitude: 24 } }] }] });
  ws.receive({ name: "not a delta" });
  ws.receive("not JSON");
  ws.receive({ context: "vessels.urn:mrn:signalk:uuid:main", updates: ["junk"] });
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

test("the main server's own android.* sources move under its label, elsewhere they stay", () => {
  const { s, app, WebSocket } = setup();
  s.follow("http://192.168.1.9:3000", "a.b.c");
  const ws = WebSocket.made[0];
  ws.hello();
  const sog = [{ path: "navigation.speedOverGround", value: 3 }];
  ws.receive({
    context: "vessels.urn:mrn:signalk:uuid:main",
    updates: [
      { $source: "android.gnss", values: sog },
      { source: { label: "android.usb-1a86-7523", talker: "GP", sentence: "RMC" }, values: sog },
      { source: { label: "android.bt-00-11" }, values: sog },
      { $source: "androidish.x", values: sog },
    ],
  });
  ws.receive({ context: "vessels.urn:mrn:imo:mmsi:230000001", updates: [{ $source: "android.gnss", values: sog }] });
  const [[, self], [, other]] = app.deltas;
  const label = `${LABEL}.192.168.1.9:3000`;
  assert.deepEqual(self.updates.map((u) => u.$source), [
    `${label}.android.gnss`,
    `${label}.android.usb-1a86-7523.GP`,
    `${label}.android.bt-00-11`,
    "androidish.x",
  ]);
  assert.deepEqual(self.updates[1].source, { label: "android.usb-1a86-7523", talker: "GP", sentence: "RMC" }, "the source object stays");
  assert.equal(other.updates[0].$source, "android.gnss", "another vessel's sources are not this phone's");
  s.stop();
});

test("a delta the server throws on costs that delta, and the stream goes on", () => {
  const debugs = [];
  const { s, app, WebSocket } = setup({ debug: (line) => debugs.push(line) });
  let calls = 0;
  app.handleMessage = (id, delta) => {
    calls++;
    if (calls === 1) throw new Error("bad delta");
    app.deltas.push([id, delta]);
  };
  s.follow("http://192.168.1.9:3000", "a.b.c");
  const ws = WebSocket.made[0];
  ws.hello();
  const delta = { context: "vessels.urn:mrn:signalk:uuid:main", updates: [{ $source: "gps.GP", values: [{ path: "navigation.speedOverGround", value: 3 }] }] };
  assert.doesNotThrow(() => ws.receive(delta));
  ws.receive(delta);
  assert.equal(app.deltas.length, 1);
  assert.ok(debugs.some((line) => line.includes("refused a delta from 192.168.1.9:3000: bad delta")));
  assert.equal(s.status().state, "connected");
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
  assert.deepEqual(s.status(), { state: "off", detail: null, url: null, deltas: 0, write: null, pushed: 0 });
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

/** A main server that lets this phone write, the stream up and the write level heard. */
async function writing(more = {}) {
  const t = setup({ status: { status: "loggedIn", authenticationRequired: true, userLevel: "readwrite" }, ...more });
  t.s.follow("http://192.168.1.9:3000", "a.b.c", { label: "pocketsk.0b1c2d3e", push: { sensors: true, serial: true } });
  t.ws = t.WebSocket.made[0];
  t.ws.hello();
  await until(() => t.s.status().write === true);
  return t;
}

test("a reconnect sends nothing until the main server says again whether this phone may write", async (t) => {
  let level = "readwrite";
  const fetch = async () => ({ ok: true, json: async () => ({ status: "loggedIn", authenticationRequired: true, userLevel: level }) });
  const { s, ws, WebSocket } = await writing({ fetch });
  t.after(() => s.stop());
  const delta = { updates: [{ $source: "android.baro", values: [] }] };
  ws.receive({ context: "vessels.urn:mrn:signalk:uuid:main", updates: [{ $source: "x.y", values: [{ path: "notifications.mob", value: { state: "alarm", id: "c52ef7ac-7c16-481d-9dc1-db72f5f2b281" } }] }] });
  level = "readonly";
  ws.fail("gone");
  assert.equal(s.status().write, null, "a connection lost takes its answer with it");
  assert.equal(await s.silence("notifications.mob", "silence"), false);
  await until(() => WebSocket.made.length === 2);
  WebSocket.made[1].hello();
  assert.equal(s.status().write, null, "the last connection's answer is not this one's");
  assert.equal(s.send(delta, "sensors"), false);
  await until(() => s.status().write === false);
  assert.equal(s.send(delta, "sensors"), false, "the admin took the right away meanwhile");
});

test("the phone's own deltas go to the main server under its label, each group as chosen", async (t) => {
  const writes = [];
  const { s, ws } = await writing({ onState: (state, detail, write) => writes.push(write) });
  t.after(() => s.stop());
  assert.equal(writes.at(-1), true, "the app hears it may write");
  const baro = { updates: [{ $source: "android.baro", timestamp: "2026-10-30T12:00:00Z", values: [{ path: "environment.outside.pressure", value: 101000 }] }] };
  const nmea = {
    context: "vessels.urn:mrn:imo:mmsi:230000001",
    updates: [{ source: { label: "android.usb-1a86-7523", talker: "AI", sentence: "VDM" }, values: [{ path: "navigation.position", value: { latitude: 60, longitude: 24 } }] }],
  };
  assert.equal(s.send(baro, "sensors"), true);
  assert.equal(s.send(nmea, "serial"), true);
  assert.equal(s.send({ updates: [{ values: [] }] }, "sensors"), true);
  assert.deepEqual(ws.sent[0].updates[0].$source, "pocketsk.0b1c2d3e.android.baro");
  assert.equal("context" in ws.sent[0], false, "the phone's own sensors are the main server's own vessel");
  assert.deepEqual(ws.sent[1].updates[0].source, { label: "pocketsk.0b1c2d3e.android.usb-1a86-7523", talker: "AI", sentence: "VDM" });
  assert.equal(ws.sent[1].context, "vessels.urn:mrn:imo:mmsi:230000001");
  assert.equal(ws.sent[2].updates[0].$source, "pocketsk.0b1c2d3e");
  assert.equal(baro.updates[0].$source, "android.baro", "the delta this server took stays as it was");
  const own = { context: "vessels.urn:mrn:signalk:uuid:phone", updates: [{ $source: "android.gnss", values: [] }] };
  s.app.selfId = "urn:mrn:signalk:uuid:phone";
  s.send(own, "sensors");
  assert.equal("context" in ws.sent.at(-1), false, "this server's own vessel is the main server's own there");
  s.app.selfContext = "vessels.urn:mrn:signalk:uuid:other";
  s.send({ ...own, context: "vessels.self" }, "sensors");
  assert.equal("context" in ws.sent.at(-1), false);
  s.send(own, "sensors");
  assert.equal(ws.sent.at(-1).context, "vessels.urn:mrn:signalk:uuid:phone", "selfContext is what names this server's vessel");
  delete s.app.selfId;
  delete s.app.selfContext;
  ws.sent.length = 3;
  s.follow("http://192.168.1.9:3000", "a.b.c", { label: "pocketsk.0b1c2d3e", push: { sensors: false, serial: true } });
  assert.equal(s.send(baro, "sensors"), false, "a group turned off sends nothing, and the stream stays");
  assert.equal(s.send(nmea, "serial"), true);
  assert.equal(s.send(null, "serial"), false);
  assert.equal(s.send({ updates: ["junk"] }, "serial"), false);
  assert.equal(s.status().pushed, 7);
});

test("nothing is sent without the right to write, a label, or a stream", async () => {
  const readOnly = setup();
  readOnly.s.follow("http://192.168.1.9:3000", "a.b.c", { label: "pocketsk.0b1c2d3e", push: { sensors: true } });
  const delta = { updates: [{ $source: "android.baro", values: [] }] };
  assert.equal(readOnly.s.send(delta, "sensors"), false, "not connected yet");
  readOnly.WebSocket.made[0].hello();
  await until(() => readOnly.s.status().write === false);
  assert.equal(readOnly.s.send(delta, "sensors"), false, "a read-only token");
  readOnly.s.stop();
  const unlabelled = await writing();
  unlabelled.s.follow("http://192.168.1.9:3000", "a.b.c", { push: { sensors: true } });
  assert.equal(unlabelled.s.send(delta, "sensors"), false, "no label");
  unlabelled.s.stop();
  const broken = await writing();
  broken.ws.send = () => { throw new Error("not open"); };
  assert.equal(broken.s.send(delta, "sensors"), false, "a socket that cannot send");
  broken.s.stop();
});

test("what the phone sent comes back from the main server and is dropped, what others sent is kept", async (t) => {
  const { s, app, ws } = await writing();
  t.after(() => s.stop());
  const v = [{ path: "environment.outside.pressure", value: 101000 }];
  ws.receive({
    context: "vessels.urn:mrn:signalk:uuid:main",
    updates: [
      { $source: "pocketsk.0b1c2d3e.android.baro", values: v },
      { source: { label: "pocketsk.0b1c2d3e.android.usb-1a86-7523", talker: "GP" }, values: v },
      { $source: "pocketsk.99999999.android.baro", values: v },
    ],
  });
  ws.receive({ context: "vessels.urn:mrn:signalk:uuid:main", updates: [{ $source: "pocketsk.0b1c2d3e", values: v }] });
  assert.equal(app.deltas.length, 1);
  assert.deepEqual(app.deltas[0][1].updates.map((u) => u.$source), ["pocketsk.99999999.android.baro"]);
});

test("a server without security may be written to, an admin's token too, a read-only one not", async () => {
  for (const [status, write] of [
    [{ authenticationRequired: false }, true],
    [{ status: "loggedIn", authenticationRequired: true, userLevel: "admin" }, true],
    [{ status: "loggedIn", authenticationRequired: true, userLevel: "readonly" }, false],
    [{ status: "notLoggedIn", authenticationRequired: true, readOnlyAccess: true }, false],
  ]) {
    const { s, WebSocket } = setup({ status });
    s.follow("http://192.168.1.9:3000", null);
    WebSocket.made[0].hello();
    await until(() => s.status().write !== null);
    assert.equal(s.status().write, write, JSON.stringify(status));
    s.stop();
  }
});

test("the phone's Silence goes to the main server's notification API with the token", async (t) => {
  const posts = [];
  const status = { status: "loggedIn", authenticationRequired: true, userLevel: "readwrite" };
  const fetch = async (url, init) => {
    if (init?.method === "POST") {
      posts.push({ url, headers: init.headers });
      return { ok: !url.includes("0000"), status: 400 };
    }
    return { ok: true, json: async () => status };
  };
  const { s, ws } = await writing({ fetch });
  t.after(() => s.stop());
  const id = "c52ef7ac-7c16-481d-9dc1-db72f5f2b281";
  const notice = (path, value) => ws.receive({ context: "vessels.urn:mrn:signalk:uuid:main", updates: [{ $source: "x.y", values: [{ path, value }] }] });
  notice("notifications.navigation.anchor", { state: "alarm", id });
  notice("notifications.odd", { state: "alarm", id: "00000000-0000-4000-8000-000000000000" });
  ws.receive({ context: "vessels.urn:mrn:imo:mmsi:230000001", updates: [{ values: [{ path: "notifications.other", value: { state: "alarm", id } }] }] });
  assert.equal(await s.silence("notifications.navigation.anchor", "silence"), true);
  assert.deepEqual(posts[0], { url: `http://192.168.1.9:3000/signalk/v2/api/notifications/${id}/silence`, headers: { Authorization: "Bearer a.b.c" } });
  assert.equal(await s.silence("notifications.navigation.anchor", "acknowledge"), true);
  assert.equal(posts[1].url.endsWith("/acknowledge"), true);
  assert.equal(await s.silence("notifications.odd", "silence"), false, "an id the main server answers 400 for");
  assert.equal(await s.silence("notifications.mob", "silence"), false, "an alarm the main server never had");
  assert.equal(await s.silence("notifications.other", "silence"), false, "another vessel's");
  assert.equal(await s.silence("notifications.navigation.anchor", "clear"), false);
  assert.equal(posts.length, 3);
  notice("notifications.navigation.anchor", null);
  assert.equal(await s.silence("notifications.navigation.anchor", "silence"), false, "an alarm gone is forgotten");
});

test("a Silence needs the right to write, and a main server that cannot be reached costs nothing", async () => {
  const id = "c52ef7ac-7c16-481d-9dc1-db72f5f2b281";
  const readOnly = setup();
  readOnly.s.follow("http://192.168.1.9:3000", "a.b.c");
  readOnly.s.notices.set("notifications.mob", id);
  assert.equal(await readOnly.s.silence("notifications.mob", "silence"), false);
  readOnly.s.stop();
  let calls = 0;
  const flaky = async (url, init) => {
    if (init?.method === "POST") throw new Error("unreachable");
    calls++;
    return { ok: true, json: async () => ({ status: "loggedIn", authenticationRequired: true, userLevel: "readwrite" }) };
  };
  const { s, ws } = await writing({ fetch: flaky });
  ws.receive({ context: "vessels.urn:mrn:signalk:uuid:main", updates: [{ $source: "x.y", values: [{ path: "notifications.mob", value: { state: "emergency", id } }] }] });
  assert.equal(await s.silence("notifications.mob", "acknowledge"), false);
  assert.ok(calls > 0);
  s.stop();
  assert.equal(await s.silence("notifications.mob", "acknowledge"), false, "nothing followed");
});
