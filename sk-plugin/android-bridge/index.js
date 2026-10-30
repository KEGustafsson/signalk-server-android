// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * @pocketsk/android-bridge: the Signal K server's view of the phone it runs on (docs/PLAN.md §7.2).
 *
 * Pocket SK runs the real signalk-server on an Android phone and serves a Unix socket in its
 * private directory, named to the server by the POCKETSK_BRIDGE environment variable. This plugin
 * connects to it (lib/link.js), says which of the phone's sensors it wants (the plugin's
 * settings), and turns what the app reports into Signal K deltas under `$source: android.<sensor>`
 * (lib/protocol.js). The app installs and enables it; on any other server there is no socket, and
 * the plugin says so in its status and does nothing.
 *
 * The phone's serial devices (GPS and AIS receivers, NMEA 0183 interfaces), plugged in by USB or
 * paired over Bluetooth, come the same way: the app reads them, since Android gives the server no
 * serial port, and passes their bytes over the socket; lib/serial.js parses their NMEA 0183 with
 * the server's own parser.
 *
 * The other way, the boat's alarms go to the phone (lib/alarms.js): the server's notifications at
 * or above a level, so the app can ring and wake the phone's screen, and its Silence comes back to
 * be done through the server's notification API.
 *
 * And the phone's Bluetooth LE scanner (lib/ble.js): the advertisements it hears go to the
 * server's Bluetooth LE API and to plugins written for BlueZ, through the node-ble stand-in the
 * app preloads, which also says when such a plugin wants a scan.
 *
 * Satellite mode (lib/satellite.js): when the app says so, this server follows the boat's main
 * Signal K server, the main server's data coming in under its own sources, with the device token
 * its admin approved for this phone, which the app hands over the socket.
 *
 * Its web page (public/index.html, a Signal K webapp) uploads files into the server's data folders,
 * charts above all, through the routes in registerWithRouter (lib/files.js). Those work on any
 * server, phone or not.
 */

const { Link } = require("./lib/link");
const { parseLine, toDelta, subscribeMessage, statsMessage, glanceMessage, satelliteMessage, VERSION, BAUD_RATES, DEFAULT_BAUD } = require("./lib/protocol");
const { Satellite } = require("./lib/satellite");
const { SerialInput, loadNmeaParser } = require("./lib/serial");
const { Alarms, LEVELS } = require("./lib/alarms");
const { Ble } = require("./lib/ble");
const { Files, FilesError, bytes } = require("./lib/files");
const pkg = require("./package.json");

const ENV = "POCKETSK_BRIDGE";
/** How often the server's delta rate goes to the app: the server itself counts every 5 s. */
const STATS_MS = 5000;
/** How often the boat at a glance goes to the app while its widget is on a screen that is on. */
const GLANCE_MS = 2000;

/**
 * @param {object} app the Signal K plugin API; `deps.env` lets tests name a socket,
 * `deps.Parser` an NMEA 0183 parser (null for none) in place of the server's, `deps.statsMs`
 * how often the server's delta rate is sent, `deps.glanceMs` how often the widget's glance is,
 * `deps.bleHub` the node-ble stand-in's hub (null for none) in place of the one the app
 * preloads, `deps.configDir` the server's
 * `.signalk` for the file routes in place of the server's own, and `deps.WebSocket` and
 * `deps.fetch` satellite mode's in place of Node's own
 */
