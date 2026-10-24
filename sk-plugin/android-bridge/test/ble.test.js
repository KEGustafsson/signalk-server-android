// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

const { Ble, parseAd, record, forServer, shortUuid, longUuid, NO_GATT } = require("../lib/ble");
const { parseLine, subscribeMessage, MAX_WATCH } = require("../lib/protocol");

/** One AD structure: its length, type and body. */
const ad = (type, ...body) => Buffer.from([body.length + 1, type, ...body]);
const RUUVI = "0512FC5394C37C0004FFFC040CAC364200CDCBB8334C884F";
const MAC = "CB:B8:33:4C:88:4F";

test("advertising data gives its manufacturer data, service data, UUIDs, name and TX power", () => {
  const data = Buffer.concat([
    ad(0x01, 0x06),
    ad(0xff, 0x99, 0x04, ...Buffer.from(RUUVI, "hex")),
    ad(0x16, 0xd2, 0xfc, 0x40, 0x01, 0x64),
    ad(0x20, 0x78, 0x56, 0x34, 0x12, 0xaa),
    ad(0x21, ...Buffer.from("9ecadc240ee5a9e093f3a3b50100406e", "hex"), 0xbb),
    ad(0x03, 0x95, 0xfe, 0xd2, 0xfc),
    ad(0x05, 0x78, 0x56, 0x34, 0x12),
    ad(0x07, ...Buffer.from("9ecadc240ee5a9e093f3a3b50100406e", "hex")),
    ad(0x08, ...Buffer.from("Ruu")),
    ad(0x09, ...Buffer.from("Ruuvi 884F\u0001")),
    ad(0x08, ...Buffer.from("late short")),
    ad(0x0a, 0xfc),
    ad(0x03, 0x95, 0xfe),
    ad(0x2a),
    Buffer.from([0x00, 0x00]),
  ]);
  const out = parseAd(data);
  assert.deepEqual(out.manufacturerData[1177], Buffer.from(RUUVI, "hex"));
  assert.deepEqual(out.serviceData["0000fcd2-0000-1000-8000-00805f9b34fb"], Buffer.from([0x40, 0x01, 0x64]));
  assert.deepEqual(out.serviceData["12345678-0000-1000-8000-00805f9b34fb"], Buffer.from([0xaa]));
  assert.deepEqual(out.serviceData["6e400001-b5a3-f393-e0a9-e50e24dcca9e"], Buffer.from([0xbb]));
  assert.deepEqual(out.serviceUuids, [
    "0000fe95-0000-1000-8000-00805f9b34fb",
    "0000fcd2-0000-1000-8000-00805f9b34fb",
    "12345678-0000-1000-8000-00805f9b34fb",
    "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
  ], "each once, in the order heard");
  assert.equal(out.name, "Ruuvi 884F", "the complete name, cleared of control characters, wins over a shortened one");
  assert.equal(out.txPower, -4);
});

test("data cut short or too short for its type keeps what came before it", () => {
  const ok = ad(0xff, 0x99, 0x04, 0x05);
  assert.deepEqual(parseAd(Buffer.concat([ok, Buffer.from([0x09, 0xff, 0x01])])).manufacturerData, { 1177: Buffer.from([0x05]) });
  const short = parseAd(Buffer.concat([ad(0xff, 0x01), ad(0x16, 0x01), ad(0x20, 1, 2, 3), ad(0x21, 1, 2), ad(0x0a), ad(0x09)]));
  assert.deepEqual(short, { manufacturerData: {}, serviceData: {}, serviceUuids: [] });
  assert.equal(parseAd(Buffer.concat([ad(0x08, ...Buffer.from("Shorter")), ad(0x08, ...Buffer.from("Second"))])).name, "Shorter");
  assert.deepEqual(parseAd(Buffer.alloc(0)), { manufacturerData: {}, serviceData: {}, serviceUuids: [] });
});

test("UUIDs are the lower-case 128-bit strings BlueZ gives", () => {
  assert.equal(shortUuid(0x181a), "0000181a-0000-1000-8000-00805f9b34fb");
  assert.equal(longUuid(Buffer.from("9ecadc240ee5a9e093f3a3b50100406e", "hex")), "6e400001-b5a3-f393-e0a9-e50e24dcca9e");
});

