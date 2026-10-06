# Search-quality evaluation

Measures how well a geocoding provider finds real places across West Bengal, before launch
(addendum v7.2 section E, [ADR 0018](../../../docs/adr/0018-search-and-geocoding.md)). It is a
**measurement, not a gate**: it always exits 0, and it is never run in CI (no key, no network).

| File | What |
| --- | --- |
| [`fixture.json`](fixture.json) | The queries. Committed, public |
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

   `GEOCODING_PROVIDER` is `geoapify` or `locationiq`. The key must belong to that provider.

2. In `backend/`:

   ```sh
   pnpm search:eval
   ```

3. Copy the printed tables (overall, by script, by district, thresholds) and share **only those**.
   Never share the key, the `out/` folder or a raw provider response.

4. To compare the other provider, change both lines in `backend/.env` and run it again.

It sends one request per second by default. `SEARCH_EVAL_RPS` changes that (at most 3). Check the
provider's own limit first: a free plan can be as low as two requests per second and sixty per
minute. One run of the starter fixture uses 74 of the day's requests.

## How a query is scored

- The query goes through the same normalisation as the API, with the default search bias, a
  limit of six, and Bengali result names for Bengali-script queries.
- **Top-1:** the first result's name or label contains one of `expectedNameContains`.
  **Top-3:** one of the first three does. The comparison ignores case, spacing and Unicode form.
- When an entry has `expected` coordinates, the detail file also says whether a top-3 result lies
  within `toleranceMeters`. No starter entry has coordinates.
- A provider error (timeout, rate limit, refused key) is counted in its own column and left out
  of the hit rate.

Proposed thresholds, to be confirmed by Rahul in the pull-request review: overall top-3 at least
80%, no district under 60%, Bengali-script queries at least 70%. Below them, compare another
provider behind the adapter before launch.

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
- `addedBy`: `rahul` for your own entries, `contributor` for a friend's.
- `id`: lower-case letters, digits and hyphens, unique.

A friend can send a spreadsheet or CSV with these columns; add the rows to `fixture.json`:

```text
query,script,district,expectedNameContains
Example Junction railway station,en,Example District,Example Junction
```

Then, in `backend/`: `pnpm test:unit` (validates the fixture) and `pnpm format`.
