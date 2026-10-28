// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * npm on the phone: what `files/bin/npm` runs (the launcher started under the name npm runs the
 * script POCKETSK_NPM_CLI names, native/launcher/main.cc), and what the app's own npm runs
 * (NodeRuntime.npm). It runs npm-cli.js, which POCKETSK_NPM names, with the same arguments, and
 * after a successful install puts the Android builds of native addons in place (prebuilds.js,
 * docs/PLAN.md §9.2), since nothing else on the phone would: Signal K's App Store installs with
 * `--ignore-scripts`.
 *
 * Any other command (`npm --version`, which Signal K runs at every start, `npm view`) runs npm in
 * this process, as npm's own wrapper would, and costs no second node. An install runs npm as a
 * child of this node instead, since npm ends its process when it is done and the builds go in
 * after that. Its output is npm's, and its exit status is npm's: a build that could not be put
 * in place is reported on stderr, and the plugin it belongs to fails when it loads, as it would
 * have without this file.
 */

const { spawn } = require("node:child_process");
const os = require("node:os");
const prebuilds = require("./prebuilds.js");

function main() {
  const cli = process.env.POCKETSK_NPM;
  if (!cli) {
    process.stderr.write("pocketsk npm: POCKETSK_NPM is not set\n");
    process.exitCode = 127;
    return;
  }
  const args = process.argv.slice(2);
  const root = prebuilds.installRoot(args, process.cwd(), process.env);
  if (root == null) {
    // npm reads its arguments from process.argv[2] on, and argv[1] names npm-cli.js, as it does
    // when npm's wrapper runs it.
    process.argv[1] = cli;
    require(cli);
    return;
  }
  const npm = spawn(process.execPath, [cli, ...args], { stdio: "inherit" });
  // The app stops npm by signalling its process group, which reaches the child too; a signal
  // sent to this process alone is passed on, so npm still gets to clean up.
  const forward = (signal) => npm.kill(signal);
  for (const s of ["SIGTERM", "SIGINT", "SIGHUP"]) process.on(s, forward);
  npm.on("error", (e) => {
    process.stderr.write(`pocketsk npm: ${e.message}\n`);
    process.exitCode = 127;
  });
  npm.on("exit", async (code, signal) => {
    for (const s of ["SIGTERM", "SIGINT", "SIGHUP"]) process.off(s, forward);
    process.exitCode = code ?? 128 + (os.constants.signals[signal] ?? 0);
    if (code !== 0) return;
    try {
      const { out, err } = prebuilds.report(await prebuilds.place(root));
      for (const line of out) process.stdout.write(line + "\n");
      for (const line of err) process.stderr.write(line + "\n");
    } catch (e) {
      process.stderr.write(`pocketsk: the Android builds could not be put in place: ${e.message}\n`);
    }
  });
}

main();
