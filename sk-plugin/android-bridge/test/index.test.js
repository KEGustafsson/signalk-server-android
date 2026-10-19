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
  assert.deepEqual(JSON.parse(subscribe), {
    t: "subscribe",
    sensors: ["baro"],
    rateHz: { baro: 0.2, gnss: 1, attitude: 1, environment: 0.1 },
    bowOffsetDeg: 0,
    serial: { usb: true, baud: 4800, devices: {} },
  });
  assert.equal(app.deltas[0][0], "pocketsk-android-bridge");
  assert.equal(app.deltas[0][1].updates[0].values[0].value, 101000);
  assert.ok(app.status.includes("Connected to the phone"));
  assert.ok(app.debugs.some((d) => d.includes("bridge version 9")));
  assert.deepEqual(p.statusNow(), { connected: true, app: "1.200", sensors: 1, dropped: 1, devices: [], serial: {} });
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
  assert.deepEqual(JSON.parse(subscribe).serial, { usb: true, baud: 38400, devices: { "usb-0403-6001-a1": 4800 } });

  const devices = (list) => conn.write(`${JSON.stringify({ t: "devices", list })}\n`);
  devices([
    { id: "usb-1a86-7523", kind: "usb", name: "GPS", baud: 38400, state: "open" },
    { id: "usb-0403-6001-a1", kind: "usb", name: "AIS", baud: 4800, state: "needs-permission" },
    { id: "usb-0403-6001-b2", kind: "usb", name: "Spare", baud: 38400, state: "idle" },
    { id: "usb-067b-2303", kind: "usb", name: "Old", baud: 38400, state: "error", detail: "it stopped" },
    { id: "usb-10c4-ea60", kind: "usb", name: "Odd", baud: 38400, state: "error" },
  ]);
  await until(() => p.statusNow().devices.length === 5);
  assert.equal(
    app.status.at(-1),
    "Connected to the phone; USB: GPS at 38400 baud, AIS waits for permission in the app, Spare (USB is off), Old: it stopped, Odd: failed",
  );
  assert.match(p.schema().properties.serialDevices.description, /Plugged in now: usb-1a86-7523 \(GPS\), usb-0403-6001-a1 \(AIS\)/);

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
