// SPDX-License-Identifier: EUPL-1.2
"use strict";

const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");
const { pipeline } = require("node:stream/promises");
const { Transform } = require("node:stream");

/**
 * Files into the server's data folders from a browser (docs/PLAN.md §16 9.5.10): offline charts
 * into `charts`, which the charts plugin watches, or any other data folder of `.signalk`. The
 * phone may hold that folder on its own storage or on a card (the app links it there), so the
 * upload goes to wherever the folder really is.
 *
 * In parts: a browser sends a file in pieces of at most [MAX_PIECE] bytes, each a request of its
 * own that says where in the file it starts, so a chart of several gigabytes needs no request
 * longer than Node's five minutes, and an upload a dropped Wi-Fi cut short carries on from the
 * last piece that arrived. The pieces gather in `.pocketsk-uploads` beside the folder, on the
 * same volume, named by the file and its whole size, and the finished file is renamed into the
 * folder: the charts plugin never sees half a chart, and its watcher is not woken by every piece.
 *
 * Only the server's data folders: a name of letters, digits, `.`, `_` and `-`, not hidden, not
 * `node_modules` and not `plugin-config-data` (the plugins and their settings); a file name with
 * no path in it. The routes are the plugin's own, which signalk-server lets admins alone use when
 * security is on.
 */

const UPLOADS = ".pocketsk-uploads";
/** The largest piece one request may carry. */
const MAX_PIECE = 64 * 1024 * 1024;
/** Room kept free beyond what an upload still needs, for the server's own writes. */
const MARGIN = 64 * 1024 * 1024;
const FOLDER = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
const NOT_DATA = new Set(["node_modules", "plugin-config-data"]);

/** An error with the HTTP status the route answers with. */
class FilesError extends Error {
  constructor(status, message, extra = {}) {
    super(message);
    this.status = status;
    this.extra = extra;
  }
}

/** Whether `name` is one of the server's data folders. */
function validFolder(name) {
  return typeof name === "string" && FOLDER.test(name) && !NOT_DATA.has(name) && !name.endsWith(".partial") && !name.endsWith(".moved");
}

/** Whether `name` is a plain file name: no path, not hidden, not too long. */
function validName(name) {
  return typeof name === "string" && name.length > 0 && Buffer.byteLength(name) <= 200 && !name.startsWith(".") && !/[/\\\0]/.test(name) && name.trim() === name;
}

/** A whole number of bytes from a query string, or null. */
function bytes(value) {
  if (typeof value !== "string" || !/^\d{1,15}$/.test(value)) return null;
  return Number(value);
}

class Files {
  /**
   * @param {object} o
   * @param {string} o.configDir the server's `.signalk`
   * @param {(dir: string) => Promise<number>} [o.free] bytes free on a directory's volume
   */
  constructor({ configDir, free = freeBytes }) {
    this.configDir = configDir;
    this.free = free;
    /** The uploads a request is writing now, by their staging path: one writer each. */
    this.busy = new Set();
  }

  /** The data folders there are, and `charts` always, sorted. */
  async folders() {
    const names = new Set(["charts"]);
    for (const e of await fsp.readdir(this.configDir, { withFileTypes: true }).catch(() => [])) {
      if (validFolder(e.name) && (e.isDirectory() || e.isSymbolicLink())) names.add(e.name);
    }
    return [...names].sort();
  }

  /** A folder's files, the uploads under way into it, and the room left where it is. */
  async list(folder) {
    const dir = await this.folder(folder, false);
    const files = [];
    for (const e of await fsp.readdir(dir, { withFileTypes: true }).catch(() => [])) {
      if (!e.isFile() || e.name.startsWith(".")) continue;
      const st = await fsp.stat(path.join(dir, e.name));
      files.push({ name: e.name, size: st.size, modified: st.mtime.toISOString() });
    }
    const uploads = [];
    const staging = this.staging(dir, folder);
    for (const e of await fsp.readdir(staging, { withFileTypes: true }).catch(() => [])) {
      const m = /^(.*)\.(\d+)\.part$/.exec(e.name);
      if (!e.isFile() || !m) continue;
      const st = await fsp.stat(path.join(staging, e.name));
      uploads.push({ name: m[1], size: Number(m[2]), received: st.size });
    }
    files.sort((a, b) => a.name.localeCompare(b.name));
    uploads.sort((a, b) => a.name.localeCompare(b.name));
    return { folder, free: await this.free(dir), files, uploads };
  }

