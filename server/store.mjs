// Row log + media store for the Dimitris' App sync server.
//
// Storage layout (everything under DATA_DIR):
//   rows.jsonl        one JSON object per line: {"seq":1,"table":"items","row":{...}}
//   media/<sha256>    media blobs, named by the lowercase hex sha-256 of their bytes
//
// The whole current state is kept in memory (Map<table, Map<id, {seq,row}>>); the
// file is an append-only log that is replayed on start. Two caregiver phones and a
// few thousand rows fit in memory many times over.
//
// Node 22 built-ins only. No dependencies.

import {
  appendFileSync,
  createWriteStream,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  statSync,
  truncateSync,
  unlinkSync,
} from 'node:fs';
import { rename, rm } from 'node:fs/promises';
import { createHash, randomBytes } from 'node:crypto';
import { join } from 'node:path';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';

/** Every table the app syncs. A row for any other table is rejected. */
export const TABLES = Object.freeze([
  'items',
  'recordings',
  'attempts',
  'schedules',
  'sessions',
  'error_logs',
  'scripts',
  'script_lines',
]);

/**
 * Append-only tables: a row is immutable once stored, so the first row seen for
 * an id wins and later rows with the same id are ignored. Every other table
 * merges last-write-wins on `updatedAt` (ties keep the row already stored).
 */
export const APPEND_ONLY_TABLES = Object.freeze(['attempts', 'error_logs']);

/** Body limits, enforced by counting bytes (see server.mjs). */
export const MEDIA_MAX_BYTES = 20 * 1024 * 1024; // 20 MB
export const JSON_MAX_BYTES = 5 * 1024 * 1024; //  5 MB

/** Pull page size. `limit` is clamped into 1..500 and defaults to 500. */
export const PULL_MAX_LIMIT = 500;
export const PULL_DEFAULT_LIMIT = 500;

const SHA256_RE = /^[0-9a-f]{64}$/;
const NEWLINE = 0x0a;

/** An error with a machine-readable `code` the HTTP layer maps to a status. */
export class SyncError extends Error {
  constructor(code, message, extra = {}) {
    super(message);
    this.name = 'SyncError';
    this.code = code;
    Object.assign(this, extra);
  }
}

export function isSha256(sha) {
  return typeof sha === 'string' && SHA256_RE.test(sha);
}

/** `limit` clamped into 1..PULL_MAX_LIMIT; anything unparseable falls back to the default. */
export function clampLimit(limit) {
  const n = Number(limit);
  if (!Number.isFinite(n)) return PULL_DEFAULT_LIMIT;
  return Math.min(PULL_MAX_LIMIT, Math.max(1, Math.floor(n)));
}

/** The sync id of a row: a non-empty string, or a finite number rendered as one. */
export function rowId(row) {
  if (!row || typeof row !== 'object' || Array.isArray(row)) return null;
  const id = row.id;
  if (typeof id === 'string' && id.length > 0) return id;
  if (typeof id === 'number' && Number.isFinite(id)) return String(id);
  return null;
}

/** Is `a` a strictly newer `updatedAt` than `b`? Numbers compare numerically, anything else as text. */
export function isNewer(a, b) {
  if (typeof a === 'number' && typeof b === 'number') return a > b;
  return String(a) > String(b);
}

export class Store {
  /** @param {string} dir DATA_DIR — created if missing. */
  constructor(dir) {
    this.dir = dir;
    this.rowsFile = join(dir, 'rows.jsonl');
    this.mediaDir = join(dir, 'media');
    /** @type {Map<string, Map<string, {seq:number,row:object}>>} */
    this.tables = new Map(TABLES.map((t) => [t, new Map()]));
    this.appendOnly = new Set(APPEND_ONLY_TABLES);
    this.seq = 0;
    this.skippedLines = 0;
    mkdirSync(this.mediaDir, { recursive: true });
    this.sweepTempMedia();
    this.load();
  }

  isAppendOnly(table) {
    return this.appendOnly.has(table);
  }

