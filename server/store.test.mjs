// Store unit tests: merge rules, pagination, media, crash recovery.
// Run with: node --test   (from server/)

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { appendFileSync, existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { Readable } from 'node:stream';
import { APPEND_ONLY_TABLES, PULL_MAX_LIMIT, Store, TABLES, clampLimit } from './store.mjs';

/** A fresh DATA_DIR next to the tests, removed when the test ends. */
function tempDir(t) {
  const dir = mkdtempSync(join(import.meta.dirname, '.tmp-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  return dir;
}

function item(id, updatedAt, extra = {}) {
  return { id, updatedAt, deleted: false, ...extra };
}

/** Swallow the expected [store] warnings and hand back what was logged. */
function captureWarnings(t) {
  const lines = [];
  const original = console.warn;
  console.warn = (...args) => lines.push(args.join(' '));
  t.after(() => {
    console.warn = original;
  });
  return lines;
}

test('the table registry matches the sync contract', () => {
  assert.deepEqual([...TABLES], [
    'items',
    'recordings',
    'attempts',
    'schedules',
    'sessions',
    'error_logs',
    'scripts',
    'script_lines',
    'advice',
    'notes',
  ]);
  assert.deepEqual([...APPEND_ONLY_TABLES], ['attempts', 'error_logs']);
});

/**
 * Phase 11's two tables. Both are things people wrote — an answer from Claude and a caregiver's
 * own note — so both are last-write-wins: a person may correct what they wrote. Without them here
 * the server rejects the whole push batch, the phone's watermark can never advance past the first
 * note, and the notes are exactly what the father's half of the week reaches Chris by.
 */
test('advice and notes are stored, merged last-write-wins, and pulled back', (t) => {
  const store = new Store(tempDir(t));

  const advice = {
    id: 'ad1',
    at: 1_757_000_000_000,
    model: 'claude-opus-5',
    report: 'Προφίλ…',
    caregivers: '- Δούλεψε τα «π».',
    dimitris: 'Πάει καλά.',
    focusJson: '{"items":["καφές"],"sounds":["π"]}',
    updatedAt: 100,
    deleted: false,
  };
  assert.equal(store.apply('advice', advice).accepted, true);
  assert.equal(store.apply('advice', { ...advice, caregivers: 'διορθωμένο', updatedAt: 200 }).accepted, true);
  assert.equal(store.apply('advice', { ...advice, caregivers: 'παλιό', updatedAt: 150 }).accepted, false);
  assert.equal(store.apply('advice', { ...advice, caregivers: 'ισοπαλία', updatedAt: 200 }).accepted, false);

  const note = { id: 'n1', at: 5, text: 'Είπε «καλημέρα» μόνος του.', author: 'CAREGIVER', updatedAt: 10, deleted: false };
  assert.equal(store.apply('notes', note).accepted, true);
  assert.equal(store.apply('notes', { ...note, deleted: true, updatedAt: 11 }).accepted, true);

  const rows = store.since(0).rows;
  const stored = new Map(rows.map((r) => [r.table, r.row]));
  assert.equal(stored.get('advice').caregivers, 'διορθωμένο');
  assert.equal(stored.get('advice').focusJson, '{"items":["καφές"],"sounds":["π"]}');
  assert.equal(stored.get('notes').deleted, true);
  assert.equal(store.isAppendOnly('advice'), false);
  assert.equal(store.isAppendOnly('notes'), false);
});

test('last-write-wins keeps the newer updatedAt and ignores the older one', (t) => {
  const store = new Store(tempDir(t));

  const first = store.apply('items', item('i1', 100, { greek: 'ψωμί' }));
  assert.equal(first.accepted, true);
  assert.equal(first.seq, 1);

  const newer = store.apply('items', item('i1', 200, { greek: 'νερό' }));
  assert.equal(newer.accepted, true);
  assert.equal(newer.seq, 2);

  const older = store.apply('items', item('i1', 150, { greek: 'παλιό' }));
  assert.equal(older.accepted, false);
  assert.equal(store.seq, 2, 'a rejected row must not burn a seq');

  const tie = store.apply('items', item('i1', 200, { greek: 'ισοπαλία' }));
  assert.equal(tie.accepted, false, 'ties keep the row already stored');

  const { rows } = store.since(0);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].row.greek, 'νερό');
  assert.equal(rows[0].seq, 2);
});

test('last-write-wins applies to script_lines like every other non-append-only table', (t) => {
  const store = new Store(tempDir(t));
  store.apply('script_lines', { id: 'l1', updatedAt: 10, deleted: false, text: 'Γεια' });
  store.apply('script_lines', { id: 'l1', updatedAt: 20, deleted: true, text: 'Γεια' });
  const { rows } = store.since(0);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].row.deleted, true, 'a soft delete must overwrite the earlier line');
});

