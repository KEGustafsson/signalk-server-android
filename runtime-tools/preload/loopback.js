// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * The Dock's "LAN access" switch, off (docs/PLAN.md §11): nothing in the node processes the app
 * starts listens beyond this phone. NodeRuntime loads this file into every one of them
 * (`NODE_OPTIONS=--require`), and it does nothing unless the app started the process with
 * `POCKETSK_LOOPBACK=1`, which the supervisor sets for the server while the switch is off and
 * the installer always sets for its trial run.
 *
 * signalk-server has no setting for the address it listens on: its HTTP server, its Signal K
 * over TCP (8375) and its NMEA 0183 over TCP (10110) all call `listen(port)`, which is every
 * interface, and a plugin may open listeners of its own. So `net.Server.prototype.listen`, which
 * `http`, `https`, `tls` and `http2` servers inherit, is wrapped: a listener on a TCP port gets
 * 127.0.0.1 as its address unless it asked for a loopback one already. A Unix socket, a handle
 * or a file descriptor is left alone; none of them is reachable from the network.
 *
 * UDP is left alone too. A UDP socket the server opens is an input the owner set up (NMEA 0183
 * from a Wi-Fi multiplexer, the discovery of such devices), not a way into the server, and the
 * `.local` lookups of mdns-lookup.js send from one. The server's own mDNS advertisement is the
 * one exception: it would tell the tablets on board about a server they cannot reach, so
 * signalk-server's `./mdns` module is replaced, while this is on, by one that advertises nothing,
 * which is what that module itself returns with mDNS switched off in the server's settings.
 *
 * Plain CommonJS with no dependencies. Unlike the other preloads it fails closed: a process asked
 * to stay on the loopback that cannot be kept there says so and exits, rather than run on every
 * interface while the Dock says only this phone reaches it. Without the variable it does nothing,
 * so npm and every other node the app starts are never in its way.
 */

const Module = require("node:module");
const net = require("node:net");

const ENV = "POCKETSK_LOOPBACK";
const LOOPBACK = "127.0.0.1";
const INSTALLED = Symbol.for("pocketsk.loopback");
/** The file that requires signalk-server's mDNS advertisement, `require('./mdns')(app)`. */
const SIGNALK_INDEX = /[\\/]signalk-server[\\/]dist[\\/]index\.js$/;

/** Whether [host] is an address of this phone alone: `localhost`, 127.0.0.0/8 or ::1. */
function isLoopback(host) {
  if (typeof host !== "string") return false;
  const h = host.toLowerCase().replace(/^\[(.*)\]$/, "$1");
  if (h === "localhost" || h === "::1" || h === "0:0:0:0:0:0:0:1") return true;
  return net.isIPv4(h) && h.startsWith("127.");
}

/** [host] if it is loopback already, else [LOOPBACK]. */
function loopbackHost(host) {
  return isLoopback(host) ? host : LOOPBACK;
}

/** Whether [value] is what `listen` takes for a TCP port: a number, or a string of one. */
function isPort(value) {
  if (typeof value === "number") return true;
  return typeof value === "string" && /^\s*\d+\s*$/.test(value);
}

/**
 * `listen`'s arguments with a TCP listener's address moved to the loopback, the rest as given.
 * Every form Node takes: `(port, host, backlog, cb)` and its shorter forms, `(options, cb)`,
 * `(path, ...)`, `(handle, ...)`, `(cb)` and `()`, the last two a random port on every interface.
 */
function loopbackArgs(args) {
  const first = args[0];
  if (args.length === 0 || first === undefined || first === null || typeof first === "function") {
    // No port: Node picks one, on every interface. The callback, if any, stays last.
    return [0, LOOPBACK, ...args.slice(first === undefined || first === null ? 1 : 0)];
  }
  if (isPort(first)) {
    const second = args[1];
    if (typeof second === "string") return [first, loopbackHost(second), ...args.slice(2)];
    if (second === undefined || second === null) return [first, LOOPBACK, ...args.slice(2)];
    // A backlog or a callback: the address goes in before it.
    return [first, LOOPBACK, ...args.slice(1)];
  }
  if (typeof first === "object") {
    // A handle, or an options object for one or a descriptor: not on the network.
    if (first._handle !== undefined || first.handle != null || first.fd != null) return args;
    // A Unix socket, unless a port is given too: Node takes the port over the path then.
    if (first.path != null && first.port == null) return args;
    if (isLoopback(first.host)) return args;
    // ipv6Only is about "::"; beside an IPv4 address it has nothing to mean.
    const options = { ...first, host: LOOPBACK };
    delete options.ipv6Only;
    return [options, ...args.slice(1)];
  }
  // A string that is no port is a Unix socket's path.
  return args;
}

/** What replaces signalk-server's `./mdns`: the module's own answer with mDNS off, nothing. */
function noAdvertisement() {
  return undefined;
}

/** Wraps `net.Server.prototype.listen` and stops the server's mDNS advertisement, once. */
function install() {
  if (net.Server.prototype[INSTALLED]) return false;
  const listen = net.Server.prototype.listen;
  net.Server.prototype.listen = function (...args) {
    return listen.apply(this, loopbackArgs(args));
  };
  net.Server.prototype[INSTALLED] = true;
  const load = Module._load;
  Module._load = function (request, parent, ...rest) {
    if (request === "./mdns" && parent && typeof parent.filename === "string" && SIGNALK_INDEX.test(parent.filename)) {
      return noAdvertisement;
    }
    return load.call(this, request, parent, ...rest);
  };
  return true;
}

if (process.env[ENV] === "1") {
  try {
    install();
  } catch (e) {
    process.stderr.write(`pocketsk: could not keep this process on the loopback (${e}); not starting\n`);
    process.exit(78);
  }
}

module.exports = { isLoopback, loopbackHost, loopbackArgs, noAdvertisement, install, ENV, LOOPBACK };
