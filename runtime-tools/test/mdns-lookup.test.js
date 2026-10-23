// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const dgram = require("node:dgram");
const dns = require("node:dns");
const util = require("node:util");
const { EventEmitter } = require("node:events");

const m = require("../preload/mdns-lookup.js");

/** A DNS name in wire form. */
const wire = (name) => Buffer.concat([...name.split(".").map((l) => Buffer.concat([Buffer.from([l.length]), Buffer.from(l)])), Buffer.from([0])]);

/** A resource record: [owner] is a wire name or a pointer, [cls] may carry the cache-flush bit. */
function record(owner, type, data, { cls = 0x8001, ttl = 120 } = {}) {
  const fixed = Buffer.alloc(10);
  fixed.writeUInt16BE(type, 0);
  fixed.writeUInt16BE(cls, 2);
  fixed.writeUInt32BE(ttl, 4);
  fixed.writeUInt16BE(data.length, 8);
  return Buffer.concat([owner, fixed, data]);
}

/** A response: the question repeated for [name] (as a legacy unicast answer does), then [answers] and [extra]. */
function response(name, answers, extra = [], { flags = 0x8400 } = {}) {
  const header = Buffer.alloc(12);
  header.writeUInt16BE(flags, 2);
  header.writeUInt16BE(1, 4);
  header.writeUInt16BE(answers.length, 6);
  header.writeUInt16BE(extra.length, 10);
  const question = Buffer.concat([wire(name), Buffer.from([0, 1, 0, 1])]);
  return Buffer.concat([header, question, ...answers, ...extra]);
}

const POINTER_TO_QUESTION = Buffer.from([0xc0, 12]);
const a = (...octets) => Buffer.from(octets);

test("only .local names are ours", () => {
  assert.equal(m.localName("Boat.Local."), "boat.local");
  assert.equal(m.localName("jetson-xavier-nx-devkit.local."), "jetson-xavier-nx-devkit.local");
  assert.equal(m.localName("a.b.local"), "a.b.local");
  for (const other of ["local", ".local", "boat..local", "boat.local.lan", "example.com", "192.168.1.9", "::1", "", null, 42]) {
    assert.equal(m.localName(other), null, String(other));
  }
  assert.equal(m.localName(`${"x".repeat(64)}.local`), null, "a label past 63");
  assert.equal(m.localName(`${"x.".repeat(124)}local`).length, 253, "253 is the limit");
  assert.equal(m.localName(`${"x.".repeat(125)}local`), null, "a name past 253");
});

test("the query asks for A and AAAA with unicast answers", () => {
  const q = m.query("boat.local", 0x1234);
  assert.equal(q.readUInt16BE(0), 0x1234);
  assert.equal(q.readUInt16BE(2), 0, "a query, not a response");
  assert.equal(q.readUInt16BE(4), 2);
  let at = 12;
  for (const type of [1, 28]) {
    const { name, next } = m.readName(q, at);
    assert.equal(name, "boat.local");
    assert.equal(q.readUInt16BE(next), type);
    assert.equal(q.readUInt16BE(next + 2), 0x8001, "IN with the QU bit");
    at = next + 4;
  }
  assert.equal(at, q.length);
});

test("A and AAAA records are read from answers and additional records", () => {
  const msg = response(
    "boat.local",
    [record(POINTER_TO_QUESTION, 1, a(192, 168, 1, 9), { ttl: 10 })],
    [
      record(wire("Boat.local"), 28, a(0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7)),
      record(wire("boat.local"), 28, a(0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)),
      record(wire("other.local"), 1, a(10, 0, 0, 1)),
      record(wire("boat.local"), 16, Buffer.from("txt")),
      record(wire("boat.local"), 1, a(10, 0, 0, 2), { cls: 3 }),
    ],
  );
  assert.deepEqual(m.answers(msg, "boat.local"), [
    { address: "192.168.1.9", family: 4, ttl: 10 },
    { address: "fd00:0:0:0:0:0:0:7", family: 6, ttl: 120 },
  ]);
});