  /**
   * Delete `media/*.tmp` files left by a hard kill (SIGKILL, power loss) between
   * opening the temp file and the rename. Only files older than this process are
   * touched, so an upload running right now is never disturbed.
   * @returns {number} how many were removed
   */
  sweepTempMedia() {
    const processStart = Date.now() - Math.floor(process.uptime() * 1000);
    let removed = 0;
    let names = [];
    try {
      names = readdirSync(this.mediaDir);
    } catch {
      return 0;
    }
    for (const name of names) {
      if (!name.endsWith('.tmp')) continue;
      const file = join(this.mediaDir, name);
      try {
        if (statSync(file).mtimeMs >= processStart) continue; // an upload in flight
        unlinkSync(file);
        removed++;
      } catch {
        // Raced with something else removing it - nothing to do.
      }
    }
    if (removed > 0) {
      console.warn(`[store] removed ${removed} orphaned media temp file(s) from an earlier run`);
    }
    return removed;
  }

  /**
   * Replay rows.jsonl. A crash can leave a half-written last line: if the file
   * does not end in a newline the partial tail is truncated away, so the next
   * append cannot glue itself onto it. Any line that still fails to parse (or
   * that is not a well-formed entry) is skipped with a warning instead of
   * taking the server down.
   */
  load() {
    if (!existsSync(this.rowsFile)) return;
    const buf = readFileSync(this.rowsFile);
    let end = buf.length;
    if (end > 0 && buf[end - 1] !== NEWLINE) {
      const keep = buf.lastIndexOf(NEWLINE) + 1; // 0 when there is no newline at all
      console.warn(`[store] rows.jsonl ends with a partial line (${end - keep} bytes) - truncating it`);
      truncateSync(this.rowsFile, keep);
      end = keep;
    }
    // Decoded one line at a time, straight out of the buffer. Joining the whole file into a
    // single string first cost roughly three copies of it at peak and, worse, V8 refuses a string
    // over about half a gigabyte outright - so a log that grew that far would be a server that
    // will not start rather than a server that is slow. This way only one line is ever a string.
    let lineNo = 0;
    for (let start = 0; start < end; ) {
      let stop = buf.indexOf(NEWLINE, start);
      if (stop === -1 || stop > end) stop = end;
      const line = buf.toString('utf8', start, stop);
      start = stop + 1;
      lineNo++;
      if (line.trim() === '') continue;
      let entry = null;
      try {
        entry = JSON.parse(line);
      } catch {
        this.#skip(lineNo, 'not valid JSON');
        continue;
      }
      if (!entry || typeof entry !== 'object' || !Number.isInteger(entry.seq) || entry.seq < 1) {
        this.#skip(lineNo, 'missing or invalid seq');
        continue;
      }
      if (!this.tables.has(entry.table)) {
        this.#skip(lineNo, `unknown table ${JSON.stringify(entry.table)}`);
        continue;
      }
      const id = rowId(entry.row);
      if (id === null) {
        this.#skip(lineNo, 'row has no usable id');
        continue;
      }
      // Replayed in file order, so the last entry for an id wins - the same
      // outcome apply() produced when the line was written.
      this.tables.get(entry.table).set(id, { seq: entry.seq, row: entry.row });
      if (entry.seq > this.seq) this.seq = entry.seq;
    }
    if (this.skippedLines > 0) {
      console.warn(`[store] skipped ${this.skippedLines} malformed line(s) in rows.jsonl`);
    }
  }

