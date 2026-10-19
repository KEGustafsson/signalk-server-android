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
  p.start({ battery: false, gnss: false, attitude: false, environment: false, barometerSeconds: 5 });
  await until(() => app.deltas.length === 1 && p.statusNow().dropped === 1 && subscribe.endsWith("\n"));
  assert.deepEqual(JSON.parse(subscribe.split("\n")[0]), {
    t: "subscribe",
    sensors: ["baro"],
    rateHz: { baro: 0.2, gnss: 1, attitude: 1, environment: 0.1 },
    bowOffsetDeg: 0,
    serial: { usb: true, baud: 4800, devices: {}, bt: true },
  });
  assert.equal(app.deltas[0][0], "pocketsk-android-bridge");
  assert.equal(app.deltas[0][1].updates[0].values[0].value, 101000);
  assert.ok(app.status.includes("Connected to the phone"));
  assert.ok(app.debugs.some((d) => d.includes("bridge version 9")));
  assert.deepEqual(p.statusNow(), { connected: true, app: "1.200", sensors: 1, dropped: 1, devices: [], serial: {}, alarms: [] });
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
  app.subscriptionManager = {
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
  app.subscriptionManager = { subscribe() { subscribed = true; } };
  const p = plugin(app, { env: { POCKETSK_BRIDGE: sock }, Parser: null });
  t.after(async () => {
    p.stop();
    await server.closeAll();
  });
  p.start({ alarms: false });
  assert.equal(subscribed, false);
  p.stop();
  delete app.subscriptionManager;
  p.start({});
  assert.ok(app.debugs.some((d) => d.includes("no subscription manager")));
});
