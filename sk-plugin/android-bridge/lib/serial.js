// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * The phone's serial devices as Signal K: the bytes each one sent, as the app read them off its
 * USB port (lib/protocol.js, `serial`), cut into NMEA 0183 sentences and parsed with the server's
 * own parser, @signalk/nmea0183-signalk, the one its NMEA 0183 data connections use. So a GPS or
 * AIS receiver plugged into the phone gives what it would on a connection to a serial port on a
 * Raspberry Pi, under its own source: `android.<device id>.<talker>`, e.g.
 * `android.usb-1a86-7523.GP`. Each sentence is also given to the server's `nmea0183` event as
 * its own connections do, so its NMEA 0183 TCP server (port 10110) passes it on to the boat's
 * network, and so do plugins that listen to it.
 *
 * Android gives an app no `/dev/tty*`, so a data connection cannot open the device itself; the
 * app passes the bytes over its private socket instead of a TCP port on the phone, which every
 * other app on it could reach (docs/PLAN.md §16, 9.5.4).
 *
 * The parser is the server's, found through the server's own module paths (`require.main`, the
 * signalk-server script): it is one of the server's dependencies, and this plugin keeps none of
 * its own. Where it cannot be found, sentences still reach the `nmea0183` event and the status
 * says what is missing.
 */

const { createRequire } = require("node:module");

const NMEA_PARSER = "@signalk/nmea0183-signalk";

/** Longest sentence kept while waiting for its end; NMEA's own limit is 82, tag blocks add some. */
const MAX_SENTENCE = 1024;

/**
 * The server's NMEA 0183 parser class, or null. `main` is the module the server was started with;
 * the plugin's own paths are tried after it, for a parser installed beside the plugin.
 */
function loadNmeaParser(main = require.main, own = require) {
  const requires = [];
  if (main && typeof main.filename === "string") requires.push(createRequire(main.filename));
  requires.push(own);
  for (const r of requires) {
    try {
      const mod = r(NMEA_PARSER);
      const Parser = typeof mod === "function" ? mod : mod?.Parser;
      if (typeof Parser === "function") return Parser;
    } catch {
      // Not on that path: try the next.
    }
  }
  return null;
}

/**
 * The serial input of every device: `data(id, bytes)` for what a device sent, `forget(id)` once it
 * is gone. `Parser` is the NMEA 0183 parser class (null: sentences are counted and emitted only),
 * `parserOptions` what it is made with, `handle(delta)` takes a parsed delta, `emit(sentence)`
 * gives a sentence to the server's `nmea0183` event.
 */
class SerialInput {
  constructor({ Parser, parserOptions = {}, handle, emit }) {
    this.Parser = Parser;
    this.parserOptions = parserOptions;
    this.handle = handle;
    this.emit = emit;
    this.devices = new Map();
  }

  /** What [id] sent: the sentences finished by it are passed on, a part one is kept for the next. */
  data(id, bytes) {
    const d = this.device(id);
    d.bytes += bytes.length;
    // latin1 keeps each byte one character: NMEA 0183 is ASCII, and a stray byte stays one.
    const lines = (d.pending + bytes.toString("latin1")).split(/\r\n|\r|\n/);
    d.pending = lines.pop();
    if (d.pending.length > MAX_SENTENCE) {
      // No end in sight: not NMEA 0183 (a binary device, a wrong baud rate). Dropped, counted.
      d.pending = "";
      d.overlong++;
    }
    for (const line of lines) this.sentence(id, d, line.trim());
  }

  /** A device is gone: what it had half sent goes with it, its counts are kept. */
  forget(id) {
    const d = this.devices.get(id);
    if (d) d.pending = "";
  }

  /** Per device: bytes, sentences, those that did not parse, and lines too long to be one. */
  stats() {
    const out = {};
    for (const [id, d] of this.devices) {
      out[id] = { bytes: d.bytes, sentences: d.sentences, invalid: d.invalid, overlong: d.overlong };
    }
    return out;
  }

  device(id) {
    let d = this.devices.get(id);
    if (!d) {
      // A parser per device: it keeps state across sentences (multi-part AIS, GSV).
      const parser = this.Parser ? new this.Parser({ ...this.parserOptions }) : null;
      d = { parser, pending: "", bytes: 0, sentences: 0, invalid: 0, overlong: 0 };
      this.devices.set(id, d);
    }
    return d;
  }

  sentence(id, d, s) {
    // Blank lines and comments, as the server's own NMEA 0183 stage skips them.
    if (s.length === 0 || s.startsWith("#")) return;
    if (s.length > MAX_SENTENCE) {
      d.overlong++;
      return;
    }
    d.sentences++;
    this.emit(s);
    if (!d.parser) return;
    let delta;
    try {
      delta = d.parser.parse(s);
    } catch {
      // A bad checksum or a cut sentence, as a serial line gives now and then.
      d.invalid++;
      return;
    }
    if (!delta || !Array.isArray(delta.updates)) return;
    const label = `android.${id}`;
    for (const u of delta.updates) u.source = { ...(u.source ?? {}), label };
    this.handle(delta);
  }
}

module.exports = { SerialInput, loadNmeaParser, MAX_SENTENCE, NMEA_PARSER };