  /**
   * One piece of the file `name` of `total` bytes, starting at `offset`, read from `body`. A piece
   * must start where the last one ended: otherwise the answer is 409 with what has arrived, so a
   * browser knows where to carry on (offset 0 starts the file again). Returns the bytes received
   * and whether the file is whole, and in place.
   */
  async put(folder, name, offset, total, length, body) {
    if (!validName(name)) throw new FilesError(400, "not a file name");
    if (offset === null || total === null || offset > total) throw new FilesError(400, "offset and total are byte counts, offset no more than total");
    if (length === null || length > MAX_PIECE || offset + length > total) {
      throw new FilesError(400, `a piece is at most ${MAX_PIECE} bytes and ends within the file`);
    }
    const dir = await this.folder(folder, true);
    const staging = this.staging(dir, folder);
    await fsp.mkdir(staging, { recursive: true });
    const part = path.join(staging, `${name}.${total}.part`);
    if (this.busy.has(part)) throw new FilesError(409, "this file is being written by another request", { received: null });
    this.busy.add(part);
    try {
      const have = offset === 0 ? 0 : await size(part);
      if (have !== offset) throw new FilesError(409, `the upload is at ${have} bytes`, { received: have });
      const need = total - offset;
      const room = await this.free(staging);
      if (room < need + MARGIN) throw new FilesError(507, `the file needs ${mb(need)} MB more, and there are ${mb(room)} MB free`);
      let seen = 0;
      const count = new Transform({
        transform(chunk, _enc, done) {
          seen += chunk.length;
          if (seen > length) done(new FilesError(400, "the piece is longer than it said"));
          else done(null, chunk);
        },
      });
      await pipeline(body, count, fs.createWriteStream(part, { flags: offset === 0 ? "w" : "r+", start: offset }));
      if (seen !== length) throw new FilesError(400, `the piece was ${seen} bytes, not ${length}`, { received: offset + seen });
      const received = offset + seen;
      if (received < total) return { received, done: false };
      // Whole: into the folder, over a file of the same name (a newer chart of the same area).
      await fsp.rename(part, path.join(dir, name));
      return { received, done: true };
    } finally {
      this.busy.delete(part);
    }
  }

  /** Deletes the file `name` in the folder, or the upload of it under way when `upload` is set. */
  async remove(folder, name, upload) {
    if (!validName(name)) throw new FilesError(400, "not a file name");
    const dir = await this.folder(folder, false);
    if (upload) {
      const staging = this.staging(dir, folder);
      const parts = (await fsp.readdir(staging).catch(() => [])).filter((f) => /^(.*)\.\d+\.part$/.exec(f)?.[1] === name);
      if (parts.length === 0) throw new FilesError(404, "no such upload");
      for (const f of parts) await fsp.rm(path.join(staging, f), { force: true });
      return;
    }
    const file = path.join(dir, name);
    const st = await fsp.lstat(file).catch(() => null);
    if (!st || !st.isFile()) throw new FilesError(404, "no such file");
    await fsp.rm(file);
  }

  /**
   * The real path of the data folder `name` (through the app's link to a card), made when `make`
   * is set and it is not there; 404 when it is not and may not be made.
   */
  async folder(name, make) {
    if (!validFolder(name)) throw new FilesError(400, "not one of the server's data folders");
    const dir = path.join(this.configDir, name);
    if (make) await fsp.mkdir(dir, { recursive: true });
    try {
      return await fsp.realpath(dir);
    } catch {
      throw new FilesError(404, "no such folder");
    }
  }

  /** Where the pieces of a folder's uploads gather: beside the folder, on its volume. */
  staging(realDir, folder) {
    return path.join(path.dirname(realDir), UPLOADS, folder);
  }
}

async function size(file) {
  try {
    return (await fsp.stat(file)).size;
  } catch {
    return 0;
  }
}

/** Bytes free to this process on a directory's volume. */
async function freeBytes(dir) {
  const s = await fsp.statfs(dir);
  return s.bavail * s.bsize;
}

function mb(n) {
  return Math.floor(n / (1024 * 1024));
}

module.exports = { Files, FilesError, validFolder, validName, bytes, MAX_PIECE, UPLOADS };
