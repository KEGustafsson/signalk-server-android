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
 * Plugin to app:
 *   {"t":"subscribe","sensors":["battery","baro","gnss"],"rateHz":{"baro":0.1,"gnss":1}}
 *
 * The app is this phone's own process (the socket lives in its private directory), yet every
 * line is still checked before it becomes a delta: a path or a source the server would store
 * under a name nobody asked for is dropped, not passed on.
 */

const VERSION = 1;
/** Longest line either side sends or accepts; a longer one is a broken peer, not a message. */
const MAX_LINE = 64 * 1024;
/** The sensors a subscribe may name; the app ignores any other. */
const SENSORS = ["battery", "baro", "gnss"];

/** The largest time, in ms either side of 1970, a JavaScript Date can hold. */
const MAX_TIME_MS = 8.64e15;

const PATH = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)*$/;
const SOURCE = /^android\.[a-z0-9_]+$/;

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

/** A number, or an object of numbers and nulls (a position, an attitude): what a sensor reports. */
function validValue(value) {
  if (typeof value === "number") return Number.isFinite(value);
  if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
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

/** What to ask the app for, from the plugin's settings; the app clamps the rates to its own bounds. */
function subscribeMessage(options = {}) {
  const sensors = [];
  if (options.battery !== false) sensors.push("battery");
  if (options.barometer !== false) sensors.push("baro");
  if (options.gnss !== false) sensors.push("gnss");
  return {
    t: "subscribe",
    sensors,
    rateHz: { baro: 1 / seconds(options.barometerSeconds, 10), gnss: 1 / seconds(options.gnssSeconds, 1) },
  };
}

module.exports = { VERSION, MAX_LINE, SENSORS, parseLine, toDelta, subscribeMessage, validValue };