test("an adv line is read and checked", () => {
  const good = { t: "adv", mac: MAC, rssi: -67, data: Buffer.concat([ad(0xff, 0x99, 0x04, 0x05)]).toString("base64"), conn: true, at: "random", ts: 5 };
  const p = parseLine(JSON.stringify(good));
  assert.equal(p.ok, true);
  assert.deepEqual(p.msg, { t: "adv", mac: MAC, rssi: -67, data: Buffer.from([4, 0xff, 0x99, 0x04, 0x05]), conn: true, at: "random", ts: 5 });
  const bare = parseLine(JSON.stringify({ ...good, conn: "yes", at: "other", ts: 1e20 }));
  assert.deepEqual(bare.msg, { t: "adv", mac: MAC, rssi: -67, data: Buffer.from([4, 0xff, 0x99, 0x04, 0x05]), ts: null });
  for (const bad of [{ mac: "cb:b8:33:4c:88:4f" }, { mac: 1 }, { rssi: -128 }, { rssi: 21 }, { rssi: 1.5 }, { data: "not base64!" }, { data: 7 }, { data: "AAA" }, { data: "A".repeat(4500) }]) {
    assert.equal(parseLine(JSON.stringify({ ...good, ...bad })).ok, false, JSON.stringify(bad));
  }
});

test("a record is the parsed advertisement with what the app told of it", () => {
  const msg = { mac: MAC, rssi: -60, data: ad(0xff, 0x99, 0x04, 0x05), conn: false, at: "public", ts: 9 };
  assert.deepEqual(record(msg), { mac: MAC, rssi: -60, manufacturerData: { 1177: Buffer.from([5]) }, serviceData: {}, serviceUuids: [], timestamp: 9, connectable: false, addressType: "public" });
  const bare = record({ mac: MAC, rssi: -60, data: Buffer.alloc(0), ts: null }, () => 42);
  assert.equal(bare.timestamp, 42);
  assert.ok(!("connectable" in bare) && !("addressType" in bare));
});

test("the server's API gets the record with its data as hex, and only the fields it has", () => {
  const full = record({ mac: MAC, rssi: -60, data: Buffer.concat([ad(0x09, ...Buffer.from("Tag")), ad(0x0a, 4), ad(0xff, 0x99, 0x04, 0x05), ad(0x16, 0xd2, 0xfc, 0x40), ad(0x03, 0xd2, 0xfc)]), conn: true, at: "random", ts: 1 });
  assert.deepEqual(forServer(full, "p"), {
    mac: MAC, rssi: -60, providerId: "p", timestamp: 1, name: "Tag",
    manufacturerData: { 1177: "05" }, serviceData: { "0000fcd2-0000-1000-8000-00805f9b34fb": "40" },
    serviceUuids: ["0000fcd2-0000-1000-8000-00805f9b34fb"], txPower: 4, connectable: true, addressType: "random",
  });
  assert.deepEqual(forServer(record({ mac: MAC, rssi: -60, data: Buffer.alloc(0), ts: 1 }), "p"), { mac: MAC, rssi: -60, providerId: "p", timestamp: 1 });
});

/** A hub like the stand-in's, recording what the plugin does with it. */
function fakeHub() {
  return {
    published: [],
    wish: { scan: false, watch: [] },
    listeners: new Set(),
    publish(adv) { this.published.push(adv); },
    demand() { return this.wish; },
    onDemand(cb) {
      this.listeners.add(cb);
      return () => this.listeners.delete(cb);
    },
    want(wish) {
      this.wish = wish;
      for (const cb of this.listeners) cb(wish);
    },
  };
}

test("advertisements go to the server's API and to the stand-in's hub", async () => {
  const providers = [];
  const app = { registerBLEProvider: (p) => providers.push(p) };
  const hub = fakeHub();
  const debugs = [];
  let clock = 0;
  const b = new Ble({ app, pluginId: "pocketsk-android-bridge", hub, debug: (s) => debugs.push(s), now: () => clock });
  assert.equal(providers.length, 1);
  const { name, methods } = providers[0];
  assert.equal(name, "Android phone");
  assert.equal(methods.pluginId, "pocketsk-android-bridge");
  for (const m of ["startDiscovery", "stopDiscovery", "getDevices", "onAdvertisement", "supportsGATT", "availableGATTSlots", "subscribeGATT"]) {
    assert.equal(typeof methods[m], "function", m);
  }
  await methods.startDiscovery();
  await methods.stopDiscovery();
  assert.equal(methods.supportsGATT(), false);
  assert.equal(methods.availableGATTSlots(), 0);
  assert.equal(methods.totalGATTSlots(), 0);
  await assert.rejects(methods.subscribeGATT({ mac: MAC, service: "x" }, () => {}), (e) => e.message === NO_GATT);
  const got = [];
  const off = methods.onAdvertisement((adv) => got.push(adv));
  b.advertisement({ mac: MAC, rssi: -60, data: ad(0xff, 0x99, 0x04, 0x05), ts: 1 });
  assert.deepEqual(got.map((a) => a.manufacturerData), [{ 1177: "05" }]);
  assert.deepEqual(hub.published[0].manufacturerData, { 1177: Buffer.from([5]) });
  assert.deepEqual(await methods.getDevices(), [MAC]);
  clock += 120_001;
  assert.deepEqual(await methods.getDevices(), [], "a device not heard for two minutes is gone, as the server's table forgets it");
  off();
  b.advertisement({ mac: MAC, rssi: -61, data: Buffer.alloc(0), ts: 2 });
  assert.equal(got.length, 1);
  assert.deepEqual(b.stats(), { heard: 2, devices: 1, provider: true, standIn: true });
  b.stop();
});

