#!/usr/bin/env node
// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Builds the native addons of packages.json for Android (docs/PLAN.md §9.1), the builds the phone
 * cannot make itself: it has no compiler, and npm finds no Android build of these packages
 * anywhere else. Run by .github/workflows/prebuilds.yml; by hand:
 *
 *   node prebuilds/build.js --ndk <ndk dir> --nodejs-mobile <unpacked release zip> \
 *     --nodejs-mobile-version 24.20.0-0 --out <dir> [--index <published index.json>] [--strict]
 *
 * Each version is fetched with `npm pack`, its dependencies installed with `--ignore-scripts`
 * (node-addon-api's headers, mostly), and built with node-gyp: the NDK's clang for arm64 at
 * API 29 (the app's minSdk), Node's headers for the version nodejs-mobile's headers name, and
 * linked against nodejs-mobile's libnode.so, as nodejs-mobile builds addons for Android itself.
 * That link is the one R2 (§3) has still to settle on a phone; an addon linked so loads either
 * way, since libnode.so is already in the process that loads it. 16 KB pages (R6) are allowed
 * for. gyp's OS stays "linux": the packages' binding.gyp files know Linux, whose sources build
 * against bionic, and none of them knows Android.
 *
 * Every `.node` the build leaves in build/Release is checked to be an AArch64 shared object,
 * stripped, and packed as build/Release/<name>.node, the path the `bindings` package looks in,
 * into <out>/<asset>.tar.gz, made the same way every time (sorted, owner 0, mtime 0, gzip -n).
 * <out>/index.json is the published index with this run's builds joined in. A version already in
 * the published index is not built again: N-API builds keep loading across Node releases.
 *
 * A build that fails is reported and the rest carry on, so one package's new release cannot hold
 * back the others; with --strict (pull requests, where packages.json or this script changed) it
 * fails the run.
 */

const { execFileSync } = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const lib = require("./lib.js");

/** The node-gyp that builds, pinned: the build must not change because node-gyp did. */
const NODE_GYP = "node-gyp@13.1.0";
/** Android's API level the addons are compiled for: the app's minSdk. */
const API = 29;
const PLATFORM = "android";
const ARCH = "arm64";
const ABI = "arm64-v8a";

function args(argv) {
  const out = { strict: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--strict") out.strict = true;
    else if (a.startsWith("--")) out[a.slice(2)] = argv[++i];
  }
  for (const need of ["ndk", "nodejs-mobile", "nodejs-mobile-version", "out"]) {
    if (!out[need]) throw new Error(`--${need} is missing`);
  }
  // The builds run in a directory of their own, so every path given is made absolute here.
  for (const dir of ["ndk", "nodejs-mobile", "out", "index"]) if (out[dir]) out[dir] = path.resolve(out[dir]);
  return out;
}

/** The Node version nodejs-mobile's headers are, from node_version.h: the headers node-gyp fetches. */
function nodeVersion(nodejsMobileDir) {
  const h = fs.readFileSync(path.join(nodejsMobileDir, "include/node/node_version.h"), "utf8");
  const part = (n) => new RegExp(`#define NODE_${n}_VERSION (\\d+)`).exec(h)?.[1];
  const v = `${part("MAJOR")}.${part("MINOR")}.${part("PATCH")}`;
  if (!lib.parseVersion(v)) throw new Error(`no Node version in ${nodejsMobileDir}/include/node/node_version.h`);
  return v;
}

function run(cmd, argv, opts = {}) {
  return execFileSync(cmd, argv, { stdio: ["ignore", "inherit", "inherit"], ...opts });
}

function view(name, range) {
  const out = execFileSync("npm", ["view", `${name}@${range}`, "version", "--json"], { encoding: "utf8" }).trim();
  return out ? JSON.parse(out) : [];
}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

/** Builds one version; returns its index entry. Throws with the reason when it cannot. */
function build(name, version, o) {
  const work = fs.mkdtempSync(path.join(os.tmpdir(), "prebuild-"));
  try {
    run("npm", ["pack", `${name}@${version}`, "--pack-destination", work, "--silent"], { stdio: ["ignore", "ignore", "inherit"] });
    const tgz = fs.readdirSync(work).find((f) => f.endsWith(".tgz"));
    if (!tgz) throw new Error("npm pack left no tarball");
    run("tar", ["-xzf", path.join(work, tgz), "-C", work]);
    const pkg = path.join(work, "package");
    if (!fs.existsSync(path.join(pkg, "binding.gyp"))) throw new Error("no binding.gyp: nothing to build");
    run("npm", ["install", "--ignore-scripts", "--omit=dev", "--no-audit", "--no-fund", "--no-package-lock"], { cwd: pkg });

    const bin = path.join(o.ndk, "toolchains/llvm/prebuilt/linux-x86_64/bin");
    const clang = path.join(bin, `aarch64-linux-android${API}-clang`);
    const env = {
      ...process.env,
      CC: clang,
      CXX: `${clang}++`,
      LINK: `${clang}++`,
      AR: path.join(bin, "llvm-ar"),
      LDFLAGS: `-L${path.join(o["nodejs-mobile"], "bin", ABI)} -lnode -Wl,-z,max-page-size=16384`,
    };
    const jobs = String(os.availableParallelism ? os.availableParallelism() : os.cpus().length);
    const preGyp = lib.preGypArgs(JSON.parse(fs.readFileSync(path.join(pkg, "package.json"), "utf8")), path.join(pkg, "build/binding"));
    run("npx", ["--yes", NODE_GYP, "rebuild", "--release", "-j", jobs, `--target=${o.node}`, `--arch=${ARCH}`, `--devdir=${o.devdir}`, ...preGyp], { cwd: pkg, env });

    const release = path.join(pkg, "build/Release");
    const files = fs.existsSync(release) ? fs.readdirSync(release).filter((f) => f.endsWith(".node")).sort() : [];
    if (files.length === 0) throw new Error("the build left no .node in build/Release");
    for (const f of files) {
      const file = path.join(release, f);
      const head = Buffer.alloc(20);
      const fd = fs.openSync(file, "r");
      fs.readSync(fd, head, 0, 20, 0);
      fs.closeSync(fd);
      if (!lib.isAarch64SharedObject(head)) throw new Error(`${f} is not an AArch64 shared object`);
      run(path.join(bin, "llvm-strip"), ["--strip-unneeded", file]);
    }

    const asset = lib.assetName(name, version, PLATFORM, ARCH);
    const tarball = path.join(o.out, asset);
    const members = files.map((f) => `build/Release/${f}`);
    const tar = execFileSync("tar", ["--sort=name", "--mtime=@0", "--owner=0", "--group=0", "--numeric-owner", "-cf", "-", ...members], { cwd: pkg, maxBuffer: 1 << 30 });
    fs.writeFileSync(tarball, execFileSync("gzip", ["-n", "-9"], { input: tar, maxBuffer: 1 << 30 }));
    return { name, version, platform: PLATFORM, arch: ARCH, file: asset, sha256: sha256(tarball), size: fs.statSync(tarball).size, files: members };
  } finally {
    fs.rmSync(work, { recursive: true, force: true });
  }
}

function main() {
  const o = args(process.argv.slice(2));
  o.node = nodeVersion(o["nodejs-mobile"]);
  o.devdir = path.join(os.tmpdir(), "prebuild-node-gyp");
  fs.mkdirSync(o.out, { recursive: true });
  const config = JSON.parse(fs.readFileSync(path.join(__dirname, "packages.json"), "utf8"));
  const published = o.index && fs.existsSync(o.index) ? JSON.parse(fs.readFileSync(o.index, "utf8")) : null;

  const built = [];
  const failed = [];
  const kept = [];
  for (const p of config.packages) {
    if (p.skip) {
      console.log(`${p.name}: skipped (${p.skip})`);
      continue;
    }
    for (const range of p.versions) {
      for (const version of lib.pickVersions(view(p.name, range), config.keep)) {
        if (lib.has(published, p.name, version, PLATFORM, ARCH)) {
          kept.push(`${p.name}@${version}`);
          continue;
        }
        console.log(`::group::${p.name}@${version}`);
        try {
          built.push(build(p.name, version, o));
        } catch (e) {
          failed.push({ what: `${p.name}@${version}`, why: e.message });
        }
        console.log("::endgroup::");
      }
    }
  }

  fs.writeFileSync(path.join(o.out, "index.json"), JSON.stringify(lib.mergeIndex(published, built, o["nodejs-mobile-version"]), null, 2) + "\n");
  const summary = [
    `Built for ${PLATFORM}-${ARCH} against nodejs-mobile ${o["nodejs-mobile-version"]} (Node ${o.node} headers).`,
    "",
    ...built.map((e) => `- built ${e.name}@${e.version}: ${e.file}, ${e.size} bytes`),
    ...kept.map((k) => `- already published: ${k}`),
    ...failed.map((f) => `- **failed** ${f.what}: ${f.why}`),
  ].join("\n");
  console.log(summary);
  if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary + "\n");
  for (const f of failed) console.log(`::${o.strict ? "error" : "warning"}::${f.what} did not build: ${f.why}`);
  if (o.strict && failed.length > 0) process.exitCode = 1;
}

if (require.main === module) main();
