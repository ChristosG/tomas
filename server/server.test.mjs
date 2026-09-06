// HTTP tests: token check, routes, pagination, media, body limits, logging.
// Run with: node --test   (from server/)

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { request as httpRequest } from 'node:http';
import { createServer } from './server.mjs';
import { JSON_MAX_BYTES, MEDIA_MAX_BYTES } from './store.mjs';

const TOKEN = 'test-token-0123456789abcdef';
const AUTH = { authorization: `Bearer ${TOKEN}` };
const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');

/** Start the server on an ephemeral port with a throwaway DATA_DIR. */
async function start(t, { token = TOKEN } = {}) {
  const dir = mkdtempSync(join(import.meta.dirname, '.tmp-test-'));
  const logs = [];
  const server = createServer({ dataDir: dir, token, log: (line) => logs.push(line) });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  const port = server.address().port;
  t.after(async () => {
    server.closeAllConnections();
    await new Promise((done) => server.close(done));
    rmSync(dir, { recursive: true, force: true });
  });
  return { server, dir, logs, port, base: `http://127.0.0.1:${port}` };
}

const json = (base, path, init = {}) =>
  fetch(`${base}${path}`, {
    ...init,
    headers: { 'content-type': 'application/json', ...(init.headers ?? {}) },
  });

/**
 * A raw request, used for the cases fetch() cannot express or survive.
 *
 * - `declaredLength` sets a `content-length` far larger than what we actually
 *   write and the request is deliberately left unfinished, so the promise can
 *   only settle if the *server* hangs up. If the server drained instead, it
 *   would sit waiting for the missing bytes and the probe would time out.
 * - without `declaredLength` the chunks are streamed with
 *   `transfer-encoding: chunked` and the request is ended normally, so the
 *   server's byte counter (not a declared length) is what stops it.
 *
 * Resolves with whatever happened: the status if a response arrived before the
 * socket went away, the socket error if the reset beat it, and how long it took.
 */
function probe({ port, method = 'POST', path, headers = {}, declaredLength = null, chunks = [], timeoutMs = 5000 }) {
  return new Promise((done) => {
    const outcome = { status: null, body: '', closed: false, timedOut: false, error: null, ms: 0 };
    const started = Date.now();
    let settled = false;
    const settle = () => {
      if (settled) return;
      settled = true;
      outcome.ms = Date.now() - started;
      clearTimeout(timer);
      done(outcome);
    };
    const timer = setTimeout(() => {
      outcome.timedOut = true;
      req.destroy();
      settle();
    }, timeoutMs);

    const req = httpRequest({
      host: '127.0.0.1',
      port,
      method,
      path,
      headers: declaredLength === null
        ? { ...headers, 'transfer-encoding': 'chunked' }
        : { ...headers, 'content-length': String(declaredLength) },
    });
    req.on('response', (res) => {
      outcome.status = res.statusCode;
      res.setEncoding('utf8');
      res.on('data', (c) => {
        outcome.body += c;
      });
    });
    req.on('error', (err) => {
      outcome.error = err.code ?? err.message;
    });
    req.on('close', () => {
      outcome.closed = true;
      settle();
    });
    for (const chunk of chunks) req.write(chunk);
    // With a declaredLength we owe the server bytes we will never send.
    if (declaredLength === null) req.end();
  });
}

const row = (id, updatedAt) => ({ id, updatedAt, deleted: false });

test('health is open, everything else needs the bearer token', async (t) => {
  const { base } = await start(t);

  const health = await fetch(`${base}/v1/health`);
  assert.equal(health.status, 200);
  assert.deepEqual(await health.json(), { ok: true, seq: 0 });

  // Health ignores the header entirely - a phone with a stale token can still
  // tell whether the server is up.
  assert.equal((await fetch(`${base}/v1/health`, { headers: { authorization: 'Bearer wrong' } })).status, 200);

  assert.equal((await fetch(`${base}/v1/pull`)).status, 401, 'no header');
  assert.equal((await fetch(`${base}/v1/pull`, { headers: { authorization: TOKEN } })).status, 401, 'no Bearer prefix');
  assert.equal(
    (await fetch(`${base}/v1/pull`, { headers: { authorization: 'Bearer short' } })).status,
    401,
    'a shorter token cannot even be compared',
  );
  assert.equal(
    (await fetch(`${base}/v1/pull`, { headers: { authorization: `Bearer ${'x'.repeat(TOKEN.length)}` } })).status,
    401,
    'a same-length wrong token',
  );
  assert.equal((await fetch(`${base}/v1/pull`, { headers: AUTH })).status, 200);

  const pushUnauthorised = await json(base, '/v1/push', { method: 'POST', body: JSON.stringify({ rows: [] }) });
  assert.equal(pushUnauthorised.status, 401);
  assert.deepEqual(await pushUnauthorised.json(), { error: 'unauthorized' });

  const mediaUnauthorised = await fetch(`${base}/v1/media/${sha256(Buffer.from('x'))}`);
  assert.equal(mediaUnauthorised.status, 401);
});

