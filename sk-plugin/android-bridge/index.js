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
 * The phone's USB serial devices (GPS and AIS receivers, NMEA 0183 interfaces) come the same way:
 * the app reads them, since Android gives the server no serial port, and passes their bytes over
 * the socket; lib/serial.js parses their NMEA 0183 with the server's own parser. Bluetooth serial
 * devices and the alarm path back to the phone come in later steps; the protocol has room for them.
 */

const { Link } = require("./lib/link");
const { parseLine, toDelta, subscribeMessage, VERSION, BAUD_RATES, DEFAULT_BAUD } = require("./lib/protocol");
const { SerialInput, loadNmeaParser } = require("./lib/serial");
const pkg = require("./package.json");

const ENV = "POCKETSK_BRIDGE";

/**
 * @param {object} app the Signal K plugin API; `deps.env` lets tests name a socket, and
 * `deps.Parser` an NMEA 0183 parser (null for none) in place of the server's
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
          enum: BAUD_RATES,
        },
        serialDevices: {
          type: "array",
          title: "Baud rate per device",
          description: `A device whose rate is not the one above, by its id as the app's USB devices card shows it. ${pluggedIn()}`,
          default: [],
          items: {
            type: "object",
            required: ["id", "baud"],
            properties: {
              id: { type: "string", title: "Device id", description: "e.g. usb-0403-6001-a10k1b2c" },
              baud: { type: "number", title: "Baud rate", default: 38400, enum: BAUD_RATES },
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
        handle: (delta) => app.handleMessage(plugin.id, delta),
        emit: emitSentence,
      });
      link = new Link(socketPath, { onLine, onState });
      link.start();
      app.setPluginStatus("Connecting to the phone");
    },

    stop() {
      if (link) link.stop();
      link = null;
      input = null;
    },

    /** For tests and the status line. */
    statusNow: () => ({
      connected: link?.connected ?? false,
      app: appVersion,
      ...stats,
      devices,
      serial: input?.stats() ?? {},
    }),
  };

  /** The devices plugged in now, for the settings' description of the per-device rates. */
  function pluggedIn() {
    if (devices.length === 0) return "No USB device is plugged into the phone now.";
    return `Plugged in now: ${devices.map((d) => `${d.id} (${d.name})`).join(", ")}.`;
  }

  /** A sentence to the server's nmea0183 event, as its own NMEA 0183 connections give theirs. */
  function emitSentence(sentence) {
    if (typeof app.emit === "function") app.emit("nmea0183", sentence);
    if (app.signalk && typeof app.signalk.emit === "function") app.signalk.emit("nmea0183", sentence);
  }

  function onState(up, detail) {
    connected = up;
    if (up) {
      link.send(subscribeMessage(options));
    }
    showStatus(detail);
  }

  /** The status line: the link, then each USB device and how it stands. */
  function showStatus(detail) {
    if (!connected) {
      app.setPluginStatus(`Waiting for the phone (${detail})`);
      return;
    }
    const parts = devices.map((d) => {
      switch (d.state) {
        case "open":
          return `${d.name} at ${d.baud} baud`;
        case "needs-permission":
          return `${d.name} waits for permission in the app`;
        case "idle":
          return `${d.name} (USB is off)`;
        default:
          return `${d.name}: ${d.detail ?? "failed"}`;
      }
    });
    const parser = devices.length > 0 && !Parser ? "; the server's NMEA 0183 parser was not found, so sentences are not parsed" : "";
    app.setPluginStatus(parts.length > 0 ? `Connected to the phone; USB: ${parts.join(", ")}${parser}` : "Connected to the phone");
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
    stats.sensors++;
    app.handleMessage(plugin.id, toDelta(msg));
  }

  return plugin;
};