test('append-only tables keep the first row stored for an id', (t) => {
  const store = new Store(tempDir(t));
  for (const table of ['attempts', 'error_logs']) {
    const first = store.apply(table, { id: `${table}-1`, updatedAt: 100, correct: true });
    assert.equal(first.accepted, true, `${table}: first row accepted`);

    const again = store.apply(table, { id: `${table}-1`, updatedAt: 999, correct: false });
    assert.equal(again.accepted, false, `${table}: a later duplicate id is ignored`);
    assert.match(again.reason, /append-only/);

    const other = store.apply(table, { id: `${table}-2`, updatedAt: 1, correct: false });
    assert.equal(other.accepted, true, `${table}: a different id is still accepted`);
  }
  const { rows } = store.since(0);
  assert.equal(rows.length, 4);
  const kept = rows.find((r) => r.row.id === 'attempts-1');
  assert.equal(kept.row.correct, true, 'the immutable first row survived');
});

test('append-only rows do not need an updatedAt, other tables do', (t) => {
  const store = new Store(tempDir(t));
  assert.equal(store.apply('attempts', { id: 'a1', correct: true }).accepted, true);
  assert.throws(() => store.apply('items', { id: 'i1' }), /updatedAt/);
  assert.throws(() => store.apply('items', { updatedAt: 1 }), /id/);
  assert.throws(() => store.apply('nope', { id: 'x', updatedAt: 1 }), /unknown table/);
});

test('since() paginates by seq and clamps the limit into 1..500', (t) => {
  const store = new Store(tempDir(t));
  for (let i = 1; i <= 12; i++) store.apply('items', item(`i${i}`, 1000 + i));

  const firstPage = store.since(0, 5);
  assert.equal(firstPage.rows.length, 5);
  assert.deepEqual(firstPage.rows.map((r) => r.seq), [1, 2, 3, 4, 5]);
  assert.equal(firstPage.seq, 12, 'the top seq is reported alongside the page');

  const secondPage = store.since(5, 5);
  assert.deepEqual(secondPage.rows.map((r) => r.seq), [6, 7, 8, 9, 10]);

  const lastPage = store.since(10, 5);
  assert.deepEqual(lastPage.rows.map((r) => r.seq), [11, 12]);
  assert.equal(store.since(12, 5).rows.length, 0, 'a caught-up client gets nothing');

  // limit clamping
  assert.equal(store.since(0, 0).rows.length, 1, 'limit 0 clamps up to 1');
  assert.equal(store.since(0, -7).rows.length, 1, 'a negative limit clamps up to 1');
  assert.equal(store.since(0, 100000).rows.length, 12, 'a huge limit clamps down to 500');
  assert.equal(clampLimit(100000), PULL_MAX_LIMIT);
  assert.equal(clampLimit('abc'), PULL_MAX_LIMIT, 'garbage falls back to the default');
  assert.equal(clampLimit(0), 1);

  // A bad `since` never reaches the store - `GET /v1/pull` answers 400 first
  // (see server.test.mjs, "pull rejects a since it cannot trust"). This is only
  // the library-level guard behind it: it must not throw or return garbage.
  assert.equal(store.since(Number.NaN, 500).rows.length, 12);
  assert.equal(store.since(-4, 500).rows.length, 12);
});

