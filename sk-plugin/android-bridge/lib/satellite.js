// SPDX-License-Identifier: EUPL-1.2
"use strict";

const FIRST_DELAY_MS = 1000;
const MAX_DELAY_MS = 30000;
/**
 * A stream that says nothing this long is taken as dead and opened again: a boat's server sends
 * its GNSS at least once a second, and a Wi-Fi gone under a TCP connection is otherwise noticed
 * only after minutes of retransmission.
 */
const SILENT_MS = 60000;
/** Where a delta whose source has no name of its own is said to come from (lib/satellite.js). */
const LABEL = "pocketsk-satellite";

const STATES = ["off", "connecting", "connected", "unauthorized", "unreachable"];
/** What the link says when the main server does not let this phone read. */
const REFUSED = "the main server does not accept this phone's token";
/** This bridge's own source names (lib/protocol.js, lib/serial.js). */
const BRIDGE = /^android\./;
/** A notification id as signalk-server makes them. */
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Satellite mode's link (docs/PLAN.md §8.4, §16 13.1): this phone's server follows the boat's main
 * Signal K server. The app finds the server, gets its admin's approval for a device token, and
 * hands the address and the token over the bridge ({t: "satellite"}); this opens the main
 * server's delta stream with the token in the Authorization header, and gives every delta to this
 * server, the main server's own vessel as this one's `vessels.self`.
 *
 * Not signalk-server's own Signal K connection (@signalk/streams' mdns-ws): in 2.35.1 that opens
 * the stream without the token and sends it as a message the server's WebSocket interface does
 * not read, so a main server with security on gives it nothing. Here the token also never lands
 * in `settings.json`, which the phone copies to the folder a computer sees over USB.
 *
 * Sources: an update that names its own (`$source`, or a `source` with a label, a talker, a src
 * or a CAN name) keeps it, as signalk-server's remote connections do, so a device seen on the main
 * server is the same device here; one that does not is stamped `pocketsk-satellite.<host:port>`.
 * The main server's vessel identity comes with its other values but for `uuid`, which stays this
 * server's own.
 *
 * Whether the main server takes the token is asked of its `/skServer/loginStatus`, with the token,
 * whenever the stream could not tell: after the hello, which it says to anyone, and after a
 * connection that failed, since Node's WebSocket reports a refused upgrade with no status. The
 * same answer says whether the phone may write (its `userLevel`, read/write or admin, or a server
 * without security), which the push and the silence need.
 *
 * The push (§16 13.2): the bridge's own deltas, the phone's sensors and its serial devices, as
 * the app's Dock chose them, go to the main server on the same stream, each source under this
 * phone's label (`pocketsk.<8 hex of its client id>.android.gnss`), so two phones on one main
 * server never write over each other. The main server sends them back with everything else; an
 * update under this phone's label is dropped on the way in, not stored twice. A read-only token
 * sends nothing: the main server would drop it without a word.
 *
 * Options: app (the plugin API), pluginId, WebSocket and fetch (Node's own, by default),
 * onState(state, detail, write) when the link or the right to write changes (STATES), debug, and
 * timing for tests.
 */
class Satellite {
  constructor({
    app,
    pluginId,
    WebSocket = globalThis.WebSocket,
    fetch = globalThis.fetch,
    onState = () => {},
    debug = () => {},
    firstDelayMs = FIRST_DELAY_MS,
    maxDelayMs = MAX_DELAY_MS,
    silentMs = SILENT_MS,
  }) {
    this.app = app;
    this.pluginId = pluginId;
    this.WebSocket = WebSocket;
    this.fetch = fetch;
    this.onState = onState;
    this.debug = debug;
    this.firstDelayMs = firstDelayMs;
    this.maxDelayMs = maxDelayMs;
    this.silentMs = silentMs;
    this.delayMs = firstDelayMs;
    this.target = null;
    this.socket = null;
    this.timer = null;
    this.watchdog = null;
    this.remoteSelf = null;
    this.current = { state: "off", detail: null };
    this.deltas = 0;
    /** Whether the main server lets this phone write, null until it has said. */
    this.write = null;
    /** This phone's label for what it sends, and which of its groups it sends. */
    this.label = null;
    this.push = { sensors: false, serial: false };
    this.pushed = 0;
    /** The main server's id of each notification of its own vessel, by path ([silence]). */
    this.notices = new Map();
  }

  /**
   * Follows the server at `url` (`http://host:port`) with `token` (null: it runs without
   * security), or nothing when `url` is null; `label` and `push` say what of this phone's own it
   * sends there. The same target again changes nothing but what is sent.
   */
  follow(url, token, { label = null, push = {} } = {}) {
    this.label = label;
    this.push = { sensors: push.sensors === true, serial: push.serial === true };
    const next = url ? { url, token: token || null } : null;
    if (next && this.target && next.url === this.target.url && next.token === this.target.token) return;
    this.close();
    this.target = next;
    this.write = null;
    this.notices.clear();
    this.delayMs = this.firstDelayMs;
    if (!next) {
      this.report("off", null);
      return;
    }
    this.connect();
  }

  stop() {
    this.close();
    this.target = null;
  }

  /** Where the link stands, for the plugin's status and the app. */
  status() {
    return { ...this.current, url: this.target?.url ?? null, deltas: this.deltas, write: this.write, pushed: this.pushed };
  }

  /**
   * One of this bridge's own deltas, of `group` (`sensors` or `serial`), to the main server: sent
   * when the stream is up, the phone may write and the Dock chose that group. Each source goes
   * under this phone's label; a delta's context (an AIS target's) stays, none or this server's own
   * vessel (which signalk-server writes into a delta it is given) is the main server's own vessel.
   */
  send(delta, group) {
    const socket = this.socket;
    if (!socket || this.current.state !== "connected" || this.write !== true || !this.label || this.push[group] !== true) return false;
    if (!delta || !Array.isArray(delta.updates)) return false;
    const updates = delta.updates.filter((u) => u && typeof u === "object").map((u) => {
      const update = { ...u };
      if (typeof update.$source === "string" && update.$source.length > 0) update.$source = `${this.label}.${update.$source}`;
      else if (update.source && typeof update.source === "object" && typeof update.source.label === "string") {
        update.source = { ...update.source, label: `${this.label}.${update.source.label}` };
      } else update.$source = this.label;
      return update;
    });
    if (updates.length === 0) return false;
    const out = { ...delta, updates };
    if (out.context === "vessels.self" || out.context === selfContext(this.app)) delete out.context;
    try {
      socket.send(JSON.stringify(out));
    } catch (e) {
      this.debug(`satellite: could not send to ${hostPort(this.target?.url)}: ${e?.message ?? e}`);
      return false;
    }
    this.pushed++;
    return true;
  }

  /**
   * The phone's Silence (or acknowledge) for the alarm at `path`, done on the main server too
   * through its notification API, by the main server's own id for it as its stream gave it: this
   * server gives its copy an id of its own. Only with the right to write, and only for an alarm
   * of the main server's vessel; one the phone raised itself is not the main server's.
   */
  async silence(path, action) {
    const target = this.target;
    const id = this.notices.get(path);
    if (!target || this.write !== true || (action !== "silence" && action !== "acknowledge") || !id) return false;
    try {
      const res = await this.fetch(`${target.url}/signalk/v2/api/notifications/${id}/${action}`, {
        method: "POST",
        headers: target.token ? { Authorization: `Bearer ${target.token}` } : {},
        signal: AbortSignal.timeout(5000),
      });
      if (!res.ok) this.debug(`satellite: ${hostPort(target.url)} answered ${res.status} to ${action} ${id}`);
      return res.ok;
    } catch (e) {
      this.debug(`satellite: could not ${action} ${id} on ${hostPort(target.url)}: ${e?.message ?? e}`);
      return false;
    }
  }

  close() {
    clearTimeout(this.timer);
    this.timer = null;
    clearTimeout(this.watchdog);
    this.watchdog = null;
    const socket = this.socket;
    this.socket = null;
    this.remoteSelf = null;
    if (socket) {
      try {
        socket.close();
      } catch {
        // Not open yet, or closed already: nothing to end.
      }
    }
  }

  connect() {
    const target = this.target;
    if (!target) return;
    if (typeof this.WebSocket !== "function") {
      this.report("unreachable", "this Node has no WebSocket");
      return;
    }
    this.report("connecting", null);
    const stream = `${target.url.replace(/^http/, "ws")}/signalk/v1/stream?subscribe=all`;
    let socket;
    try {
      socket = new this.WebSocket(stream, target.token ? { headers: { Authorization: `Bearer ${target.token}` } } : undefined);
    } catch (e) {
      this.lost(`cannot connect: ${e.message}`);
      return;
    }
    this.socket = socket;
    this.listen();
    socket.onmessage = (event) => {
      if (this.socket !== socket) return;
      this.listen();
      this.message(event.data);
    };
    // Node's WebSocket fires error alone on an upgrade the server refuses, with no close after it:
    // either one is the end of this connection, and whichever comes second finds it gone.
    socket.onerror = (event) => {
      const message = event?.message || event?.error?.message || "error";
      socket.lastError = message;
      if (this.socket !== socket) return;
      this.socket = null;
      try {
        // Closing a socket still connecting fires one more error, which finds it gone.
        socket.close();
      } catch {
        // Never opened: nothing to close.
      }
      this.lost(message);
    };
    socket.onclose = (event) => {
      if (this.socket !== socket) return;
      this.socket = null;
      this.lost(socket.lastError || `closed (${event?.code ?? "?"})`);
    };
  }

  /** Arms the silence watchdog again: a message has just come. */
  listen() {
    clearTimeout(this.watchdog);
    this.watchdog = setTimeout(() => {
      this.watchdog = null;
      const socket = this.socket;
      if (!socket) return;
      this.socket = null;
      try {
        socket.close();
      } catch {
        // Gone already.
      }
      this.lost(`silent for ${Math.round(this.silentMs / 1000)} s`);
    }, this.silentMs);
    this.watchdog.unref?.();
  }

  message(data) {
    let msg;
    try {
      msg = JSON.parse(typeof data === "string" ? data : Buffer.from(data).toString("utf8"));
    } catch {
      return;
    }
    if (!msg || typeof msg !== "object") return;
    if (typeof msg.self === "string" && msg.updates === undefined) {
      // The hello. A main server with security on says it to a client without a token too, and
      // then nothing; a token it refuses fails the connection itself.
      this.remoteSelf = msg.self;
      this.delayMs = this.firstDelayMs;
      // The last connection's word on writing is not this one's: the admin may have changed it
      // meanwhile, so nothing is sent until the server says again.
      this.write = null;
      this.report("connected", null);
      const socket = this.socket;
      this.access().then((a) => {
        if (this.socket !== socket || !a) return;
        if (a.read) {
          this.allow(a.write);
          return;
        }
        this.socket = null;
        try {
          socket.close();
        } catch {
          // Gone already.
        }
        this.lost(REFUSED);
      });
      return;
    }
    if (!Array.isArray(msg.updates)) return;
    const delta = this.local(msg);
    if (!delta) return;
    this.deltas++;
    // Thrown inside a WebSocket listener, an error is the process's uncaught exception: one delta
    // the server will not take (a shape no other provider sends it) costs that delta, not the server.
    try {
      this.app.handleMessage(this.pluginId, delta);
    } catch (e) {
      this.debug(`satellite: the server refused a delta from ${hostPort(this.target?.url)}: ${e?.message ?? e}`);
    }
  }

  /**
   * The delta as this server takes it: the main server's vessel as self, sources named, and a
   * source of the main server's self under this bridge's own names (`android.*`: another phone's
   * bridge feeding the main server, or this phone's own data coming back once 13.2 sends it there)
   * moved under the main server's label, so it never takes the place of this phone's own reading
   * of the same path. The source object stays as it came.
   */
  local(msg) {
    const label = `${LABEL}.${hostPort(this.target?.url)}`;
    const self = msg.context === this.remoteSelf || msg.context === "vessels.self";
    const updates = [];
    for (const u of msg.updates) {
      if (!u || typeof u !== "object") continue;
      // What this phone sent, come back: it is here already under its own name.
      if (this.label && sentBy(u, this.label)) continue;
      const update = { ...u };
      const ours = self && bridgeSource(update);
      if (ours) update.$source = `${label}.${ours}`;
      else if (!named(update)) update.$source = label;
      if (self && Array.isArray(update.values)) this.remember(update.values);
      if (Array.isArray(update.values)) {
        update.values = update.values.map((v) =>
          v && v.path === "" && v.value && typeof v.value === "object" && "uuid" in v.value ? { ...v, value: withoutUuid(v.value) } : v,
        );
      }
      updates.push(update);
    }
    if (updates.length === 0) return null;
    const delta = { ...msg, updates };
    if (delta.context === this.remoteSelf || delta.context === "vessels.self") delete delta.context;
    return delta;
  }

  /** The main server's ids of its own vessel's notifications in `values`, kept by path. */
  remember(values) {
    for (const v of values) {
      if (!v || typeof v.path !== "string" || !v.path.startsWith("notifications.")) continue;
      if (v.value && typeof v.value === "object" && UUID.test(String(v.value.id))) this.notices.set(v.path, v.value.id);
      else this.notices.delete(v.path);
    }
  }

  lost(detail) {
    clearTimeout(this.watchdog);
    this.watchdog = null;
    this.remoteSelf = null;
    const target = this.target;
    if (!target) return;
    // Whether this phone may write is a connection's answer: none is open now, so none holds.
    this.write = null;
    this.report(detail === REFUSED ? "unauthorized" : "unreachable", detail);
    if (detail !== REFUSED) {
      // A refused token fails the upgrade as any network error does: the server says which it was.
      this.access().then((a) => {
        if (a && !a.read && this.target === target && !this.socket) this.report("unauthorized", REFUSED);
      });
    }
    const delay = this.delayMs;
    this.delayMs = Math.min(this.delayMs * 2, this.maxDelayMs);
    this.timer = setTimeout(() => {
      this.timer = null;
      this.connect();
    }, delay);
    this.timer.unref?.();
  }

  /**
   * What the main server lets this phone do, `{read, write}` as its login status says, null when
   * it cannot be asked (unreachable, not a Signal K server, no fetch). Reading needs a login, read
   * access for anyone, or no security; writing a login at read/write or admin, or no security.
   */
  async access() {
    const target = this.target;
    if (!target || typeof this.fetch !== "function") return null;
    try {
      const res = await this.fetch(`${target.url}/skServer/loginStatus`, {
        headers: target.token ? { Authorization: `Bearer ${target.token}` } : {},
        signal: AbortSignal.timeout(5000),
      });
      if (!res.ok) return null;
      const s = await res.json();
      if (!s || typeof s !== "object") return null;
      const open = !s.authenticationRequired;
      const loggedIn = s.status === "loggedIn";
      return {
        read: open || loggedIn || s.readOnlyAccess === true,
        write: open || (loggedIn && (s.userLevel === "readwrite" || s.userLevel === "admin")),
      };
    } catch {
      return null;
    }
  }

  /** The right to write as the main server last said it; the app hears of a change. */
  allow(write) {
    if (this.write === write) return;
    this.write = write;
    this.debug(`satellite: ${write ? "may write to" : "may only read"} ${hostPort(this.target?.url)}`);
    this.onState(this.current.state, this.current.detail, this.write);
  }

  report(state, detail) {
    if (this.current.state === state && this.current.detail === detail) return;
    this.current = { state, detail };
    this.debug(`satellite: ${state}${detail ? ` (${detail})` : ""}`);
    this.onState(state, detail, this.write);
  }
}

/** Whether an update says where it comes from, as signalk-server's remote-deltas reads it. */
function named(update) {
  if (typeof update.$source === "string" && update.$source.length > 0) return true;
  const s = update.source;
  if (!s || typeof s !== "object") return false;
  return (typeof s.canName === "string" && s.canName.length > 0) ||
    s.src !== undefined ||
    (typeof s.talker === "string" && s.talker.length > 0) ||
    (typeof s.label === "string" && s.label.length > 0);
}

/**
 * The update's source as this bridge names its own (`android.*`: the phone's sensors as `$source`,
 * its serial devices as `source.label`, the talker after it), else null.
 */
function bridgeSource(update) {
  if (typeof update.$source === "string" && update.$source.length > 0) return BRIDGE.test(update.$source) ? update.$source : null;
  const s = update.source;
  if (!s || typeof s !== "object" || typeof s.label !== "string" || !BRIDGE.test(s.label)) return null;
  return typeof s.talker === "string" && s.talker.length > 0 ? `${s.label}.${s.talker}` : s.label;
}

/** This server's own vessel as a delta's context names it, from the plugin API. */
function selfContext(app) {
  if (typeof app?.selfContext === "string") return app.selfContext;
  return typeof app?.selfId === "string" ? `vessels.${app.selfId}` : null;
}

/** Whether an update is one this phone sent under `label` ([Satellite.send]). */
function sentBy(update, label) {
  const prefix = `${label}.`;
  if (typeof update.$source === "string") return update.$source === label || update.$source.startsWith(prefix);
  const l = update.source?.label;
  return typeof l === "string" && (l === label || l.startsWith(prefix));
}

function withoutUuid(value) {
  const { uuid, ...rest } = value;
  return rest;
}

function hostPort(url) {
  return String(url ?? "").replace(/^https?:\/\//, "");
}

module.exports = { Satellite, STATES, LABEL, FIRST_DELAY_MS, MAX_DELAY_MS, SILENT_MS };
