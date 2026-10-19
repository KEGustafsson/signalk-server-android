// SPDX-License-Identifier: EUPL-1.2
"use strict";

const net = require("node:net");
const { MAX_LINE } = require("./protocol");

const FIRST_DELAY_MS = 1000;
const MAX_DELAY_MS = 15000;

/**
 * The plugin's end of the bridge socket: connects to the app's Unix socket, splits what arrives
 * into lines, and connects again when the socket goes (the app restarting the bridge, the
 * server outliving a service restart), waiting 1 s doubling to 15 s like the app's own Backoff.
 * A line longer than MAX_LINE closes the connection: a peer that sends one is broken, and
 * keeping its bytes would only grow the buffer.
 *
 * Callbacks: onLine(line) for every complete line, onState(connected, detail) when the
 * connection comes or goes, so the plugin can say so in its status.
 */
class Link {
  constructor(socketPath, { onLine, onState = () => {}, firstDelayMs = FIRST_DELAY_MS, maxDelayMs = MAX_DELAY_MS } = {}) {
    this.socketPath = socketPath;
    this.onLine = onLine;
    this.onState = onState;
    this.firstDelayMs = firstDelayMs;
    this.maxDelayMs = maxDelayMs;
    this.delayMs = firstDelayMs;
    this.socket = null;
    this.timer = null;
    this.stopped = true;
    this.connected = false;
  }

  start() {
    this.stopped = false;
    this.connect();
  }

  stop() {
    this.stopped = true;
    clearTimeout(this.timer);
    this.timer = null;
    if (this.socket) this.socket.destroy();
    this.socket = null;
    this.connected = false;
  }

  /** Sends `msg` as one JSON line; false when there is no connection to send it on. */
  send(msg) {
    if (!this.connected || !this.socket) return false;
    this.socket.write(JSON.stringify(msg) + "\n");
    return true;
  }

  connect() {
    if (this.stopped) return;
    let buffer = "";
    const socket = net.connect({ path: this.socketPath });
    this.socket = socket;
    socket.setEncoding("utf8");
    socket.on("connect", () => {
      this.connected = true;
      this.delayMs = this.firstDelayMs;
      this.onState(true, "connected");
    });
    socket.on("data", (chunk) => {
      buffer += chunk;
      let nl;
      while ((nl = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, nl);
        buffer = buffer.slice(nl + 1);
        if (line.length > 0) this.onLine(line);
        if (this.socket !== socket) return;      // onLine stopped or replaced the link
      }
      if (buffer.length > MAX_LINE) socket.destroy(new Error("line too long"));
    });
    socket.on("error", (err) => {
      socket.lastError = err;
    });
    socket.on("close", () => {
      if (this.socket !== socket) return;
      const was = this.connected;
      this.connected = false;
      this.socket = null;
      if (this.stopped) return;
      const detail = socket.lastError ? socket.lastError.code || socket.lastError.message : "closed";
      if (was) this.onState(false, detail);
      else this.onState(false, `cannot connect: ${detail}`);
      this.schedule();
    });
  }

  schedule() {
    const delay = this.delayMs;
    this.delayMs = Math.min(this.delayMs * 2, this.maxDelayMs);
    this.timer = setTimeout(() => {
      this.timer = null;
      this.connect();
    }, delay);
  }
}

module.exports = { Link, FIRST_DELAY_MS, MAX_DELAY_MS };