test("a consumer that throws is logged, and the other still hears the advertisement", () => {
  let cb;
  const app = { registerBLEProvider: (p) => { cb = p.methods.onAdvertisement; } };
  const hub = { publish() { throw new Error("a BlueZ plugin choked"); } };
  const debugs = [];
  const b = new Ble({ app, pluginId: "p", hub, debug: (s) => debugs.push(s) });
  const heard = [];
  cb(() => { throw new Error("the API choked"); });
  cb((adv) => heard.push(adv));
  b.advertisement({ mac: MAC, rssi: -60, data: Buffer.alloc(0), ts: 1 });
  assert.equal(heard.length, 1);
  assert.ok(debugs.some((d) => d.includes("API failed")));
  assert.ok(debugs.some((d) => d.includes("Bluetooth LE plugin failed")));
  assert.deepEqual(b.demand(), { scan: false, watch: [] }, "a hub without demand() wants nothing");
});

test("a server without the BLE API, or one that refuses the provider, still feeds the hub", () => {
  const hub = fakeHub();
  const plain = new Ble({ app: {}, pluginId: "p", hub });
  plain.advertisement({ mac: MAC, rssi: -60, data: Buffer.alloc(0), ts: 1 });
  assert.equal(hub.published.length, 1);
  assert.equal(plain.stats().provider, false);
  const debugs = [];
  const refused = new Ble({ app: { registerBLEProvider() { throw new Error("no"); } }, pluginId: "p", hub, debug: (s) => debugs.push(s) });
  assert.equal(refused.stats().provider, false);
  assert.ok(debugs[0].includes("refused"));
  const none = new Ble({ app: {}, pluginId: "p", hub: null });
  none.advertisement({ mac: MAC, rssi: -60, data: Buffer.alloc(0), ts: 1 });
  assert.deepEqual(none.demand(), { scan: false, watch: [] });
  assert.equal(none.stats().standIn, false);
});

test("the oldest of more than 1024 devices is forgotten", async () => {
  let getDevices;
  const b = new Ble({ app: { registerBLEProvider: (p) => { getDevices = p.methods.getDevices; } }, pluginId: "p", hub: null });
  for (let i = 0; i < 1030; i++) {
    const n = i.toString(16).padStart(4, "0").toUpperCase();
    b.advertisement({ mac: `02:00:00:00:${n.slice(0, 2)}:${n.slice(2)}`, rssi: -60, data: Buffer.alloc(0), ts: 1 });
  }
  assert.equal((await getDevices()).length, 1024);
});

test("the hub's wishes reach the subscription: a scan, and the addresses to keep hearing", () => {
  const hub = fakeHub();
  const told = [];
  const b = new Ble({ app: {}, pluginId: "p", hub, onDemand: (d) => told.push(d) });
  hub.want({ scan: true, watch: [MAC] });
  assert.deepEqual(told, [{ scan: true, watch: [MAC] }]);
  assert.deepEqual(subscribeMessage({}, b.demand()).ble, { scan: true, watch: [MAC] });
  b.stop();
  hub.want({ scan: false, watch: [] });
  assert.equal(told.length, 1, "no word after stop");
});

test("the subscription's Bluetooth LE part follows the setting and the hub, with good addresses only", () => {
  assert.deepEqual(subscribeMessage().ble, { scan: false, watch: [] });
  assert.deepEqual(subscribeMessage({ ble: true }).ble, { scan: true, watch: [] });
  assert.deepEqual(subscribeMessage({}, { scan: "yes", watch: "nope" }).ble, { scan: false, watch: [] });
  const many = Array.from({ length: MAX_WATCH + 3 }, (_, i) => `02:00:00:00:00:${i.toString(16).padStart(2, "0").toUpperCase()}`);
  const watch = subscribeMessage({}, { watch: ["bad", 7, MAC.toLowerCase(), ...many] }).ble.watch;
  assert.equal(watch.length, MAX_WATCH);
  assert.ok(watch.every((m) => /^[0-9A-F]{2}(:[0-9A-F]{2}){5}$/.test(m)));
});
