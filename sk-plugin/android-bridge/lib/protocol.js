// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * The bridge protocol (docs/PLAN.md §7): newline-delimited JSON over the Unix socket the app
 * serves, one object a line, its type in `t`. The app's side is fi.pocketsk.bridge.BridgeProtocol;
 * change both together.
 *
 * App to plugin:
 *   {"t":"hello","v":1,"app":"1.123"}
 *   {"t":"sensor","path":"environment.outside.pressure","value":101325,"src":"android.baro","ts":1760000000000}
 *   {"t":"sensor","path":"navigation.courseOverGroundTrue","value":null,"src":"android.gnss","ts":1760000000000}
 *     (null: the sensor has no reading now, so the server stops showing the last one as current)
 *   {"t":"sensor","path":"communication.cellular.operator","value":"Elisa FI","src":"android.cellular","ts":1760000000000}
 *   {"t":"sensor","path":"communication.cellular.roaming","value":false,"src":"android.cellular","ts":1760000000000}
 *     (a value is a number, an object of numbers, a short text or a yes or no)
 *   {"t":"devices","list":[{"id":"usb-1a86-7523","kind":"usb","name":"USB Serial","baud":4800,"state":"open"}]}
 *     (the serial devices plugged into the phone or chosen among its paired Bluetooth ones, kind
 *     usb or bt, whenever that changes; state is open, connecting, needs-permission, idle or
 *     error, with a detail for an error or a redial)
 *   {"t":"serial","id":"usb-1a86-7523","data":"JEdQUk1DLC4uLg0K"}
 *     (bytes a device sent, as they came, base64; lib/serial.js makes sentences of them)
 *   {"t":"silence","id":"0c1f3f5e-6f43-4d8e-9a8b-6a3b1c2d4e5f"}
 *     (the owner silenced that alarm on the phone; lib/alarms.js does it through the server)
 *   {"t":"adv","mac":"CB:B8:33:4C:88:4F","rssi":-67,"data":"AgEGG/+ZBAUS/FOUw3wABP/8BAysNkIAzcu4M0yITw==","conn":true,"at":"random","ts":1760000000000}
 *     (a Bluetooth LE advertisement as the phone heard it: the raw advertising data and scan
 *     response, base64; conn and at where Android tells; lib/ble.js parses it)
 * Plugin to app:
 *   {"t":"subscribe","sensors":["battery","baro","gnss","attitude","environment","cellular"],
 *    "rateHz":{"baro":0.1,"gnss":1,"attitude":1,"environment":0.1,"cellular":0.0333},"bowOffsetDeg":0,
 *    "serial":{"usb":true,"baud":4800,"devices":{"usb-0403-6001-a10k1b2c":38400},"bt":true},
 *    "ble":{"scan":true,"watch":["CB:B8:33:4C:88:4F"]}}
 *     (ble: whether to scan for Bluetooth LE devices, and the addresses to keep hearing with the
 *     screen off, where Android stops a scan that names none)
 *   {"t":"alarms","list":[{"id":"0c1f3f5e-6f43-4d8e-9a8b-6a3b1c2d4e5f","path":"notifications.navigation.anchor",
 *    "state":"alarm","message":"Anchor dragging","sound":true,"action":"silence"}]}
 *     (the boat's alarms whenever they change, the most urgent first; action is silence,
 *     acknowledge or null, what the server allows done to it)
 *   {"t":"stats","deltaRate":12.4}
 *     (the deltas a second the server takes in, from its own statistics, for the app's Dock)
 *
 * The app is this phone's own process (the socket lives in its private directory), yet every
 * line is still checked before it becomes a delta: a path or a source the server would store
 * under a name nobody asked for is dropped, not passed on.
 */

const VERSION = 1;
/** Longest line either side sends or accepts; a longer one is a broken peer, not a message. */
const MAX_LINE = 64 * 1024;
/** The sensors a subscribe may name; the app ignores any other. */
const SENSORS = ["battery", "baro", "gnss", "attitude", "heading", "environment", "cellular"];
/** Longest text a sensor value may be (an operator's name), as the app cuts it. */
const MAX_TEXT = 64;

/** The largest time, in ms either side of 1970, a JavaScript Date can hold. */
const MAX_TIME_MS = 8.64e15;

const PATH = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)*$/;
const SOURCE = /^android\.[a-z0-9_]+$/;

