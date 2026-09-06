// Self-hosted sync server for Dimitris' App.
//
//   GET  /v1/health                 -> {"ok":true,"seq":N}          (no token)
//   POST /v1/push    {rows:[{table,row}]}  -> {"ok":true,"seq":N,"accepted":a,"ignored":i}
//   GET  /v1/pull?since=<seq>&limit=<n>    -> {"rows":[{seq,table,row}],"seq":N}
//   HEAD /v1/media/<sha256>         -> 200 / 404
//   PUT  /v1/media/<sha256>         -> 201 {"ok":true,"sha":...}
//   GET  /v1/media/<sha256>         -> the bytes, application/octet-stream
//
// Everything except /v1/health requires `Authorization: Bearer <SYNC_TOKEN>`.
// Run it behind nginx/caddy with TLS - see README.md.
//
// Node 22 built-ins only. No dependencies.

import { createServer as createHttpServer } from 'node:http';
import { createReadStream } from 'node:fs';
import { timingSafeEqual } from 'node:crypto';
import { resolve } from 'node:path';
import { PassThrough } from 'node:stream';
import {
  JSON_MAX_BYTES,
  MEDIA_MAX_BYTES,
  PULL_DEFAULT_LIMIT,
  Store,
  SyncError,
  isSha256,
} from './store.mjs';

const MEDIA_PREFIX = '/v1/media/';

/**
 * @param {{dataDir?:string, token:string, store?:Store, log?:(line:string)=>void}} options
 * @returns {import('node:http').Server}
 */
