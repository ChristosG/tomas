// Usage: node tools/seed/fetch-arasaac.mjs
// Reads words.json, asks ARASAAC for the Greek pictogram of each word (falling back to an
// English search when Greek has no exact match — ARASAAC's Greek keyword coverage is partial,
// its pictograms are language-neutral images), writes PNGs + seed.json into app assets.
import fs from 'node:fs/promises';

const words = JSON.parse(await fs.readFile(new URL('./words.json', import.meta.url), 'utf8'));
const outDir = new URL('../../app/src/main/assets/seed/', import.meta.url);
await fs.mkdir(outDir, { recursive: true });

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

for (const w of words) {
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
    const png = await fetch(`https://static.arasaac.org/pictograms/${id}/${id}_300.png`);
    if (png.ok) {
      image = `${id}.png`;
      await fs.writeFile(new URL(image, outDir), Buffer.from(await png.arrayBuffer()));
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
  items.push({ text: w.text, kind: w.kind ?? 'WORD', category: w.category, image, arasaacId: pick?._id ?? null, via });
  process.stdout.write(image ? (via === 'en' ? 'e' : '.') : 'x');
  await sleep(150);
}

await fs.writeFile(new URL('seed.json', outDir), JSON.stringify({ version: 1, items }, null, 1));
console.log(`\n${items.length} items, ${items.length - missing.length} with pictograms (${viaEl} via el, ${viaEn} via en). Missing: ${missing.join(', ') || 'none'}`);
