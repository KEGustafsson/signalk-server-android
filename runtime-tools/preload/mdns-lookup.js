// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * `.local` names for Node on Android. NodeRuntime loads this file into every node process the app
 * starts (`NODE_OPTIONS=--require`), before signalk-server or npm runs a line.
 *
 * On a Raspberry Pi, `getaddrinfo` resolves `boat.local` through Avahi (nss-mdns). Android has no
 * such service for native code: Node's lookup of a `.local` name goes to the network's unicast DNS
 * and fails with ENOTFOUND. Signal K connects by those names all the time. A server found by its
 * mDNS discovery is saved as a Signal K connection to `<host>.local.`, and a name typed into a
 * data connection is often one too. Seen on a phone: `getaddrinfo ENOTFOUND
 * jetson-xavier-nx-devkit.local.`, while the same server by its address worked.
 *
 * So `dns.lookup`, which `net`, `http`, `ws` and `fetch` all resolve through, is wrapped. A name
 * that ends in `.local` is asked for over mDNS first (RFC 6762): one query for its A and AAAA
 * records, sent to 224.0.0.251:5353 from a port of its own on every IPv4 interface, so the
 * responder answers it directly (§6.7, a "legacy unicast" query) and nothing needs port 5353,
 * which the server's own mDNS advertising holds. What it finds is kept for the answer's TTL, at
 * most [MAX_TTL_S]. A name nobody answers for, and every other name, goes to Node's own lookup
 * exactly as before. IPv6 link-local answers are skipped: they need an interface Node is not told.
 *
 * Plain CommonJS with no dependencies, and it never throws while loading: a preload that fails
 * would stop every node process, npm and the server among them.
 */

const crypto = require("node:crypto");
const dgram = require("node:dgram");
const dns = require("node:dns");
const net = require("node:net");
const os = require("node:os");
const util = require("node:util");

const MDNS_ADDRESS = "224.0.0.251";
const MDNS_PORT = 5353;
const TYPE_A = 1;
const TYPE_AAAA = 28;
const CLASS_IN = 1;
/** The question's "unicast response" bit, the top bit of its class (RFC 6762 §5.4). */
const QU = 0x8000;
/** How long one query waits, and how many are sent, before a name is left to Node's lookup. */
const TIMEOUT_MS = 1000;
const TRIES = 2;
/**
 * How long the first answer waits for more. A responder may answer the A and AAAA questions in
 * separate messages (RFC 6762 §6), and a host with no IPv6 sends no AAAA at all, so a query ends
 * when both families are in or this long after its first answer, whichever comes first.
 */
const COLLECT_MS = 150;
/** The longest an answer is kept, whatever its TTL says. */
const MAX_TTL_S = 60;
const INSTALLED = Symbol.for("pocketsk.mdnsLookup");

/** The `.local` name [hostname] asks for, lower case and without its final dot, or null. */
function localName(hostname) {
  if (typeof hostname !== "string") return null;
  const name = hostname.toLowerCase().replace(/\.$/, "");
  if (!name.endsWith(".local") || net.isIP(name) !== 0 || name.length > 253) return null;
  const labels = name.split(".");
  if (labels.length < 2 || labels.some((l) => l.length === 0 || l.length > 63)) return null;
  return name;
}

function encodeName(name) {
  const parts = [];
  for (const label of name.split(".")) {
    const bytes = Buffer.from(label, "utf8");
    parts.push(Buffer.from([bytes.length]), bytes);
  }
  parts.push(Buffer.from([0]));
  return Buffer.concat(parts);
}

/** A query for [name]'s A and AAAA records, unicast answers asked for, with message id [id]. */
function query(name, id) {
  const header = Buffer.alloc(12);
  header.writeUInt16BE(id, 0);
  header.writeUInt16BE(2, 4);
  const encoded = encodeName(name);
  const question = (type) => {
    const tail = Buffer.alloc(4);
    tail.writeUInt16BE(type, 0);
    tail.writeUInt16BE(CLASS_IN | QU, 2);
    return Buffer.concat([encoded, tail]);
  };
  return Buffer.concat([header, question(TYPE_A), question(TYPE_AAAA)]);
}

/** The name at [offset] in [buf], compression pointers followed: `{ name, next }`. Throws on a malformed one. */
function readName(buf, offset) {
  const labels = [];
  let at = offset;
  let next = -1;
  for (let jumps = 0; ; ) {
    if (at >= buf.length) throw new RangeError("name runs past the message");
    const len = buf[at];
    if (len === 0) {
      if (next < 0) next = at + 1;
      break;
    }
    if ((len & 0xc0) === 0xc0) {
      if (at + 1 >= buf.length || ++jumps > 32) throw new RangeError("bad compression pointer");
      if (next < 0) next = at + 2;
      at = ((len & 0x3f) << 8) | buf[at + 1];
      continue;
    }
    if (len > 63 || at + 1 + len > buf.length) throw new RangeError("bad label");
    labels.push(buf.toString("utf8", at + 1, at + 1 + len).toLowerCase());
    at += 1 + len;
  }
  return { name: labels.join("."), next };
}