test("queries, garbage and cut messages give what they can and never throw", () => {
  const good = response("boat.local", [record(POINTER_TO_QUESTION, 1, a(10, 1, 2, 3))]);
  assert.deepEqual(m.answers(response("boat.local", [record(POINTER_TO_QUESTION, 1, a(10, 1, 2, 3))], [], { flags: 0 }), "boat.local"), []);
  assert.deepEqual(m.answers(Buffer.from("not dns"), "boat.local"), []);
  assert.deepEqual(m.answers(good.subarray(0, good.length - 2), "boat.local"), []);
  const loop = Buffer.from(good);
  loop[12] = 0xc0;
  loop[13] = 12; // the question's name points at itself
  assert.deepEqual(m.answers(loop, "boat.local"), []);
  assert.throws(() => m.readName(Buffer.from([5, 0x61]), 0), RangeError);
  const two = response("boat.local", [record(POINTER_TO_QUESTION, 1, a(10, 1, 2, 3)), record(POINTER_TO_QUESTION, 1, a(10, 1, 2, 4))]);
  assert.deepEqual(m.answers(two.subarray(0, two.length - 1), "boat.local").map((r) => r.address), ["10.1.2.3"]);
});

test("pick filters by family", () => {
  const found = [
    { address: "fd00::7", family: 6, ttl: 1 },
    { address: "10.0.0.2", family: 4, ttl: 1 },
  ];
  assert.deepEqual(m.pick(found, { family: 6 }), [{ address: "fd00::7", family: 6 }]);
  assert.deepEqual(m.pick(found, { family: "IPv4" }), [{ address: "10.0.0.2", family: 4 }]);
  assert.deepEqual(m.pick(found, { family: "IPv6" }), [{ address: "fd00::7", family: 6 }]);
});

test("pick reads null as unset, and V4MAPPED as getaddrinfo does", () => {
  const v4only = [{ address: "10.0.0.2", family: 4, ttl: 1 }];
  const both = [{ address: "fd00::7", family: 6, ttl: 1 }, ...v4only];
  assert.deepEqual(m.pick(both, { family: null, order: null, verbatim: null }, "ipv4first"), [
    { address: "10.0.0.2", family: 4 },
    { address: "fd00::7", family: 6 },
  ]);
  assert.deepEqual(m.pick(v4only, { family: 6 }), [], "no AAAA and no V4MAPPED: nothing for IPv6");
  assert.deepEqual(m.pick(v4only, { family: 6, hints: dns.V4MAPPED }), [{ address: "::ffff:10.0.0.2", family: 6 }]);
  assert.deepEqual(m.pick(both, { family: 6, hints: dns.V4MAPPED }), [{ address: "fd00::7", family: 6 }], "AAAA found: no mapping");
  assert.deepEqual(m.pick(both, { family: 6, hints: dns.V4MAPPED | dns.ALL }), [
    { address: "fd00::7", family: 6 },
    { address: "::ffff:10.0.0.2", family: 6 },
  ]);
  assert.deepEqual(m.pick(v4only, { family: 4, hints: dns.V4MAPPED }), [{ address: "10.0.0.2", family: 4 }], "V4MAPPED is for IPv6 lookups");
  assert.deepEqual(m.pick(both, { hints: dns.ADDRCONFIG }, "verbatim").map((r) => r.address), ["fd00::7", "10.0.0.2"], "ADDRCONFIG filters nothing");
});

test("a .local lookup with null fields or net's hints stays on mDNS", async () => {
  const { original, calls } = fakeOriginal();
  const lookup = m.makeLookup(original, { resolveName: async () => [{ address: "10.0.0.2", family: 4, ttl: 5 }] });
  assert.deepEqual(await lookupOnce(lookup, "a.local", { family: null, all: null, verbatim: null, order: null }), { address: "10.0.0.2", family: 4 });
  assert.deepEqual(await lookupOnce(lookup, "a.local", { hints: dns.ADDRCONFIG, all: true }), { address: [{ address: "10.0.0.2", family: 4 }], family: undefined });
  assert.deepEqual(calls, [], "Node's lookup never asked");
});

