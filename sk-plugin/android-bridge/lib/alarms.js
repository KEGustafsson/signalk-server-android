// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * The boat's alarms for the phone (docs/PLAN.md §7.1, alarms in): the server's notifications at or
 * above a level (alarm, by default), as one list the app is sent whenever it changes, so the phone
 * can ring and wake its screen for an anchor alarm, which no webapp can do. Silence on the phone
 * comes back as `silence(id)` and is done through the server's own notification API, as its admin
 * UI and every other client do it, so the alarm goes quiet everywhere at once.
 *
 * signalk-server 2.x manages notifications (unless its `manageNotifications` setting is off): each
 * one carries an `id` and a `status` (silenced, acknowledged, and whether either may be done), and
 * an emergency cannot be silenced, only acknowledged. So the phone is offered what the server
 * allows: `silence` while it may be silenced, else `acknowledge` while it may be acknowledged,
 * else nothing, and then the phone's Silence quiets the phone alone. A notification the server does
 * not manage (no id) is the same: nothing is written for it, since a delta of this plugin's own on
 * that path would stay behind as a second source after the alarm's own source went back to normal.
 */

/** Signal K's notification states, by rank; normal and nominal are no alarm. */
const RANK = { nominal: 0, normal: 0, alert: 1, warn: 2, alarm: 3, emergency: 4 };
/** The levels a phone may be woken from (the plugin's setting). */
const LEVELS = ["warn", "alarm", "emergency"];
/** More than this is not a boat's alarms but a broken source; the most urgent are kept. */
const MAX_ALARMS = 32;
const MAX_MESSAGE = 200;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
/** A notification's path; a colon is allowed, since a closest-approach alarm names a vessel by URN. */
const PATH = /^notifications(\.[A-Za-z0-9_:-]+)+$/;
const MAX_PATH = 256;

/** The rank of a state, 0 for one that is no alarm or not a state at all. */
function rank(state) {
  return Object.hasOwn(RANK, state) ? RANK[state] : 0;
}

/**
 * The alarms: `update(path, value)` for every notification delta, `resend()` once the phone is
 * connected, `silence(id)` for the phone's Silence. `send(msg)` gives the phone a message, `api` is
 * the server's notification API (`app.notifications`; null where there is none), `level` the
 * lowest state the phone hears of.
 */
class Alarms {
  constructor({ send, api = null, level = "alarm", debug = () => {} }) {
    this.send = send;
    this.api = api && typeof api.silence === "function" && typeof api.acknowledge === "function" ? api : null;
    this.level = LEVELS.includes(level) ? level : "alarm";
    this.debug = debug;
    this.byPath = new Map();
    this.told = null;
  }

  /** A notification's value at `path`: an alarm at or above the level is kept, anything else let go. */
  update(path, value) {
    if (typeof path !== "string" || path.length > MAX_PATH || !PATH.test(path)) return;
    if (value !== null && typeof value === "object" && rank(value.state) >= rank(this.level)) {
      this.byPath.set(path, value);
    } else {
      this.byPath.delete(path);
    }
    this.publish();
  }

  /** The list as the phone is sent it: the most urgent first, then by path. */
  list() {
    return [...this.byPath.entries()]
      .map(([path, value]) => ({
        id: idOf(path, value),
        path,
        state: value.state,
        message: text(value.message, MAX_MESSAGE),
        // A notification without a method says nothing about how to alert: an alarm rings.
        sound: !Array.isArray(value.method) || value.method.includes("sound"),
        action: this.actionOf(value),
      }))
      .sort((a, b) => rank(b.state) - rank(a.state) || (a.path < b.path ? -1 : a.path > b.path ? 1 : 0))
      .slice(0, MAX_ALARMS);
  }

  /** The phone has just connected: it hears the list, which is otherwise sent on change only. */
  resend() {
    this.told = null;
    this.publish();
  }

  /**
   * The phone's Silence for the alarm `id`: silenced or acknowledged through the server, as it
   * allows. Returns what was done (`silence`, `acknowledge`), or null when nothing could be,
   * which the phone already knew from the action it was sent.
   */
  silence(id) {
    for (const [path, value] of this.byPath) {
      if (idOf(path, value) !== id) continue;
      const action = this.actionOf(value);
      if (action === null) return null;
      try {
        this.api[action](value.id);
        return action;
      } catch (err) {
        // Silenced elsewhere in the meantime, or gone: the server's next delta says how it stands.
        this.debug(`could not ${action} ${path}: ${err.message}`);
        return null;
      }
    }
    return null;
  }

  /** What the server allows done to `value` from here: `silence`, `acknowledge` or null. */
  actionOf(value) {
    if (!this.api || typeof value.id !== "string" || !UUID.test(value.id)) return null;
    const status = value.status !== null && typeof value.status === "object" ? value.status : {};
    if (value.state !== "emergency" && status.canSilence !== false && !status.silenced && !status.acknowledged) return "silence";
    if (status.canAcknowledge !== false && !status.acknowledged) return "acknowledge";
    return null;
  }

  publish() {
    const msg = { t: "alarms", list: this.list() };
    const line = JSON.stringify(msg);
    if (line === this.told) return;
    this.told = line;
    this.send(msg);
  }
}

/** The id the phone knows an alarm by: the server's, or its path when the server gives none. */
function idOf(path, value) {
  return typeof value.id === "string" && UUID.test(value.id) ? value.id : path;
}

/** A string for the phone's screen: no control characters, at most `max` long. */
function text(value, max) {
  if (typeof value !== "string") return "";
  return value.replace(/[\u0000-\u001f\u007f]/g, " ").replace(/\s+/g, " ").trim().slice(0, max);
}

module.exports = { Alarms, LEVELS, MAX_ALARMS, rank };