function ipv6(bytes) {
  const groups = [];
  for (let i = 0; i < 16; i += 2) groups.push(((bytes[i] << 8) | bytes[i + 1]).toString(16));
  return groups.join(":");
}

/**
 * The A and AAAA records for [name] in the mDNS response [buf], answers and additional records
 * alike: `[{ address, family, ttl }]`. What follows a malformed record is ignored, and a query or
 * a message that is not DNS at all gives nothing.
 */
function answers(buf, name) {
  const found = [];
  try {
    if (buf.length < 12 || (buf.readUInt16BE(2) & 0x8000) === 0) return found;
    const questions = buf.readUInt16BE(4);
    const records = buf.readUInt16BE(6) + buf.readUInt16BE(8) + buf.readUInt16BE(10);
    let at = 12;
    for (let i = 0; i < questions; i++) at = readName(buf, at).next + 4;
    for (let i = 0; i < records; i++) {
      const owner = readName(buf, at);
      at = owner.next;
      if (at + 10 > buf.length) break;
      const type = buf.readUInt16BE(at);
      const cls = buf.readUInt16BE(at + 2) & 0x7fff; // the top bit is mDNS's cache-flush flag
      const ttl = buf.readUInt32BE(at + 4);
      const length = buf.readUInt16BE(at + 8);
      const data = at + 10;
      at = data + length;
      if (at > buf.length) break;
      if (owner.name !== name || cls !== CLASS_IN) continue;
      if (type === TYPE_A && length === 4) {
        found.push({ address: Array.from(buf.subarray(data, data + 4)).join("."), family: 4, ttl });
      } else if (type === TYPE_AAAA && length === 16) {
        const address = ipv6(buf.subarray(data, data + 16));
        // fe80::/10 means nothing without the interface it was heard on.
        if ((buf[data] !== 0xfe || (buf[data + 1] & 0xc0) !== 0x80)) found.push({ address, family: 6, ttl });
      }
    }
  } catch {
    // A truncated or garbled message: keep what came before it.
  }
  return found;
}

/** This phone's IPv4 addresses, one per interface the query should go out on. */
function ipv4Interfaces() {
  try {
    const out = [];
    for (const infos of Object.values(os.networkInterfaces())) {
      for (const info of infos ?? []) {
        if (!info.internal && (info.family === "IPv4" || info.family === 4)) out.push(info.address);
      }
    }
    return out;
  } catch {
    // Some Android releases refuse the interface list; the system's choice of interface remains.
    return [];
  }
}

/**
 * Asks the network for [name]: the records found, collected for [collectMs] after the first
 * answer ([COLLECT_MS]), or none after [tries] queries [timeoutMs] apart. Never rejects. The
 * socket, the interface list and where the query goes are injectable for the tests.
 */
function resolve(name, {
  timeoutMs = TIMEOUT_MS,
  tries = TRIES,
  collectMs = COLLECT_MS,
  createSocket = () => dgram.createSocket("udp4"),
  interfaces = ipv4Interfaces,
  address = MDNS_ADDRESS,
  port = MDNS_PORT,
} = {}) {
  return new Promise((done) => {
    let socket;
    try {
      socket = createSocket();
    } catch {
      done([]);
      return;
    }
    // A random id, as RFC 6762 §6.7 asks of a one-shot query: an answer must echo it to be taken.
    const id = crypto.randomInt(0x10000);
    const packet = query(name, id);
    let finished = false;
    let timer = null;
    let sent = 0;
    const found = [];
    const finish = (found) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      try {
        socket.close();
      } catch {
        // Already closed.
      }
      done(found);
    };
    const send = () => {
      sent++;
      const via = interfaces();
      const ignore = () => {}; // a failed send on one interface (no route, EPERM) is not the end
      try {
        socket.setMulticastTTL(255); // RFC 6762 §11
      } catch {
        // Not supported here: the default TTL reaches the link as well.
      }
      if (via.length === 0) {
        socket.send(packet, port, address, ignore);
      } else {
        for (const local of via) {
          try {
            socket.setMulticastInterface(local);
            socket.send(packet, port, address, ignore);
          } catch {
            // That interface went away between the list and the send.
          }
        }
      }
      timer = setTimeout(() => (sent < tries ? send() : finish([])), timeoutMs);
    };
    socket.on("error", () => finish([]));
    socket.on("message", (msg) => {
      if (finished || msg.length < 2 || msg.readUInt16BE(0) !== id) return;
      const fresh = answers(msg, name).filter((r) => !found.some((f) => f.family === r.family && f.address === r.address));
      if (fresh.length === 0) return;
      const first = found.length === 0;
      found.push(...fresh);
      if (found.some((r) => r.family === 4) && found.some((r) => r.family === 6)) {
        finish(found);
      } else if (first) {
        // No more queries now: the rest of this answer may still be on its way.
        clearTimeout(timer);
        timer = setTimeout(() => finish(found), collectMs);
      }
    });
    try {
      socket.bind(0, () => {
        if (!finished) send();
      });
    } catch {
      finish([]);
    }
  });
}