test("pick gives the order dns.lookup would: order, then verbatim, then the default", () => {
  const found = [
    { address: "fd00::7", family: 6, ttl: 1 },
    { address: "10.0.0.2", family: 4, ttl: 1 },
    { address: "fd00::8", family: 6, ttl: 1 },
    { address: "10.0.0.3", family: 4, ttl: 1 },
  ];
  const addresses = (options, defaultOrder) => m.pick(found, options, defaultOrder).map((r) => r.address);
  const asGiven = ["fd00::7", "10.0.0.2", "fd00::8", "10.0.0.3"];
  const v4First = ["10.0.0.2", "10.0.0.3", "fd00::7", "fd00::8"];
  const v6First = ["fd00::7", "fd00::8", "10.0.0.2", "10.0.0.3"];
  assert.deepEqual(addresses({}, "verbatim"), asGiven);
  assert.deepEqual(addresses({}, "ipv4first"), v4First);
  assert.deepEqual(addresses({}, "ipv6first"), v6First);
  assert.deepEqual(addresses({ order: "ipv4first" }, "verbatim"), v4First);
  assert.deepEqual(addresses({ order: "ipv6first" }, "ipv4first"), v6First);
  assert.deepEqual(addresses({ order: "verbatim", verbatim: false }, "ipv4first"), asGiven, "order wins over verbatim");
  assert.deepEqual(addresses({ verbatim: true }, "ipv4first"), asGiven);
  assert.deepEqual(addresses({ verbatim: false }, "verbatim"), v4First);
  assert.equal(m.resultOrder({}), dns.getDefaultResultOrder(), "the process's default when none is given");
});

/** A responder on the loopback that answers [name] with [ip] to whoever asked, as an mDNS responder answers a one-shot query. */
async function responder(name, ip, { silent = 0 } = {}) {
  const socket = dgram.createSocket("udp4");
  let heard = 0;
  socket.on("message", (msg, from) => {
    heard++;
    assert.equal(m.readName(msg, 12).name, name);
    if (heard <= silent) return;
    const reply = response(name, [record(POINTER_TO_QUESTION, 1, Buffer.from(ip.split(".").map(Number)), { ttl: 10 })]);
    reply.writeUInt16BE(msg.readUInt16BE(0), 0);
    socket.send(reply, from.port, from.address);
  });
  await new Promise((ok) => socket.bind(0, "127.0.0.1", ok));
  return { port: socket.address().port, heard: () => heard, close: () => socket.close() };
}

test("resolve asks over UDP and takes the answer", async () => {
  const r = await responder("boat.local", "192.168.1.9");
  try {
    const found = await m.resolve("boat.local", { address: "127.0.0.1", port: r.port, interfaces: () => [] });
    assert.deepEqual(found, [{ address: "192.168.1.9", family: 4, ttl: 10 }]);
  } finally {
    r.close();
  }
});

test("resolve asks again when the first query goes unanswered", async () => {
  const r = await responder("boat.local", "192.168.1.9", { silent: 1 });
  try {
    const found = await m.resolve("boat.local", { address: "127.0.0.1", port: r.port, interfaces: () => [], timeoutMs: 100 });
    assert.equal(found[0].address, "192.168.1.9");
    assert.equal(r.heard(), 2);
  } finally {
    r.close();
  }
});

/** A socket that records what is done to it, for the paths a real network does not give on demand. */
class FakeSocket extends EventEmitter {
  constructor({ failInterface } = {}) {
    super();
    this.sent = [];
    this.via = null;
    this.closed = false;
    this.failInterface = failInterface;
  }
  bind(_port, cb) { setImmediate(cb); }
  setMulticastTTL(ttl) { this.ttl = ttl; }
  setMulticastInterface(address) {
    if (address === this.failInterface) throw new Error("EADDRNOTAVAIL");
    this.via = address;
  }
  send(packet, port, address) { this.sent.push({ via: this.via, port, address, packet }); }
  close() { this.closed = true; }
}