  #skip(lineNo, why) {
    this.skippedLines++;
    console.warn(`[store] skipping rows.jsonl line ${lineNo}: ${why}`);
  }

  /**
   * Check a pushed row before anything is written.
   * @returns {string} the sync id
   * @throws {SyncError} code BAD_TABLE or BAD_ROW
   */
  validate(table, row) {
    if (!this.tables.has(table)) {
      throw new SyncError('BAD_TABLE', `unknown table ${JSON.stringify(table)}`, { table });
    }
    const id = rowId(row);
    if (id === null) {
      throw new SyncError('BAD_ROW', 'row must be an object with a non-empty id', { table });
    }
    if (!this.isAppendOnly(table)) {
      const u = row.updatedAt;
      const ok = (typeof u === 'number' && Number.isFinite(u)) || (typeof u === 'string' && u.length > 0);
      if (!ok) {
        throw new SyncError('BAD_ROW', 'row must carry a numeric or string updatedAt', { table, id });
      }
    }
    return id;
  }

  /**
   * Merge one row. Accepted rows get the next seq and are appended to the log.
   * @returns {{accepted:boolean, seq:number, reason?:string}}
   */
  apply(table, row) {
    const id = this.validate(table, row);
    const index = this.tables.get(table);
    const existing = index.get(id);
    if (existing) {
      if (this.isAppendOnly(table)) {
        return { accepted: false, seq: existing.seq, reason: 'append-only: id already stored' };
      }
      if (!isNewer(row.updatedAt, existing.row.updatedAt)) {
        return { accepted: false, seq: existing.seq, reason: 'not newer than the stored row' };
      }
    }
    const seq = this.seq + 1;
    try {
      appendFileSync(this.rowsFile, `${JSON.stringify({ seq, table, row })}\n`);
    } catch (err) {
      throw new SyncError('WRITE_FAILED', `could not append to rows.jsonl: ${err.message}`);
    }
    this.seq = seq;
    index.set(id, { seq, row });
    return { accepted: true, seq };
  }

  /**
   * Rows with seq > `sinceSeq`, in seq order, at most `limit` (clamped 1..500).
   * Only the current version of each row is ever returned - a row that was
   * superseded carries the newer seq, so a client can never miss an update.
   * @returns {{rows: Array<{seq:number, table:string, row:object}>, seq:number}}
   */
  since(sinceSeq = 0, limit = PULL_DEFAULT_LIMIT) {
    const n = Number(sinceSeq);
    const from = Number.isFinite(n) && n > 0 ? Math.floor(n) : 0;
    const max = clampLimit(limit);
    const out = [];
    for (const [table, index] of this.tables) {
      for (const entry of index.values()) {
        if (entry.seq > from) out.push({ seq: entry.seq, table, row: entry.row });
      }
    }
    out.sort((a, b) => a.seq - b.seq);
    return { rows: out.slice(0, max), seq: this.seq };
  }

  // ---- media -------------------------------------------------------------

  /** Absolute path of a blob. The sha is validated, so no traversal is possible. */
  path(sha) {
    if (!isSha256(sha)) throw new SyncError('BAD_SHA', 'sha must be 64 lowercase hex characters', { sha });
    return join(this.mediaDir, sha);
  }

  has(sha) {
    return isSha256(sha) && existsSync(join(this.mediaDir, sha));
  }

  /** Byte size of a stored blob, or null when it is not stored. */
  size(sha) {
    if (!this.has(sha)) return null;
    return statSync(join(this.mediaDir, sha)).size;
  }

  /**
   * Stream a blob in, hashing as it goes, and keep it only if the hash matches.
   * Written to `<sha>.<random>.tmp` and renamed, so a reader never sees a
   * partial file and two concurrent uploads of the same sha cannot clobber
   * each other. On any failure the temp file is removed.
   * @throws {SyncError} code BAD_SHA, TOO_LARGE or HASH_MISMATCH
   */
  async put(sha, stream, maxBytes = MEDIA_MAX_BYTES) {
    const target = this.path(sha); // validates the sha
    const tmp = `${target}.${randomBytes(6).toString('hex')}.tmp`;
    const hash = createHash('sha256');
    let bytes = 0;
    const meter = new Transform({
      transform(chunk, _enc, cb) {
        bytes += chunk.length;
        if (bytes > maxBytes) {
          cb(new SyncError('TOO_LARGE', `media body exceeds ${maxBytes} bytes`));
          return;
        }
        hash.update(chunk);
        cb(null, chunk);
      },
    });
    try {
      await pipeline(stream, meter, createWriteStream(tmp));
      const actual = hash.digest('hex');
      if (actual !== sha) {
        throw new SyncError('HASH_MISMATCH', 'body does not hash to the requested sha', { expected: sha, actual });
      }
      await rename(tmp, target);
    } catch (err) {
      await rm(tmp, { force: true });
      throw err;
    }
    return { sha, bytes };
  }
}
