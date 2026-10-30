// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { Alarms, MAX_ALARMS, rank } = require("../lib/alarms");

const ID = "0c1f3f5e-6f43-4d8e-9a8b-6a3b1c2d4e5f";
const ID2 = "1d2e3f4a-5b6c-4d7e-8f90-a1b2c3d4e5f6";

/** The server's notification API as a plugin sees it, recording what was asked of it. */
function fakeApi() {
  return {
    calls: [],
    silence(id) { this.calls.push(["silence", id]); },
    acknowledge(id) { this.calls.push(["acknowledge", id]); },
  };
}

function alarms(opts = {}) {
  const sent = [];
  const a = new Alarms({ send: (m) => sent.push(m), ...opts });
  return { a, sent };
}

const anchor = (extra = {}) => ({
  id: ID,
  state: "alarm",
  method: ["visual", "sound"],
  message: "Anchor dragging",
  status: { silenced: false, acknowledged: false, canSilence: true, canAcknowledge: true },
  ...extra,
});

test("an alarm at the level is sent, with what the server allows done to it", () => {
  const { a, sent } = alarms({ api: fakeApi() });
  a.update("notifications.navigation.anchor", anchor());
  assert.deepEqual(sent, [
    {
      t: "alarms",
      list: [{ id: ID, path: "notifications.navigation.anchor", state: "alarm", message: "Anchor dragging", sound: true, action: "silence" }],
    },
  ]);
});

test("below the level, back to normal or cleared, an alarm is let go", () => {
  const { a, sent } = alarms({ api: fakeApi() });
  a.update("notifications.engine.temp", anchor({ state: "warn" }));
  a.update("notifications.navigation.anchor", anchor());
  assert.equal(sent.length, 1);
  a.update("notifications.navigation.anchor", anchor({ state: "normal" }));
  assert.deepEqual(sent.at(-1).list, []);
  a.update("notifications.navigation.anchor", anchor());
  a.update("notifications.navigation.anchor", null);
  assert.deepEqual(sent.at(-1).list, []);
});

test("the level may be warn or emergency; anything else is alarm", () => {
  const warn = alarms({ level: "warn" });
  warn.a.update("notifications.engine.temp", anchor({ state: "warn" }));
  assert.equal(warn.sent.at(-1).list.length, 1);
  const emergency = alarms({ level: "emergency" });
  emergency.a.update("notifications.navigation.anchor", anchor());
  assert.equal(emergency.sent.length, 0);
  const odd = alarms({ level: "loud" });
  odd.a.update("notifications.engine.temp", anchor({ state: "warn" }));
  assert.equal(odd.sent.length, 0);
});

test("a plugin just started sends no empty list, even when the phone connects", () => {
  // A server restart: the phone still holds the anchor alarm, and its source raises it again later.
  const { a, sent } = alarms();
  a.update("navigation.anchor.position", null);
  a.update("notifications.engine.temp", anchor({ state: "normal" }));
  a.resend();
  assert.equal(sent.length, 0);
  a.update("notifications.navigation.anchor", anchor());
  assert.equal(sent.length, 1);
  a.update("notifications.navigation.anchor", anchor({ state: "normal" }));
  a.resend();
  assert.deepEqual(sent.map((m) => m.list.length), [1, 0, 0]);
});

test("the phone hears of changes only, and the whole list again on resend", () => {
  const { a, sent } = alarms({ api: fakeApi() });
  a.update("notifications.navigation.anchor", anchor());
  a.update("notifications.navigation.anchor", anchor());
  assert.equal(sent.length, 1);
  a.resend();
  assert.equal(sent.length, 2);
});

test("the most urgent come first, then by path, and only so many", () => {
  const { a, sent } = alarms();
  a.update("notifications.b", anchor({ id: undefined }));
  a.update("notifications.a", anchor({ id: undefined }));
  a.update("notifications.z", anchor({ id: undefined, state: "emergency" }));
  assert.deepEqual(sent.at(-1).list.map((x) => x.path), ["notifications.z", "notifications.a", "notifications.b"]);
  for (let i = 0; i < MAX_ALARMS + 5; i++) a.update(`notifications.n${i}`, anchor({ id: undefined }));
  assert.equal(sent.at(-1).list.length, MAX_ALARMS);
  assert.equal(sent.at(-1).list[0].path, "notifications.z");
});

test("a closest-approach alarm, its path naming a vessel by URN, is kept and known by its path", () => {
  const { a, sent } = alarms();
  const path = "notifications.navigation.closestApproach.urn:mrn:imo:mmsi:230099999";
  a.update(path, anchor({ id: undefined, message: "Vessel close" }));
  assert.deepEqual(sent.at(-1).list.map((x) => [x.id, x.path]), [[path, path]]);
});

