// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { parseLine, toDelta, subscribeMessage, MAX_LINE, MAX_DEVICES } = require("../lib/protocol");

test("a sensor line becomes a message", () => {
  const r = parseLine('{"t":"sensor","path":"environment.outside.pressure","value":101325,"src":"android.baro","ts":1760000000000}');
  assert.deepEqual(r, {
    ok: true,
    msg: { t: "sensor", path: "environment.outside.pressure", value: 101325, src: "android.baro", ts: 1760000000000 },
  });
});

test("an object value of numbers and nulls is a sensor value", () => {
  const r = parseLine('{"t":"sensor","path":"navigation.attitude","value":{"roll":0.02,"pitch":-0.01,"yaw":null},"src":"android.imu"}');
  assert.equal(r.ok, true);
  assert.equal(r.msg.ts, null);
});

test("a time no Date can hold is taken as missing, so the delta gets the time of arrival", () => {
  for (const ts of [1e20, -1e20, 8.64e15 + 1]) {
    const r = parseLine(JSON.stringify({ t: "sensor", path: "a.b", value: 1, src: "android.baro", ts }));
    assert.equal(r.msg.ts, null, String(ts));
    assert.equal(toDelta(r.msg, () => 0).updates[0].timestamp, "1970-01-01T00:00:00.000Z");
  }
  assert.equal(parseLine('{"t":"sensor","path":"a.b","value":1,"src":"android.baro","ts":8.64e15}').msg.ts, 8.64e15);
});

test("hello carries the protocol version and the app's", () => {
  assert.deepEqual(parseLine('{"t":"hello","v":1,"app":"1.123"}'), { ok: true, msg: { t: "hello", v: 1, app: "1.123" } });
  assert.deepEqual(parseLine('{"t":"hello","v":2}'), { ok: true, msg: { t: "hello", v: 2, app: "" } });
  assert.equal(parseLine('{"t":"hello"}').ok, false);
});

test("what is not a known message is dropped with a reason", () => {
  const cases = {
    "not json": "not JSON",
    "[1,2]": "not an object",
    "null": "not an object",
    '{"t":"reboot"}': 'unknown type "reboot"',
    '{"t":"sensor","path":"../etc","value":1,"src":"android.baro"}': "sensor with a bad path",
    '{"t":"sensor","path":"a.b","value":1,"src":"gps"}': "sensor with a bad source",
    [`{"t":"sensor","path":"a.b","value":"${"x".repeat(65)}","src":"android.baro"}`]: "sensor a.b with a bad value",
    '{"t":"sensor","path":"a.b","value":"two\\nlines","src":"android.baro"}': "sensor a.b with a bad value",
    '{"t":"sensor","path":"a.b","value":"\\u0085","src":"android.baro"}': "sensor a.b with a bad value",
    '{"t":"sensor","path":"a.b","value":"","src":"android.baro"}': "sensor a.b with a bad value",
    '{"t":"sensor","path":"a.b","value":{"x":true},"src":"android.baro"}': "sensor a.b with a bad value",
    '{"t":"sensor","path":"a.b","value":{},"src":"android.baro"}': "sensor a.b with a bad value",
    '{"t":"sensor","path":"a.b","value":[1],"src":"android.baro"}': "sensor a.b with a bad value",
    '{"t":"sensor","path":"a.b","value":{"x":"1"},"src":"android.baro"}': "sensor a.b with a bad value",
  };
  for (const [line, reason] of Object.entries(cases)) assert.deepEqual(parseLine(line), { ok: false, reason }, line);
  assert.deepEqual(parseLine("x".repeat(MAX_LINE + 1)), { ok: false, reason: "line too long" });
});

test("a delta carries the sensor's source and time", () => {
  const delta = toDelta({ path: "electrical.batteries.phone.voltage", value: 4.1, src: "android.battery", ts: 0 });
  assert.deepEqual(delta, {
    updates: [
      {
        $source: "android.battery",
        timestamp: "1970-01-01T00:00:00.000Z",
        values: [{ path: "electrical.batteries.phone.voltage", value: 4.1 }],
      },
    ],
  });
  assert.equal(toDelta({ path: "a.b", value: 1, src: "android.x", ts: null }, () => 1000).updates[0].timestamp, "1970-01-01T00:00:01.000Z");
});

