// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Bluetooth LE for plugins written for BlueZ. NodeRuntime loads this file into every node process
 * the app starts (`NODE_OPTIONS=--require`), beside mdns-lookup.js.
 *
 * Signal K's Bluetooth LE plugins (bt-sensors-plugin-sk above all: Ruuvi, Victron, Govee,
 * Inkbird, Xiaomi, BTHome and some sixty others) talk to BlueZ over D-Bus through
 * `@naugehyde/node-ble`. Android has neither: its Bluetooth stack is the app's to use, and the
 * plugin dies on a missing `/var/run/dbus/system_bus_socket` the moment the server loads it. The
 * plugins are to run unchanged, so `require('@naugehyde/node-ble')` (and the original `node-ble`)
 * is answered here with a stand-in that offers the same objects (Bluetooth, Adapter, Device and
 * their `helper`) and is fed by the phone's own Bluetooth LE scanner: the app scans, the bridge
 * plugin (sk-plugin/android-bridge, lib/ble.js) parses each advertisement and publishes it on the
 * [hub] below, and every Device the stand-in handed out hears it as BlueZ's PropertiesChanged.
 *
 * Faithful to node-ble where the plugins look, quirks included, because bt-sensors reaches past
 * its public API: values come wrapped in dbus-next-style [Variant]s (`.value`) at the same places
 * node-ble's do, manufacturer and service data entries are Variants of a Buffer inside the dict,
 * `getDevice` hands out a fresh Device each call, `helper._prepare()` subscribes and
 * `helper.removeListeners()` unsubscribes, the adapter is `hci0` and always powered, and
 * `devices()` lists every device heard in the last [DEVICE_TTL_MS], as BlueZ's object tree does.
 * What a scanner cannot give is refused plainly: connecting (GATT) rejects with [NO_GATT], so a
 * sensor that needs a connection reports its error rather than waiting for ever.
 *
 * The hub is the one thing shared with the bridge plugin, through a global symbol, since the two
 * are loaded separately: the plugin publishes advertisements and reads what is wanted (a scan,
 * and the addresses to keep hearing with the screen off); the stand-in says what it wants. Plain
 * CommonJS with no dependencies, and it never throws while loading.
 */

const { EventEmitter } = require("node:events");
const Module = require("node:module");

const HUB = Symbol.for("pocketsk.ble");
const INSTALLED = Symbol.for("pocketsk.nodeBle");
/** The module names answered with the stand-in: the fork bt-sensors uses, and the original. */
const MODULES = ["@naugehyde/node-ble", "node-ble"];
const ADAPTER = "hci0";
const DEVICE_IFACE = "org.bluez.Device1";
/** How long a device stays in the adapter's list after it was last heard; the server's BLE API keeps it as long. */
const DEVICE_TTL_MS = 120_000;
/** Most devices kept at once: phones rotate their addresses, and every new one is a new device. */
const MAX_DEVICES = 1024;
/** Most addresses asked to be heard with the screen off: Android's hardware filters are few. */
const MAX_WATCH = 32;
/** node-ble's own default for waitDevice, and the interval it polls at. */
const WAIT_TIMEOUT_MS = 2 * 60 * 1000;
const WAIT_INTERVAL_MS = 1000;
const NO_GATT = "Bluetooth LE connections (GATT) are not available on Android yet: only sensors that advertise their readings work";
const MAC = /^[0-9A-F]{2}(:[0-9A-F]{2}){5}$/;

/** dbus-next's Variant, by name as bt-sensors tests for it (`constructor.name == 'Variant'`). */
class Variant {
  constructor(signature, value) {
    this.signature = signature;
    this.value = value;
  }
}

/**
 * The hub between the bridge plugin and the stand-in: advertisements one way, what is wanted the
 * other. Created once per process, by whichever side is loaded first; this file is loaded first.
 */
