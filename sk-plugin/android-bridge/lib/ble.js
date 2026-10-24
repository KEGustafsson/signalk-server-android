// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * The phone's Bluetooth LE scanner as the server's (docs/PLAN.md §7.1). The app scans and sends
 * each advertisement it hears as it came (lib/protocol.js, `adv`: the address, the signal and the
 * raw advertising data); this file parses the data's AD structures (Bluetooth Core Supplement,
 * Part A) and gives each advertisement to the two that want them:
 *
 * - the server's own Bluetooth LE API (signalk-server 2.33 and later), with this plugin
 *   registered as a provider through `app.registerBLEProvider`, so the phone's devices show in
 *   the admin UI and reach every plugin that reads the API;
 * - the node-ble stand-in the app preloads into the server (runtime-tools/preload/node-ble.js),
 *   through the hub it keeps on a global symbol, so plugins written for BlueZ (bt-sensors-plugin-sk
 *   and its sixty sensor types) hear them as they would on a Raspberry Pi, unchanged.
 *
 * What to scan for goes the other way: the plugin's own setting, or the stand-in's wish (a BlueZ
 * plugin started discovery), with the addresses such a plugin was set up for, which the app keeps
 * listening for with the screen off. Connections (GATT) are not offered: the scanner cannot make
 * them.
 */

const HUB = Symbol.for("pocketsk.ble");
const MAC = /^[0-9A-F]{2}(:[0-9A-F]{2}){5}$/;
/** Longest advertising data the app sends: an extended advertisement and its scan response. */
const MAX_DATA = 2 * 1650;
/** The Bluetooth base UUID a 16- or 32-bit UUID stands in for. */
const BASE = "-0000-1000-8000-00805f9b34fb";

/** A 16- or 32-bit UUID as the 128-bit lower-case string BlueZ and the server use. */
function shortUuid(n) {
  return `${n.toString(16).padStart(8, "0")}${BASE}`;
}