test("subscribe follows the settings", () => {
  assert.deepEqual(subscribeMessage(), {
    t: "subscribe",
    sensors: ["battery", "baro", "gnss", "attitude", "environment", "cellular"],
    rateHz: { baro: 0.1, gnss: 1, attitude: 1, environment: 0.1, cellular: 1 / 30 },
    bowOffsetDeg: 0,
    serial: { usb: true, baud: 4800, devices: {}, bt: true },
  });
  assert.deepEqual(subscribeMessage({ battery: false, gnss: false, attitude: false, environment: false, cellular: false, barometerSeconds: 2 }), {
    t: "subscribe",
    sensors: ["baro"],
    rateHz: { baro: 0.5, gnss: 1, attitude: 1, environment: 0.1, cellular: 1 / 30 },
    bowOffsetDeg: 0,
    serial: { usb: true, baud: 4800, devices: {}, bt: true },
  });
  assert.deepEqual(subscribeMessage({ barometer: false, gnss: false, attitude: false, environment: false, cellular: false, barometerSeconds: -1 }).sensors, ["battery"]);
  assert.equal(subscribeMessage({ barometerSeconds: "x" }).rateHz.baro, 0.1);
  assert.equal(subscribeMessage({ gnssSeconds: 0.5 }).rateHz.gnss, 2);
  assert.equal(subscribeMessage({ gnssSeconds: 0 }).rateHz.gnss, 1);
});

test("the heading is asked for only when turned on, the attitude with its period and bow offset", () => {
  assert.ok(!subscribeMessage().sensors.includes("heading"));
  const m = subscribeMessage({ heading: true, attitudeSeconds: 0.5, environmentSeconds: 60, bowOffset: 90 });
  assert.ok(m.sensors.includes("heading"));
  assert.equal(m.rateHz.attitude, 2);
  assert.equal(m.rateHz.environment, 1 / 60);
  assert.equal(m.bowOffsetDeg, 90);
  assert.equal(subscribeMessage({ bowOffset: "aft" }).bowOffsetDeg, 0);
});

test("an attitude and a rate of turn are sensor values", () => {
  const a = parseLine('{"t":"sensor","path":"navigation.attitude","value":{"roll":0.05,"pitch":-0.02},"src":"android.imu","ts":1}');
  assert.equal(a.ok, true);
  assert.deepEqual(toDelta(a.msg).updates[0].values[0], { path: "navigation.attitude", value: { roll: 0.05, pitch: -0.02 } });
  const r = parseLine('{"t":"sensor","path":"navigation.rateOfTurn","value":-0.01,"src":"android.imu","ts":1}');
  assert.equal(r.ok, true);
  assert.equal(toDelta(r.msg).updates[0].$source, "android.imu");
});

test("a null value is a sensor value: the reading is gone", () => {
  const r = parseLine('{"t":"sensor","path":"navigation.courseOverGroundTrue","value":null,"src":"android.gnss","ts":1}');
  assert.equal(r.ok, true);
  assert.deepEqual(toDelta(r.msg).updates[0].values[0], { path: "navigation.courseOverGroundTrue", value: null });
});

test("the cellular network: a name and a yes or no are sensor values, read every 30 s unless set", () => {
  const op = parseLine('{"t":"sensor","path":"communication.cellular.operator","value":"Elisa FI","src":"android.cellular","ts":1}');
  assert.equal(op.ok, true);
  assert.deepEqual(toDelta(op.msg).updates[0].values[0], { path: "communication.cellular.operator", value: "Elisa FI" });
  const roaming = parseLine('{"t":"sensor","path":"communication.cellular.roaming","value":false,"src":"android.cellular","ts":1}');
  assert.equal(roaming.ok, true);
  assert.equal(toDelta(roaming.msg).updates[0].values[0].value, false);
  assert.equal(parseLine(`{"t":"sensor","path":"a.b","value":"${"x".repeat(64)}","src":"android.cellular"}`).ok, true);
  assert.equal(subscribeMessage({ cellularSeconds: 60 }).rateHz.cellular, 1 / 60);
  assert.equal(subscribeMessage({ cellularSeconds: "x" }).rateHz.cellular, 1 / 30);
});

test("a missing value is not a sensor value", () => {
  assert.equal(parseLine('{"t":"sensor","path":"a.b","src":"android.baro"}').ok, false);
});

test("a GNSS position is a sensor value, altitude or not", () => {
  const r = parseLine('{"t":"sensor","path":"navigation.position","value":{"latitude":60.45,"longitude":22.27},"src":"android.gnss","ts":1}');
  assert.equal(r.ok, true);
  assert.deepEqual(toDelta(r.msg).updates[0].values[0], { path: "navigation.position", value: { latitude: 60.45, longitude: 22.27 } });
});