function hub() {
  if (globalThis[HUB]) return globalThis[HUB];
  const events = new EventEmitter();
  events.setMaxListeners(0);
  const demands = new Map();
  const h = {
    version: 1,
    /** The bridge plugin: one advertisement, normalised (see [record]). */
    publish(adv) {
      events.emit("advertisement", adv);
    },
    /** The stand-in: every advertisement from now on; returns the unsubscribe. */
    subscribe(cb) {
      events.on("advertisement", cb);
      return () => events.off("advertisement", cb);
    },
    /** One consumer's wish: whether to scan, and which addresses to keep hearing. */
    setDemand(owner, demand) {
      if (demand && (demand.scan || (demand.watch && demand.watch.length))) demands.set(owner, demand);
      else demands.delete(owner);
      events.emit("demand", h.demand());
    },
    /** Everyone's wishes together. */
    demand() {
      const watch = new Set();
      let scan = false;
      for (const d of demands.values()) {
        scan = scan || !!d.scan;
        for (const mac of d.watch ?? []) if (watch.size < MAX_WATCH) watch.add(mac);
      }
      return { scan, watch: [...watch] };
    },
    /** The bridge plugin: told whenever the wishes change; returns the unsubscribe. */
    onDemand(cb) {
      events.on("demand", cb);
      return () => events.off("demand", cb);
    },
  };
  globalThis[HUB] = h;
  return h;
}

/** What the adapter knows of one device: its BlueZ properties, kept up to date by its advertisements. */
function freshRecord(mac) {
  return {
    mac,
    lastSeen: 0,
    props: { Address: mac, AddressType: "public", Alias: mac.replace(/:/g, "-"), Paired: false, Connected: false, Trusted: false, Blocked: false, ServicesResolved: false },
    listeners: new Set(),
  };
}

/** The D-Bus type BlueZ gives each property, for the Variant around it. */
const SIGNATURES = {
  Address: "s", AddressType: "s", Name: "s", Alias: "s", RSSI: "n", TxPower: "n", UUIDs: "as",
  ManufacturerData: "a{qv}", ServiceData: "a{sv}", Paired: "b", Connected: "b", Trusted: "b", Blocked: "b", ServicesResolved: "b",
};

/** A property as GetAll and PropertiesChanged give it: wrapped, with the data dicts' entries wrapped too. */
function wrap(name, value) {
  if (name === "ManufacturerData" || name === "ServiceData") {
    const dict = {};
    for (const [k, buf] of Object.entries(value)) dict[k] = new Variant("ay", buf);
    return new Variant(SIGNATURES[name], dict);
  }
  return new Variant(SIGNATURES[name] ?? "v", value);
}

/** The stand-in's state: the devices heard, and what the adapters want of the hub. */
function makeState({ hub: h = hub(), now = Date.now } = {}) {
  const records = new Map();
  const watched = new Set();
  let discovering = false;

  const tell = () => h.setDemand("node-ble", { scan: discovering, watch: [...watched] });

  /**
   * Makes room for one more: first the devices not heard for [DEVICE_TTL_MS], then the least
   * recently heard, but never one a plugin listens to (its sensor would go deaf for good).
   */
  const prune = () => {
    const cutoff = now() - DEVICE_TTL_MS;
    for (const [mac, r] of records) if (r.lastSeen < cutoff && r.listeners.size === 0) records.delete(mac);
    for (const [mac, r] of records) {
      if (records.size < MAX_DEVICES) break;
      if (r.listeners.size === 0) records.delete(mac);
    }
  };

  /** One advertisement in: the record updated, and every prepared helper told what changed. */
  const heard = (adv) => {
    if (!adv || typeof adv.mac !== "string") return;
    const mac = adv.mac.toUpperCase();
    if (!MAC.test(mac)) return;
    let r = records.get(mac);
    if (!r) {
      if (records.size >= MAX_DEVICES) prune();
      r = freshRecord(mac);
      records.set(mac, r);
    } else {
      // Most recently heard last, so the oldest are the first evicted.
      records.delete(mac);
      records.set(mac, r);
    }
    r.lastSeen = now();
    const changed = {};
    const set = (name, value) => {
      r.props[name] = value;
      changed[name] = wrap(name, value);
    };
    if (Number.isFinite(adv.rssi)) set("RSSI", adv.rssi);
    if (adv.addressType === "public" || adv.addressType === "random") r.props.AddressType = adv.addressType;
    if (typeof adv.name === "string" && adv.name && adv.name !== r.props.Name) {
      set("Name", adv.name);
      r.props.Alias = adv.name;
    }
    if (Number.isFinite(adv.txPower)) r.props.TxPower = adv.txPower;
    if (Array.isArray(adv.serviceUuids) && adv.serviceUuids.length > 0 && adv.serviceUuids.join() !== (r.props.UUIDs ?? []).join()) {
      set("UUIDs", [...adv.serviceUuids]);
    }
    // Replaced whole when an advertisement carries them, kept when it does not, as BlueZ keeps them.
    if (adv.manufacturerData && Object.keys(adv.manufacturerData).length > 0) set("ManufacturerData", { ...adv.manufacturerData });
    if (adv.serviceData && Object.keys(adv.serviceData).length > 0) set("ServiceData", { ...adv.serviceData });
    for (const listener of [...r.listeners]) listener(changed);
  };

  const unsubscribe = h.subscribe(heard);

  return {
    records,
    heard,
    close: unsubscribe,
    get discovering() {
      return discovering;
    },
    discover(on) {
      discovering = on;
      tell();
    },
    watch(mac) {
      if (watched.has(mac)) return;
      if (watched.size >= MAX_WATCH) watched.delete(watched.values().next().value);
      watched.add(mac);
      tell();
    },
    devices() {
      const cutoff = now() - DEVICE_TTL_MS;
      return [...records.values()].filter((r) => r.lastSeen >= cutoff).map((r) => r.mac);
    },
    record(mac) {
      const r = records.get(mac);
      return r && r.lastSeen >= now() - DEVICE_TTL_MS ? r : null;
    },
  };
}

