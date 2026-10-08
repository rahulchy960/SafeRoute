# Search-quality evaluation

Measures how well a geocoding provider finds real places across West Bengal, before launch
(addendum v7.2 section E, [ADR 0018](../../../docs/adr/0018-search-and-geocoding.md)). It is a
**measurement, not a gate**: it always exits 0, and it is never run in CI (no key, no network).

| File | What |
| --- | --- |
| [`fixture.json`](fixture.json) | The queries. Committed, public |
| [`small-town-template.json`](small-town-template.json) | Category and brand queries from small towns: a **template** for Rahul to review, not yet a measurement set |
| `fixture.ts` | The fixture's schema |
| `harness.ts` | Runs the queries through the real adapter and scores them |
| `run.ts` | The `pnpm search:eval` command |
| `out/` | Per-query detail. **Git-ignored, local only** |

## Run it (Rahul, on your own machine)

1. Put two lines in `backend/.env` (git-ignored; Claude Code never reads it):

   ```text
   GEOCODING_PROVIDER=geoapify
   GEOCODING_API_KEY=<the key for that provider>
   ```

   `GEOCODING_PROVIDER` is `geoapify` (the default when the line is absent) or `locationiq`.
   The key must belong to that provider.

2. In `backend/`:

   ```sh
   pnpm search:eval
   ```

3. Copy the printed tables (overall, by script, by district, local intent, thresholds) and share
   **only those**.
   Never share the key, the `out/` folder or a raw provider response.

4. To compare the other provider, change both lines in `backend/.env` and run it again.

It sends one request per second by default. `SEARCH_EVAL_RPS` changes that (at most 3). Check the
provider's own limit first: a free plan can be as low as two requests per second and sixty per
minute. One run of the fixture uses between 98 and 122 of the day's requests: 74 ordinary
queries with one request each, and 24 local-intent queries with one or two each.

## How a query is scored

- The query goes through the same normalisation as the API, with the default search bias, a
  limit of six, and Bengali result names for Bengali-script queries.
- **Top-1:** the first result's name or label contains one of `expectedNameContains`.
  **Top-3:** one of the first three does. The comparison ignores case, spacing and Unicode form.
- When an entry has `expected` coordinates, the detail file also says whether a top-3 result lies
  within `toleranceMeters`. No starter entry has coordinates.
- A provider error (timeout, rate limit, refused key) is counted in its own column and left out
  of the hit rate.

Thresholds, confirmed by Rahul on 2026-10-07 (ADR 0018): overall top-3 at least 80%;
Bengali-script queries at least 70%; at least 60% for every district that has at least 3
queries (smaller districts are listed, not judged, until the fixture grows). Below them, compare
another provider behind the adapter before launch.

### Local-intent queries (since P011e)

An entry with `near` and `intent` is a search made **from a town**: "bank" typed in Raiganj
should find a bank in or around Raiganj, not one in another district.

- It is searched the way the API searches when the app sends an area: **local-first**
  ([ADR 0018](../../../docs/adr/0018-search-and-geocoding.md), "Local ranking"). First only
  within 50 km of `near`; if that finds fewer than 3 places, once more without the area limit.
- It is a hit when one of the first three results has a matching name **and** lies within
  **25 km** of `near`. A bank with the right name 200 km away is a miss.
- These queries have their own table, "Local intent", with one row per intent:
  - `generic`: a category or a chain found in many towns (bank, pharmacy, bus stand);
  - `named`: one particular place, searched from nearby.
- The column "Needed the wide pass" counts the queries where fewer than 3 places were found
  nearby. A high number means the map data around those towns is thin.
- They are **left out** of the overall, per-script and per-district tables and of the
  thresholds, so those stay comparable with the runs of 2026-10-07.
- No threshold is set for local intent yet. The first run gives the baseline.

**A name match is a weak test.** It says the provider returned a place with the right name, not
that it is the right place: a locality name can exist in more than one district. Coordinates
from a cited public source make an entry stronger.

## Adding queries

The target is at least 100 queries in at least 10 districts. The starter set has 74 in 23
districts, all marked `"addedBy": "claude-known"`: written by Claude Code from general knowledge
and **not checked against a map**. Review each one; delete or correct anything doubtful.

Rules, checked by `search-eval.test.ts`:

- **Public places only:** stations, bus terminals, airports, hospitals, universities, markets,
  temples, mosques, churches, museums, landmarks, localities and towns.
- **Never** a home, a personal address, a private person, a phone number, or a small private
  business.
- `script` is `en` (English), `bn` (Bengali script) or `translit` (Bengali written in Latin
  letters the way people type it, e.g. an informal spelling).
- `district` is the West Bengal district the place is in.
- `expectedNameContains`: one or more fragments, any of which proves a hit. Give alternative
  spellings and, for Bengali queries, both the Bengali and the English name.
- `expected` (optional): `latitude`, `longitude`, `toleranceMeters` and a `source` that names
  where the position came from. **Never guess a coordinate**; leave `expected` out instead.
- `near` and `intent` (optional, always together): see "Adding small-town entries" below.
- `addedBy`: `rahul` for your own entries, `contributor` for a friend's.
- `id`: lower-case letters, digits and hyphens, unique.

A friend can send a spreadsheet or CSV with these columns; add the rows to `fixture.json`:

```text
query,script,district,expectedNameContains
Example Junction railway station,en,Example District,Example Junction
```

