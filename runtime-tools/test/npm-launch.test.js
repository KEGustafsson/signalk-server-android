// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const http = require("node:http");
const os = require("node:os");
const path = require("node:path");
const zlib = require("node:zlib");

const LAUNCH = path.join(__dirname, "..", "npm", "npm-launch.js");

/**
 * A stand-in for npm-cli.js: records how it was run in $FAKE_NPM_LOG, for an install creates
 * node_modules/needs (a package with a binding.gyp), and exits with $FAKE_NPM_EXIT.
 */
const FAKE_NPM = `
const fs = require("node:fs");
const path = require("node:path");
fs.writeFileSync(process.env.FAKE_NPM_LOG, JSON.stringify({ argv1: process.argv[1], args: process.argv.slice(2), pid: process.pid }));
if (process.argv.includes("install")) {
  const dir = path.join(process.cwd(), "node_modules", "needs");
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, "package.json"), JSON.stringify({ name: "needs", version: "1.0.0" }));
  fs.writeFileSync(path.join(dir, "binding.gyp"), "{}");
}
process.exitCode = Number(process.env.FAKE_NPM_EXIT || 0);
`;

function tmp() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "pocketsk-npm-launch-"));
}

function tar(name, data) {
  const h = Buffer.alloc(512);
  h.write(name, 0);
  h.write(data.length.toString(8).padStart(11, "0") + "\0", 124);
  h.write("0", 156);
  return Buffer.concat([h, data, Buffer.alloc((512 - (data.length % 512)) % 512), Buffer.alloc(1024)]);
}

/** Runs npm-launch.js with [args] in [cwd]; resolves to its exit code, output and the fake's record. */
function run(args, { cwd, env = {} }) {
  const dir = tmp();
  const cli = path.join(dir, "npm-cli.js");
  const log = path.join(dir, "log.json");
  fs.writeFileSync(cli, FAKE_NPM);
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [LAUNCH, ...args], {
      cwd,
      env: { PATH: process.env.PATH, POCKETSK_NPM: cli, FAKE_NPM_LOG: log, ...env },
    });
    let out = "";
    let err = "";
    child.stdout.on("data", (d) => (out += d));
    child.stderr.on("data", (d) => (err += d));
    child.on("close", (code) => {
      const record = fs.existsSync(log) ? JSON.parse(fs.readFileSync(log, "utf8")) : null;
      resolve({ code, out, err, record, cli, pid: child.pid });
    });
  });
}

test("a command that installs nothing runs npm in this process, with npm-cli.js as argv[1]", async () => {
  const r = await run(["--version"], { cwd: tmp() });
  assert.equal(r.code, 0);
  assert.equal(r.record.argv1, r.cli);
  assert.deepEqual(r.record.args, ["--version"]);
  assert.equal(r.record.pid, r.pid, "no second node");
});

test("npm's exit status is the launcher's, and a failed install puts nothing in place", async () => {
  assert.equal((await run(["view", "x"], { cwd: tmp(), env: { FAKE_NPM_EXIT: "4" } })).code, 4);
  const cwd = tmp();
  const r = await run(["install", "--ignore-scripts"], { cwd, env: { FAKE_NPM_EXIT: "3", POCKETSK_PREBUILDS: tmp(), POCKETSK_PREBUILDS_URL: "http://127.0.0.1:9/" } });
  assert.equal(r.code, 3);
  assert.notEqual(r.record.pid, r.pid, "an install runs npm as a child");
  assert.equal(r.err, "", "no attempt at the builds after npm failed");
});

test("after an install the builds are put in place, and npm's arguments reach it unchanged", async (t) => {
  const tarball = zlib.gzipSync(tar("build/Release/needs.node", Buffer.from("built for android")));
  const entry = {
    name: "needs",
    version: "1.0.0",
    platform: process.platform,
    arch: process.arch,
    abi: "napi",
    file: "needs@1.0.0-test.tar.gz",
    sha256: crypto.createHash("sha256").update(tarball).digest("hex"),
    size: tarball.length,
    files: ["build/Release/needs.node"],
  };
  const files = { "/index.json": Buffer.from(JSON.stringify({ format: 1, entries: [entry] })), "/needs%401.0.0-test.tar.gz": tarball };
  const server = http.createServer((req, res) => {
    const body = files[req.url];
    res.writeHead(body ? 200 : 404);
    res.end(body);
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  t.after(() => server.close());
  const cwd = tmp();
  const args = ["--save", "--ignore-scripts", "install", "--", "needs@1.0.0"];
  const r = await run(args, { cwd, env: { POCKETSK_PREBUILDS: tmp(), POCKETSK_PREBUILDS_URL: `http://127.0.0.1:${server.address().port}/` } });
  assert.equal(r.code, 0, r.err);
  assert.deepEqual(r.record.args, args);
  assert.equal(fs.readFileSync(path.join(cwd, "node_modules/needs/build/Release/needs.node"), "utf8"), "built for android");
  assert.match(r.out, /put the Android build of needs@1\.0\.0 in place/);
});

test("without POCKETSK_NPM there is no npm to run", async () => {
  const child = spawn(process.execPath, [LAUNCH, "--version"], { env: { PATH: process.env.PATH } });
  let err = "";
  child.stderr.on("data", (d) => (err += d));
  const code = await new Promise((r) => child.on("close", r));
  assert.equal(code, 127);
  assert.match(err, /POCKETSK_NPM is not set/);
});