test("the device list is read, an entry that is not a device left out", () => {
  const line = JSON.stringify({
    t: "devices",
    list: [
      { id: "usb-1a86-7523", kind: "usb", name: "USB\u0007 Serial", baud: 4800, state: "open" },
      { id: "usb-0403-6001-a1", kind: "usb", name: "", baud: 38400, state: "error", detail: "gone\n" },
      { id: "../etc", kind: "usb", name: "x", baud: 4800, state: "open" },
      { id: "usb-1a86-7524", kind: "USB", name: "x", baud: 4800, state: "open" },
      { id: "usb-1a86-7525", kind: "usb", name: "x", baud: 4800, state: "lost" },
      { id: "bt-001bdc0a1b2c", kind: "bt", name: "BT GPS", baud: 0, state: "connecting" },
      { id: "usb-1a86-7526", kind: "usb", name: "x", baud: "fast", state: "open" },
      null,
    ],
  });
  assert.deepEqual(parseLine(line), {
    ok: true,
    msg: {
      t: "devices",
      list: [
        { id: "usb-1a86-7523", kind: "usb", name: "USB Serial", baud: 4800, state: "open" },
        { id: "usb-0403-6001-a1", kind: "usb", name: "usb-0403-6001-a1", baud: 38400, state: "error", detail: "gone" },
        { id: "bt-001bdc0a1b2c", kind: "bt", name: "BT GPS", baud: 0, state: "connecting" },
      ],
    },
  });
  assert.deepEqual(parseLine('{"t":"devices"}'), { ok: false, reason: "devices without a list" });
  const many = Array.from({ length: MAX_DEVICES + 1 }, () => ({}));
  assert.equal(parseLine(JSON.stringify({ t: "devices", list: many })).ok, false);
});

test("a serial line carries a device's bytes as base64", () => {
  const r = parseLine('{"t":"serial","id":"usb-1a86-7523","data":"JEdQUk1DLDENCg=="}');
  assert.equal(r.ok, true);
  assert.equal(r.msg.id, "usb-1a86-7523");
  assert.equal(r.msg.data.toString("latin1"), "$GPRMC,1\r\n");
  assert.deepEqual(parseLine('{"t":"serial","id":"usb 1","data":""}'), { ok: false, reason: "serial with a bad device id" });
  assert.deepEqual(parseLine('{"t":"serial","id":"usb-1","data":"not base64!"}'), { ok: false, reason: "serial from usb-1 that is not base64" });
  assert.equal(parseLine('{"t":"serial","id":"usb-1","data":"abc"}').ok, false);
  assert.equal(parseLine('{"t":"serial","id":"usb-1","data":7}').ok, false);
});

test("subscribe says which serial devices to read and at what rate", () => {
  const m = subscribeMessage({
    usb: false,
    serialBaud: 38400,
    serialDevices: [
      { id: " usb-0403-6001-a1 ", baud: 9600 },
      { id: "usb-1a86-7523", baud: 1234 },
      { id: "bad id", baud: 4800 },
      null,
      { baud: 4800 },
    ],
  });
  assert.deepEqual(m.serial, { usb: false, baud: 38400, devices: { "usb-0403-6001-a1": 9600 }, bt: true });
  assert.equal(subscribeMessage({ bluetooth: false }).serial.bt, false);
  assert.equal(subscribeMessage({ serialBaud: 1000 }).serial.baud, 4800);
  assert.deepEqual(subscribeMessage({ serialDevices: "all" }).serial.devices, {});
  const lots = Array.from({ length: MAX_DEVICES + 5 }, (_, i) => ({ id: `usb-0000-${i}`, baud: 4800 }));
  assert.equal(Object.keys(subscribeMessage({ serialDevices: lots }).serial.devices).length, MAX_DEVICES);
});

test("a silence from the app names the alarm by an id the plugin gave it", () => {
  const id = "0c1f3f5e-6f43-4d8e-9a8b-6a3b1c2d4e5f";
  assert.deepEqual(parseLine(JSON.stringify({ t: "silence", id })), { ok: true, msg: { t: "silence", id } });
  assert.deepEqual(parseLine('{"t":"silence","id":"notifications.navigation.anchor"}').msg.id, "notifications.navigation.anchor");
  const urn = "notifications.navigation.closestApproach.urn:mrn:imo:mmsi:230099999";
  assert.deepEqual(parseLine(JSON.stringify({ t: "silence", id: urn })).msg.id, urn);
  for (const bad of [undefined, 7, "", "a b", "x".repeat(257)]) {
    assert.deepEqual(parseLine(JSON.stringify({ t: "silence", id: bad })), { ok: false, reason: "silence with a bad id" });
  }
});