/** A serial device's id, as the app makes them (fi.pocketsk.bridge.SerialIds): its kind, then its USB ids. */
const DEVICE_ID = /^[a-z]+-[a-z0-9-]{1,64}$/;
/** NMEA 0183's own rate, and those a device may be read at (the app ignores any other). */
const DEFAULT_BAUD = 4800;
const BAUD_RATES = [1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800];
const DEVICE_STATES = ["open", "connecting", "needs-permission", "idle", "error"];
/** More devices than this in one list is a broken peer; more rates than the app takes, too. */
const MAX_DEVICES = 32;
const BASE64 = /^[A-Za-z0-9+/]*={0,2}$/;
/** An alarm's id, as lib/alarms.js gives them: the server's notification id, or the path. */
const ALARM_ID = /^[A-Za-z0-9._:-]{1,256}$/;
/** A Bluetooth address as Android gives it, upper case. */
const MAC = /^[0-9A-F]{2}(:[0-9A-F]{2}){5}$/;
/** Longest advertising data in an adv line: an extended advertisement and its scan response. */
const MAX_ADV_BYTES = 2 * 1650;
/** Most addresses a subscribe asks the app to keep hearing: Android's hardware filters are few. */
const MAX_WATCH = 32;

/**
 * Reads one line from the app: `{ok: true, msg}` for a message this plugin knows, `{ok: false,
 * reason}` for anything else, so the caller can count and log what it drops.
 */
function parseLine(line) {
  if (line.length > MAX_LINE) return { ok: false, reason: "line too long" };
  let msg;
  try {
    msg = JSON.parse(line);
  } catch {
    return { ok: false, reason: "not JSON" };
  }
  if (msg === null || typeof msg !== "object" || Array.isArray(msg)) return { ok: false, reason: "not an object" };
  switch (msg.t) {
    case "hello":
      if (!Number.isInteger(msg.v)) return { ok: false, reason: "hello without a version" };
      return { ok: true, msg: { t: "hello", v: msg.v, app: typeof msg.app === "string" ? msg.app : "" } };
    case "sensor":
      return parseSensor(msg);
    case "devices":
      return parseDevices(msg);
    case "serial":
      return parseSerial(msg);
    case "silence":
      if (typeof msg.id !== "string" || !ALARM_ID.test(msg.id)) return { ok: false, reason: "silence with a bad id" };
      return { ok: true, msg: { t: "silence", id: msg.id } };
    case "adv":
      return parseAdv(msg);
    default:
      return { ok: false, reason: `unknown type ${JSON.stringify(String(msg.t)).slice(0, 40)}` };
  }
}

function parseSensor(msg) {
  if (typeof msg.path !== "string" || !PATH.test(msg.path)) return { ok: false, reason: "sensor with a bad path" };
  if (typeof msg.src !== "string" || !SOURCE.test(msg.src)) return { ok: false, reason: "sensor with a bad source" };
  if (!validValue(msg.value)) return { ok: false, reason: `sensor ${msg.path} with a bad value` };
  // A time no Date can hold would throw in toDelta, inside the socket's data listener: taken as missing.
  const ts = Number.isFinite(msg.ts) && Math.abs(msg.ts) <= MAX_TIME_MS ? msg.ts : null;
  return { ok: true, msg: { t: "sensor", path: msg.path, value: msg.value, src: msg.src, ts } };
}

/**
 * The device list. An entry that is not a device is left out rather than losing the others; a
 * name is cut and cleared of control characters, as it ends up in the plugin's status line.
 */