/** A device's helper, node-ble's BusHelper as far as the plugins use it. */
class DeviceHelper extends EventEmitter {
  constructor(record) {
    super();
    this.service = "org.bluez";
    this.object = `/org/bluez/${ADAPTER}/dev_${record.mac.replace(/:/g, "_")}`;
    this.iface = DEVICE_IFACE;
    this._record = record;
    this._ready = false;
    this._listener = (changed) => {
      // Each listener on its own: one plugin's sensor that throws must not deafen the others.
      for (const cb of this.listeners("PropertiesChanged")) {
        try {
          cb(changed);
        } catch {
          // A sensor's parser failing on a packet it did not expect, as on BlueZ: that packet is lost.
        }
      }
    };
    this._propsProxy = props(record);
    this.dbus = { getProxyObject: async () => ({ getInterface: async () => this._propsProxy }) };
  }

  /** Subscribes to the device's advertisements; idempotent, and synchronous where it matters, since callers do not await it. */
  _prepare() {
    if (!this._ready) {
      this._record.listeners.add(this._listener);
      this._ready = true;
    }
    return Promise.resolve();
  }

  removeListeners() {
    this.removeAllListeners("PropertiesChanged");
    this._record.listeners.delete(this._listener);
    this._ready = false;
  }

  async prop(name) {
    return (await this._propsProxy.Get(this.iface, name)).value;
  }

  async props() {
    const raw = await this._propsProxy.GetAll(this.iface);
    return Object.fromEntries(Object.entries(raw).map(([k, v]) => [k, v.value]));
  }

  async callMethod(name) {
    if (name === "Disconnect") return undefined;
    throw new Error(NO_GATT);
  }
}

/** The org.freedesktop.DBus.Properties interface of a device, over its record. */
function props(record) {
  return {
    async GetAll(iface) {
      if (iface !== DEVICE_IFACE) return {};
      return Object.fromEntries(Object.entries(record.props).map(([k, v]) => [k, wrap(k, v)]));
    },
    async Get(iface, name) {
      if (iface !== DEVICE_IFACE || !(name in record.props)) throw new Error(`No such property '${name}'`);
      return wrap(name, record.props[name]);
    },
  };
}

/** node-ble's Device, as far as a scanner can make one. */
class Device extends EventEmitter {
  constructor(record) {
    super();
    this.device = `dev_${record.mac.replace(/:/g, "_")}`;
    this.helper = new DeviceHelper(record);
    // bt-sensors caches the properties interface here, and finds it already made.
    this._propsProxy = this.helper._propsProxy;
  }