test('a superseded row is pulled once, under its newest seq', (t) => {
  const store = new Store(tempDir(t));
  store.apply('items', item('i1', 10));
  store.apply('items', item('i2', 10));
  store.apply('items', item('i1', 20)); // seq 3

  const all = store.since(0);
  assert.deepEqual(all.rows.map((r) => r.seq), [2, 3]);
  const caughtUpAtTwo = store.since(2);
  assert.deepEqual(caughtUpAtTwo.rows.map((r) => r.row.id), ['i1']);
});

test('rows survive a restart and the seq continues', (t) => {
  const dir = tempDir(t);
  const first = new Store(dir);
  first.apply('items', item('i1', 10, { greek: 'σπίτι' }));
  first.apply('attempts', { id: 'a1', updatedAt: 11, correct: true });
  first.apply('items', item('i1', 20, { greek: 'σπίτι μου' }));

  const reopened = new Store(dir);
  assert.equal(reopened.seq, 3);
  assert.equal(reopened.skippedLines, 0);
  const { rows } = reopened.since(0);
  assert.equal(rows.length, 2);
  assert.equal(rows.find((r) => r.table === 'items').row.greek, 'σπίτι μου');
  assert.equal(reopened.apply('items', item('i2', 5)).seq, 4);
});

test('a malformed trailing line in rows.jsonl is skipped on load', (t) => {
  const dir = tempDir(t);
  const seed = new Store(dir);
  seed.apply('items', item('i1', 10));
  seed.apply('items', item('i2', 20));

  const warnings = captureWarnings(t);
  const rowsFile = join(dir, 'rows.jsonl');

  // A crash mid-append: a half-written line with no newline at the end.
  appendFileSync(rowsFile, '{"seq":3,"table":"items","row":{"id":"i3","upda');
  const recovered = new Store(dir);
  assert.equal(recovered.seq, 2, 'the partial line is ignored');
  assert.equal(recovered.since(0).rows.length, 2);
  assert.ok(warnings.some((w) => w.includes('partial line')), 'the partial line was reported');
  assert.ok(
    !readFileSync(rowsFile, 'utf8').includes('"i3"'),
    'the partial tail is truncated so the next append cannot glue onto it',
  );
  assert.ok(readFileSync(rowsFile, 'utf8').endsWith('\n'));

  // A complete but unparseable line (for instance a torn write that still got
  // its newline) is skipped too, and the good rows around it still load.
  appendFileSync(rowsFile, '{"seq":3,"table":"items",,,}\n');
  const alsoRecovered = new Store(dir);
  assert.equal(alsoRecovered.skippedLines, 1);
  assert.equal(alsoRecovered.seq, 2);
  assert.ok(warnings.some((w) => w.includes('not valid JSON')));

  // ...and the server keeps working afterwards.
  assert.equal(alsoRecovered.apply('items', item('i3', 30)).seq, 3);
  assert.equal(new Store(dir).since(0).rows.length, 3);
});

test('entries for unknown tables or without an id are skipped on load', (t) => {
  const dir = tempDir(t);
  const warnings = captureWarnings(t);
  writeFileSync(
    join(dir, 'rows.jsonl'),
    [
      '{"seq":1,"table":"items","row":{"id":"i1","updatedAt":1}}',
      '{"seq":2,"table":"ghosts","row":{"id":"g1","updatedAt":1}}',
      '{"seq":3,"table":"items","row":{"updatedAt":1}}',
      '{"seq":"x","table":"items","row":{"id":"i9","updatedAt":1}}',
      '',
    ].join('\n'),
  );
  const store = new Store(dir);
  assert.equal(store.skippedLines, 3);
  assert.equal(store.seq, 1);
  assert.equal(store.since(0).rows.length, 1);
  assert.ok(warnings.length > 0);
});

// ---- media -----------------------------------------------------------------

const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');

