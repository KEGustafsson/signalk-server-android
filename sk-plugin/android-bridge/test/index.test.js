// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const plugin = require("..");
const { socketPath, listen, until } = require("./helpers");

function fakeApp() {
  return {
    status: [],
    deltas: [],
    debugs: [],
    setPluginStatus(s) { this.status.push(s); },
    setPluginError(s) { this.status.push(`error: ${s}`); },
    handleMessage(id, delta) { this.deltas.push([id, delta]); },
    debug(s) { this.debugs.push(s); },
  };
}

test("the plugin has a schema whose defaults start it", () => {
  const p = plugin(fakeApp(), { env: {} });
  const schema = p.schema();
  assert.equal(schema.type, "object");
  assert.equal(schema.properties.battery.default, true);
  assert.equal(p.id, "pocketsk-android-bridge");
});

test("the schema shares no object between its parts, as the Signal K plugin CI checks", () => {
  // Its check takes an object met twice for a circular reference, even when it is only shared.
  const seen = new WeakSet();
  (function walk(node, path) {
    if (node === null || typeof node !== "object") return;
    assert.ok(!seen.has(node), `${path} is met twice`);
    seen.add(node);
    for (const [k, v] of Object.entries(node)) walk(v, `${path}.${k}`);
  })(plugin(fakeApp(), { env: {} }).schema(), "schema");
});

test("without the app's socket the plugin says so and does nothing", () => {
  const app = fakeApp();
  const p = plugin(app, { env: {} });
  p.start({});
  assert.deepEqual(app.status, ["Not running on Pocket SK: there is no phone to bridge"]);
  p.stop();
  assert.equal(p.statusNow().connected, false);
});

test("on the phone it subscribes and turns sensor lines into deltas", async (t) => {
  const sock = socketPath();
  let subscribe = "";
  let conn = null;
  const server = await listen(sock, (c) => {
    conn = c;
    c.setEncoding("utf8");
    c.on("data", (d) => (subscribe += d));
    c.write('{"t":"hello","v":9}\n');
    c.write('{"t":"hello","v":1,"app":"1.200"}\n');
    c.write('{"t":"sensor","path":"environment.outside.pressure","value":101000,"src":"android.baro","ts":0}\n');
    c.write('{"t":"sensor","path":"bad path","value":1,"src":"android.baro"}\n');
  });
  const app = fakeApp();
  const p = plugin(app, { env: { POCKETSK_BRIDGE: sock } });
  t.after(async () => {
    p.stop();
    await server.closeAll();
  });
  p.start({ battery: false, gnss: false, attitude: false, environment: false, cellular: false, barometerSeconds: 5 });
  await until(() => app.deltas.length === 1 && p.statusNow().dropped === 1 && subscribe.endsWith("\n"));
  assert.deepEqual(JSON.parse(subscribe.split("\n")[0]), {
    t: "subscribe",
    sensors: ["baro"],
    rateHz: { baro: 0.2, gnss: 1, attitude: 1, environment: 0.1, cellular: 1 / 30 },
    bowOffsetDeg: 0,
    serial: { usb: true, baud: 4800, devices: {}, bt: true },
    ble: { scan: false, watch: [] },
  });
  assert.equal(app.deltas[0][0], "pocketsk-android-bridge");
  assert.equal(app.deltas[0][1].updates[0].values[0].value, 101000);
  assert.ok(app.status.includes("Connected to the phone"));
  assert.ok(app.debugs.some((d) => d.includes("bridge version 9")));
  assert.deepEqual(p.statusNow(), {
    connected: true, app: "1.200", sensors: 1, dropped: 1, devices: [], serial: {}, alarms: [],
    ble: { heard: 0, devices: 0, provider: false, standIn: false },
  });
  conn.destroy();
  await until(() => app.status.at(-1).startsWith("Waiting for the phone"));
});

