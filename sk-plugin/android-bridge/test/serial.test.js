// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { SerialInput, loadNmeaParser, MAX_SENTENCE } = require("../lib/serial");

/** Stands in for @signalk/nmea0183-signalk: a delta per RMC, a throw for a bad checksum, null else. */
class FakeParser {
  constructor(options) {
    this.options = options;
    this.seen = 0;
  }
  parse(sentence) {
    this.seen++;
    if (sentence.endsWith("*FF")) throw new Error(`Sentence "${sentence}" is invalid`);
    if (!sentence.startsWith("$GPRMC")) return null;
    return {
      updates: [{ source: { sentence: "RMC", talker: "GP", type: "NMEA0183" }, values: [{ path: "navigation.position", value: { latitude: 60, longitude: 22 } }] }],
    };
  }
}

function input(Parser = FakeParser) {
  const deltas = [];
  const sentences = [];
  const i = new SerialInput({ Parser, parserOptions: { x: 1 }, handle: (d) => deltas.push(d), emit: (s) => sentences.push(s) });
  return { i, deltas, sentences };
}

test("sentences are cut from the bytes as they come, parsed and labelled with the device", () => {
  const { i, deltas, sentences } = input();
  i.data("usb-1a86-7523", Buffer.from("$GPRMC,1*00\r\n$GPG"));
  i.data("usb-1a86-7523", Buffer.from("SV,2*00\r\n\r\n# a comment\n$GPRMC,3*FF\r"));
  assert.deepEqual(sentences, ["$GPRMC,1*00", "$GPGSV,2*00", "$GPRMC,3*FF"]);
  assert.equal(deltas.length, 1);
  assert.deepEqual(deltas[0].updates[0].source, { sentence: "RMC", talker: "GP", type: "NMEA0183", label: "android.usb-1a86-7523" });
  assert.deepEqual(i.stats(), { "usb-1a86-7523": { bytes: 52, sentences: 3, invalid: 1, overlong: 0 } });
  // One parser per device, made with the options given.
  assert.equal(i.devices.get("usb-1a86-7523").parser.options.x, 1);
});

test("each device keeps its own part sentence and parser", () => {
  const { i, sentences } = input();
  i.data("usb-a", Buffer.from("$GPRMC,a"));
  i.data("usb-b", Buffer.from("$GPRMC,b\n"));
  i.data("usb-a", Buffer.from("1\n"));
  assert.deepEqual(sentences, ["$GPRMC,b", "$GPRMC,a1"]);
  assert.notEqual(i.devices.get("usb-a").parser, i.devices.get("usb-b").parser);
  // A device that goes loses what it had half sent.
  i.data("usb-a", Buffer.from("$GPRMC,half"));
  i.forget("usb-a");
  i.forget("usb-gone");
  i.data("usb-a", Buffer.from(",rest\n"));
  assert.equal(sentences.at(-1), ",rest");
});

test("bytes with no end of line in sight are dropped, not kept for ever", () => {
  const { i, sentences } = input();
  i.data("usb-a", Buffer.alloc(MAX_SENTENCE + 1, 0x41));
  assert.equal(i.devices.get("usb-a").pending, "");
  i.data("usb-a", Buffer.from("x".repeat(MAX_SENTENCE + 1) + "\n$GPRMC,ok\n"));
  assert.deepEqual(sentences, ["$GPRMC,ok"]);
  assert.equal(i.stats()["usb-a"].overlong, 2);
});

test("without the server's parser the sentences are still counted and passed on", () => {
  const { i, deltas, sentences } = input(null);
  i.data("usb-a", Buffer.from("$GPRMC,1\n"));
  assert.deepEqual(sentences, ["$GPRMC,1"]);
  assert.equal(deltas.length, 0);
});

test("a delta without updates is not handled", () => {
  class Odd {
    parse() {
      return {};
    }
  }
  const { i, deltas } = input(Odd);
  i.data("usb-a", Buffer.from("$GPXXX\n"));
  assert.equal(deltas.length, 0);
});

/** A server install with `source` as its NMEA 0183 parser; the module paths of its script. */
function serverWith(source) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bridge-parser-"));
  const pkg = path.join(dir, "node_modules", "@signalk", "nmea0183-signalk");
  fs.mkdirSync(pkg, { recursive: true });
  fs.writeFileSync(path.join(pkg, "index.js"), source);
  fs.mkdirSync(path.join(dir, "bin"));
  return { filename: path.join(dir, "bin", "signalk-server") };
}

test("the parser is found from the server's own module paths", () => {
  const main = serverWith("module.exports = class Parser {}; module.exports.Parser = module.exports;");
  const Parser = loadNmeaParser(main, () => {
    throw new Error("not beside the plugin");
  });
  assert.equal(typeof Parser, "function");
  assert.equal(Parser.name, "Parser");
  // An export with the class under a name only, and one with no class at all.
  assert.equal(loadNmeaParser(serverWith("module.exports = { Parser: class Esm {} };"), () => ({})).name, "Esm");
  assert.equal(loadNmeaParser(serverWith("module.exports = { Parser: 1 };"), () => ({})), null);
  // Nowhere: none.
  assert.equal(loadNmeaParser(null, () => ({})), null);
  assert.equal(loadNmeaParser(undefined, () => {
    throw new Error("missing");
  }), null);
});