test("a path that is not a notification's is ignored", () => {
  const { a, sent } = alarms();
  a.update("navigation.position", anchor());
  a.update("notifications", anchor());
  a.update(`notifications.${"x".repeat(300)}`, anchor());
  a.update(42, anchor());
  assert.equal(sent.length, 0);
});

test("a message is cleaned and cut; sound follows the method, and rings without one", () => {
  const { a, sent } = alarms();
  a.update("notifications.a", anchor({ message: "Anchor\n\u0007  dragging " + "x".repeat(300), method: ["visual"] }));
  const [one] = sent.at(-1).list;
  assert.ok(one.message.startsWith("Anchor dragging x"));
  assert.equal(one.message.length, 200);
  assert.equal(one.sound, false);
  a.update("notifications.a", anchor({ message: 7, method: undefined }));
  assert.equal(sent.at(-1).list[0].message, "");
  assert.equal(sent.at(-1).list[0].sound, true);
});

test("without the server's API the alarm's own action is offered and comes back; without an id nothing can be done", () => {
  // A main server followed in satellite mode does it (lib/satellite.js); this server cannot.
  const none = alarms();
  none.a.update("notifications.a", anchor());
  assert.equal(none.sent.at(-1).list[0].action, "silence");
  assert.equal(none.a.silence(ID), "silence");
  none.a.update("notifications.a", anchor({ state: "emergency" }));
  assert.equal(none.a.silence(ID), "acknowledge");
  const api = fakeApi();
  const noId = alarms({ api });
  noId.a.update("notifications.a", anchor({ id: "not-a-uuid" }));
  const [one] = noId.sent.at(-1).list;
  assert.equal(one.id, "notifications.a");
  assert.equal(one.action, null);
  assert.equal(noId.a.silence("notifications.a"), null);
  assert.deepEqual(api.calls, []);
  assert.equal(new Alarms({ send() {}, api: { silence() {} } }).api, null);
});

test("silence is silenced on the server; an emergency, or one silenced, is acknowledged", () => {
  const api = fakeApi();
  const { a, sent } = alarms({ api });
  a.update("notifications.navigation.anchor", anchor());
  assert.equal(a.silence(ID), "silence");
  a.update("notifications.navigation.anchor", anchor({ method: ["visual"], status: { silenced: true } }));
  assert.equal(sent.at(-1).list[0].action, "acknowledge");
  assert.equal(a.silence(ID), "acknowledge");
  a.update("notifications.mob", anchor({ id: ID2, state: "emergency", status: { canSilence: false } }));
  assert.equal(a.silence(ID2), "acknowledge");
  assert.deepEqual(api.calls, [["silence", ID], ["acknowledge", ID], ["acknowledge", ID2]]);
  a.update("notifications.mob", anchor({ id: ID2, state: "emergency", status: { acknowledged: true } }));
  assert.equal(sent.at(-1).list[0].action, null);
  assert.equal(a.silence(ID2), null);
  a.update("notifications.navigation.anchor", anchor({ status: null }));
  assert.equal(sent.at(-1).list.find((x) => x.id === ID).action, "silence");
  assert.equal(a.silence("unknown"), null);
});

test("a silence the server refuses is reported, not thrown, and the action still returned", () => {
  const debugs = [];
  const refuse = () => { throw new Error("Alarm already silenced or acknowledged!"); };
  const { a } = alarms({ api: { silence: refuse, acknowledge: refuse }, debug: (s) => debugs.push(s) });
  a.update("notifications.navigation.anchor", anchor());
  assert.equal(a.silence(ID), "silence", "the boat's main server may still silence it");
  assert.match(debugs[0], /could not silence notifications\.navigation\.anchor/);
  a.update("notifications.navigation.anchor", anchor({ state: "emergency" }));
  assert.equal(a.silence(ID), "acknowledge", "an emergency stays an acknowledge");
});

test("rank orders the states and knows no others", () => {
  assert.ok(rank("emergency") > rank("alarm") && rank("alarm") > rank("warn") && rank("warn") > rank("alert"));
  assert.equal(rank("normal"), 0);
  assert.equal(rank("constructor"), 0);
  assert.equal(rank(undefined), 0);
});

test("an alarm's path is found by the id the phone knows it by", () => {
  const { a } = alarms({ api: fakeApi() });
  a.update("notifications.navigation.anchor", anchor());
  a.update("notifications.mob", anchor({ id: undefined, state: "emergency" }));
  assert.equal(a.pathOf(ID), "notifications.navigation.anchor");
  assert.equal(a.pathOf("notifications.mob"), "notifications.mob", "one without a server id is known by its path");
  assert.equal(a.pathOf(ID2), null);
});
