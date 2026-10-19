// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { parseLine, toDelta, subscribeMessage, MAX_LINE } = require("../lib/protocol");

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
    '{"t":"sensor","path":"a.b","value":"high","src":"android.baro"}': "sensor a.b with a bad value",
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
  assert.deepEqual(subscribeMessage(), { t: "subscribe", sensors: ["battery", "baro", "gnss"], rateHz: { baro: 0.1, gnss: 1 } });
  assert.deepEqual(subscribeMessage({ battery: false, gnss: false, barometerSeconds: 2 }), {
    t: "subscribe",
    sensors: ["baro"],
    rateHz: { baro: 0.5, gnss: 1 },
  });
  assert.deepEqual(subscribeMessage({ barometer: false, gnss: false, barometerSeconds: -1 }).sensors, ["battery"]);
  assert.equal(subscribeMessage({ barometerSeconds: "x" }).rateHz.baro, 0.1);
  assert.equal(subscribeMessage({ gnssSeconds: 0.5 }).rateHz.gnss, 2);
  assert.equal(subscribeMessage({ gnssSeconds: 0 }).rateHz.gnss, 1);
});

test("a null value is a sensor value: the reading is gone", () => {
  const r = parseLine('{"t":"sensor","path":"navigation.courseOverGroundTrue","value":null,"src":"android.gnss","ts":1}');
  assert.equal(r.ok, true);
  assert.deepEqual(toDelta(r.msg).updates[0].values[0], { path: "navigation.courseOverGroundTrue", value: null });
});

test("a missing value is not a sensor value", () => {
  assert.equal(parseLine('{"t":"sensor","path":"a.b","src":"android.baro"}').ok, false);
});

test("a GNSS position is a sensor value, altitude or not", () => {
  const r = parseLine('{"t":"sensor","path":"navigation.position","value":{"latitude":60.45,"longitude":22.27},"src":"android.gnss","ts":1}');
  assert.equal(r.ok, true);
  assert.deepEqual(toDelta(r.msg).updates[0].values[0], { path: "navigation.position", value: { latitude: 60.45, longitude: 22.27 } });
});