test("a USB device's NMEA 0183 becomes deltas under its own source and reaches the nmea0183 event", async (t) => {
  const sock = socketPath();
  let conn = null;
  let subscribe = "";
  const server = await listen(sock, (c) => {
    conn = c;
    c.setEncoding("utf8");
    c.on("data", (d) => (subscribe += d));
  });
  class Parser {
    parse(s) {
      return s.startsWith("$GPRMC")
        ? { updates: [{ source: { talker: "GP", type: "NMEA0183" }, values: [{ path: "navigation.position", value: { latitude: 1, longitude: 2 } }] }] }
        : null;
    }
  }
  const app = fakeApp();
  const emitted = [];
  app.emit = (e, s) => emitted.push(["app", e, s]);
  app.signalk = { emit: (e, s) => emitted.push(["signalk", e, s]) };
  const p = plugin(app, { env: { POCKETSK_BRIDGE: sock }, Parser });
  t.after(async () => {
    p.stop();
    await server.closeAll();
  });
  p.start({ serialBaud: 38400, serialDevices: [{ id: "usb-0403-6001-a1", baud: 4800 }] });
  await until(() => conn !== null && subscribe.endsWith("\n"));
  assert.deepEqual(JSON.parse(subscribe.split("\n")[0]).serial, { usb: true, baud: 38400, devices: { "usb-0403-6001-a1": 4800 }, bt: true });

  const devices = (list) => conn.write(`${JSON.stringify({ t: "devices", list })}\n`);
  devices([
    { id: "usb-1a86-7523", kind: "usb", name: "GPS", baud: 38400, state: "open" },
    { id: "usb-0403-6001-a1", kind: "usb", name: "AIS", baud: 4800, state: "needs-permission" },
    { id: "usb-0403-6001-b2", kind: "usb", name: "Spare", baud: 38400, state: "idle" },
    { id: "usb-067b-2303", kind: "usb", name: "Old", baud: 38400, state: "error", detail: "it stopped" },
    { id: "usb-10c4-ea60", kind: "usb", name: "Odd", baud: 38400, state: "error" },
    { id: "bt-001bdc0a1b2c", kind: "bt", name: "BT GPS", baud: 0, state: "open" },
    { id: "bt-001bdc0a1b2d", kind: "bt", name: "BT AIS", baud: 0, state: "connecting" },
    { id: "bt-001bdc0a1b2e", kind: "bt", name: "BT Old", baud: 0, state: "connecting", detail: "the link dropped" },
    { id: "bt-001bdc0a1b2f", kind: "bt", name: "BT Off", baud: 0, state: "idle" },
    { id: "bt-001bdc0a1b30", kind: "bt", name: "BT Down", baud: 0, state: "error", detail: "Bluetooth is off" },
  ]);
  await until(() => p.statusNow().devices.length === 10);
  assert.equal(
    app.status.at(-1),
    "Connected to the phone; serial: GPS at 38400 baud, AIS waits for permission in the app, Spare (USB is off in these settings), Old: it stopped, Odd: failed, " +
      "BT GPS connected, BT AIS connecting, BT Old connecting (the link dropped), BT Off (Bluetooth is off in these settings), BT Down: Bluetooth is off",
  );
  // The per-device rates are USB's: the Bluetooth devices are not offered there.
  const described = p.schema().properties.serialDevices.description;
  assert.match(described, /Plugged in now: usb-1a86-7523 \(GPS\), usb-0403-6001-a1 \(AIS\)/);
  assert.doesNotMatch(described, /bt-/);

  conn.write(`${JSON.stringify({ t: "serial", id: "usb-1a86-7523", data: Buffer.from("$GPRMC,1*00\r\n$GPG").toString("base64") })}\n`);
  await until(() => app.deltas.length === 1);
  assert.equal(app.deltas[0][0], "pocketsk-android-bridge");
  assert.equal(app.deltas[0][1].updates[0].source.label, "android.usb-1a86-7523");
  assert.deepEqual(emitted, [
    ["app", "nmea0183", "$GPRMC,1*00"],
    ["signalk", "nmea0183", "$GPRMC,1*00"],
  ]);

  // Unplugged: what it had half sent goes, so a new plug does not start mid-sentence.
  devices([]);
  await until(() => p.statusNow().devices.length === 0);
  assert.equal(app.status.at(-1), "Connected to the phone");
  assert.match(p.schema().properties.serialDevices.description, /No USB device is plugged into the phone now/);
  conn.write(`${JSON.stringify({ t: "serial", id: "usb-1a86-7523", data: Buffer.from("SV\r\n").toString("base64") })}\n`);
  await until(() => p.statusNow().serial["usb-1a86-7523"].sentences === 2);
  assert.equal(emitted.at(-1)[2], "SV");
});