export function createServer({ dataDir = './data', token, store: given, log = console.log }) {
  // Checked before the store is built, so a misconfigured server never creates
  // a data directory anywhere.
  if (typeof token !== 'string' || token.length === 0) {
    throw new SyncError('NO_TOKEN', 'a non-empty token is required');
  }
  const store = given ?? new Store(dataDir);
  const expectedToken = Buffer.from(token, 'utf8');

  /** Constant-time bearer check. Different lengths cannot be compared, so they just fail. */
  function authorized(req) {
    const header = req.headers.authorization;
    if (typeof header !== 'string' || !header.startsWith('Bearer ')) return false;
    const given = Buffer.from(header.slice('Bearer '.length), 'utf8');
    if (given.length !== expectedToken.length) return false;
    return timingSafeEqual(given, expectedToken);
  }

  const server = createHttpServer((req, res) => {
    const started = process.hrtime.bigint();
    let logged = false;
    const writeLog = () => {
      if (logged) return;
      logged = true;
      const ms = Number(process.hrtime.bigint() - started) / 1e6;
      log(`${new Date().toISOString()} ${req.method} ${req.url} ${res.statusCode} ${ms.toFixed(1)}ms`);
    };
    res.on('finish', writeLog);
    res.on('close', writeLog);

    handle(req, res).catch((err) => {
      console.error('[server] unhandled error', err);
      if (!res.headersSent) sendJson(res, 500, { error: 'internal error' });
      else res.destroy();
    });
  });

  async function handle(req, res) {
    const url = new URL(req.url, 'http://sync.invalid');
    const path = normalise(url.pathname);
    const method = req.method ?? 'GET';

    // Health is deliberately unauthenticated: it is what the reverse proxy,
    // docker and the caregiver phones probe before anything else.
    if (path === '/v1/health') {
      if (method !== 'GET' && method !== 'HEAD') return methodNotAllowed(req, res, 'GET');
      return sendJson(res, 200, { ok: true, seq: store.seq });
    }

    if (!authorized(req)) {
      // A request we will not authenticate never gets its body read: answer and
      // hang up, so an unauthenticated client cannot stream bytes at us.
      return refuse(req, res, 401, { error: 'unauthorized' });
    }

    if (path === '/v1/push') {
      if (method !== 'POST') return methodNotAllowed(req, res, 'POST');
      return await handlePush(req, res);
    }
    if (path === '/v1/pull') {
      if (method !== 'GET') return methodNotAllowed(req, res, 'GET');
      return handlePull(req, res, url);
    }
    if (path.startsWith(MEDIA_PREFIX)) {
      const sha = path.slice(MEDIA_PREFIX.length);
      if (!isSha256(sha)) {
        drain(req);
        return sendJson(res, 400, { error: 'media id must be 64 lowercase hex characters' });
      }
      if (method === 'HEAD' || method === 'GET') return handleMediaGet(res, sha, method === 'HEAD');
      if (method === 'PUT') return await handleMediaPut(req, res, sha);
      return methodNotAllowed(req, res, 'GET, HEAD, PUT');
    }

    drain(req);
    return sendJson(res, 404, { error: 'not found' });
  }

  async function handlePush(req, res) {
    let body;
    try {
      body = await readJson(req, JSON_MAX_BYTES);
    } catch (err) {
      if (err.code === 'TOO_LARGE') {
        // Do not read another byte of an oversized body: answer and hang up.
        return refuse(req, res, 413, { error: `json body exceeds ${JSON_MAX_BYTES} bytes` });
      }
      drain(req);
      return sendJson(res, 400, { error: 'malformed json' });
    }
    const rows = body?.rows;
    if (!Array.isArray(rows)) {
      return sendJson(res, 400, { error: 'body must be {"rows":[{"table":...,"row":{...}}]}' });
    }
    // Validate the whole batch before touching the log, so a push either lands
    // completely or not at all and the client sees exactly which row is wrong.
    for (let i = 0; i < rows.length; i++) {
      const entry = rows[i];
      if (!entry || typeof entry !== 'object' || Array.isArray(entry)) {
        return sendJson(res, 400, { error: 'each row must be {"table":...,"row":{...}}', index: i });
      }
      try {
        store.validate(entry.table, entry.row);
      } catch (err) {
        return sendJson(res, 400, { error: err.message, index: i, code: err.code });
      }
    }
    let accepted = 0;
    let ignored = 0;
    for (const entry of rows) {
      if (store.apply(entry.table, entry.row).accepted) accepted++;
      else ignored++;
    }
    return sendJson(res, 200, { ok: true, seq: store.seq, accepted, ignored });
  }

  function handlePull(req, res, url) {
    drain(req); // a GET should not carry a body, but an unread one breaks keep-alive framing
    // `since` is the client's cursor: garbage would silently re-pull the whole
    // history, so it is rejected instead of defaulted. `limit` is a hint and is
    // still clamped into 1..500.
    const sinceParam = url.searchParams.get('since');
    const since = sinceParam === null ? 0 : Number(sinceParam);
    if (sinceParam !== null && (!/^[0-9]+$/.test(sinceParam) || !Number.isSafeInteger(since))) {
      return sendJson(res, 400, { error: 'since must be a non-negative integer' });
    }
    const limit = url.searchParams.get('limit') ?? String(PULL_DEFAULT_LIMIT);
    return sendJson(res, 200, store.since(since, Number.parseInt(limit, 10)));
  }

  function handleMediaGet(res, sha, headOnly) {
    const size = store.size(sha);
    if (size === null) return sendJson(res, 404, { error: 'not found' });
    res.writeHead(200, {
      'content-type': 'application/octet-stream',
      'content-length': String(size),
      'cache-control': 'private, max-age=31536000, immutable',
    });
    if (headOnly) return res.end();
    return createReadStream(store.path(sha)).pipe(res);
  }

  async function handleMediaPut(req, res, sha) {
    const declared = Number(req.headers['content-length']);
    if (Number.isFinite(declared) && declared > MEDIA_MAX_BYTES) {
      // Refused before a single byte is read, and the connection is closed.
      return refuse(req, res, 413, { error: `media body exceeds ${MEDIA_MAX_BYTES} bytes` });
    }
    if (store.has(sha)) {
      // Content addressed: identical bytes, nothing to do. The client is meant
      // to HEAD first; drain the body so the connection stays clean.
      drain(req);
      return sendJson(res, 200, { ok: true, sha, stored: false });
    }
    // The body is piped through a PassThrough rather than handed to the store
    // directly: when the store aborts (too large, write failure) it destroys
    // the stream it was given, which would tear the socket down before the 413
    // could be written. We write the answer first and destroy the request
    // ourselves, in that order.
    const source = new PassThrough();
    req.pipe(source);
    const abort = () => source.destroy(new SyncError('ABORTED', 'client aborted the upload'));
    req.on('error', abort);
    req.on('aborted', abort);
    req.on('close', () => {
      if (!req.readableEnded) abort();
    });
    try {
      const { bytes } = await store.put(sha, source, MEDIA_MAX_BYTES);
      return sendJson(res, 201, { ok: true, sha, bytes, stored: true });
    } catch (err) {
      if (err.code === 'TOO_LARGE') {
        return refuse(req, res, 413, { error: `media body exceeds ${MEDIA_MAX_BYTES} bytes` });
      }
      drain(req);
      if (err.code === 'HASH_MISMATCH') {
        return sendJson(res, 400, { error: 'hash mismatch', expected: err.expected, actual: err.actual });
      }
      if (err.code === 'ABORTED') {
        // The phone went away mid-upload; nothing was stored and there is
        // nobody left to answer.
        return res.destroyed ? undefined : sendJson(res, 400, { error: 'upload aborted' });
      }
      throw err;
    }
  }

  function methodNotAllowed(req, res, allow) {
    drain(req);
    res.setHeader('allow', allow);
    return sendJson(res, 405, { error: 'method not allowed' });
  }

  server.store = store;
  return server;
}