module.exports = function androidBridge(app, deps = {}) {
  const env = deps.env ?? process.env;
  let link = null;
  let options = {};
  let stats = { sensors: 0, dropped: 0 };
  let appVersion = "";
  let devices = [];
  let connected = false;
  let input = null;
  let Parser = null;
  let alarms = null;
  let unsubscribes = [];
  let statsTimer = null;
  let glanceTimer = null;
  let ble = null;
  let satellite = null;
  let linkDetail = "";

  const plugin = {
    id: "pocketsk-android-bridge",
    name: "Pocket SK Android bridge",
    description: pkg.description,
    // The app writes this plugin's enabled config on first install; on a server where the
    // plugin was installed by hand it stays off until someone turns it on.
    enabledByDefault: false,

    schema: () => ({
      type: "object",
      properties: {
        battery: {
          type: "boolean",
          title: "Phone battery",
          description: "State of charge, voltage and temperature as electrical.batteries.phone.*",
          default: true,
        },
        barometer: {
          type: "boolean",
          title: "Barometer",
          description: "The phone's pressure sensor as environment.outside.pressure, where the phone has one",
          default: true,
        },
        barometerSeconds: {
          type: "number",
          title: "Barometer period (seconds)",
          description: "How often the averaged pressure is sent",
          default: 10,
          minimum: 1,
          maximum: 600,
        },
        gnss: {
          type: "boolean",
          title: "GNSS",
          description: "The phone's own GPS as navigation.position, speedOverGround and courseOverGroundTrue, once precise location is allowed in the app",
          default: true,
        },
        gnssSeconds: {
          type: "number",
          title: "GNSS period (seconds)",
          description: "How often a fix is sent",
          default: 1,
          minimum: 0.2,
          maximum: 10,
        },
        attitude: {
          type: "boolean",
          title: "Attitude and rate of turn",
          description: "Roll and pitch as navigation.attitude and the rate of turn as navigation.rateOfTurn, from the phone's rotation sensor and gyroscope. Level the phone with Level now in the app's Sensors card",
          default: true,
        },
        attitudeSeconds: {
          type: "number",
          title: "Attitude period (seconds)",
          description: "How often the attitude, the rate of turn and the heading are sent",
          default: 1,
          minimum: 0.1,
          maximum: 10,
        },
        heading: {
          type: "boolean",
          title: "Magnetic heading",
          description: "The phone's compass as navigation.headingMagnetic (and the attitude's yaw). Off by default: a compass beside a boat's steel and wiring is often wrong",
          default: false,
        },
        bowOffset: {
          type: "number",
          title: "Bow offset (degrees)",
          description: "How far the bow is to starboard of where the phone points: its top, or its back when it stands upright. 90 when the phone's right side faces the bow, -90 for its left",
          default: 0,
          minimum: -180,
          maximum: 180,
        },
        environment: {
          type: "boolean",
          title: "Cabin sensors",
          description: "Temperature, humidity and light as environment.inside.temperature, .relativeHumidity and .illuminance, those the phone has (most have only light)",
          default: true,
        },
        environmentSeconds: {
          type: "number",
          title: "Cabin sensors period (seconds)",
          description: "How often the averaged cabin readings are sent",
          default: 10,
          minimum: 1,
          maximum: 600,
        },
        cellular: {
          type: "boolean",
          title: "Cellular network",
          description: "The phone's mobile network as communication.cellular.signalStrength (0 to 1, the phone's bars), .dbm (the serving cell's power), .operator and .roaming, where the phone has a cellular radio",
          default: true,
        },
        cellularSeconds: {
          type: "number",
          title: "Cellular period (seconds)",
          description: "How often the cellular network is read",
          default: 30,
          minimum: 1,
          maximum: 600,
        },
        usb: {
          type: "boolean",
          title: "USB serial devices",
          description: "Read the NMEA 0183 of USB serial adapters plugged into the phone (GPS and AIS receivers, NMEA 0183 interfaces), each under its own source, android.<device>. Allow each one in Android's dialog when it is plugged in, or with Allow in the app's USB devices card",
          default: true,
        },
        serialBaud: {
          type: "number",
          title: "Baud rate",
          description: "The rate USB devices are read at, unless set for the device below: 4800 for most NMEA 0183, 38400 for AIS",
          default: DEFAULT_BAUD,
          enum: [...BAUD_RATES],
        },
        bluetooth: {
          type: "boolean",
          title: "Bluetooth serial devices",
          description: "Read the NMEA 0183 of the phone's paired Bluetooth serial devices (GPS and AIS receivers), each under its own source, android.<device>. Pair the device in the phone's Bluetooth settings, then switch it on in the app's Serial devices card",
          default: true,
        },
        alarms: {
          type: "boolean",
          title: "Alarms on the phone",
          description: "The boat's alarms (the server's notifications, such as an anchor alarm) ring on the phone and wake its screen, with Silence, which silences them on the server too",
          default: true,
        },
        alarmLevel: {
          type: "string",
          title: "Alarm level",
          description: "The lowest notification state that rings on the phone: alarm and emergency by default",
          default: "alarm",
          enum: [...LEVELS],
        },
        ble: {
          type: "boolean",
          title: "Bluetooth LE devices",
          description: "Scan for Bluetooth LE sensors and give what the phone hears to the server's Bluetooth LE API. Plugins written for a Raspberry Pi's Bluetooth, such as BT Sensors, start the scan themselves, so this can stay off for them. Needs the Nearby devices permission in the app. Off by default: a scan costs battery",
          default: false,
        },
        serialDevices: {
          type: "array",
          title: "Baud rate per device",
          description: `A USB device whose rate is not the one above, by its id as the app's Serial devices card shows it. ${pluggedIn()}`,
          default: [],
          items: {
            type: "object",
            required: ["id", "baud"],
            properties: {
              id: { type: "string", title: "Device id", description: "e.g. usb-0403-6001-a10k1b2c" },
              baud: { type: "number", title: "Baud rate", default: 38400, enum: [...BAUD_RATES] },
            },
          },
        },
      },
    }),

    start(settings) {
      options = settings ?? {};
      stats = { sensors: 0, dropped: 0 };
      devices = [];
      connected = false;
      const socketPath = env[ENV];
      if (!socketPath) {
        app.setPluginStatus("Not running on Pocket SK: there is no phone to bridge");
        return;
      }
      Parser = "Parser" in deps ? deps.Parser : loadNmeaParser();
      if (!Parser) app.debug("the server's NMEA 0183 parser was not found: USB sentences are passed on, not parsed");
      input = new SerialInput({
        Parser,
        // As the server makes its own: custom sentence parsers other plugins register apply too.
        parserOptions: typeof app.onPropertyValues === "function" ? { onPropertyValues: app.onPropertyValues.bind(app) } : {},
        // Sent first: signalk-server writes its own vessel into a delta it is given.
        handle: (delta) => {
          satellite?.send(delta, "serial");
          app.handleMessage(plugin.id, delta);
        },
        emit: emitSentence,
      });
      alarms = new Alarms({
        send: (msg) => {
          if (link?.connected) link.send(msg);
        },
        api: options.alarms === false ? null : (app.notifications ?? null),
        level: options.alarmLevel,
        debug: (s) => app.debug(s),
      });
      ble = new Ble({
        app,
        pluginId: plugin.id,
        ...("bleHub" in deps ? { hub: deps.bleHub } : {}),
        // A Bluetooth LE plugin started or stopped a scan, or was set up for other devices.
        onDemand: () => {
          if (link?.connected) link.send(subscribeMessage(options, ble?.demand()));
        },
        debug: (s) => app.debug(s),
      });
      satellite = new Satellite({
        app,
        pluginId: plugin.id,
        ...("WebSocket" in deps ? { WebSocket: deps.WebSocket } : {}),
        ...("fetch" in deps ? { fetch: deps.fetch } : {}),
        onState: (state, detail, write) => {
          if (link?.connected) link.send(satelliteMessage(state, detail, write));
          showStatus();
        },
        debug: (s) => app.debug(s),
      });
      link = new Link(socketPath, { onLine, onState });
      link.start();
      // Read from the server's own last statistics, not a listener of ours: a listener a plugin
      // adds with app.on stays on the server's emitter after the plugin stops.
      statsTimer = setInterval(sendStats, deps.statsMs ?? STATS_MS);
      statsTimer.unref?.();
      if (options.alarms !== false) watchNotifications();
      app.setPluginStatus("Connecting to the phone");
    },

    stop() {
      clearInterval(statsTimer);
      statsTimer = null;
      glance(false);
      for (const u of unsubscribes) u();
      unsubscribes = [];
      // The link goes first, with no word of the alarms: a server restart stops the plugin, and an
      // empty list would end an anchor alarm on the phone only for it to ring again moments later.
      // The phone keeps them a while after the link drops and clears them if the plugin stays away.
      if (link) link.stop();
      link = null;
      input = null;
      alarms = null;
      ble?.stop();
      ble = null;
      satellite?.stop();
      satellite = null;
    },

    /**
     * The file routes (lib/files.js), under /plugins/pocketsk-android-bridge/files, which
     * signalk-server lets admins alone use while security is on:
     * GET /files the data folders; GET /files/:folder its files and uploads under way;
     * PUT /files/:folder/:name?offset=&total= one piece of a file, the raw bytes as the body;
     * DELETE /files/:folder/:name a file (?upload=1: the upload of it under way).
     */
    registerWithRouter(router) {
      router.get("/files", answer(async () => ({ folders: await files().folders() })));
      router.get("/files/:folder", answer((req) => files().list(req.params.folder)));
      router.put("/files/:folder/:name", answer((req) =>
        files().put(
          req.params.folder,
          req.params.name,
          bytes(req.query.offset),
          bytes(req.query.total),
          bytes(req.headers["content-length"]),
          req,
        ),
      ));
      router.delete("/files/:folder/:name", answer(async (req) => {
        await files().remove(req.params.folder, req.params.name, req.query.upload === "1");
        return { ok: true };
      }));
    },

    /** For tests and the status line. */
    statusNow: () => ({
      connected: link?.connected ?? false,
      app: appVersion,
      ...stats,
      devices,
      serial: input?.stats() ?? {},
      alarms: alarms?.list() ?? [],
      ble: ble?.stats() ?? null,
      satellite: satellite?.status() ?? null,
    }),
  };

  /** The file store on the server's `.signalk`, made when a route first needs it. */
  let store = null;
  function files() {
    const configDir = deps.configDir ?? app.config?.configPath ?? env.SIGNALK_NODE_CONFIG_DIR;
    if (!configDir) throw new FilesError(503, "the server's configuration folder is not known");
    if (store?.configDir !== configDir) store = new Files({ configDir });
    return store;
  }

  /**
   * An Express handler from `fn`, whose result is the JSON answer and whose FilesError is the
   * status. An answer before the whole body was read closes the connection, so a browser does not
   * go on sending a piece nobody reads.
   */
  function answer(fn) {
    return async (req, res) => {
      try {
        res.json(await fn(req));
      } catch (e) {
        const status = e instanceof FilesError ? e.status : 500;
        if (status === 500) app.debug(`files: ${e.stack ?? e}`);
        if (!req.complete) res.set("Connection", "close");
        res.status(status).json({ message: e.message, ...(e.extra ?? {}) });
      }
    };
  }

  /**
   * The vessel's own notifications, through the server's subscription manager as every plugin
   * reads them: those already raised come first, from its cache, then each change as it comes.
   */
  function watchNotifications() {
    // All lower case in the plugin interface (signalk-server's interfaces/plugins), unlike the class.
    const manager = app.subscriptionmanager;
    if (!manager || typeof manager.subscribe !== "function") {
      app.debug("the server has no subscription manager: no alarms for the phone");
      return;
    }
    manager.subscribe(
      { context: "vessels.self", subscribe: [{ path: "notifications.*", policy: "instant" }] },
      unsubscribes,
      (err) => app.debug(`notifications: ${err}`),
      (delta) => {
        for (const u of delta?.updates ?? []) {
          for (const v of u?.values ?? []) alarms?.update(v?.path, v?.value);
        }
      },
    );
  }

  /** The server's delta rate to the app, while connected and once the server has counted. */
  function sendStats() {
    const msg = statsMessage(app.lastServerEvents);
    if (msg && link?.connected) link.send(msg);
  }

  /**
   * The glance for the app's widget: sent at once and every GLANCE_MS while [on], stopped when the
   * app says off or the link drops, so a phone with its screen off asks the server for nothing.
   */
  function glance(on) {
    if (!on) {
      clearInterval(glanceTimer);
      glanceTimer = null;
      return;
    }
    sendGlance();
    if (glanceTimer) return;
    glanceTimer = setInterval(sendGlance, deps.glanceMs ?? GLANCE_MS);
    glanceTimer.unref?.();
  }

  function sendGlance() {
    if (!link?.connected || typeof app.getSelfPath !== "function") return;
    link.send(glanceMessage((path) => app.getSelfPath(path)));
  }

  /** The devices plugged in now, for the settings' description of the per-device rates. */
  function pluggedIn() {
    const usb = devices.filter((d) => d.kind === "usb");
    if (usb.length === 0) return "No USB device is plugged into the phone now.";
    return `Plugged in now: ${usb.map((d) => `${d.id} (${d.name})`).join(", ")}.`;
  }

  /** A sentence to the server's nmea0183 event, as its own NMEA 0183 connections give theirs. */
  function emitSentence(sentence) {
    if (typeof app.emit === "function") app.emit("nmea0183", sentence);
    if (app.signalk && typeof app.signalk.emit === "function") app.signalk.emit("nmea0183", sentence);
  }

  function onState(up, detail) {
    connected = up;
    // A new link starts with the widget's glance off; the app asks again when it wants it.
    glance(false);
    if (up) {
      link.send(subscribeMessage(options, ble?.demand()));
      alarms?.resend();
      sendStats();
      if (satellite) {
        const s = satellite.status();
        link.send(satelliteMessage(s.state, s.detail, s.write));
      }
    }
    showStatus(detail);
  }

  /**
   * The status line: the link, then each USB device and how it stands, then satellite mode's link.
   * Without a [detail] the last one the link gave is kept.
   */
  function showStatus(detail) {
    if (detail !== undefined) linkDetail = detail;
    if (!connected) {
      app.setPluginStatus(`Waiting for the phone (${linkDetail})`);
      return;
    }
    const parts = devices.map((d) => {
      switch (d.state) {
        case "open":
          return d.kind === "bt" ? `${d.name} connected` : `${d.name} at ${d.baud} baud`;
        case "connecting":
          return d.detail ? `${d.name} connecting (${d.detail})` : `${d.name} connecting`;
        case "needs-permission":
          return `${d.name} waits for permission in the app`;
        case "idle":
          // Idle is this plugin's own setting turned off; an adapter that is off is an error with its detail.
          return `${d.name} (${d.kind === "bt" ? "Bluetooth" : "USB"} is off in these settings)`;
        default:
          return `${d.name}: ${d.detail ?? "failed"}`;
      }
    });
    const parser = devices.length > 0 && !Parser ? "; the server's NMEA 0183 parser was not found, so sentences are not parsed" : "";
    const s = satellite?.status();
    const access = s?.write === true ? ", read/write" : s?.write === false ? ", read only" : "";
    const following = s?.url ? `; following ${s.url.replace(/^http:\/\//, "")} (${s.state}${s.detail ? `: ${s.detail}` : ""}${access})` : "";
    app.setPluginStatus(`${parts.length > 0 ? `Connected to the phone; serial: ${parts.join(", ")}${parser}` : "Connected to the phone"}${following}`);
  }

  function onLine(line) {
    const parsed = parseLine(line);
    if (!parsed.ok) {
      stats.dropped++;
      app.debug(`dropped a line from the phone: ${parsed.reason}`);
      return;
    }
    const msg = parsed.msg;
    if (msg.t === "hello") {
      appVersion = msg.app;
      if (msg.v !== VERSION) app.debug(`the app speaks bridge version ${msg.v}, this plugin ${VERSION}`);
      return;
    }
    if (msg.t === "devices") {
      const now = new Set(msg.list.map((d) => d.id));
      for (const d of devices) if (!now.has(d.id)) input?.forget(d.id);
      devices = msg.list;
      showStatus();
      return;
    }
    if (msg.t === "serial") {
      input?.data(msg.id, msg.data);
      return;
    }
    if (msg.t === "adv") {
      ble?.advertisement(msg);
      return;
    }
    if (msg.t === "glance") {
      glance(msg.on);
      return;
    }
    if (msg.t === "satellite") {
      satellite?.follow(msg.url, msg.token, { label: msg.label, push: msg.push });
      return;
    }
    if (msg.t === "silence") {
      const path = alarms?.pathOf(msg.id);
      const done = alarms?.silence(msg.id);
      app.debug(done ? `the phone asked to ${done} ${msg.id}` : `the phone silenced ${msg.id} on the phone alone`);
      // An alarm that came from the boat's main server goes quiet there too, whatever this server
      // could do with its copy.
      if (path) satellite?.silence(path, done ?? "silence");
      return;
    }
    stats.sensors++;
    const delta = toDelta(msg);
    // Sent first: signalk-server writes its own vessel into a delta it is given.
    satellite?.send(delta, "sensors");
    app.handleMessage(plugin.id, delta);
  }

  return plugin;
};