Then, in `backend/`: `pnpm test:unit` (validates the fixture) and `pnpm format`.

## Adding small-town entries

The 24 local-intent entries cover ten towns in nine districts. All are `claude-known`: the town
centres were written from general knowledge, rounded to two decimals, and **not checked against
a map**. Review each `near`; with a 25 km hit radius an error of a kilometre or two does no
harm, a wrong town does.

To add a town you know:

1. Find the town's centre on a public map (its main crossing, bus stand or station) and round
   both numbers to **two decimals**, for example `25.62`, `88.12`. Two decimals is about 1 km:
   it names the town, not a spot. **Never use your home, your own position or anybody's
   address**, and never more than two decimals; the fixture test refuses a third.
2. Add entries with that `near`, the town's `district`, and an `intent`:

   ```json
   {
     "id": "loc-exampletown-bank-en",
     "query": "bank",
     "script": "en",
     "district": "Example District",
     "expectedNameContains": ["Bank", "ব্যাংক"],
     "near": { "latitude": 10.12, "longitude": 20.34 },
     "intent": "generic",
     "addedBy": "rahul"
   }
   ```

3. Type what people type: a category ("pharmacy", "bus stand", "hospital", "ATM") or a chain
   that has many branches, in English, Bengali script or the informal Latin spelling.
   `expectedNameContains` lists words any correct result would carry.
4. Chains and public services only. Never a small private business, a person or a home.
5. The same query may be asked from several towns; the same query from the same town only once.
6. Aim for at least three entries per town, and for towns in districts that have none yet.

Then `pnpm test:unit`, `pnpm format`, and run the evaluation again.

## Small towns: category and brand entries (since P011f1)

Since P011f1 the API treats a word for a kind of place ("bank", "pharmacy") and a well-known
brand ("SBI") differently from a name: it looks for places of that kind inside a circle of
10 km around the point, once more at 25 km when it finds fewer than three, and **never
further** ([ADR 0018](../../../docs/adr/0018-search-and-geocoding.md), "Category and brand
search"). An entry whose `intent` is `category`, `brand` or `name` is searched exactly that
way, through the same code as the endpoint.

| `intent` | The query is | Example |
| --- | --- | --- |
| `category` | a kind of place | `pharmacy`, `ব্যাংক`, `petrol pump` |
| `brand` | a chain with many branches | `sbi`, `apollo pharmacy` |
| `name` | one particular public place that must still be found by its name | a named hospital or college |

Two optional fields go with these intents:

- `inOsm`: `yes` when **you looked** on [openstreetmap.org](https://www.openstreetmap.org) and
  a place that answers the query is mapped within the radius; `no` when you looked and it is
  not; `unknown` (or left out) when nobody looked. Never guess: it decides whether a miss
  counts as a gap in the map or as a failure of the search.
- `radiusKm`: the first radius for this entry, 1 to 25, when 10 km is wrong for the place.

### The table

"Category/brand intent", one row per district and one for all:

| Column | Meaning |
| --- | --- |
| Found within radius | The search, run like the app runs it, returned a place with a matching name inside the circle it searched |
| Found only far | It did not, but a plain name search without any area found a matching name somewhere |
| Not found | Neither did |
| Data gap (not on the map) | Not found within the radius, and `inOsm` is `no`: the map has no such place, so no search could find it |
| Search failure (on the map) | Not found within the radius, although `inOsm` is `yes`: this is the number to bring down |

What to look for: a high "search failure" count means the classifier, the categories or the
radius are wrong and can be fixed in code. A high "data gap" count means the places must be
added to OpenStreetMap first. Rows with `inOsm: unknown` are in neither column, so the two do
not add up to the misses until the rows are reviewed. If **every** row is a provider error,
the Places request itself is being refused: tell Claude Code, the adapter was written from
documentation.

A matching name is a weak test here too: for `category` rows the expected words are generic
("Bank"), so "found" says a place of roughly that kind came back, not which one.

### Run the template

`small-town-template.json` has 27 rows from 11 towns in 9 districts. **All of it is a
placeholder**: written by Claude Code from general knowledge, the town centres not checked
against a map, every `inOsm` `unknown`.

1. Review it. Correct each `near` (a town's centre, two decimals), delete rows you cannot
   vouch for, and set `addedBy` to `rahul` on the rows you checked.
2. For the towns you know (Kaliyaganj and others), add rows for **public places** you know
   are there: bank branches, the post office, the hospital, pharmacies of a chain, petrol
   pumps, the bus stand. Look each one up on openstreetmap.org and set `inOsm`.
   - **Public places only.** Never a home, a person, a private address, a doctor's chamber in
     a house, or a shop known only by its owner's name.
   - `near` is the **town's centre rounded to two decimals**, never your own position.
   - `expectedNameContains` holds words the right result would carry, in English and Bengali.
3. In `backend/`:

   ```sh
   pnpm test:unit
   pnpm format
   SEARCH_EVAL_FIXTURE=small-town-template.json pnpm search:eval
   ```

   In PowerShell: `$env:SEARCH_EVAL_FIXTURE = 'small-town-template.json'; pnpm search:eval`,
   then `Remove-Item Env:SEARCH_EVAL_FIXTURE`.

   `SEARCH_EVAL_FIXTURE` is a file **name** in this folder, never a path. One run uses between
   27 and 108 provider credits: each row takes one to four requests.
4. Share the "Category/brand intent" table only. Rows that have been reviewed can later be
   moved into `fixture.json`.
