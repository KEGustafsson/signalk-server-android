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
 * The phone's USB and Bluetooth serial devices, its GNSS and attitude, and the alarm path back
 * to the phone come in later steps; the protocol has room for them.
 */

const { Link } = require("./lib/link");
const { parseLine, toDelta, subscribeMessage, VERSION } = require("./lib/protocol");
const pkg = require("./package.json");

const ENV = "POCKETSK_BRIDGE";

/** @param {object} app the Signal K plugin API; `deps.env` lets tests name a socket */
module.exports = function androidBridge(app, deps = {}) {
  const env = deps.env ?? process.env;
  let link = null;
  let options = {};
  let stats = { sensors: 0, dropped: 0 };
  let appVersion = "";

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
      },
    }),

    start(settings) {
      options = settings ?? {};
      stats = { sensors: 0, dropped: 0 };
      const socketPath = env[ENV];
      if (!socketPath) {
        app.setPluginStatus("Not running on Pocket SK: there is no phone to bridge");
        return;
      }
      link = new Link(socketPath, { onLine, onState });
      link.start();
      app.setPluginStatus("Connecting to the phone");
    },

    stop() {
      if (link) link.stop();
      link = null;
    },

    /** For tests and the status line. */
    statusNow: () => ({ connected: link?.connected ?? false, app: appVersion, ...stats }),
  };

  function onState(connected, detail) {
    if (connected) {
      link.send(subscribeMessage(options));
      app.setPluginStatus("Connected to the phone");
    } else {
      app.setPluginStatus(`Waiting for the phone (${detail})`);
    }
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
    stats.sensors++;
    app.handleMessage(plugin.id, toDelta(msg));
  }

  return plugin;
};