  async getAddress() { return this.helper.prop("Address"); }
  async getAddressType() { return this.helper.prop("AddressType"); }
  async getName() { return this.helper.prop("Name"); }
  async getAlias() { return this.helper.prop("Alias"); }
  async getRSSI() { return this.helper.prop("RSSI"); }
  async isPaired() { return false; }
  async isConnected() { return false; }
  async toString() { return `${await this.getAlias()} [${await this.getAddress()}]`; }
  async connect() { throw new Error(NO_GATT); }
  async gatt() { throw new Error(NO_GATT); }
  async pair() { throw new Error(NO_GATT); }

  async disconnect() {
    this.helper.removeListeners();
  }
}

/** node-ble's Adapter: always `hci0`, always powered, discovering when a plugin asked it to. */
class Adapter {
  constructor(state, name) {
    this.adapter = name;
    this._state = state;
    const proxy = new EventEmitter();
    this.helper = {
      _ready: true,
      _propsProxy: proxy,
      _prepare: async () => {},
      callMethod: async (method) => {
        if (method === "SetDiscoveryFilter") return undefined;
        if (method === "StartDiscovery") return state.discover(true);
        if (method === "StopDiscovery") return state.discover(false);
        throw new Error(`${method} is not available on Android`);
      },
      removeListeners: () => proxy.removeAllListeners("PropertiesChanged"),
    };
  }

  async getAddress() { return "00:00:00:00:00:00"; }
  async getAddressType() { return "public"; }
  async getName() { return "Android"; }
  async getAlias() { return "Android"; }
  async isPowered() { return true; }
  async setPowered() {}
  async isDiscovering() { return this._state.discovering; }

  async startDiscovery() {
    if (this._state.discovering) throw new Error("Discovery already in progress");
    this._state.discover(true);
  }

  async stopDiscovery() {
    if (!this._state.discovering) throw new Error("No discovery started");
    this._state.discover(false);
  }

  async devices() {
    return this._state.devices();
  }

  async getDevice(mac) {
    const key = String(mac).toUpperCase();
    // Asked for by address: one the plugin was set up for, to keep hearing with the screen off.
    if (MAC.test(key)) this._state.watch(key);
    const record = this._state.record(key);
    if (!record) throw new Error("Device not found");
    return new Device(record);
  }

  async waitDevice(mac, timeout = WAIT_TIMEOUT_MS, interval = WAIT_INTERVAL_MS) {
    const until = Date.now() + timeout;
    for (;;) {
      try {
        return await this.getDevice(mac);
      } catch {
        if (Date.now() + interval > until) throw new Error("operation timed out");
        await new Promise((ok) => setTimeout(ok, interval));
      }
    }
  }

  toString() {
    return this.adapter;
  }
}

/** node-ble's Bluetooth: one adapter, under whatever name it is asked for (`hci0` by default). */
class Bluetooth {
  constructor(state) {
    this._state = state;
    this._adapters = new Map();
  }

  _adapter(name) {
    if (!this._adapters.has(name)) this._adapters.set(name, new Adapter(this._state, name));
    return this._adapters.get(name);
  }

  async adapters() { return [ADAPTER]; }
  async defaultAdapter() { return this._adapter(ADAPTER); }
  async getAdapter(name = ADAPTER) { return this._adapter(String(name)); }
  async activeAdapters() { return [this._adapter(ADAPTER)]; }
}

let shared = null;

/** node-ble's entry point: synchronous, and nothing opened. */
function createBluetooth() {
  if (!shared) shared = makeState();
  const bluetooth = new Bluetooth(shared);
  return { bluetooth, destroy() {} };
}

const standIn = { createBluetooth, Variant };

/** Answers [MODULES] with the stand-in in every later `require`, once. */
function install() {
  if (globalThis[INSTALLED]) return false;
  hub();
  const load = Module._load;
  Module._load = function (request, parent, isMain) {
    if (MODULES.includes(request)) return standIn;
    return load.call(this, request, parent, isMain);
  };
  globalThis[INSTALLED] = true;
  return true;
}

try {
  install();
} catch {
  // Never in the way of the process it was loaded into: without it, those plugins fail as before.
}

module.exports = {
  hub, makeState, createBluetooth, Bluetooth, Adapter, Device, Variant, install, wrap,
  NO_GATT, DEVICE_TTL_MS, MAX_DEVICES, MAX_WATCH, MODULES,
};