test("without the server's parser the status says USB sentences are not parsed", async (t) => {
  const sock = socketPath();
  let conn = null;
  const server = await listen(sock, (c) => (conn = c));
  const app = fakeApp();
  const p = plugin(app, { env: { POCKETSK_BRIDGE: sock }, Parser: null });
  t.after(async () => {
    p.stop();
    await server.closeAll();
  });
  p.start({});
  assert.ok(app.debugs.some((d) => d.includes("parser was not found")));
  await until(() => conn !== null && p.statusNow().connected);
  conn.write(`${JSON.stringify({ t: "devices", list: [{ id: "usb-1a86-7523", kind: "usb", name: "GPS", baud: 4800, state: "open" }] })}\n`);
  await until(() => p.statusNow().devices.length === 1);
  assert.match(app.status.at(-1), /parser was not found, so sentences are not parsed/);
  // No nmea0183 event to give them to on this app: nothing breaks.
  conn.write(`${JSON.stringify({ t: "serial", id: "usb-1a86-7523", data: Buffer.from("$GPRMC\n").toString("base64") })}\n`);
  await until(() => p.statusNow().serial["usb-1a86-7523"]?.sentences === 1);
  assert.equal(app.deltas.length, 0);
});

test("the boat's alarms reach the phone, and its Silence is done through the server", async (t) => {
  const sock = socketPath();
  let conn = null;
  let received = "";
  const server = await listen(sock, (c) => {
    conn = c;
    c.setEncoding("utf8");
    c.on("data", (d) => (received += d));
  });
  const id = "0c1f3f5e-6f43-4d8e-9a8b-6a3b1c2d4e5f";
  const app = fakeApp();
  const calls = [];
  app.notifications = { silence: (x) => calls.push(["silence", x]), acknowledge: (x) => calls.push(["acknowledge", x]) };
  let onDelta = null;
  let unsubscribed = 0;
  app.subscriptionmanager = {
    subscribe(command, unsubscribes, onError, callback) {
      assert.deepEqual(command, { context: "vessels.self", subscribe: [{ path: "notifications.*", policy: "instant" }] });
      unsubscribes.push(() => unsubscribed++);
      onError("a test error");
      onDelta = callback;
    },
  };
  const p = plugin(app, { env: { POCKETSK_BRIDGE: sock }, Parser: null });
  t.after(async () => {
    p.stop();
    await server.closeAll();
  });
  p.start({});
  assert.ok(app.debugs.includes("notifications: a test error"));
  const lines = () => received.split("\n").filter(Boolean).map((l) => JSON.parse(l));
  const alarmLines = () => lines().filter((m) => m.t === "alarms");
  // Raised before the phone connected: sent once it is.
  onDelta({
    updates: [
      {
        values: [
          { path: "notifications.navigation.anchor", value: { id, state: "alarm", method: ["visual", "sound"], message: "Anchor dragging", status: {} } },
          { path: "notifications.engine.temp", value: { state: "warn", method: ["visual"], message: "Warm" } },
        ],
      },
    ],
  });
  await until(() => alarmLines().length === 1);
  assert.deepEqual(alarmLines()[0].list, [
    { id, path: "notifications.navigation.anchor", state: "alarm", message: "Anchor dragging", sound: true, action: "silence" },
  ]);
  assert.equal(p.statusNow().alarms.length, 1);
  conn.write(`${JSON.stringify({ t: "silence", id })}\n`);
  await until(() => calls.length === 1);
  assert.deepEqual(calls, [["silence", id]]);
  assert.ok(app.debugs.includes(`the phone asked to silence ${id}`));
  conn.write(`${JSON.stringify({ t: "silence", id: "notifications.unknown" })}\n`);
  await until(() => app.debugs.includes("the phone silenced notifications.unknown on the phone alone"));
  onDelta({ updates: [{ values: [{ path: "notifications.navigation.anchor", value: null }] }] });
  onDelta({});
  await until(() => alarmLines().length === 2);
  assert.deepEqual(alarmLines()[1].list, []);
  // A stop (a server restart) closes the link without telling the phone the alarms ended.
  onDelta({ updates: [{ values: [{ path: "notifications.navigation.anchor", value: { id, state: "alarm", method: ["sound"], message: "Again" } }] }] });
  await until(() => alarmLines().length === 3);
  p.stop();
  assert.equal(unsubscribed, 1);
  await new Promise((r) => setTimeout(r, 50));
  assert.equal(alarmLines().length, 3);
  assert.equal(alarmLines()[2].list.length, 1);
});

test("with alarms off, or no subscription manager, the phone hears of none", async (t) => {
  const sock = socketPath();
  const server = await listen(sock);
  const app = fakeApp();
  let subscribed = false;
  app.subscriptionmanager = { subscribe() { subscribed = true; } };
  const p = plugin(app, { env: { POCKETSK_BRIDGE: sock }, Parser: null });
  t.after(async () => {
    p.stop();
    await server.closeAll();
  });
  p.start({ alarms: false });
  assert.equal(subscribed, false);
  p.stop();
  delete app.subscriptionmanager;
  p.start({});
  assert.ok(app.debugs.some((d) => d.includes("no subscription manager")));
});