/** A 128-bit UUID from its 16 little-endian bytes. */
function longUuid(bytes) {
  const h = Buffer.from(bytes).reverse().toString("hex");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/**
 * The fields of advertising data: its local name, TX power, manufacturer data by company id,
 * service data by UUID and service UUIDs, in the order they came. A structure that runs past the
 * end, or one too short for what its type holds, ends the parse; what came before it is kept.
 */
function parseAd(data) {
  const out = { manufacturerData: {}, serviceData: {}, serviceUuids: [] };
  const uuids = (body, size) => {
    for (let i = 0; i + size <= body.length; i += size) {
      const u = size === 16 ? longUuid(body.subarray(i, i + 16)) : shortUuid(size === 2 ? body.readUInt16LE(i) : body.readUInt32LE(i));
      if (!out.serviceUuids.includes(u)) out.serviceUuids.push(u);
    }
  };
  let at = 0;
  while (at < data.length) {
    const len = data[at];
    if (len === 0) break; // the rest is padding
    if (at + 1 + len > data.length) break;
    const type = data[at + 1];
    const body = data.subarray(at + 2, at + 1 + len);
    at += 1 + len;
    switch (type) {
      case 0x02:
      case 0x03:
        uuids(body, 2);
        break;
      case 0x04:
      case 0x05:
        uuids(body, 4);
        break;
      case 0x06:
      case 0x07:
        uuids(body, 16);
        break;
      case 0x08:
      case 0x09: {
        // The complete name wins over a shortened one, whichever came first.
        const name = body.toString("utf8").replace(/[\u0000-\u001f\u007f]/g, "").trim();
        if (name && (type === 0x09 || out.name === undefined)) out.name = name;
        break;
      }
      case 0x0a:
        if (body.length >= 1) out.txPower = body.readInt8(0);
        break;
      case 0x16:
        if (body.length >= 2) out.serviceData[shortUuid(body.readUInt16LE(0))] = Buffer.from(body.subarray(2));
        break;
      case 0x20:
        if (body.length >= 4) out.serviceData[shortUuid(body.readUInt32LE(0))] = Buffer.from(body.subarray(4));
        break;
      case 0x21:
        if (body.length >= 16) out.serviceData[longUuid(body.subarray(0, 16))] = Buffer.from(body.subarray(16));
        break;
      case 0xff:
        if (body.length >= 2) out.manufacturerData[body.readUInt16LE(0)] = Buffer.from(body.subarray(2));
        break;
      default:
        break;
    }
  }
  return out;
}

/**
 * One advertisement from the app (`{mac, rssi, data, conn, at, ts}`, as lib/protocol.js reads it)
 * as both consumers take it: the address, the signal, the parsed fields, whether it can be
 * connected to and its address type when the phone tells, and when it was heard.
 */
function record(msg, now = Date.now) {
  const ad = parseAd(msg.data);
  const adv = { mac: msg.mac, rssi: msg.rssi, ...ad, timestamp: msg.ts ?? now() };
  if (typeof msg.conn === "boolean") adv.connectable = msg.conn;
  if (msg.at === "public" || msg.at === "random") adv.addressType = msg.at;
  return adv;
}

/** The record as the server's BLE API takes an advertisement: the data as hex strings. */
function forServer(adv, providerId) {
  const hex = (dict) => Object.fromEntries(Object.entries(dict).map(([k, v]) => [k, v.toString("hex")]));
  const out = { mac: adv.mac, rssi: adv.rssi, providerId, timestamp: adv.timestamp };
  if (adv.name) out.name = adv.name;
  if (Object.keys(adv.manufacturerData).length > 0) out.manufacturerData = hex(adv.manufacturerData);
  if (Object.keys(adv.serviceData).length > 0) out.serviceData = hex(adv.serviceData);
  if (adv.serviceUuids.length > 0) out.serviceUuids = [...adv.serviceUuids];
  if (adv.txPower !== undefined) out.txPower = adv.txPower;
  if (adv.connectable !== undefined) out.connectable = adv.connectable;
  if (adv.addressType) out.addressType = adv.addressType;
  return out;
}

/** The scanner errors a provider gives when asked to connect. */
const NO_GATT = "the phone's Bluetooth LE scanner cannot connect to devices (GATT) yet";

/**
 * The plugin's Bluetooth LE side: the advertisements in, to the server's API and the hub, and
 * what to scan for out. `onDemand(demand)` is called whenever the hub's wishes change, so the
 * plugin can tell the app again; `hub` is the stand-in's (null when the app did not preload it).
 */
class Ble {
  constructor({ app, pluginId, hub = globalThis[HUB] ?? null, onDemand = () => {}, debug = () => {}, now = Date.now }) {
    this.app = app;
    this.pluginId = pluginId;
    this.hub = hub;
    this.debug = debug;
    this.now = now;
    this.callbacks = new Set();
    this.seen = new Map();
    this.heard = 0;
    this.registered = false;
    this.offDemand = hub && typeof hub.onDemand === "function" ? hub.onDemand(onDemand) : () => {};
    if (typeof app.registerBLEProvider === "function") {
      try {
        app.registerBLEProvider(this.provider());
        this.registered = true;
      } catch (e) {
        debug(`the server refused the Bluetooth LE provider: ${e?.message ?? e}`);
      }
    }
  }

  /** What the stand-in wants now: whether to scan, and the addresses to keep hearing. */
  demand() {
    return this.hub && typeof this.hub.demand === "function" ? this.hub.demand() : { scan: false, watch: [] };
  }

  /** The provider the server's BLE API takes: advertisements only, no connections. */
  provider() {
    return {
      name: "Android phone",
      methods: {
        pluginId: this.pluginId,
        startDiscovery: async () => {},
        stopDiscovery: async () => {},
        getDevices: async () => this.devices(),
        onAdvertisement: (cb) => {
          this.callbacks.add(cb);
          return () => this.callbacks.delete(cb);
        },
        supportsGATT: () => false,
        totalGATTSlots: () => 0,
        availableGATTSlots: () => 0,
        subscribeGATT: async () => {
          throw new Error(NO_GATT);
        },
      },
    };
  }

  /** The devices heard in the last two minutes, as the server's own device table keeps them. */
  devices() {
    const cutoff = this.now() - 120_000;
    for (const [mac, t] of this.seen) if (t < cutoff) this.seen.delete(mac);
    return [...this.seen.keys()];
  }

  /** One `adv` message from the app. */
  advertisement(msg) {
    const adv = record(msg, this.now);
    this.heard++;
    this.seen.delete(adv.mac);
    this.seen.set(adv.mac, this.now());
    if (this.seen.size > 1024) this.seen.delete(this.seen.keys().next().value);
    if (this.callbacks.size > 0) {
      const out = forServer(adv, this.pluginId);
      for (const cb of this.callbacks) {
        try {
          cb(out);
        } catch (e) {
          this.debug(`the server's BLE API failed on ${adv.mac}: ${e?.message ?? e}`);
        }
      }
    }
    if (this.hub && typeof this.hub.publish === "function") {
      try {
        this.hub.publish(adv);
      } catch (e) {
        this.debug(`a Bluetooth LE plugin failed on ${adv.mac}: ${e?.message ?? e}`);
      }
    }
  }

  stats() {
    return { heard: this.heard, devices: this.seen.size, provider: this.registered, standIn: !!this.hub };
  }

  stop() {
    this.offDemand();
    this.callbacks.clear();
    // The server unregisters a plugin's provider itself when the plugin stops.
  }
}

module.exports = { Ble, parseAd, record, forServer, shortUuid, longUuid, MAC, MAX_DATA, NO_GATT };