function parseDevices(msg) {
  if (!Array.isArray(msg.list) || msg.list.length > MAX_DEVICES) return { ok: false, reason: "devices without a list" };
  const list = [];
  for (const d of msg.list) {
    if (d === null || typeof d !== "object") continue;
    if (typeof d.id !== "string" || !DEVICE_ID.test(d.id)) continue;
    if (typeof d.kind !== "string" || !/^[a-z]+$/.test(d.kind)) continue;
    if (!DEVICE_STATES.includes(d.state) || !Number.isInteger(d.baud)) continue;
    const device = { id: d.id, kind: d.kind, name: text(d.name, 64) || d.id, baud: d.baud, state: d.state };
    if (typeof d.detail === "string") device.detail = text(d.detail, 200);
    list.push(device);
  }
  return { ok: true, msg: { t: "devices", list } };
}

/** Bytes from a device, decoded: what Buffer.from would make of anything else is not trusted. */
function parseSerial(msg) {
  if (typeof msg.id !== "string" || !DEVICE_ID.test(msg.id)) return { ok: false, reason: "serial with a bad device id" };
  if (typeof msg.data !== "string" || msg.data.length % 4 !== 0 || !BASE64.test(msg.data)) {
    return { ok: false, reason: `serial from ${msg.id} that is not base64` };
  }
  return { ok: true, msg: { t: "serial", id: msg.id, data: Buffer.from(msg.data, "base64") } };
}

/**
 * A Bluetooth LE advertisement: its address, a signal strength a radio can give (-127 to 20 dBm),
 * and its advertising data as base64; whether it is connectable and its address type when the app
 * sends them.
 */
function parseAdv(msg) {
  if (typeof msg.mac !== "string" || !MAC.test(msg.mac)) return { ok: false, reason: "adv with a bad address" };
  if (!Number.isInteger(msg.rssi) || msg.rssi < -127 || msg.rssi > 20) return { ok: false, reason: `adv from ${msg.mac} with a bad signal` };
  if (typeof msg.data !== "string" || msg.data.length % 4 !== 0 || !BASE64.test(msg.data) || msg.data.length > Math.ceil(MAX_ADV_BYTES / 3) * 4) {
    return { ok: false, reason: `adv from ${msg.mac} whose data is not base64` };
  }
  const out = { t: "adv", mac: msg.mac, rssi: msg.rssi, data: Buffer.from(msg.data, "base64") };
  if (typeof msg.conn === "boolean") out.conn = msg.conn;
  if (msg.at === "public" || msg.at === "random") out.at = msg.at;
  out.ts = Number.isFinite(msg.ts) && Math.abs(msg.ts) <= MAX_TIME_MS ? msg.ts : null;
  return { ok: true, msg: out };
}

/** A string for the status line: no control characters, at most `max` long. */
function text(value, max) {
  if (typeof value !== "string") return "";
  return value.replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, max);
}

/**
 * A number, null (no reading now, as Signal K's own deltas say it), an object of numbers and
 * nulls (a position, an attitude), a short text without control characters (a name, as the app
 * cleans it) or a yes or no: what a sensor reports.
 */
function validValue(value) {
  if (value === null || typeof value === "boolean") return true;
  if (typeof value === "number") return Number.isFinite(value);
  if (typeof value === "string") return value.length > 0 && value.length <= MAX_TEXT && !/[\u0000-\u001f\u007f-\u009f]/.test(value);
  if (typeof value !== "object" || Array.isArray(value)) return false;
  const entries = Object.entries(value);
  return entries.length > 0 && entries.every(([k, v]) => PATH.test(k) && (v === null || Number.isFinite(v)));
}

/** The Signal K delta for a sensor message, under its own `$source`. */
function toDelta(msg, now = Date.now) {
  return {
    updates: [
      {
        $source: msg.src,
        timestamp: new Date(msg.ts ?? now()).toISOString(),
        values: [{ path: msg.path, value: msg.value }],
      },
    ],
  };
}

/** A period in seconds from the settings, or `fallback` when it is not a positive number. */
function seconds(value, fallback) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? n : fallback;
}

