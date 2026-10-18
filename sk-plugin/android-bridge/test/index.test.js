// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const plugin = require("..");

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

function until(check, ms = 3000) {
  return new Promise((resolve, reject) => {
    const end = Date.now() + ms;
    (function poll() {
      if (check()) return resolve();
      if (Date.now() > end) return reject(new Error("timed out"));
      setTimeout(poll, 10);
    })();
  });
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

test("on the phone it subscribes and turns sensor lines into deltas", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bridge-"));
  const sock = path.join(dir, "bridge.sock");
  let subscribe = "";
  let conn = null;
  const server = await new Promise((resolve) => {
    const s = net.createServer((c) => {
      conn = c;
      c.setEncoding("utf8");
      c.on("data", (d) => (subscribe += d));
      c.write('{"t":"hello","v":9}\n');
      c.write('{"t":"hello","v":1,"app":"1.200"}\n');
      c.write('{"t":"sensor","path":"environment.outside.pressure","value":101000,"src":"android.baro","ts":0}\n');
      c.write('{"t":"sensor","path":"bad path","value":1,"src":"android.baro"}\n');
    });
    s.listen(sock, () => resolve(s));
  });
  const app = fakeApp();
  const p = plugin(app, { env: { POCKETSK_BRIDGE: sock } });
  p.start({ battery: false, barometerSeconds: 5 });
  await until(() => app.deltas.length === 1 && p.statusNow().dropped === 1 && subscribe.endsWith("\n"));
  assert.deepEqual(JSON.parse(subscribe), { t: "subscribe", sensors: ["baro"], rateHz: { baro: 0.2 } });
  assert.equal(app.deltas[0][0], "pocketsk-android-bridge");
  assert.equal(app.deltas[0][1].updates[0].values[0].value, 101000);
  assert.ok(app.status.includes("Connected to the phone"));
  assert.ok(app.debugs.some((d) => d.includes("bridge version 9")));
  assert.deepEqual(p.statusNow(), { connected: true, app: "1.200", sensors: 1, dropped: 1 });
  conn.destroy();
  server.close();
  await until(() => app.status.at(-1).startsWith("Waiting for the phone"));
  p.stop();
});