test("resolve gives up after its tries, having asked on every interface", async () => {
  const socket = new FakeSocket({ failInterface: "10.9.9.9" });
  const found = await m.resolve("boat.local", { createSocket: () => socket, interfaces: () => ["192.168.1.20", "10.9.9.9", "172.16.0.5"], timeoutMs: 20, tries: 2 });
  assert.deepEqual(found, []);
  assert.deepEqual(socket.sent.map((s) => s.via), ["192.168.1.20", "172.16.0.5", "192.168.1.20", "172.16.0.5"]);
  assert.ok(socket.sent.every((s) => s.address === m.MDNS_ADDRESS && s.port === m.MDNS_PORT));
  assert.equal(socket.ttl, 255);
  assert.ok(socket.closed);
});

test("resolve ends at a socket error, and when there is no socket at all", async () => {
  const socket = new FakeSocket();
  const pending = m.resolve("boat.local", { createSocket: () => socket, interfaces: () => [], timeoutMs: 5000 });
  setImmediate(() => socket.emit("error", new Error("EPERM")));
  assert.deepEqual(await pending, []);
  assert.ok(socket.closed);
  assert.deepEqual(await m.resolve("boat.local", { createSocket: () => { throw new Error("no sockets"); } }), []);
});

test("resolve takes only an answer that echoes its query's id", async () => {
  const socket = new FakeSocket();
  const pending = m.resolve("boat.local", { createSocket: () => socket, interfaces: () => [], timeoutMs: 5000 });
  await new Promise((ok) => setImmediate(ok));
  const id = socket.sent[0].packet.readUInt16BE(0);
  const answer = (withId, last) => {
    const msg = response("boat.local", [record(POINTER_TO_QUESTION, 1, a(10, 0, 0, last))]);
    msg.writeUInt16BE(withId, 0);
    return msg;
  };
  socket.emit("message", answer(id ^ 1, 1));
  socket.emit("message", Buffer.from([7]));
  socket.emit("message", answer(id, 2));
  assert.deepEqual((await pending).map((r) => r.address), ["10.0.0.2"]);
});

/** An answer to the query [socket] sent, echoing its id, with the records [rr]. */
function answerTo(socket, ...rr) {
  const msg = response("boat.local", rr);
  msg.writeUInt16BE(socket.sent[0].packet.readUInt16BE(0), 0);
  return msg;
}

const AAAA = a(0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7);

test("resolve keeps collecting when A and AAAA come in separate answers", async () => {
  const socket = new FakeSocket();
  const pending = m.resolve("boat.local", { createSocket: () => socket, interfaces: () => [], timeoutMs: 5000, collectMs: 5000 });
  await new Promise((ok) => setImmediate(ok));
  socket.emit("message", answerTo(socket, record(POINTER_TO_QUESTION, 28, AAAA)));
  socket.emit("message", answerTo(socket, record(POINTER_TO_QUESTION, 28, AAAA)));
  assert.equal(socket.closed, false, "one family in: still listening");
  socket.emit("message", answerTo(socket, record(POINTER_TO_QUESTION, 1, a(10, 0, 0, 2))));
  assert.deepEqual((await pending).map((r) => r.address), ["fd00:0:0:0:0:0:0:7", "10.0.0.2"], "both, each once, as soon as both are in");
  assert.ok(socket.closed);
});

test("resolve ends a short while after an answer that has one family only", async () => {
  const socket = new FakeSocket();
  const pending = m.resolve("boat.local", { createSocket: () => socket, interfaces: () => [], timeoutMs: 30, tries: 3, collectMs: 60 });
  await new Promise((ok) => setImmediate(ok));
  socket.emit("message", answerTo(socket, record(POINTER_TO_QUESTION, 1, a(10, 0, 0, 2))));
  assert.deepEqual((await pending).map((r) => r.address), ["10.0.0.2"]);
  assert.equal(socket.sent.length, 1, "no second query once an answer came");
});

test("resolve ignores answers about other names", async () => {
  const socket = new FakeSocket();
  const pending = m.resolve("boat.local", { createSocket: () => socket, interfaces: () => [], timeoutMs: 50, tries: 1 });
  setImmediate(() => {
    const msg = response("other.local", [record(POINTER_TO_QUESTION, 1, a(10, 0, 0, 1))]);
    msg.writeUInt16BE(socket.sent[0].packet.readUInt16BE(0), 0);
    socket.emit("message", msg);
  });
  assert.deepEqual(await pending, []);
});