/**
 * The order `dns.lookup` gives its results in for [options], as Node decides it: `order` first,
 * then the older `verbatim`, then the process's default ([defaultOrder], `verbatim` since Node 22
 * unless `--dns-result-order` or `dns.setDefaultResultOrder` said otherwise).
 */
function resultOrder(options, defaultOrder = defaultResultOrder()) {
  if (options.order === "ipv4first" || options.order === "ipv6first" || options.order === "verbatim") return options.order;
  if (options.verbatim === true) return "verbatim";
  if (options.verbatim === false) return "ipv4first";
  return defaultOrder;
}

function defaultResultOrder() {
  return typeof dns.getDefaultResultOrder === "function" ? dns.getDefaultResultOrder() : "verbatim";
}

/**
 * The records in [found] that [options] asks for (`family` 0, 4 or 6), in the order it asks for
 * ([resultOrder]): `verbatim` keeps the order the responder gave them in.
 */
function pick(found, options, defaultOrder = defaultResultOrder()) {
  const family = options.family === "IPv4" ? 4 : options.family === "IPv6" ? 6 : Number(options.family) || 0;
  const order = resultOrder(options, defaultOrder);
  const list = found.filter((r) => family === 0 || r.family === family);
  // Array.prototype.sort is stable, so each family keeps the responder's order.
  if (order === "ipv4first") list.sort((a, b) => a.family - b.family);
  else if (order === "ipv6first") list.sort((a, b) => b.family - a.family);
  return list.map(({ address, family }) => ({ address, family }));
}

/**
 * A `dns.lookup` that resolves `.local` names with [resolveName] and passes everything else, and
 * every `.local` name nobody answered for, to [original]. Answers are kept for their TTL, and
 * concurrent lookups of one name share a query. [now] is the clock, injectable for the tests.
 */
function makeLookup(original, { resolveName = resolve, now = Date.now } = {}) {
  const cache = new Map();
  const asking = new Map();

  const records = (name) => {
    const hit = cache.get(name);
    if (hit && hit.until > now()) return Promise.resolve(hit.found);
    let pending = asking.get(name);
    if (!pending) {
      pending = resolveName(name).then((found) => {
        asking.delete(name);
        if (found.length > 0) {
          const ttl = Math.min(MAX_TTL_S, ...found.map((r) => r.ttl));
          if (ttl > 0) cache.set(name, { found, until: now() + ttl * 1000 });
        }
        return found;
      });
      asking.set(name, pending);
    }
    return pending;
  };

  function lookup(hostname, options, callback) {
    if (typeof options === "function") {
      callback = options;
      options = {};
    }
    const name = localName(hostname);
    if (name === null || typeof callback !== "function") return original.call(dns, hostname, options, callback);
    const opts = typeof options === "number" ? { family: options } : (options ?? {});
    records(name).then((found) => {
      const list = pick(found, opts);
      // Out of the promise, so a callback that throws does so as it would from Node's own lookup.
      process.nextTick(() => {
        if (list.length === 0) original.call(dns, hostname, options, callback);
        else if (opts.all) callback(null, list);
        else callback(null, list[0].address, list[0].family);
      });
    });
  }
  return lookup;
}

/** The promise form of [lookup], as `dns.promises.lookup` and `util.promisify(dns.lookup)` give it. */
function promiseLookup(lookup, original) {
  return function (hostname, options) {
    if (localName(hostname) === null) return original.call(dns.promises, hostname, options);
    return new Promise((ok, fail) => {
      lookup(hostname, options ?? {}, (err, address, family) => {
        if (err) fail(err);
        else ok(Array.isArray(address) ? address : { address, family });
      });
    });
  };
}

/** Puts the wrapped lookup in place of `dns.lookup` and `dns.promises.lookup`, once. */
function install() {
  if (dns[INSTALLED]) return false;
  const lookup = makeLookup(dns.lookup);
  const promises = promiseLookup(lookup, dns.promises.lookup);
  lookup[util.promisify.custom] = promises;
  dns.lookup = lookup;
  dns.promises.lookup = promises;
  dns[INSTALLED] = true;
  return true;
}

try {
  install();
} catch {
  // Never in the way of the process it was loaded into: without it, `.local` names fail as before.
}

module.exports = { localName, query, readName, answers, ipv4Interfaces, resolve, resultOrder, pick, makeLookup, promiseLookup, install, MDNS_ADDRESS, MDNS_PORT, MAX_TTL_S };
