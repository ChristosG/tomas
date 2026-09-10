// Usage: node tools/seed/fetch-steps.mjs
//
// The pictograms for «Βήματα». Reads app/src/main/assets/seed/steps.json, asks ARASAAC for a
// drawing for every step that has not got one yet, writes the PNGs into
// app/src/main/assets/seed/steps/ and puts the file name back into steps.json.
//
// Why its own script rather than a pass inside fetch-arasaac.mjs: that one owns
// `assets/seed/*.png` and deletes every PNG there that `words.json` does not name (and
// `SeedContentTest` asserts exactly that agreement). The step pictograms are not vocabulary — they
// are never `Item` rows, they are never in the word coach, and a step is a whole phrase rather than
// a word — so they live in a subdirectory of their own, which both the prune and the test walk past.
//
// Searching is by the step's `en` term only. A step is a Greek phrase («Βάζω νερό στο μπρίκι») and
// ARASAAC has no keyword for a phrase; the `en` is the one content word the drawing should show
// («water»), chosen by hand when the step was written.
//
// A step whose search finds nothing ships text-led, with `"image": null`: the screen then shows the
// phrase alone, which is the honest answer and is what `StepTasks` expects. Nothing here is ever a
// reason for a step to be missing.
//
// Re-running is cheap and safe: a step whose `image` names a file that is on disk is left exactly as
// it is, so the drawings he has learned never churn. To re-pick one, set its `image` back to null.
import fs from 'node:fs/promises';

const seedFile = new URL('../../app/src/main/assets/seed/steps.json', import.meta.url);
const outDir = new URL('../../app/src/main/assets/seed/steps/', import.meta.url);
await fs.mkdir(outDir, { recursive: true });

const seed = JSON.parse(await fs.readFile(seedFile, 'utf8'));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function searchPictograms(mode, term) {
  try {
    const res = await fetch(`https://api.arasaac.org/v1/pictograms/en/${mode}/${encodeURIComponent(term)}`);
    if (res.ok) return await res.json();
  } catch (e) {
    console.warn(`${mode} failed for ${term}: ${e.message}`);
  }
  return [];
}

const exactMatch = (list, term) =>
  list.find((p) => p.keywords?.some((k) => k.keyword?.trim().toLowerCase() === term.toLowerCase()));

async function alreadyHave(step) {
  if (!step.image) return false;
  try {
    await fs.access(new URL(step.image.replace(/^steps\//, ''), outDir));
    return true;
  } catch {
    return false;
  }
}

let fetched = 0;
let kept = 0;
const missing = [];

/** Every tile the screen can show: the steps, and the difficulty-5 distractor. */
const allSteps = seed.tasks.flatMap((t) => [...t.steps, ...(t.distractor ? [t.distractor] : [])]);

for (const step of allSteps) {
  if (await alreadyHave(step)) {
    kept++;
    process.stdout.write('=');
    continue;
  }
  const term = (step.en ?? '').trim();
  if (!term) {
    step.image = null;
    missing.push(step.text);
    process.stdout.write('-');
    continue;
  }
  const best = await searchPictograms('bestsearch', term);
  let pick = exactMatch(best, term);
  let list = [];
  if (!pick) {
    list = await searchPictograms('search', term);
    pick = exactMatch(list, term);
  }
  pick = pick ?? best[0] ?? list[0] ?? null;

  let image = null;
  if (pick) {
    const id = pick._id;
    try {
      const png = await fetch(`https://static.arasaac.org/pictograms/${id}/${id}_300.png`);
      if (png.ok) {
        image = `steps/${id}.png`;
        await fs.writeFile(new URL(`${id}.png`, outDir), Buffer.from(await png.arrayBuffer()));
      }
    } catch (e) {
      console.warn(`image download failed for ${step.text} (id ${id}): ${e.message}`);
    }
  }
  step.image = image;
  if (image) fetched++;
  else missing.push(step.text);
  process.stdout.write(image ? '.' : 'x');
  await sleep(150);
}

// Whatever no step points at any more — a step that was re-worded, dropped, or turned text-led.
// Only this subdirectory is touched: the vocabulary's own pictograms are fetch-arasaac.mjs' business.
const live = new Set(allSteps.map((s) => s.image).filter(Boolean).map((i) => i.replace(/^steps\//, '')));
let pruned = 0;
for (const name of await fs.readdir(outDir)) {
  if (!name.endsWith('.png') || live.has(name)) continue;
  await fs.rm(new URL(name, outDir));
  pruned++;
}

/**
 * steps.json as a human wrote it: one line per step, the tasks in their own order.
 *
 * `JSON.stringify(…, null, 1)` would put all five keys of all hundred steps on lines of their own,
 * and the one file a person has to read to know what the module asks him to do would stop being
 * readable. The content of this file is written by hand; only `image` is written by a machine.
 */
function dump(seed) {
  const q = (s) => JSON.stringify(s);
  const step = (s) => `{ "text": ${q(s.text)}, "en": ${q(s.en ?? null)}, "image": ${q(s.image ?? null)} }`;
  const lines = [];
  lines.push('{');
  lines.push(` "version": ${seed.version},`);
  lines.push(' "tasks": [');
  seed.tasks.forEach((t, i) => {
    lines.push('  {');
    lines.push(`   "id": ${q(t.id)},`);
    lines.push(`   "title": ${q(t.title)},`);
    lines.push(`   "difficulty": ${t.difficulty},`);
    lines.push('   "steps": [');
    t.steps.forEach((s, j) => lines.push(`    ${step(s)}${j === t.steps.length - 1 ? '' : ','}`));
    lines.push(t.distractor ? '   ],' : '   ]');
    if (t.distractor) lines.push(`   "distractor": ${step(t.distractor)}`);
    lines.push(`  }${i === seed.tasks.length - 1 ? '' : ','}`);
  });
  lines.push(' ]');
  lines.push('}');
  return lines.join('\n') + '\n';
}

await fs.writeFile(seedFile, dump(seed));

const steps = allSteps.length;
console.log(
  `\nv${seed.version}: ${seed.tasks.length} tasks, ${steps} steps, ${steps - missing.length} with pictograms ` +
    `(${fetched} fetched, ${kept} kept, ${pruned} orphan file(s) removed). ` +
    `Text-led: ${missing.join(', ') || 'none'}`,
);