/** Node's lookup, as the wrapper sees it: records each call and answers with [address]. */
function fakeOriginal(address = "203.0.113.1") {
  const calls = [];
  const original = (hostname, options, callback) => {
    calls.push({ hostname, options });
    process.nextTick(() => (options && options.all ? callback(null, [{ address, family: 4 }]) : callback(null, address, 4)));
  };
  return { original, calls };
}

const lookupOnce = (lookup, hostname, options) =>
  new Promise((ok, fail) => {
    const cb = (err, address, family) => (err ? fail(err) : ok({ address, family }));
    if (options === undefined) lookup(hostname, cb);
    else lookup(hostname, options, cb);
  });

test("a .local name is answered from mDNS, everything else by Node's own lookup", async () => {
  const { original, calls } = fakeOriginal();
  const asked = [];
  const lookup = m.makeLookup(original, {
    resolveName: async (name) => {
      asked.push(name);
      return [{ address: "fd00::7", family: 6, ttl: 5 }, { address: "192.168.1.9", family: 4, ttl: 5 }];
    },
  });
  assert.deepEqual(await lookupOnce(lookup, "jetson.local.", { order: "ipv4first" }), { address: "192.168.1.9", family: 4 });
  assert.deepEqual(await lookupOnce(lookup, "jetson.local", { order: "verbatim" }), { address: "fd00::7", family: 6 });
  assert.deepEqual(await lookupOnce(lookup, "jetson.local", { all: true, order: "ipv4first" }), {
    address: [{ address: "192.168.1.9", family: 4 }, { address: "fd00::7", family: 6 }],
    family: undefined,
  });
  assert.deepEqual(await lookupOnce(lookup, "jetson.local", 6), { address: "fd00::7", family: 6 });
  assert.deepEqual(asked, ["jetson.local"], "answered from the cache within the TTL");
  assert.deepEqual(await lookupOnce(lookup, "example.com", { family: 4 }), { address: "203.0.113.1", family: 4 });
  assert.deepEqual(calls, [{ hostname: "example.com", options: { family: 4 } }]);
});

test("a .local name nobody answers, or not in the family asked, goes to Node's lookup", async () => {
  const { original, calls } = fakeOriginal();
  const lookup = m.makeLookup(original, {
    resolveName: async (name) => (name === "v4only.local" ? [{ address: "10.0.0.2", family: 4, ttl: 5 }] : []),
  });
  assert.deepEqual(await lookupOnce(lookup, "nobody.local"), { address: "203.0.113.1", family: 4 });
  assert.deepEqual(await lookupOnce(lookup, "v4only.local", { family: 6 }), { address: "203.0.113.1", family: 4 });
  assert.deepEqual(calls.map((c) => c.hostname), ["nobody.local", "v4only.local"]);
});

test("one query serves concurrent lookups, and the cache ends with the TTL", async () => {
  let clock = 1_000_000;
  let queries = 0;
  const lookup = m.makeLookup(fakeOriginal().original, {
    now: () => clock,
    resolveName: async () => {
      queries++;
      return [{ address: "10.0.0.2", family: 4, ttl: 5 }];
    },
  });
  await Promise.all([lookupOnce(lookup, "a.local"), lookupOnce(lookup, "a.local"), lookupOnce(lookup, "A.local.")]);
  assert.equal(queries, 1);
  clock += 4_000;
  await lookupOnce(lookup, "a.local");
  assert.equal(queries, 1);
  clock += 2_000;
  await lookupOnce(lookup, "a.local");
  assert.equal(queries, 2);
});

test("no answer is cached past MAX_TTL_S, and a zero TTL not at all", async () => {
  let clock = 0;
  let queries = 0;
  let ttl = 4500;
  const lookup = m.makeLookup(fakeOriginal().original, {
    now: () => clock,
    resolveName: async () => {
      queries++;
      return [{ address: "10.0.0.2", family: 4, ttl }];
    },
  });
  await lookupOnce(lookup, "a.local");
  clock += m.MAX_TTL_S * 1000 + 1;
  await lookupOnce(lookup, "a.local");
  assert.equal(queries, 2);
  ttl = 0;
  clock += m.MAX_TTL_S * 1000 + 1;
  await lookupOnce(lookup, "a.local");
  await lookupOnce(lookup, "a.local");
  assert.equal(queries, 4);
});

