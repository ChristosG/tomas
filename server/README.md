# Dimitris' App — sync server

A tiny self-hosted sync server for [Dimitris' App](../docs). Chris and Dimitris' father each
install the same Android app as *caregivers*; this service is what lets their phones (and
Dimitris' phone) see the same words, photos, voice recordings, schedules and practice results.

It is deliberately small: **two source files, zero dependencies, Node 22 built-ins only.**
Everything lives in a directory you can copy: a line-per-row log and a folder of media files.

- `server.mjs` — the HTTP layer (routes, bearer token, body limits, request log)
- `store.mjs` — the row log and the media store (merge rules, sha-256 verification)
- `store.test.mjs`, `server.test.mjs` — the tests (`node --test`)

---

## 1. Run it

Requires Node 22 or newer. Nothing to install — there are no dependencies.

```bash
cd server
SYNC_TOKEN=$(openssl rand -hex 32) PORT=8787 DATA_DIR=./data node server.mjs
```

| Variable | Default | Meaning |
| --- | --- | --- |
| `SYNC_TOKEN` | *(required)* | Shared secret. Every request except `/v1/health` must send `Authorization: Bearer <token>`. The server refuses to start without it. |
| `PORT` | `8787` | TCP port to listen on. |
| `DATA_DIR` | `./data` | Where `rows.jsonl` and `media/` are kept. Created if missing. |

Generate the token once and put the *same* value into both caregiver phones
(Settings → server URL + token in the app):

```bash
openssl rand -hex 32
```

Run the tests:

```bash
cd server && node --test
```

## 2. Run it with Docker

```bash
cd server
echo "SYNC_TOKEN=$(openssl rand -hex 32)" > .env   # never commit this file
docker compose up -d --build
docker compose logs -f
```

`compose.yaml` publishes the port on `127.0.0.1` only and keeps the data in a named volume
(`sync-data`, mounted at `/data`). Put a TLS reverse proxy in front of it — see the next
section. To back the volume up:

```bash
docker run --rm -v sync-data:/data -v "$PWD":/out alpine tar czf /out/sync-backup.tgz -C /data .
```

## 3. Put it behind TLS

**Never expose the server directly.** The bearer token is sent on every request, so the
connection must be encrypted by a reverse proxy that already has a certificate.

### Caddy

```caddyfile
sync.example.com {
    reverse_proxy 127.0.0.1:8787 {
        flush_interval -1
    }
    request_body {
        max_size 25MB
    }
}
```

### nginx

```nginx
server {
    listen 443 ssl http2;
    server_name sync.example.com;

    ssl_certificate     /etc/letsencrypt/live/sync.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/sync.example.com/privkey.pem;

    # Voice recordings and photos are uploaded whole; the default 1m is far too small.
    client_max_body_size 25m;
    proxy_request_buffering off;
    proxy_read_timeout 120s;

    location /v1/ {
        proxy_pass http://127.0.0.1:8787;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Then set the app's server URL to `https://sync.example.com` on both caregiver phones.

## 4. The API

Base path `/v1`. All responses are JSON (`application/json; charset=utf-8`) except a media
download, which is `application/octet-stream`. Every route except `/v1/health` requires
`Authorization: Bearer $SYNC_TOKEN`; a missing or wrong token gets `401`.

The examples below assume:

```bash
BASE=http://127.0.0.1:8787
TOKEN=your-sync-token
```

### `GET /v1/health` — is it up? (no token)

```bash
curl -s $BASE/v1/health
# {"ok":true,"seq":42}
```

`seq` is the server's current sequence number — the number of rows it has accepted.

### `POST /v1/push` — send changed rows

```bash
curl -s -X POST $BASE/v1/push \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"rows":[
        {"table":"items","row":{"id":"i1","updatedAt":1757000000000,"deleted":false,"text":"ψωμί"}},
        {"table":"attempts","row":{"id":"a1","updatedAt":1757000000001,"itemId":"i1","outcome":"CORRECT"}}
      ]}'
# {"ok":true,"seq":2,"accepted":2,"ignored":0}
```

`accepted` is how many rows were stored, `ignored` how many lost the merge (older than the
stored row, or a duplicate id in an append-only table). `seq` is the server's top sequence
number **after** the batch — it is informational; do **not** use it to advance a pull cursor.

The whole batch is validated first: if any entry names an unknown table or carries a row
without an `id` (or, outside the append-only tables, without an `updatedAt`), the server
answers `400` with the offending `index` and stores *nothing*.

### `GET /v1/pull?since=<seq>&limit=<n>` — receive rows

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/v1/pull?since=0&limit=500"
# {"rows":[{"seq":1,"table":"items","row":{...}},{"seq":2,"table":"attempts","row":{...}}],"seq":2}
```

Returns the rows with `seq > since`, in `seq` order. The top-level `seq` is the server's
current sequence number.

`since` must be a non-negative integer (it defaults to 0 when omitted); anything else —
`abc`, `NaN`, `-1`, `1.5` — is answered with `400 {"error":"since must be a non-negative
integer"}` rather than quietly starting from the beginning, because a broken cursor would
otherwise re-download the entire history on every sync. `limit` is only a hint: it is clamped
into `1..500` and falls back to 500 if it is not a number.

**Client loop:** keep the highest `seq` of the rows you received as your cursor and pull
again until `rows` comes back empty. Do not jump the cursor to the top-level `seq` — that
would skip everything a capped page left behind.

Only the current version of a row is ever returned: when a row is updated it moves to a new,
higher `seq`, so no client can miss an update and nobody has to download superseded copies.

### `HEAD|PUT|GET /v1/media/<sha256>` — photos and voice recordings

Media is content-addressed: the file name *is* the lowercase hex sha-256 of the bytes. In
rows, a media field holds `media://<sha256>`; the app resolves it to a local file.

```bash
FILE=photo.jpg
SHA=$(sha256sum "$FILE" | cut -d' ' -f1)

# Already there? 200 = yes (skip the upload), 404 = no
curl -s -o /dev/null -w '%{http_code}\n' -I -H "Authorization: Bearer $TOKEN" $BASE/v1/media/$SHA

# Upload (201 the first time, 200 if the server already had it)
curl -s -X PUT --data-binary @"$FILE" -H "Authorization: Bearer $TOKEN" $BASE/v1/media/$SHA
# {"ok":true,"sha":"…","bytes":51234,"stored":true}

# Download
curl -s -o photo-copy.jpg -H "Authorization: Bearer $TOKEN" $BASE/v1/media/$SHA
```

The server hashes the upload as it streams it to a temp file and only keeps it if the hash
matches the URL — a corrupted upload is answered with `400 {"error":"hash mismatch"}` and
leaves nothing on disk. Media is never deleted by the server. (A temp file orphaned by a hard
kill is swept on the next start, and the number removed is logged.)

### Limits and errors

| Situation | Status |
| --- | --- |
| Missing / wrong bearer token | `401 {"error":"unauthorized"}` |
| Body is not valid JSON | `400 {"error":"malformed json"}` |
| Unknown table, row without an id | `400 {"error":"…","index":N}` |
| `since` that is not a non-negative integer | `400 {"error":"since must be a non-negative integer"}` |
| JSON body over **5 MB** | `413` |
| Media body over **20 MB** | `413` |
| Media body does not match the sha in the URL | `400 {"error":"hash mismatch"}` |
| Media id that is not 64 lowercase hex characters | `400` |
| Unknown path | `404 {"error":"not found"}` |
| Known path, wrong method | `405` + `Allow` |

A `401` or `413` on a request that is still uploading is answered and then **hung up on**:
the server writes the status and closes the connection instead of reading the rest of a body
it has already refused. Clients see the real status (the response is flushed first) and
should not retry the same oversized request.

Every request logs exactly one line — timestamp, method, path, status, duration — and never
logs the token:

```
2026-09-05T09:12:44.031Z POST /v1/push 200 1.8ms
```

## 5. Merge rules

Every synced row carries `id`, `updatedAt` and `deleted`. Two phones can edit offline, so
the server has to decide which copy wins:

| Tables | Rule |
| --- | --- |
| `attempts`, `error_logs` | **Append-only.** The first row stored for an id wins; later rows with the same id are ignored. These are immutable facts (a practice attempt, a logged error). |
| `items`, `recordings`, `schedules`, `sessions`, `scripts`, `script_lines` | **Last-write-wins on `updatedAt`.** A pushed row replaces the stored one only if its `updatedAt` is strictly greater; a tie keeps the row already stored. Deletes are soft — `deleted: true` with a newer `updatedAt`. |

Those eight names are the complete list; a row for any other table is rejected with `400`.
(Adding a table later means adding its name to `TABLES` in `store.mjs` and redeploying.)

`schedules` rows are keyed on `(itemId, module)`, which the app sends as the id
`"<itemId>:<module>"`.

**An open editor does not know about a delete that arrived while it was open.** If Chris deletes
«ψωμί» while the father has that word open on his screen, the father's next save writes the word
back — a newer row with `deleted: false` — and it returns on all three phones. That is
last-write-wins doing what it says; the word is visible and can simply be deleted again.

**`updatedAt` is three phone clocks, not one.** "Last write" means "the larger number", which is
only "the later edit" while the phones agree about the time. If the father's phone runs two
minutes slow, an edit he makes now can lose to an edit Chris made three minutes ago, and neither
of them is told. **Leave automatic date & time on in every phone's settings** — that is the whole
requirement, and Android does it by default.

## 6. Data layout and backup

```
data/                     <- DATA_DIR
├── rows.jsonl            one JSON object per line: {"seq":1,"table":"items","row":{…}}
└── media/
    ├── 3f786850e387550f… photo / recording, named by its sha-256
    └── …
```

`rows.jsonl` is append-only and written with a single synchronous append per accepted row,
so an interrupted write can only ever damage the last line. On start the server truncates a
half-written trailing line and skips any line it cannot parse, logging a warning — it never
refuses to start because of one bad line. It also sweeps `media/*.tmp` files left by a hard
kill (only ones older than the running process, so an upload in flight is never touched) and
logs how many it removed.

**Backup is a copy of `data/`.** Nothing else is state.

```bash
# stopping the server first gives a perfectly consistent copy, but a hot copy is fine too
tar czf sync-backup-$(date +%F).tgz -C /path/to data
```

To restore, put `data/` back and start the server. To start over, stop the server and delete
`data/` — the phones will push everything again on their next sync.

## 7. Known limitations

- **Per-device levels are not synced.** The difficulty levels (numbers, sentences, tracing)
  live on each phone and stay there on purpose, so the father can adjust Dimitris' phone
  without changing his own. Everything else — words, photos, recordings, schedules, scripts,
  attempts, sessions, errors — is shared.
- One shared token, no user accounts. Anyone with the token can read and write everything, so
  keep it off group chats and rotate it (restart with a new `SYNC_TOKEN`, then update both
  phones) if a phone is lost.
- Media is never deleted, even when the row that referenced it is deleted. A few thousand
  photos and recordings is a handful of gigabytes at most; prune by hand if it ever matters.
- The whole row index is kept in memory, and a pull sorts it. That is instantaneous at this
  scale (a few thousand rows) and is not meant to scale to thousands of users.
- Single process, no clustering. Run exactly one instance per `DATA_DIR`.
- After restoring a phone from a backup, the app resets its sync cursors and pulls everything
  again; last-write-wins sorts the result out.
- **Clearing the error list is per phone.** `error_logs` is append-only on both sides, so hiding
  the list on one phone leaves those rows on the other two and on the server. There is no way to
  clear them everywhere short of deleting `rows.jsonl`.
- **`rows.jsonl` only grows.** Every accepted edit appends a line and nothing compacts it, and the
  whole file is read back into memory on start. At a few hundred thousand rows that is a second and
  a couple of hundred megabytes; a log that ever grew past about half a gigabyte would stop the
  server from starting at all. For three phones that is decades away, but if it ever matters: stop
  the server, keep only the newest line per `table` + `row.id`, and start it again.
- **Sync does not ask whether the phone is on mobile data.** A phone that has just been set up
  sends its whole vocabulary and about 180 pictograms, and every launch afterwards syncs whatever
  has changed. That is a few hundred megabytes once and very little after that, so the app does not
  bother anyone with a question — but the first sync is better done on wi-fi.

## 8. Handing it over

Five things, once, and then nobody has to think about this again.

1. **Put it behind TLS.** Chapter 3. The token is sent on every request, so the connection has to
   be encrypted by a proxy that already holds a certificate. Never publish the port itself.
2. **Generate the token once** with `openssl rand -hex 32`, and type *the same one* into every
   phone: Φροντιστής → Συγχρονισμός → Κλειδί, with the address (`https://sync.example.com`) in
   the field above it. Three phones, one address, one token. Send it in a way you would send a
   house key, not in a group chat.
3. **Set up Dimitris' phone too.** It is the one whose practice everyone else wants to see. Hold
   his name on the first screen for two seconds, say Ναι, and the same two fields are there. His
   own screens never mention any of this.
4. **Leave automatic date & time on in all three phones** — see chapter 5. It is what makes "the
   newest edit wins" mean what it says.
5. **Back up `data/`.** Chapter 6. That directory is the whole of it: copy it, and you have
   everything; put it back, and the server is where it was.

What to expect the first time: each phone sends its whole vocabulary up (a few hundred rows and
about 180 pictograms) and takes back whatever the others added. The screen says so in one line —
«Έστειλα … πήρα …» — and the second sync is quiet. The bundled words and dialogues carry the same
ids on every phone, so they merge into one copy rather than three.

---

## Περίληψη στα ελληνικά

**Τι είναι:** ένας μικρός δικός μας διακομιστής (server) που κρατάει τα δεδομένα της
εφαρμογής του Δημήτρη, για να βλέπουν τα δύο κινητά των φροντιστών και το κινητό του Δημήτρη
τις ίδιες λέξεις, φωτογραφίες, ηχογραφήσεις, προγράμματα και αποτελέσματα. Δεν χρησιμοποιεί
καμία εξωτερική υπηρεσία — τρέχει στον δικό σας web server.

**Τι χρειάζεται:** Node 22 (ή Docker). Δεν εγκαθίσταται τίποτα άλλο.

**Εκκίνηση:**

```bash
cd server
SYNC_TOKEN=το-μυστικό-κλειδί PORT=8787 DATA_DIR=./data node server.mjs
```

Ή με Docker:

```bash
cd server
echo "SYNC_TOKEN=$(openssl rand -hex 32)" > .env
docker compose up -d --build
```

**Το μυστικό κλειδί (token):** φτιάχνεται μία φορά με `openssl rand -hex 32`. Το ίδιο ακριβώς
κλειδί μπαίνει και στα δύο κινητά των φροντιστών, στις Ρυθμίσεις της εφαρμογής, μαζί με τη
διεύθυνση του διακομιστή (π.χ. `https://sync.example.com`). Χωρίς αυτό ο διακομιστής δεν
απαντάει σε κανέναν.

**Ασφάλεια:** ο διακομιστής πρέπει να μπει **πίσω από nginx ή caddy με HTTPS** (κεφάλαιο 3
παραπάνω). Ποτέ να μην εκτεθεί απευθείας στο ίντερνετ.

**Έλεγχος ότι δουλεύει:**

```bash
curl -s https://sync.example.com/v1/health
# {"ok":true,"seq":42}
```

**Αντίγραφα ασφαλείας:** όλα τα δεδομένα είναι μέσα στον φάκελο `data/` (το αρχείο
`rows.jsonl` και ο φάκελος `media/`). Αντιγραφή αυτού του φακέλου = πλήρες αντίγραφο
ασφαλείας. Επαναφορά = τον βάζετε πίσω στη θέση του και ξεκινάτε τον διακομιστή.

**Τι ΔΕΝ συγχρονίζεται:** τα επίπεδα δυσκολίας (αριθμοί, προτάσεις, γραφή) μένουν στο κάθε
κινητό ξεχωριστά. Έτσι μπορείτε να αλλάξετε το επίπεδο στο κινητό του Δημήτρη χωρίς να
επηρεαστεί το δικό σας. Όλα τα υπόλοιπα (λέξεις, φωτογραφίες, ηχογραφήσεις, προγράμματα,
προσπάθειες, σφάλματα) συγχρονίζονται κανονικά.

**Η ώρα των τηλεφώνων:** αφήστε την **αυτόματη ημερομηνία και ώρα** ανοιχτή και στα τρία
κινητά (Ρυθμίσεις Android → Σύστημα → Ημερομηνία και ώρα). Όταν δύο άνθρωποι αλλάξουν την ίδια
λέξη, κρατιέται η αλλαγή με τη νεότερη ώρα — κι αν ένα κινητό πηγαίνει πίσω, η δική του αλλαγή
μπορεί να χαθεί χωρίς να το πει κανείς. Το Android το κάνει αυτόματα από μόνο του· απλώς μην
το κλείσετε.

**Το κινητό του Δημήτρη:** ρυθμίζεται κι αυτό, από εσάς. Κρατήστε πατημένο το όνομά του στην
πρώτη οθόνη για δύο δευτερόλεπτα, «Ναι», μετά «Συγχρονισμός», και βάλτε την ίδια διεύθυνση και
το ίδιο κλειδί. Οι δικές του οθόνες δεν δείχνουν ποτέ τίποτα από αυτά.

**Την πρώτη φορά:** κάθε κινητό στέλνει όλο του το λεξιλόγιο (μερικές εκατοντάδες γραμμές και
γύρω στις 180 εικόνες) και παίρνει ό,τι πρόσθεσαν τα άλλα. Η οθόνη το λέει σε μία γραμμή
(«Έστειλα … πήρα …»). Ο δεύτερος συγχρονισμός δεν έχει τίποτα να πει — και οι λέξεις που
έρχονται μαζί με την εφαρμογή δεν διπλασιάζονται, γιατί είναι οι ίδιες σε κάθε κινητό.

**Αν κάτι πάει στραβά:** δείτε τα μηνύματα του διακομιστή (`docker compose logs -f` ή την
κονσόλα). Κάθε αίτημα γράφει μία γραμμή με την ώρα, τη διαδρομή και τον κωδικό απάντησης.
Ο κωδικός `401` σημαίνει λάθος κλειδί, ο `413` πολύ μεγάλο αρχείο, ο `404` άγνωστη διαδρομή.