test("the server's delta rate goes to the phone, from the server's own last statistics", async (t) => {
  const sock = socketPath();
  let got = "";
  const server = await listen(sock, (c) => {
    c.setEncoding("utf8");
    c.on("data", (d) => (got += d));
    c.write('{"t":"hello","v":1,"app":"1.200"}\n');
  });
  const app = fakeApp();
  app.lastServerEvents = {};
  const p = plugin(app, { env: { POCKETSK_BRIDGE: sock }, statsMs: 20 });
  t.after(async () => {
    p.stop();
    await server.closeAll();
  });
  const stats = () => got.split("\n").slice(0, -1).filter(Boolean).map((l) => JSON.parse(l)).filter((m) => m.t === "stats");
  p.start({});
  await until(() => p.statusNow().connected);
  // Nothing until the server has counted once.
  await new Promise((r) => setTimeout(r, 60));
  assert.deepEqual(stats(), []);
  app.lastServerEvents.SERVERSTATISTICS = { type: "SERVERSTATISTICS", data: { deltaRate: 12.4 } };
  await until(() => stats().length > 0);
  assert.deepEqual(stats()[0], { t: "stats", deltaRate: 12.4 });
  p.stop();
  const sent = stats().length;
  await new Promise((r) => setTimeout(r, 60));
  assert.equal(stats().length, sent, "nothing more once stopped");
});

test("the widget's glance goes to the phone every few ms while asked, and stops when off or the link drops", async (t) => {
  const sock = socketPath();
  let got = "";
  let conn = null;
  const server = await listen(sock, (c) => {
    conn = c;
    c.setEncoding("utf8");
    c.on("data", (d) => (got += d));
  });
  const app = fakeApp();
  app.getSelfPath = (path) => (path === "navigation.speedOverGround" ? { value: 2.5, timestamp: "2026-10-26T12:00:00.000Z" } : undefined);
  const p = plugin(app, { env: { POCKETSK_BRIDGE: sock }, glanceMs: 20 });
  t.after(async () => {
    p.stop();
    await server.closeAll();
  });
  const glances = () => got.split("\n").slice(0, -1).filter(Boolean).map((l) => JSON.parse(l)).filter((m) => m.t === "glance");
  p.start({});
  await until(() => conn !== null && p.statusNow().connected);
  await new Promise((r) => setTimeout(r, 60));
  assert.deepEqual(glances(), [], "nothing until the app asks");
  conn.write('{"t":"glance","on":true}\n');
  await until(() => glances().length >= 3);
  assert.deepEqual(glances()[0], { t: "glance", depth: null, sog: { v: 2.5, ts: Date.parse("2026-10-26T12:00:00.000Z") }, aws: null, awa: null });
  conn.write('{"t":"glance","on":false}\n');
  await new Promise((r) => setTimeout(r, 40));
  let sent = glances().length;
  await new Promise((r) => setTimeout(r, 60));
  assert.equal(glances().length, sent, "nothing more once off");
  conn.write('{"t":"glance","on":true}\n');
  await until(() => glances().length > sent);
  p.stop();
  sent = glances().length;
  await new Promise((r) => setTimeout(r, 60));
  assert.equal(glances().length, sent, "nothing more once stopped");
});

test("an adv line reaches the stand-in's hub, and its wishes send the app a new subscription", async (t) => {
  const sock = socketPath();
  let received = "";
  let conn = null;
  const server = await listen(sock, (c) => {
    conn = c;
    c.setEncoding("utf8");
    c.on("data", (d) => (received += d));
  });
  const listeners = new Set();
  const hub = {
    published: [],
    wish: { scan: false, watch: [] },
    publish(adv) { this.published.push(adv); },
    demand() { return this.wish; },
    onDemand(cb) {
      listeners.add(cb);
      return () => listeners.delete(cb);
    },
  };
  const app = fakeApp();
  const p = plugin(app, { env: { POCKETSK_BRIDGE: sock }, bleHub: hub });
  t.after(async () => {
    p.stop();
    await server.closeAll();
  });
  p.start({});
  const subscriptions = () => received.split("\n").slice(0, -1).map((l) => JSON.parse(l)).filter((m) => m.t === "subscribe");
  await until(() => subscriptions().length === 1);
  assert.deepEqual(subscriptions()[0].ble, { scan: false, watch: [] });
  // Four bytes of manufacturer data under Ruuvi's company id.
  conn.write(`${JSON.stringify({ t: "adv", mac: "CB:B8:33:4C:88:4F", rssi: -60, data: Buffer.from([4, 0xff, 0x99, 0x04, 0x05]).toString("base64"), ts: 1 })}\n`);
  conn.write('{"t":"adv","mac":"nope","rssi":-60,"data":""}\n');
  await until(() => hub.published.length === 1 && p.statusNow().dropped === 1);
  assert.deepEqual(hub.published[0].manufacturerData, { 1177: Buffer.from([5]) });
  assert.equal(p.statusNow().ble.heard, 1);
  hub.wish = { scan: true, watch: ["CB:B8:33:4C:88:4F"] };
  for (const cb of listeners) cb(hub.wish);
  await until(() => subscriptions().length === 2);
  assert.deepEqual(subscriptions()[1].ble, { scan: true, watch: ["CB:B8:33:4C:88:4F"] });
  p.stop();
  assert.equal(listeners.size, 0, "stop leaves no listener on the hub");
});