/** The bow offset in degrees from the settings, 0 when it is not a number; the app clamps it. */
function degrees(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

/** A baud rate from the settings, or `fallback` when it is not one a device may be read at. */
function baud(value, fallback = DEFAULT_BAUD) {
  const n = Number(value);
  return BAUD_RATES.includes(n) ? n : fallback;
}

/**
 * The serial part of a subscribe: USB on unless turned off, the default rate, the rate of each
 * device the settings name (an entry without a known id or rate is left out), and Bluetooth on
 * unless turned off (the app dials only the paired devices the owner chose in it).
 */
function serialMessage(options = {}) {
  const devices = {};
  const entries = Array.isArray(options.serialDevices) ? options.serialDevices : [];
  for (const d of entries) {
    if (Object.keys(devices).length >= MAX_DEVICES) break;
    if (d && typeof d.id === "string" && DEVICE_ID.test(d.id.trim()) && BAUD_RATES.includes(Number(d.baud))) {
      devices[d.id.trim()] = Number(d.baud);
    }
  }
  return { usb: options.usb !== false, baud: baud(options.serialBaud), devices, bt: options.bluetooth !== false };
}

/**
 * The Bluetooth LE part of a subscribe: a scan when the settings turn it on or a Bluetooth LE
 * plugin asked for one through the node-ble stand-in ([demand], from lib/ble.js), and the
 * addresses such a plugin was set up for (well-formed ones, at most [MAX_WATCH]).
 */
function bleMessage(options = {}, demand = {}) {
  const watch = (Array.isArray(demand.watch) ? demand.watch : [])
    .filter((m) => typeof m === "string" && MAC.test(m))
    .slice(0, MAX_WATCH);
  return { scan: options.ble === true || demand.scan === true, watch };
}

/**
 * What to ask the app for, from the plugin's settings and what Bluetooth LE plugins want
 * ([demand]); the app clamps the rates to its own bounds. Everything is on unless turned off,
 * but the heading, which is off unless turned on: a phone's compass beside a boat's steel and
 * wiring is often wrong, and it would compete with a real one. Bluetooth LE is off unless the
 * settings or a plugin ask for it: a scan costs battery.
 */
function subscribeMessage(options = {}, demand = {}) {
  const sensors = [];
  if (options.battery !== false) sensors.push("battery");
  if (options.barometer !== false) sensors.push("baro");
  if (options.gnss !== false) sensors.push("gnss");
  if (options.attitude !== false) sensors.push("attitude");
  if (options.heading === true) sensors.push("heading");
  if (options.environment !== false) sensors.push("environment");
  if (options.cellular !== false) sensors.push("cellular");
  return {
    t: "subscribe",
    sensors,
    rateHz: {
      baro: 1 / seconds(options.barometerSeconds, 10),
      gnss: 1 / seconds(options.gnssSeconds, 1),
      attitude: 1 / seconds(options.attitudeSeconds, 1),
      environment: 1 / seconds(options.environmentSeconds, 10),
      cellular: 1 / seconds(options.cellularSeconds, 30),
    },
    bowOffsetDeg: degrees(options.bowOffset),
    serial: serialMessage(options),
    ble: bleMessage(options, demand),
  };
}

/**
 * The server's delta rate for the app, from the last SERVERSTATISTICS event the server keeps in
 * `app.lastServerEvents` (it counts every 5 s); null while it has none, or one that is not a rate.
 */
function statsMessage(lastServerEvents) {
  const rate = lastServerEvents?.SERVERSTATISTICS?.data?.deltaRate;
  return Number.isFinite(rate) && rate >= 0 ? { t: "stats", deltaRate: rate } : null;
}

module.exports = {
  VERSION,
  MAX_LINE,
  SENSORS,
  DEFAULT_BAUD,
  BAUD_RATES,
  DEVICE_ID,
  MAX_DEVICES,
  MAX_WATCH,
  MAX_ADV_BYTES,
  parseLine,
  toDelta,
  subscribeMessage,
  statsMessage,
  validValue,
};