// ---- helpers ---------------------------------------------------------------

function normalise(pathname) {
  return pathname.length > 1 && pathname.endsWith('/') ? pathname.replace(/\/+$/, '') : pathname;
}

function sendJson(res, status, payload) {
  const body = Buffer.from(`${JSON.stringify(payload)}\n`, 'utf8');
  if (res.writableEnded || res.destroyed) return undefined;
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': String(body.length),
  });
  return res.end(body);
}

/**
 * Read and discard whatever is left of an *authenticated, within-limits*
 * request body, so the client can finish writing and still read the response
 * instead of seeing a reset connection. Bodies we refuse (401, 413) are never
 * drained - see `refuse`.
 */
function drain(req) {
  if (req.readableEnded || req.destroyed) return;
  req.resume();
}

/** Does this request still have body bytes we have not read? */
function hasUnreadBody(req) {
  if (req.readableEnded || req.destroyed) return false;
  return req.headers['content-length'] !== undefined || req.headers['transfer-encoding'] !== undefined;
}

/**
 * Answer and hang up without reading the rest of the body. Used for the two
 * cases where continuing to read would mean accepting bytes we have already
 * decided to reject: an unauthenticated request, and a body over the limit.
 * The response is flushed first, then the request (and with it the socket) is
 * destroyed, so a well-behaved client still sees the status.
 */
function refuse(req, res, status, payload) {
  // Nothing left to read (a bodyless GET, say): an ordinary keep-alive answer.
  if (!hasUnreadBody(req)) return sendJson(res, status, payload);
  if (res.writableEnded || res.destroyed) {
    if (!req.destroyed) req.destroy();
    return undefined;
  }
  const body = Buffer.from(`${JSON.stringify(payload)}\n`, 'utf8');
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': String(body.length),
    connection: 'close',
  });
  return res.end(body, () => {
    if (!req.destroyed) req.destroy();
  });
}

/**
 * Buffer a JSON body, counting bytes.
 * @throws {SyncError} code TOO_LARGE or BAD_JSON
 */
function readJson(req, maxBytes) {
  return new Promise((resolvePromise, rejectPromise) => {
    const declared = Number(req.headers['content-length']);
    if (Number.isFinite(declared) && declared > maxBytes) {
      rejectPromise(new SyncError('TOO_LARGE', `json body exceeds ${maxBytes} bytes`));
      return;
    }
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > maxBytes) {
        chunks.length = 0;
        rejectPromise(new SyncError('TOO_LARGE', `json body exceeds ${maxBytes} bytes`));
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (chunks.length === 0) {
        resolvePromise(null);
        return;
      }
      try {
        resolvePromise(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        rejectPromise(new SyncError('BAD_JSON', 'malformed json'));
      }
    });
    req.on('error', rejectPromise);
  });
}

// ---- entry point -----------------------------------------------------------

export function main() {
  const token = process.env.SYNC_TOKEN;
  if (!token) {
    console.error('SYNC_TOKEN is required. Example:');
    console.error('  SYNC_TOKEN=$(openssl rand -hex 32) PORT=8787 DATA_DIR=./data node server.mjs');
    process.exit(1);
  }
  if (token.length < 16) {
    console.warn('[server] SYNC_TOKEN is shorter than 16 characters - use a long random one');
  }
  const port = Number(process.env.PORT ?? 8787);
  const dataDir = process.env.DATA_DIR ?? './data';
  const server = createServer({ dataDir, token });
  server.listen(port, () => {
    console.log(`[server] listening on :${port}  data=${resolve(dataDir)}  seq=${server.store.seq}`);
  });
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => {
      console.log(`[server] ${signal} - shutting down`);
      server.close(() => process.exit(0));
      // Do not wait forever for idle keep-alive sockets.
      setTimeout(() => process.exit(0), 2000).unref();
    });
  }
  return server;
}

const invokedDirectly = process.argv[1] !== undefined && resolve(process.argv[1]) === import.meta.filename;
if (invokedDirectly) main();
