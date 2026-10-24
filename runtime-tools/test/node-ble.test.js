// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

const ble = require("../preload/node-ble.js");

const RUUVI = Buffer.from("0512FC5394C37C0004FFFC040CAC364200CDCBB8334C884F", "hex");
const MAC = "CB:B8:33:4C:88:4F";

/** A hub of its own and a clock to move, so the tests neither share devices nor wait. */
function fresh() {
  let clock = 1_000_000;
  const events = [];
  const fakeHub = {
    listeners: new Set(),
    demands: [],
    subscribe(cb) {
      this.listeners.add(cb);
      return () => this.listeners.delete(cb);
    },
    setDemand(owner, demand) {
      this.demands.push({ owner, ...demand });
    },
    publish(adv) {
      for (const cb of this.listeners) cb(adv);
    },
  };
  const state = ble.makeState({ hub: fakeHub, now: () => clock });
  const bluetooth = new ble.Bluetooth(state);
  return {
    state,
    bluetooth,
    hub: fakeHub,
    events,
    advance: (ms) => (clock += ms),
    hear: (adv) => fakeHub.publish({ mac: MAC, rssi: -60, ...adv }),
  };
}

test("loading the file answers node-ble's names with the stand-in, and leaves others alone", () => {
  assert.equal(ble.install(), false, "already installed when the file was loaded");
  for (const name of ble.MODULES) {
    const mod = require(name);
    assert.equal(typeof mod.createBluetooth, "function", name);
    assert.equal(mod.Variant, ble.Variant);
  }
  assert.equal(typeof require("node:events").EventEmitter, "function");
  assert.throws(() => require("not-a-module-anyone-has"), /Cannot find module/);
});

test("createBluetooth is synchronous, opens nothing and gives node-ble's two fields", async () => {
  const { bluetooth, destroy } = require("@naugehyde/node-ble").createBluetooth();
  assert.equal(typeof destroy, "function");
  destroy();
  const adapter = await bluetooth.getAdapter("hci0");
  assert.equal(adapter.adapter, "hci0");
  assert.equal(await adapter.isPowered(), true);
  assert.deepEqual(await bluetooth.adapters(), ["hci0"]);
  assert.equal((await bluetooth.defaultAdapter()).adapter, "hci0");
  const [active] = await bluetooth.activeAdapters();
  assert.equal(active.adapter, "hci0");
  assert.equal(await active.getAddress(), "00:00:00:00:00:00");
  assert.equal(await active.getName(), "Android");
  assert.equal(await bluetooth.getAdapter("hci0"), adapter, "one adapter object per name");
});

test("the hub is one per process, and puts the wishes of every consumer together", () => {
  const h = ble.hub();
  assert.equal(ble.hub(), h);
  assert.equal(globalThis[Symbol.for("pocketsk.ble")], h);
  const seen = [];
  const off = h.onDemand((d) => seen.push(d));
  h.setDemand("a", { scan: false, watch: ["AA:AA:AA:AA:AA:AA"] });
  h.setDemand("b", { scan: true, watch: ["BB:BB:BB:BB:BB:BB", "AA:AA:AA:AA:AA:AA"] });
  assert.deepEqual(h.demand(), { scan: true, watch: ["AA:AA:AA:AA:AA:AA", "BB:BB:BB:BB:BB:BB"] });
  h.setDemand("b", null);
  assert.deepEqual(h.demand(), { scan: false, watch: ["AA:AA:AA:AA:AA:AA"] });
  h.setDemand("a", { scan: false, watch: [] });
  assert.deepEqual(h.demand(), { scan: false, watch: [] });
  off();
  assert.equal(seen.length, 4);
  const got = [];
  const unsub = h.subscribe((adv) => got.push(adv));
  h.publish({ mac: MAC });
  unsub();
  h.publish({ mac: MAC });
  assert.equal(got.length, 1);
});

test("the watch list is capped at MAX_WATCH", () => {
  const h = ble.hub();
  const many = Array.from({ length: ble.MAX_WATCH + 5 }, (_, i) => `00:00:00:00:00:${i.toString(16).padStart(2, "0").toUpperCase()}`);
  h.setDemand("cap", { scan: false, watch: many });
  assert.equal(h.demand().watch.length, ble.MAX_WATCH);
  h.setDemand("cap", null);
});

