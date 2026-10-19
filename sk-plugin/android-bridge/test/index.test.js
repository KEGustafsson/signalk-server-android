// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const plugin = require("..");
const { socketPath, listen, until } = require("./helpers");

function fakeApp() {
  return {
    status: [],
    deltas: [],
    debugs: [],
    setPluginStatus(s) { this.status.push(s); },
    setPluginError(s) { this.status.push(`error: ${s}`); },
    handleMessage(id, delta) { this.deltas.push([id, delta]); },
    debug(s) { this.debugs.push(s); },
  };
}

test("the plugin has a schema whose defaults start it", () => {
  const p = plugin(fakeApp(), { env: {} });
  const schema = p.schema();
  assert.equal(schema.type, "object");
  assert.equal(schema.properties.battery.default, true);
  assert.equal(p.id, "pocketsk-android-bridge");
});

test("without the app's socket the plugin says so and does nothing", () => {
  const app = fakeApp();
  const p = plugin(app, { env: {} });
  p.start({});
  assert.deepEqual(app.status, ["Not running on Pocket SK: there is no phone to bridge"]);
  p.stop();
  assert.equal(p.statusNow().connected, false);
});

test("on the phone it subscribes and turns sensor lines into deltas", async (t) => {
  const sock = socketPath();
  let subscribe = "";
  let conn = null;
  const server = await listen(sock, (c) => {
    conn = c;
    c.setEncoding("utf8");
    c.on("data", (d) => (subscribe += d));
    c.write('{"t":"hello","v":9}\n');
    c.write('{"t":"hello","v":1,"app":"1.200"}\n');
    c.write('{"t":"sensor","path":"environment.outside.pressure","value":101000,"src":"android.baro","ts":0}\n');
    c.write('{"t":"sensor","path":"bad path","value":1,"src":"android.baro"}\n');
  });
  const app = fakeApp();
  const p = plugin(app, { env: { POCKETSK_BRIDGE: sock } });
  t.after(async () => {
    p.stop();
    await server.closeAll();
  });
  p.start({ battery: false, gnss: false, barometerSeconds: 5 });
  await until(() => app.deltas.length === 1 && p.statusNow().dropped === 1 && subscribe.endsWith("\n"));
  assert.deepEqual(JSON.parse(subscribe), { t: "subscribe", sensors: ["baro"], rateHz: { baro: 0.2, gnss: 1 } });
  assert.equal(app.deltas[0][0], "pocketsk-android-bridge");
  assert.equal(app.deltas[0][1].updates[0].values[0].value, 101000);
  assert.ok(app.status.includes("Connected to the phone"));
  assert.ok(app.debugs.some((d) => d.includes("bridge version 9")));
  assert.deepEqual(p.statusNow(), { connected: true, app: "1.200", sensors: 1, dropped: 1 });
  conn.destroy();
  await until(() => app.status.at(-1).startsWith("Waiting for the phone"));
});