test('a server cannot be created without a token, and creates no data directory', (t) => {
  const dir = mkdtempSync(join(import.meta.dirname, '.tmp-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const unused = join(dir, 'never-created');
  assert.throws(() => createServer({ dataDir: unused, token: '' }), /token is required/);
  assert.throws(() => createServer({ dataDir: unused }), /token is required/);
  assert.equal(existsSync(unused), false, 'the token is checked before anything touches the disk');
});

test('push then pull round trip', async (t) => {
  const { base } = await start(t);

  const pushed = await json(base, '/v1/push', {
    method: 'POST',
    headers: AUTH,
    body: JSON.stringify({
      rows: [
        { table: 'items', row: { ...row('i1', 100), greek: 'ψωμί' } },
        { table: 'attempts', row: { id: 'a1', updatedAt: 101, correct: true } },
      ],
    }),
  });
  assert.equal(pushed.status, 200);
  assert.deepEqual(await pushed.json(), { ok: true, seq: 2, accepted: 2, ignored: 0 });

  const pulled = await (await fetch(`${base}/v1/pull?since=0`, { headers: AUTH })).json();
  assert.equal(pulled.seq, 2);
  assert.deepEqual(pulled.rows.map((r) => [r.seq, r.table, r.row.id]), [
    [1, 'items', 'i1'],
    [2, 'attempts', 'a1'],
  ]);
  assert.equal(pulled.rows[0].row.greek, 'ψωμί');

  const caughtUp = await (await fetch(`${base}/v1/pull?since=2`, { headers: AUTH })).json();
  assert.deepEqual(caughtUp.rows, []);
  assert.equal(caughtUp.seq, 2);

  // An older copy of a row is ignored, and the batch reports it.
  const again = await json(base, '/v1/push', {
    method: 'POST',
    headers: AUTH,
    body: JSON.stringify({ rows: [{ table: 'items', row: { ...row('i1', 50), greek: 'παλιό' } }] }),
  });
  assert.deepEqual(await again.json(), { ok: true, seq: 2, accepted: 0, ignored: 1 });
});

/**
 * The batch a phase-11 phone really sends. Before `advice` and `notes` were registered the whole
 * batch came back 400 and the phone's push watermark could never advance past the first note.
 */
test('a batch carrying an advice and a note is accepted whole', async (t) => {
  const { base } = await start(t);

  const pushed = await json(base, '/v1/push', {
    method: 'POST',
    headers: AUTH,
    body: JSON.stringify({
      rows: [
        { table: 'items', row: { ...row('i1', 100), greek: 'ψωμί' } },
        { table: 'notes', row: { id: 'n1', at: 90, text: 'Είπε «καλημέρα» μόνος του.', author: 'CAREGIVER', updatedAt: 101, deleted: false } },
        { table: 'advice', row: { id: 'ad1', at: 95, model: 'claude-opus-5', report: 'Προφίλ…', caregivers: '- Ένα.', dimitris: 'Πάει καλά.', focusJson: '{"sounds":["π"]}', updatedAt: 102, deleted: false } },
      ],
    }),
  });
  assert.equal(pushed.status, 200);
  assert.deepEqual(await pushed.json(), { ok: true, seq: 3, accepted: 3, ignored: 0 });

  const pulled = await (await fetch(`${base}/v1/pull?since=0`, { headers: AUTH })).json();
  assert.deepEqual(pulled.rows.map((r) => [r.table, r.row.id]), [
    ['items', 'i1'],
    ['notes', 'n1'],
    ['advice', 'ad1'],
  ]);
  assert.equal(pulled.rows[1].row.text, 'Είπε «καλημέρα» μόνος του.');
  assert.equal(pulled.rows[2].row.focusJson, '{"sounds":["π"]}');
});

test('pull honours since and clamps limit', async (t) => {
  const { base } = await start(t);
  const rows = [];
  for (let i = 1; i <= 8; i++) rows.push({ table: 'items', row: row(`i${i}`, 1000 + i) });
  await json(base, '/v1/push', { method: 'POST', headers: AUTH, body: JSON.stringify({ rows }) });

  const one = await (await fetch(`${base}/v1/pull?since=0&limit=1`, { headers: AUTH })).json();
  assert.deepEqual(one.rows.map((r) => r.seq), [1]);
  assert.equal(one.seq, 8, 'the top seq tells the client how far behind it is');

  const three = await (await fetch(`${base}/v1/pull?since=1&limit=3`, { headers: AUTH })).json();
  assert.deepEqual(three.rows.map((r) => r.seq), [2, 3, 4]);

  const clampedUp = await (await fetch(`${base}/v1/pull?since=0&limit=0`, { headers: AUTH })).json();
  assert.equal(clampedUp.rows.length, 1, 'limit=0 clamps to 1');

  const clampedDown = await (await fetch(`${base}/v1/pull?since=0&limit=99999`, { headers: AUTH })).json();
  assert.equal(clampedDown.rows.length, 8, 'limit is capped at 500');

  const noParams = await (await fetch(`${base}/v1/pull`, { headers: AUTH })).json();
  assert.equal(noParams.rows.length, 8, 'no params means everything, up to 500');

  const badLimit = await (await fetch(`${base}/v1/pull?since=0&limit=abc`, { headers: AUTH })).json();
  assert.equal(badLimit.rows.length, 8, 'a garbage limit falls back to the default - it is only a hint');
});

test('pull rejects a since it cannot trust', async (t) => {
  const { base } = await start(t);
  await json(base, '/v1/push', {
    method: 'POST',
    headers: AUTH,
    body: JSON.stringify({ rows: [{ table: 'items', row: row('i1', 1) }] }),
  });

  // A broken cursor would silently re-pull the whole history, so it is a 400
  // rather than a quiet "start from the beginning".
  for (const bad of ['abc', 'NaN', '-1', '1.5', '', ' 1', '1e3', '99999999999999999999']) {
    const res = await fetch(`${base}/v1/pull?since=${encodeURIComponent(bad)}`, { headers: AUTH });
    assert.equal(res.status, 400, `since=${JSON.stringify(bad)} must be refused`);
    assert.deepEqual(await res.json(), { error: 'since must be a non-negative integer' });
  }

  // ...while a missing or valid cursor still works.
  assert.equal((await fetch(`${base}/v1/pull`, { headers: AUTH })).status, 200);
  assert.equal((await fetch(`${base}/v1/pull?since=0`, { headers: AUTH })).status, 200);
  assert.equal((await fetch(`${base}/v1/pull?since=99`, { headers: AUTH })).status, 200);
});

test('push rejects a malformed body, an unknown table and a row without an id', async (t) => {
  const { base } = await start(t);

  const badJson = await json(base, '/v1/push', { method: 'POST', headers: AUTH, body: '{not json' });
  assert.equal(badJson.status, 400);
  assert.deepEqual(await badJson.json(), { error: 'malformed json' });

  const noRows = await json(base, '/v1/push', { method: 'POST', headers: AUTH, body: JSON.stringify({}) });
  assert.equal(noRows.status, 400);

  const badTable = await json(base, '/v1/push', {
    method: 'POST',
    headers: AUTH,
    body: JSON.stringify({ rows: [{ table: 'items', row: row('ok', 1) }, { table: 'ghosts', row: row('g', 1) }] }),
  });
  assert.equal(badTable.status, 400);
  const badTableBody = await badTable.json();
  assert.equal(badTableBody.index, 1);
  assert.match(badTableBody.error, /unknown table/);

  const noId = await json(base, '/v1/push', {
    method: 'POST',
    headers: AUTH,
    body: JSON.stringify({ rows: [{ table: 'items', row: { updatedAt: 1 } }] }),
  });
  assert.equal(noId.status, 400);

  // Nothing from a rejected batch is stored.
  const pulled = await (await fetch(`${base}/v1/pull`, { headers: AUTH })).json();
  assert.deepEqual(pulled.rows, []);
  assert.equal(pulled.seq, 0);
});

test('media: HEAD, PUT, GET and the hash check', async (t) => {
  const { base, dir } = await start(t);
  const bytes = Buffer.from('a pretend jpeg of ψωμί', 'utf8');
  const sha = sha256(bytes);

  assert.equal((await fetch(`${base}/v1/media/${sha}`, { method: 'HEAD', headers: AUTH })).status, 404);

  const put = await fetch(`${base}/v1/media/${sha}`, { method: 'PUT', headers: AUTH, body: bytes });
  assert.equal(put.status, 201);
  assert.deepEqual(await put.json(), { ok: true, sha, bytes: bytes.length, stored: true });

  const head = await fetch(`${base}/v1/media/${sha}`, { method: 'HEAD', headers: AUTH });
  assert.equal(head.status, 200);
  assert.equal(head.headers.get('content-length'), String(bytes.length));

  const got = await fetch(`${base}/v1/media/${sha}`, { headers: AUTH });
  assert.equal(got.status, 200);
  assert.equal(got.headers.get('content-type'), 'application/octet-stream');
  assert.deepEqual(Buffer.from(await got.arrayBuffer()), bytes);

  // Re-uploading known bytes is a no-op.
  const again = await fetch(`${base}/v1/media/${sha}`, { method: 'PUT', headers: AUTH, body: bytes });
  assert.equal(again.status, 200);
  assert.equal((await again.json()).stored, false);

  // A body that does not hash to the requested sha is refused, and no file survives.
  const wrongSha = sha256(Buffer.from('some other photo'));
  const mismatch = await fetch(`${base}/v1/media/${wrongSha}`, { method: 'PUT', headers: AUTH, body: bytes });
  assert.equal(mismatch.status, 400);
  const mismatchBody = await mismatch.json();
  assert.equal(mismatchBody.error, 'hash mismatch');
  assert.equal(mismatchBody.actual, sha);
  assert.deepEqual(readdirSync(join(dir, 'media')), [sha], 'only the good blob is on disk');
  assert.equal((await fetch(`${base}/v1/media/${wrongSha}`, { method: 'HEAD', headers: AUTH })).status, 404);

  // A media id that is not a sha-256 never reaches the filesystem.
  const traversal = await fetch(`${base}/v1/media/..%2F..%2Frows.jsonl`, { headers: AUTH });
  assert.equal(traversal.status, 400);
  assert.equal((await fetch(`${base}/v1/media/NOTAHASH`, { headers: AUTH })).status, 400);
});

test('bodies over the limit are refused with 413 while they are still arriving', async (t) => {
  const { base, port, dir } = await start(t);
  const megabyte = Buffer.alloc(1024 * 1024, 3);

  // Media, streamed without a content-length so the byte counter is what trips
  // (a lying or absent content-length cannot get past it).
  const chunks = new Array(MEDIA_MAX_BYTES / megabyte.length + 1).fill(megabyte);
  const bigMedia = await probe({
    port,
    method: 'PUT',
    path: `/v1/media/${sha256(Buffer.concat(chunks))}`,
    headers: AUTH,
    chunks,
  });
  assert.equal(bigMedia.timedOut, false);
  assert.equal(bigMedia.status, 413, 'the counter trips at 20 MB and answers');
  assert.match(JSON.parse(bigMedia.body).error, /media body exceeds/);
  assert.deepEqual(readdirSync(join(dir, 'media')), [], 'the partial upload was cleaned up');

  // A declared content-length over the limit is refused before any bytes are read.
  const declaredMedia = await probe({
    port,
    method: 'PUT',
    path: `/v1/media/${sha256(Buffer.from('x'))}`,
    headers: AUTH,
    declaredLength: MEDIA_MAX_BYTES * 2,
    chunks: [megabyte],
  });
  assert.equal(declaredMedia.timedOut, false);
  assert.equal(declaredMedia.status, 413);

  // The server is still perfectly healthy after all that.
  assert.equal((await fetch(`${base}/v1/health`)).status, 200);
});

test('a refused body is never drained: the server answers and hangs up', async (t) => {
  const { base, port, dir } = await start(t);
  const chunk = Buffer.alloc(256 * 1024, 'a');

  // Each probe declares a content-length far bigger than what it sends and then
  // stops. A server that drained would wait for the rest until its 300s request
  // timeout and the probe would time out; hanging up is what lets it finish.
  const oversizedPush = await probe({
    port,
    path: '/v1/push',
    headers: { ...AUTH, 'content-type': 'application/json' },
    declaredLength: JSON_MAX_BYTES * 4,
    chunks: [chunk],
  });
  assert.equal(oversizedPush.timedOut, false, 'an oversized push must be hung up on, not drained');
  assert.equal(oversizedPush.closed, true);
  assert.ok(oversizedPush.ms < 2000, `closed promptly (took ${oversizedPush.ms}ms)`);
  assert.equal(oversizedPush.status, 413, 'and the client still gets to read the 413');
  assert.match(JSON.parse(oversizedPush.body).error, /json body exceeds/);

  const unauthenticated = await probe({
    port,
    path: '/v1/push',
    headers: { authorization: `Bearer ${'x'.repeat(TOKEN.length)}`, 'content-type': 'application/json' },
    declaredLength: 64 * 1024 * 1024,
    chunks: [chunk],
  });
  assert.equal(unauthenticated.timedOut, false, 'an unauthenticated body must be hung up on, not drained');
  assert.equal(unauthenticated.closed, true);
  assert.ok(unauthenticated.ms < 2000, `closed promptly (took ${unauthenticated.ms}ms)`);
  assert.equal(unauthenticated.status, 401);
  assert.deepEqual(JSON.parse(unauthenticated.body), { error: 'unauthorized' });

  // Same for media: no token, no reading.
  const unauthenticatedMedia = await probe({
    port,
    method: 'PUT',
    path: `/v1/media/${sha256(Buffer.from('nope'))}`,
    headers: { authorization: 'Bearer wrong' },
    declaredLength: 64 * 1024 * 1024,
    chunks: [chunk],
  });
  assert.equal(unauthenticatedMedia.timedOut, false);
  assert.equal(unauthenticatedMedia.status, 401);
  assert.deepEqual(readdirSync(join(dir, 'media')), [], 'nothing was written for an unauthenticated upload');

  // None of that disturbs the next request.
  assert.equal((await fetch(`${base}/v1/health`)).status, 200);
  assert.equal((await fetch(`${base}/v1/pull?since=0`, { headers: AUTH })).status, 200);
});

test('unknown routes 404 and wrong methods 405', async (t) => {
  const { base } = await start(t);

  const unknown = await fetch(`${base}/v1/nope`, { headers: AUTH });
  assert.equal(unknown.status, 404);
  assert.deepEqual(await unknown.json(), { error: 'not found' });
  assert.equal((await fetch(`${base}/`, { headers: AUTH })).status, 404);

  const wrongMethod = await fetch(`${base}/v1/push`, { headers: AUTH });
  assert.equal(wrongMethod.status, 405);
  assert.equal(wrongMethod.headers.get('allow'), 'POST');
  assert.equal((await fetch(`${base}/v1/pull`, { method: 'DELETE', headers: AUTH })).status, 405);

  // A trailing slash is the same route.
  assert.equal((await fetch(`${base}/v1/health/`)).status, 200);
});

test('every request writes one log line with method, path, status and duration', async (t) => {
  const { base, logs } = await start(t);
  await fetch(`${base}/v1/health`);
  await fetch(`${base}/v1/pull?since=0`, { headers: AUTH });
  await fetch(`${base}/v1/nope`, { headers: AUTH });

  assert.equal(logs.length, 3);
  assert.match(logs[0], /^\S+ GET \/v1\/health 200 [\d.]+ms$/);
  assert.match(logs[1], /GET \/v1\/pull\?since=0 200 [\d.]+ms$/);
  assert.match(logs[2], /GET \/v1\/nope 404 [\d.]+ms$/);
  assert.ok(!logs.join('\n').includes(TOKEN), 'the token is never logged');
});

test('data survives a restart of the process', async (t) => {
  const first = await start(t);
  await json(first.base, '/v1/push', {
    method: 'POST',
    headers: AUTH,
    body: JSON.stringify({ rows: [{ table: 'items', row: { ...row('i1', 7), greek: 'νερό' } }] }),
  });
  first.server.closeAllConnections();
  await new Promise((done) => first.server.close(done));

  const second = createServer({ dataDir: first.dir, token: TOKEN, log: () => {} });
  await new Promise((done) => second.listen(0, '127.0.0.1', done));
  t.after(async () => {
    second.closeAllConnections();
    await new Promise((done) => second.close(done));
  });
  const base = `http://127.0.0.1:${second.address().port}`;
  const health = await (await fetch(`${base}/v1/health`)).json();
  assert.deepEqual(health, { ok: true, seq: 1 });
  const pulled = await (await fetch(`${base}/v1/pull?since=0`, { headers: AUTH })).json();
  assert.equal(pulled.rows[0].row.greek, 'νερό');
});