test("discovery goes to the hub, as BlueZ's StartDiscovery and node-ble's own calls do", async () => {
  const t = fresh();
  const adapter = await t.bluetooth.getAdapter("hci0");
  await adapter.helper._prepare();
  await adapter.helper.callMethod("SetDiscoveryFilter", { Transport: new ble.Variant("s", "le") });
  await adapter.helper.callMethod("StartDiscovery");
  assert.equal(await adapter.isDiscovering(), true);
  await adapter.helper.callMethod("StopDiscovery");
  assert.equal(await adapter.isDiscovering(), false);
  await assert.rejects(adapter.stopDiscovery(), /No discovery started/);
  await adapter.startDiscovery();
  await assert.rejects(adapter.startDiscovery(), /already in progress/);
  await adapter.stopDiscovery();
  await assert.rejects(adapter.helper.callMethod("RemoveDevice"), /not available on Android/);
  assert.deepEqual(t.hub.demands.map((d) => d.scan), [true, false, true, false]);
  await adapter.setPowered(false);
  assert.equal(await adapter.isPowered(), true, "powering off is not the stand-in's to do");
  assert.equal(adapter.toString(), "hci0");
  adapter.helper._propsProxy.on("PropertiesChanged", () => {});
  adapter.helper.removeListeners();
  assert.equal(adapter.helper._propsProxy.listenerCount("PropertiesChanged"), 0);
});

test("a device heard is listed, then forgotten DEVICE_TTL_MS after it was last heard", async () => {
  const t = fresh();
  const adapter = await t.bluetooth.getAdapter();
  assert.deepEqual(await adapter.devices(), []);
  await assert.rejects(adapter.getDevice(MAC), /Device not found/);
  t.hear({ manufacturerData: { 1177: RUUVI } });
  t.hear({ mac: "not a mac" });
  t.hear(null);
  assert.deepEqual(await adapter.devices(), [MAC]);
  t.advance(ble.DEVICE_TTL_MS + 1);
  assert.deepEqual(await adapter.devices(), []);
  await assert.rejects(adapter.getDevice(MAC), /Device not found/);
});

test("getDevice gives a fresh Device each call, and watches its address", async () => {
  const t = fresh();
  const adapter = await t.bluetooth.getAdapter();
  t.hear({ manufacturerData: { 1177: RUUVI } });
  const a = await adapter.getDevice(MAC.toLowerCase());
  const b = await adapter.getDevice(MAC);
  assert.notEqual(a, b);
  assert.deepEqual(t.hub.demands.at(-1).watch, [MAC]);
  assert.equal(await a.getAddress(), MAC);
  assert.equal(await a.getAlias(), "CB-B8-33-4C-88-4F");
  assert.equal(await a.getRSSI(), -60);
  assert.equal(await a.isPaired(), false);
  assert.equal(await a.isConnected(), false);
  assert.equal(await a.toString(), "CB-B8-33-4C-88-4F [CB:B8:33:4C:88:4F]");
});

test("waitDevice waits for a device to be heard, and times out as node-ble does", async () => {
  const t = fresh();
  const adapter = await t.bluetooth.getAdapter();
  const waiting = adapter.waitDevice(MAC, 1000, 10);
  setTimeout(() => t.hear({}), 25);
  assert.equal(await (await waiting).getAddress(), MAC);
  await assert.rejects(adapter.waitDevice("11:22:33:44:55:66", 30, 10), /operation timed out/);
});

test("GetAll and Get wrap the properties as dbus-next does, the data dicts' entries too", async () => {
  const t = fresh();
  const adapter = await t.bluetooth.getAdapter();
  t.hear({
    name: "Ruuvi 884F",
    addressType: "random",
    txPower: 4,
    serviceUuids: ["6e400001-b5a3-f393-e0a9-e50e24dcca9e"],
    manufacturerData: { 1177: RUUVI },
    serviceData: { "0000fcd2-0000-1000-8000-00805f9b34fb": Buffer.from([0x40]) },
  });
  const device = await adapter.getDevice(MAC);
  const all = await device._propsProxy.GetAll("org.bluez.Device1");
  for (const v of Object.values(all)) assert.equal(v.constructor.name, "Variant");
  assert.equal(all.Address.value, MAC);
  assert.equal(all.AddressType.value, "random");
  assert.equal(all.Name.value, "Ruuvi 884F");
  assert.equal(all.Alias.value, "Ruuvi 884F");
  assert.equal(all.RSSI.signature, "n");
  assert.equal(all.TxPower.value, 4);
  assert.deepEqual(all.UUIDs.value, ["6e400001-b5a3-f393-e0a9-e50e24dcca9e"]);
  assert.equal(all.ManufacturerData.value["1177"].constructor.name, "Variant");
  assert.equal(all.ManufacturerData.value["1177"].signature, "ay");
  assert.deepEqual(all.ManufacturerData.value["1177"].value, RUUVI);
  assert.deepEqual(all.ServiceData.value["0000fcd2-0000-1000-8000-00805f9b34fb"].value, Buffer.from([0x40]));
  assert.equal(all.Paired.value, false);
  assert.deepEqual(await device._propsProxy.GetAll("org.bluez.Battery1"), {});
  assert.equal((await device._propsProxy.Get("org.bluez.Device1", "RSSI")).value, -60);
  await assert.rejects(device._propsProxy.Get("org.bluez.Device1", "Nope"), /No such property/);
  assert.equal(await device.helper.prop("Name"), "Ruuvi 884F");
  assert.equal((await device.helper.props()).Address, MAC);
  const proxy = await (await device.helper.dbus.getProxyObject(device.helper.service, device.helper.object)).getInterface("org.freedesktop.DBus.Properties");
  assert.equal((await proxy.Get(device.helper.iface, "Address")).value, MAC);
  assert.equal(device.helper.object, "/org/bluez/hci0/dev_CB_B8_33_4C_88_4F");
});