test('media round trip: put, has, size, path', async (t) => {
  const dir = tempDir(t);
  const store = new Store(dir);
  const bytes = Buffer.from('ηχογράφηση', 'utf8');
  const sha = sha256(bytes);

  assert.equal(store.has(sha), false);
  assert.equal(store.size(sha), null);

  const result = await store.put(sha, Readable.from([bytes]));
  assert.equal(result.sha, sha);
  assert.equal(result.bytes, bytes.length);
  assert.equal(store.has(sha), true);
  assert.equal(store.size(sha), bytes.length);
  assert.deepEqual(readFileSync(store.path(sha)), bytes);
  assert.deepEqual(readdirSync(join(dir, 'media')), [sha], 'no temp file left behind');

  // Uploading the same bytes again is harmless.
  await store.put(sha, Readable.from([bytes]));
  assert.deepEqual(readdirSync(join(dir, 'media')), [sha]);
});

test('media with a mismatched hash is rejected and nothing is left behind', async (t) => {
  const dir = tempDir(t);
  const store = new Store(dir);
  const claimed = sha256(Buffer.from('the photo I promised'));
  const actual = Buffer.from('something else entirely');

  await assert.rejects(
    () => store.put(claimed, Readable.from([actual])),
    (err) => {
      assert.equal(err.code, 'HASH_MISMATCH');
      assert.equal(err.expected, claimed);
      assert.equal(err.actual, sha256(actual));
      return true;
    },
  );
  assert.equal(store.has(claimed), false);
  assert.deepEqual(readdirSync(join(dir, 'media')), [], 'no blob and no .tmp file survive');
});

test('media over the limit is rejected and nothing is left behind', async (t) => {
  const dir = tempDir(t);
  const store = new Store(dir);
  const bytes = Buffer.alloc(4096, 7);
  await assert.rejects(
    () => store.put(sha256(bytes), Readable.from([bytes]), 1024),
    (err) => err.code === 'TOO_LARGE',
  );
  assert.deepEqual(readdirSync(join(dir, 'media')), []);
});

test('orphaned media temp files from an earlier run are swept on start', (t) => {
  const dir = tempDir(t);
  const mediaDir = join(dir, 'media');
  new Store(dir); // creates DATA_DIR/media

  const bytes = Buffer.from('an upload that never finished');
  const sha = sha256(bytes);
  const orphanA = join(mediaDir, `${sha}.aabbccddeeff.tmp`);
  const orphanB = join(mediaDir, `${sha256(Buffer.from('another'))}.001122334455.tmp`);
  const inFlight = join(mediaDir, `${sha256(Buffer.from('current'))}.ffeeddccbbaa.tmp`);
  const realBlob = join(mediaDir, sha);
  for (const file of [orphanA, orphanB, inFlight, realBlob]) writeFileSync(file, bytes);

  // A hard kill leaves temp files older than the next process; an upload
  // running right now is newer than it and must survive.
  const beforeThisProcess = new Date(Date.now() - process.uptime() * 1000 - 60_000);
  utimesSync(orphanA, beforeThisProcess, beforeThisProcess);
  utimesSync(orphanB, beforeThisProcess, beforeThisProcess);

  const warnings = captureWarnings(t);
  const swept = new Store(dir);
  assert.equal(existsSync(orphanA), false);
  assert.equal(existsSync(orphanB), false);
  assert.equal(existsSync(inFlight), true, 'an upload in flight is not touched');
  assert.equal(existsSync(realBlob), true, 'stored media is not touched');
  assert.ok(
    warnings.some((w) => w.includes('removed 2 orphaned media temp file(s)')),
    `the count is logged, got: ${JSON.stringify(warnings)}`,
  );

  // Nothing to do on the next start.
  assert.equal(swept.sweepTempMedia(), 0);
});

test('a media id that is not a sha-256 is refused', async (t) => {
  const store = new Store(tempDir(t));
  assert.equal(store.has('../../etc/passwd'), false);
  assert.throws(() => store.path('../../etc/passwd'), /64 lowercase hex/);
  assert.throws(() => store.path('ABC'), /64 lowercase hex/);
  await assert.rejects(() => store.put('nope', Readable.from([Buffer.from('x')])), /64 lowercase hex/);
});
