// Usage: node tools/seed/fetch-arasaac.mjs
// Reads words.json, asks ARASAAC for the Greek pictogram of each word (falling back to an
// English search when Greek has no exact match — ARASAAC's Greek keyword coverage is partial,
// its pictograms are language-neutral images), writes PNGs + seed.json into app assets.
import fs from 'node:fs/promises';

// Bump when words.json changes: SeedImporter only looks at the manifest again when this is higher
// than the version the device has, and then adds only the texts it does not already have — and,
// since phase 13, re-grades the tier and the gender of the bundled words it has not been edited.
const VERSION = 3;

// The two gradings every entry carries out of words.json and into the manifest. `tier` is how hard
// the word is, 1 to 5 (the word coach's dots read it); `gender` is 'M', 'F' or 'N' on a noun and
// absent on everything else (the sentence builder's articles read it). They are copied from
// words.json on every run, pictogram or no pictogram, so re-grading a word that already has its
// picture costs nothing and downloads nothing.
const graded = (w) => ({ tier: w.tier ?? 1, gender: w.gender ?? null });

// A word that ships text-led on purpose. ARASAAC has one pictogram for a family of related words —
// "hope" is the same drawing for «ελπίδα» and «ελπίζω», "work" for «δουλειά» and «δουλεύω» — and a
// picture that means two words is worse than no picture at all: the word coach's first rung shows
// the picture alone, and he would be scored on two different targets from one drawing. Setting this
// keeps the word and drops the search, and the ladder skips that rung (see CueLadder).
const textLed = (w) => w.noPicture === true;

// The terms a word's picture was chosen with, recorded in the manifest beside the picture. It is
// what makes "keep what the last run downloaded" and "let me correct a bad term" both true: a row
// whose terms have not changed keeps its picture for ever, and a row whose `en` has been rewritten
// because the first English result was the wrong sense is looked up again — only that row.
const termKey = (w) => `${(w.search ?? w.text).trim()}|${(w.en ?? '').trim()}`;

const words = JSON.parse(await fs.readFile(new URL('./words.json', import.meta.url), 'utf8'));
const outDir = new URL('../../app/src/main/assets/seed/', import.meta.url);
await fs.mkdir(outDir, { recursive: true });

// Whatever the last run already chose and downloaded. ARASAAC's search results move over time, so
// re-picking a pictogram for a word that already has one would churn every image in the repo and
// change pictures he has learned; only the words without one are looked up again.
const previous = new Map();
try {
  const old = JSON.parse(await fs.readFile(new URL('seed.json', outDir), 'utf8'));
  for (const it of old.items ?? []) previous.set(it.text, it);
} catch {
  // No previous manifest: everything is fetched.
}

async function alreadyHave(word) {
  const prev = previous.get(word.text);
  if (!prev?.image) return null;
  // A term that has been corrected since the picture was chosen. An older manifest recorded no
  // term at all, and then the picture stands: not re-picking what is already downloaded is the
  // whole point of this cache.
  if (prev.term && prev.term !== termKey(word)) return null;
  try {
    await fs.access(new URL(prev.image, outDir));
    return prev;
  } catch {
    return null;
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function searchPictograms(lang, mode, term) {
  try {
    const res = await fetch(`https://api.arasaac.org/v1/pictograms/${lang}/${mode}/${encodeURIComponent(term)}`);
    if (res.ok) return await res.json();
  } catch (e) {
    console.warn(`${mode} failed for ${term} (${lang}): ${e.message}`);
  }
  return [];
}

function exactMatch(list, term) {
  return list.find((p) => p.keywords?.some((k) => k.keyword?.trim().toLowerCase() === term.toLowerCase()));
}

const items = [];
const missing = [];
let viaEl = 0;
let viaEn = 0;

let reused = 0;

for (const w of words) {
  if (textLed(w)) {
    items.push({ text: w.text, kind: w.kind ?? 'WORD', category: w.category, ...graded(w), image: null, arasaacId: null, via: null, term: termKey(w) });
    missing.push(w.text);
    process.stdout.write('-');
    continue;
  }

  const kept = await alreadyHave(w);
  if (kept) {
    items.push({ text: w.text, kind: w.kind ?? 'WORD', category: w.category, ...graded(w), image: kept.image, arasaacId: kept.arasaacId ?? null, via: kept.via ?? null, term: termKey(w) });
    if (kept.via === 'en') viaEn++; else viaEl++;
    reused++;
    process.stdout.write('=');
    continue;
  }

  const term = (w.search ?? w.text).trim();
  const listEl = await searchPictograms('el', 'search', term);
  let pick = exactMatch(listEl, term);
  let via = pick ? 'el' : null;

  if (!pick && w.en) {
    const termEn = w.en.trim();
    const listBest = await searchPictograms('en', 'bestsearch', termEn);
    let pickEn = exactMatch(listBest, termEn);
    let listSearch = [];
    if (!pickEn) {
      listSearch = await searchPictograms('en', 'search', termEn);
      pickEn = exactMatch(listSearch, termEn);
    }
    pickEn = pickEn ?? listBest[0] ?? listSearch[0] ?? null;
    if (pickEn) {
      pick = pickEn;
      via = 'en';
    }
  }

  if (!pick) pick = listEl[0];
  if (pick && !via) via = 'el';

  let image = null;
  if (pick) {
    const id = pick._id;
    try {
      const png = await fetch(`https://static.arasaac.org/pictograms/${id}/${id}_300.png`);
      if (png.ok) {
        image = `${id}.png`;
        await fs.writeFile(new URL(image, outDir), Buffer.from(await png.arrayBuffer()));
      }
    } catch (e) {
      console.warn(`image download failed for ${w.text} (id ${id}): ${e.message}`);
    }
  }
  if (!image) {
    missing.push(w.text);
    via = null;
  } else if (via === 'el') {
    viaEl++;
  } else if (via === 'en') {
    viaEn++;
  }
  items.push({ text: w.text, kind: w.kind ?? 'WORD', category: w.category, ...graded(w), image, arasaacId: pick?._id ?? null, via, term: termKey(w) });
  process.stdout.write(image ? (via === 'en' ? 'e' : '.') : 'x');
  await sleep(150);
}

await fs.writeFile(new URL('seed.json', outDir), JSON.stringify({ version: VERSION, items }, null, 1));

// Whatever no word points at any more. A word that was renamed, dropped or turned text-led leaves
// its PNG behind, and a stray half-megabyte in the APK is the least of it: the next reader of this
// directory cannot tell which files are live. Only .png files are considered — seed.json and
// scripts.json live here too.
const live = new Set(items.map((it) => it.image).filter(Boolean));
let pruned = 0;
for (const name of await fs.readdir(outDir)) {
  if (!name.endsWith('.png') || live.has(name)) continue;
  await fs.rm(new URL(name, outDir));
  pruned++;
}
console.log(`\nv${VERSION}: ${items.length} items, ${items.length - missing.length} with pictograms (${viaEl} via el, ${viaEn} via en, ${reused} kept from the last run, ${pruned} orphan file(s) removed). Without a pictogram: ${missing.join(', ') || 'none'}`);