/** An Express-like router and response, enough for the plugin's file routes. */
function fakeRouter() {
  const routes = {};
  const add = (method) => (p, h) => (routes[`${method} ${p}`] = h);
  return { routes, get: add("GET"), put: add("PUT"), delete: add("DELETE") };
}

function fakeRes() {
  return {
    statusCode: 200,
    headers: {},
    body: null,
    set(k, v) { this.headers[k] = v; return this; },
    status(c) { this.statusCode = c; return this; },
    json(b) { this.body = b; return this; },
  };
}

function fakeReq(params, query = {}, bodyText = null) {
  const { Readable } = require("node:stream");
  const req = Readable.from(bodyText === null ? [] : [Buffer.from(bodyText)]);
  req.params = params;
  req.query = query;
  req.headers = bodyText === null ? {} : { "content-length": String(Buffer.byteLength(bodyText)) };
  req.complete = false;
  return req;
}

test("the file routes upload into the server's data folders", async () => {
  const fs = require("node:fs");
  const os = require("node:os");
  const path = require("node:path");
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "routes-"));
  const p = plugin(fakeApp(), { env: {}, configDir: dir });
  const router = fakeRouter();
  p.registerWithRouter(router);

  let res = fakeRes();
  await router.routes["GET /files"](fakeReq({}), res);
  assert.deepEqual(res.body, { folders: ["charts"] });

  res = fakeRes();
  await router.routes["PUT /files/:folder/:name"](fakeReq({ folder: "charts", name: "bay.mbtiles" }, { offset: "0", total: "5" }, "tiles"), res);
  assert.deepEqual(res.body, { received: 5, done: true });
  assert.equal(fs.readFileSync(path.join(dir, "charts", "bay.mbtiles"), "utf8"), "tiles");

  res = fakeRes();
  await router.routes["GET /files/:folder"](fakeReq({ folder: "charts" }), res);
  assert.equal(res.body.files[0].name, "bay.mbtiles");

  res = fakeRes();
  await router.routes["PUT /files/:folder/:name"](fakeReq({ folder: "node_modules", name: "x" }, { offset: "0", total: "1" }, "x"), res);
  assert.equal(res.statusCode, 400);
  assert.equal(res.headers.Connection, "close");

  res = fakeRes();
  await router.routes["DELETE /files/:folder/:name"](fakeReq({ folder: "charts", name: "bay.mbtiles" }, {}), res);
  assert.deepEqual(res.body, { ok: true });
  assert.ok(!fs.existsSync(path.join(dir, "charts", "bay.mbtiles")));
});

test("the file routes say so when the server's folder is not known, and log what else goes wrong", async () => {
  const app = fakeApp();
  const router = fakeRouter();
  plugin(app, { env: {} }).registerWithRouter(router);
  let res = fakeRes();
  await router.routes["GET /files"](fakeReq({}), res);
  assert.equal(res.statusCode, 503);

  const fs = require("node:fs");
  const os = require("node:os");
  const path = require("node:path");
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "routes-")), "not-a-folder");
  fs.writeFileSync(file, "");
  const broken = fakeRouter();
  // A file where the configuration folder should be: mkdir fails, which is no FilesError.
  plugin(app, { env: { SIGNALK_NODE_CONFIG_DIR: file } }).registerWithRouter(broken);
  res = fakeRes();
  await broken.routes["PUT /files/:folder/:name"](fakeReq({ folder: "charts", name: "x" }, { offset: "0", total: "1" }, "x"), res);
  assert.equal(res.statusCode, 500);
  assert.ok(app.debugs.some((d) => d.startsWith("files: ")));
});
