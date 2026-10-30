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
 * interface, and a plugin may open listeners of its own. `listen` takes a dozen forms of
 * arguments (a port, a host, an options object, an options object inside `handle`, ...), and
 * reading them the way Node does proved a parser to keep in step with Node's: each form missed
 * was a listener left open. So the address is moved where Node has already read them all:
 * `net.Server.prototype._listen2`, which every TCP listener of an `http`, `https`, `tls`, `http2`
 * or plain `net` server goes through with the address it is about to bind (a host name already
 * looked up), and which Node keeps under that name for wrappers like this one ("to avoid breaking
 * code that wraps this method", lib/net.js, Node 22 and 24). A TCP listener whose address is not
 * a loopback one gets 127.0.0.1. A Unix socket, a file descriptor or a handle already bound is
 * left alone: none of them is reached through an address the server binds here.
 *
 * UDP is left alone too. A UDP socket the server opens is an input the owner set up (NMEA 0183
 * from a Wi-Fi multiplexer, the discovery of such devices), not a way into the server, and the
 * `.local` lookups of mdns-lookup.js send from one. The server's own mDNS advertisement is the
 * one exception: it would tell the tablets on board about a server they cannot reach, so
 * signalk-server's `./mdns` module is replaced, while this is on, by one that advertises nothing,
 * which is what that module itself returns with mDNS switched off in the server's settings.
 *
 * Plain CommonJS with no dependencies. Unlike the other preloads it fails closed: a process asked
 * to stay on the loopback that cannot be kept there (a Node without `_listen2`) says so and
 * exits, rather than run on every interface while the Dock says only this phone reaches it.
 * Without the variable it does nothing, so npm and every other node the app starts are never in
 * its way.
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

/**
 * The address and family `_listen2(address, port, addressType, backlog, fd, flags)` binds with,
 * moved to the loopback when it would bind a TCP port beyond it. Node calls it with a port from 0
 * up for a TCP listener (`address` null for every interface, else the address looked up), with
 * port -1 for a Unix socket (`address` its path) and a handle given, and with a number in `fd`
 * for a descriptor; [hasHandle] is a server whose handle is already bound (one passed in, or a
 * cluster primary's), which `_listen2` does not bind again.
 */
function loopbackAddress(address, port, addressType, fd, hasHandle) {
  const tcp = !hasHandle && typeof port === "number" && port >= 0 && typeof fd !== "number";
  if (!tcp || isLoopback(address)) return { address, addressType };
  return { address: LOOPBACK, addressType: 4 };
}

/** What replaces signalk-server's `./mdns`: the module's own answer with mDNS off, nothing. */
function noAdvertisement() {
  return undefined;
}

/** Wraps `net.Server.prototype._listen2` and stops the server's mDNS advertisement, once. */
function install() {
  const proto = net.Server.prototype;
  if (proto[INSTALLED]) return false;
  const listen2 = proto._listen2;
  if (typeof listen2 !== "function") throw new Error("this Node has no net.Server.prototype._listen2");
  proto._listen2 = function (address, port, addressType, backlog, fd, flags) {
    const to = loopbackAddress(address, port, addressType, fd, Boolean(this._handle));
    return listen2.call(this, to.address, port, to.addressType, backlog, fd, flags);
  };
  proto[INSTALLED] = true;
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

module.exports = { isLoopback, loopbackAddress, noAdvertisement, install, ENV, LOOPBACK };