test("a prepared helper hears each advertisement as PropertiesChanged, with what it carried", async () => {
  const t = fresh();
  const adapter = await t.bluetooth.getAdapter();
  t.hear({});
  const device = await adapter.getDevice(MAC);
  const heard = [];
  device.helper._prepare();
  device.helper._prepare();
  device.helper.on("PropertiesChanged", (props) => heard.push(props));
  t.hear({ rssi: -70, manufacturerData: { 1177: RUUVI }, name: "Ruuvi" });
  t.hear({ rssi: -71, name: "Ruuvi" });
  assert.equal(heard.length, 2, "one event per advertisement, the second _prepare added no listener");
  assert.deepEqual(Object.keys(heard[0]).sort(), ["ManufacturerData", "Name", "RSSI"]);
  assert.equal(heard[0].RSSI.value, -70);
  assert.equal(heard[0].ManufacturerData.value["1177"].value, RUUVI);
  assert.deepEqual(Object.keys(heard[1]), ["RSSI"], "a name heard before is not news");
  const all = await device._propsProxy.GetAll("org.bluez.Device1");
  assert.deepEqual(all.ManufacturerData.value["1177"].value, RUUVI, "data kept when a packet has none");
});

test("removeListeners deafens a helper until it is prepared again; a throwing listener deafens nobody", async () => {
  const t = fresh();
  const adapter = await t.bluetooth.getAdapter();
  t.hear({});
  const device = await adapter.getDevice(MAC);
  const other = await adapter.getDevice(MAC);
  let count = 0;
  let others = 0;
  device.helper._prepare();
  device.helper.on("PropertiesChanged", () => {
    throw new Error("a sensor that cannot parse this packet");
  });
  device.helper.on("PropertiesChanged", () => count++);
  other.helper._prepare();
  other.helper.on("PropertiesChanged", () => others++);
  t.hear({});
  assert.equal(count, 1);
  assert.equal(others, 1);
  device.helper.removeListeners();
  device.helper.on("PropertiesChanged", () => count++);
  t.hear({});
  assert.equal(count, 1, "not prepared: nothing heard");
  device.helper._prepare();
  t.hear({});
  assert.equal(count, 2);
  const before = others;
  await other.disconnect();
  t.hear({});
  assert.equal(others, before, "disconnect removes the listeners, as node-ble's does");
});

test("connecting is refused plainly, since a scanner cannot connect", async () => {
  const t = fresh();
  const adapter = await t.bluetooth.getAdapter();
  t.hear({});
  const device = await adapter.getDevice(MAC);
  await assert.rejects(device.helper.callMethod("Connect"), (e) => e.message === ble.NO_GATT);
  await assert.rejects(device.gatt(), /not available on Android/);
  await assert.rejects(device.connect(), /not available on Android/);
  await assert.rejects(device.pair(), /not available on Android/);
  assert.equal(await device.helper.callMethod("Disconnect"), undefined);
});

test("the oldest devices give way past MAX_DEVICES, unless something listens to them", async () => {
  const t = fresh();
  const adapter = await t.bluetooth.getAdapter();
  t.hear({});
  const kept = await adapter.getDevice(MAC);
  kept.helper._prepare();
  for (let i = 0; i < ble.MAX_DEVICES + 10; i++) {
    const n = i.toString(16).padStart(6, "0").toUpperCase();
    t.hear({ mac: `02:00:00:${n.slice(0, 2)}:${n.slice(2, 4)}:${n.slice(4, 6)}` });
  }
  assert.ok(t.state.records.size <= ble.MAX_DEVICES + 1);
  t.advance(ble.DEVICE_TTL_MS + 1);
  t.hear({ mac: "02:FF:FF:FF:FF:FF" });
  for (let i = 0; i < ble.MAX_DEVICES; i++) t.hear({ mac: "02:FF:FF:FF:FF:FE" });
  assert.ok(t.state.records.has(MAC), "a device a plugin listens to is not pruned");
  t.state.close();
});

test("wrap gives every property its D-Bus type", () => {
  assert.equal(ble.wrap("RSSI", -1).signature, "n");
  assert.equal(ble.wrap("Connected", false).signature, "b");
  assert.equal(ble.wrap("Unknown", 1).signature, "v");
  const md = ble.wrap("ManufacturerData", { 76: Buffer.from([2, 21]) });
  assert.equal(md.signature, "a{qv}");
  assert.equal(md.value["76"].signature, "ay");
});