test("options dns.lookup would refuse are left to it, so it refuses them as it always does", async () => {
  const refused = [{ order: "sideways" }, { family: 5 }, 5, { verbatim: "yes" }, { all: 1 }, "IPv4", { hints: 1024 }, { hints: "x" }];
  for (const bad of refused) {
    assert.equal(m.lookupOptionsValid(bad), false, JSON.stringify(bad));
    assert.throws(() => dns.lookup("localhost", bad, () => {}), /ERR_INVALID_ARG/, `Node refuses ${JSON.stringify(bad)} too`);
  }
  const taken = [
    undefined, null, {}, 0, 4, 6,
    { family: "IPv6", order: "ipv6first", verbatim: false, all: true },
    { family: null, order: null, verbatim: null, all: null, hints: null },
    { hints: dns.ADDRCONFIG }, { hints: dns.V4MAPPED | dns.ALL }, { hints: 0 }, { hints: 8.5 },
  ];
  for (const good of taken) {
    assert.equal(m.lookupOptionsValid(good), true, JSON.stringify(good));
    assert.doesNotThrow(() => dns.lookup("localhost", good, () => {}), `Node takes ${JSON.stringify(good)} too`);
  }
  const calls = [];
  let asked = 0;
  const lookup = m.makeLookup((hostname, options) => calls.push({ hostname, options }), {
    resolveName: async () => {
      asked++;
      return [];
    },
  });
  lookup("jetson.local", { order: "sideways" }, () => {});
  assert.deepEqual(calls, [{ hostname: "jetson.local", options: { order: "sideways" } }]);
  assert.equal(asked, 0, "no mDNS query for options Node refuses");
  // And the installed lookup throws what Node's own does.
  assert.throws(() => dns.lookup("jetson.local", { order: "sideways" }, () => {}), { code: "ERR_INVALID_ARG_VALUE" });
  assert.throws(() => dns.lookup("jetson.local", { family: 5 }, () => {}), { code: "ERR_INVALID_ARG_VALUE" });
});

test("a lookup without a callback is Node's to refuse", () => {
  const calls = [];
  m.makeLookup((hostname, options, callback) => calls.push({ hostname, options, callback }))("jetson.local", {});
  assert.deepEqual(calls, [{ hostname: "jetson.local", options: {}, callback: undefined }]);
});

test("the queries go out on this machine's IPv4 interfaces, the loopback left out", () => {
  const list = m.ipv4Interfaces();
  assert.ok(Array.isArray(list));
  assert.ok(list.every((ip) => typeof ip === "string" && ip !== "127.0.0.1"));
});

test("the promise forms answer as Node's do", async () => {
  const lookup = m.makeLookup(fakeOriginal().original, { resolveName: async () => [{ address: "10.0.0.2", family: 4, ttl: 5 }] });
  const originalPromise = async (hostname) => ({ address: `node:${hostname}`, family: 4 });
  const promises = m.promiseLookup(lookup, originalPromise);
  assert.deepEqual(await promises("a.local"), { address: "10.0.0.2", family: 4 });
  assert.deepEqual(await promises("a.local", { all: true }), [{ address: "10.0.0.2", family: 4 }]);
  assert.deepEqual(await promises("example.com"), { address: "node:example.com", family: 4 });
  const failing = m.promiseLookup((_h, _o, cb) => cb(new Error("nope")), originalPromise);
  await assert.rejects(failing("a.local"), /nope/);
});

test("loading the file put the lookup in place, once", async () => {
  assert.equal(m.install(), false, "already installed when the file was loaded");
  assert.equal(dns.lookup[util.promisify.custom], dns.promises.lookup);
  assert.deepEqual(await util.promisify(dns.lookup)("localhost", { family: 4 }), { address: "127.0.0.1", family: 4 });
  const viaCallback = await new Promise((ok, fail) => dns.lookup("localhost", 4, (err, address) => (err ? fail(err) : ok(address))));
  assert.equal(viaCallback, "127.0.0.1");
});
