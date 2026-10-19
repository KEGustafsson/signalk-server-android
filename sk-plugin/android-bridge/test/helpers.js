// SPDX-License-Identifier: EUPL-1.2
"use strict";

const fs = require("node:fs");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");

let count = 0;

/**
 * Somewhere for a test's socket. Node serves a Unix socket at a file path, except on Windows,
 * where `net` speaks named pipes only: the plugin runs on Android, but its tests run wherever
 * the Signal K plugin CI runs them.
 */
function socketPath() {
  if (process.platform === "win32") return `\\\\?\\pipe\\android-bridge-test-${process.pid}-${++count}`;
  return path.join(fs.mkdtempSync(path.join(os.tmpdir(), "bridge-")), "bridge.sock");
}

/** Lets a socket path be listened on again: a file is removed, a pipe goes with its server. */
function freeSocketPath(p) {
  if (process.platform !== "win32") fs.rmSync(p, { force: true });
}

/**
 * A server at `p` whose close() also ends every connection it took, so a test that fails half
 * way leaves nothing open that would keep the test run alive.
 */
function listen(p, onConnection = () => {}) {
  const connections = new Set();
  return new Promise((resolve, reject) => {
    const server = net.createServer((c) => {
      connections.add(c);
      c.on("close", () => connections.delete(c));
      onConnection(c);
    });
    server.once("error", reject);
    server.listen(p, () => {
      server.closeAll = () => {
        for (const c of connections) c.destroy();
        server.close();
      };
      resolve(server);
    });
  });
}

function until(check, ms = 3000) {
  return new Promise((resolve, reject) => {
    const end = Date.now() + ms;
    (function poll() {
      if (check()) return resolve();
      if (Date.now() > end) return reject(new Error("timed out"));
      setTimeout(poll, 10);
    })();
  });
}

module.exports = { socketPath, freeSocketPath, listen, until };
