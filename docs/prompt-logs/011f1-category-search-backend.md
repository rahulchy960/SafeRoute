# P011f1: category and brand search within a radius, with small-town evaluation (server)

| Field | Value |
| --- | --- |
| Prompt | P011 · part f, split in two: **P011f1 server, contract and evaluation** (this log) and P011f2 Android (not started) |
| Milestone | M4 (depends on P011e2 and P012c2b, both merged) |
| Branch | `feat/011f1-category-search-backend` |
| PR title | `feat(search): category and brand search within a radius, with small-town evaluation [P011f1]` |
| Notion | [P011f1 row in the Prompt Log](https://app.notion.com/p/3f3073707720815daef7f0b75bebbfed) |
| Date | 2026-10-08 |
| Plan refs | Plan v7 §3.2 (F-03), §4, §6.2, §12.2; addenda v7.2 E, v7.3 I; ADRs 0004, 0018, 0019 |

> **Nothing ran against the geocoding provider.** Claude Code has no key. The Places API
> request, its category keys and the `type=amenity` filter come from documentation; the tests
> use fake providers. Whether "bank" now finds the banks of a small town is for Rahul's
> evaluation run and phone check. **No hit rate is stated anywhere in this prompt.**
>
> **The app does not use any of this yet** beyond typed text: the Android part (chips, the
> "within N km" header, the empty state) is P011f2. Typed category words and brands change
> behaviour as soon as this is deployed, because the classifier runs on the server.

## 1. Objective

Evidence from Rahul's phone against staging, in a small town in Uttar Dinajpur: "bank" returns
places whose names merely resemble the word (Banka, Bankura); "sbi" with the town's name finds
nothing; "pharmacy" and a chain pharmacy's name return distant results; many small shops are
not found. Make a word for a kind of place, and a well-known brand, search for places of that
kind near the user instead of for names that look like the word; return an honest empty answer
when there is nothing; and make the evaluation able to tell a gap in the map from a failure of
the search. No cause was assumed: Rahul is checking OpenStreetMap coverage separately.

## 2. Context & prerequisites

- P011e2 (PR #41, `5bfb212`) and P012c2b (PR #43, `fb2f479`) are merged. No open pull
  requests. Clean tree, hooks active. Notion already showed both as Merged.
- The memory note "do not change the server's ranking; collect Rahul's examples first" is
  answered by this prompt: Rahul sent the examples as its evidence and asked for the change.
- The Plan PDF cannot be read on this machine; the prompt text is the specification.

## 3. Workflow executed

1. `/start-prompt`: main synced, prerequisites confirmed merged, branch created.
2. **Spike** (requirement 0): read the provider's Places API documentation, pricing, pricing
   details, product page and terms, and the `type` parameter of its geocoding APIs. Findings
   and what is not certain are in ADR 0018, "Category and brand search", "Spike". Verdict:
   suitable; the prompt continued.
3. Classifier and dictionary, the circle search, the adapter, the contract, the service and
   route; tests alongside. First local commit (`9ebdfab`).
4. Evaluation: fixture schema, harness, template, README; tests. Docs, diagram. Second local
   commit (`da5478e`).
5. Quality gate, this log, push, pull request, Notion.

Commands: `pnpm typecheck`, `pnpm lint`, `pnpm format:check`, `pnpm test`, `pnpm build`,
`pnpm openapi:generate`, `pnpm openapi:check`, `pnpm openapi:lint`, `oasdiff breaking` (Docker,
v1.32.1, against `main`), `node scripts/container-smoke.mjs`, `pnpm generate` in
`tools/diagrams`, markdownlint and gitleaks through Docker.

## 4. Changes

**backend, `src/modules/search/`**

| File | What |
| --- | --- |
| `intents.json` (new) | The dictionary, version 1: 81 category phrases (English, Bengali script, informal Latin spelling, four listed misspellings), 16 brands with aliases, 10 joining and filler words. Every entry `claude-known`; Bengali and transliterated ones `review: native-speaker` |
| `intents.ts` (new) | `CATEGORY_KEYS`, the dictionary's schema, `createClassifier` (pure): `classify`, `isBrand`, `labelOf` |
| `intent-search.ts` (new) | `searchByIntent`: name, category, brand, place hint, the circle and its one widening. Used by the endpoint and by the evaluation harness |
| `types.ts` | `MatchType`, `PlaceResult.matchType`, `GeocoderQuery.placesOnly`, `CategoryQuery`, optional `GeocoderProvider.searchCategory` |
| `providers/geoapify.ts` | `searchCategory` (Places API, limit capped at 20 = one credit), `GEOAPIFY_CATEGORIES`, `type=amenity` for `placesOnly` |
| `service.ts` | Calls `searchByIntent`; charges the limits before every provider call; log line gains `match_type` and `searched_radius_km` |
| `schema.ts`, `routes.ts` | `category` in the request, `q` optional; `matchType`, `searchedRadiusKm`, `searchedAround` in the response |
| `local-first.ts` | `withDistance` and `keysOf` exported, unchanged |
| `src/config.ts`, `src/app.ts`, `README.md` | `SEARCH_CATEGORY_RADIUS_KM` (default 10, 1 to 25) |

**How a query is classified** (whole words only, nothing fuzzy):

| Typed | Understood as |
| --- | --- |
| `bank`, `ব্যাংক`, `medicine shop`, `thana` | category |
| `sbi`, `state bank of india`, `apollo pharmacy` | brand (with its category) |
| `sbi atm` | that brand's places of another kind |
| `pharmacy near exampletown`, `sbi exampletown` | category or brand around a place hint |
| `pharmacy near me`, `nearest atm` | category; the filler is dropped |
| `Bankura`, `Banka`, `college street`, `hospital road`, `axis mall`, `bnak` | a name, as before |

**API contract: 0.7.0 → 0.8.0, additive.** oasdiff: 0 errors, 0 warnings, 5 info.

- Request: new optional `category` (open string; values known today: bank, atm, pharmacy,
  hospital, clinic, fuel, restaurant, grocery, bus, train, public_transport, police,
  post_office, school, college; unknown → 400). `q` became optional: one of the two is needed.
  **No radius parameter.**
- `Place`: new optional `matchType` (`name`, `category`, `brand`; open string).
- `SearchResults`: new optional `searchedRadiusKm` (1 to 25) and `searchedAround` (`near`,
  `placeHint`; open string).
- `public_transport` exists for the planned "Bus & train" chip of P011f2.

**Evaluation, `backend/test/search-eval/`**

- `fixture.ts`: intents `category`, `brand`, `name`; optional `inOsm` and `radiusKm`.
- `harness.ts`: such entries go through `searchByIntent`; new table "Category/brand intent" by
  district (found within radius, found only far, not found, data gap, search failure).
- `small-town-template.json` (new): 27 placeholder rows, 11 towns, 9 districts, all
  `claude-known`, all `inOsm: unknown`.
- `run.ts`: `SEARCH_EVAL_FIXTURE` picks another fixture file in that folder by name.
- `README.md`: how Rahul reviews and extends the template, and how to read the table.

**docs**: ADR 0018 (new section), addendum v7.3 section I, `CLAUDE.md` ("Coverage claims" and
"Search and geocoding rules"), `README.md` (coverage table), the diagram, this log.

**Database:** none. No migration, no new table, no cache.

**Size:** about 3,200 added lines, far over the 800-line guide. About 760 are the two data
files (dictionary and template), about 1,300 tests, about 500 documentation. The prompt had
already split off the Android half; the server half was not split further because the
classifier, the flow, the contract and the evaluation only make sense together.

## 5. Diagram

[`docs/diagrams/011f1-category-search-flow.svg`](../diagrams/011f1-category-search-flow.svg)
(spec: `011f1-category-search-flow.json`; 12 nodes): classification, the place hint, the
circle and its one widening, the answer and the log line.

## 6. Quality gate & test results

| Command (in `backend/`) | Result |
| --- | --- |
| `pnpm typecheck` | pass |
| `pnpm lint` | pass, 0 problems |
| `pnpm format:check` | pass |
| `pnpm test` | **627 passed**, 0 failed, 32 files (unit and database projects) |
| `pnpm build` | pass; `dist/modules/search/intents.json` is emitted |
| `pnpm openapi:generate`, `openapi:check`, `openapi:lint` | regenerated and committed; up to date; valid |
| `oasdiff breaking` against `main` (Docker, v1.32.1) | no breaking changes; changelog 5 info |
| `node scripts/container-smoke.mjs` | **did not finish on this machine.** The image built and every check up to the production guards passed; the script then stopped in its wait for `/health` (Node reported an unsettled top-level await, exit 13). The API container from the new image was running: probed by hand, `/health` answered 200 with the commit as version and `POST /v1/search` without a token answered 401. Two containers from an earlier run, 31 hours old, show the same stop happened before this change. `container-ci` in the pull request is the real check |
| `pnpm generate` in `tools/diagrams` | pass |
| markdownlint (Docker, v0.18.1), JSON validity, gitleaks (Docker, v8.30.1) | 0 errors in 102 files; valid; no leaks |

`pnpm search:eval` was **not** run: it needs Rahul's key and is not part of the gate.

New and changed tests:

- `test/search/intents.test.ts` (new, 80 cases): the dictionary file (every entry vouched
  for, Bengali flagged, no duplicate phrase, every category mapped); each intent; Bengali and
  mixed script; listed misspellings; fillers; brands with and without a category word; place
  hints; **21 queries that must stay name searches** (Bankura, Banka, college street, axis
  mall, …); decomposed Bengali; brand ownership of a name.
- `test/search/intent-search.test.ts` (new, 11 cases): flows with a fake provider: no area,
  text-only provider, limit and duplicates, a 25 km first radius, provider failure on the
  widening, a refused first charge, brand filter, an unknown hint, a hint without an area.
- `test/db/search.test.ts` (8 new cases, real Postgres): `category` alone; typed word and
  typed brand; widening with **both calls charged**; **empty answer with no far fuzzy
  fallback** (the name search that would return "Bankura" is never called); the budget
  running out before the widening; a place hint (three tokens); 400 for an unknown category;
  **log capture** for a Bengali word, a category and a brand with a hint.
- `test/search/providers.test.ts` (8 new cases): the Places request and its mapping; the cap
  at 20; failures carry a kind and never the key or a coordinate; `placesOnly`.
- `test/contract.test.ts`: the new fields, their optionality, no `radiusKm`, no enum.
- `test/config.test.ts`: `SEARCH_CATEGORY_RADIUS_KM` default and guards (0, 26, not a number).
- `test/search-eval/search-eval.test.ts` (4 new cases): the template is valid; `inOsm` and
  `radiusKm` rules; a run with pacing, the far check and the table; no table without entries;
  `SEARCH_EVAL_FIXTURE` refuses a path.
- Existing local-first endpoint tests now search "ferry ghat" instead of "bank": "bank" is a
  category now, and those tests are about the name search. Nothing was deleted or skipped.

**Failure matrix (Plan v7 §7.5):** not applicable; no SOS, live-location or contacts code.

**Generated client:** every new field is optional, so the Kotlin client generated from 0.8.0
has the same constructors as before plus nullable properties. The Android gate was not run in
this prompt (no Android file changed); `android-ci` runs on `contracts/**` in the pull request
and is the check.

## 7. Decisions & ADRs

All in [ADR 0018](../adr/0018-search-and-geocoding.md), "Category and brand search: note of
2026-10-08 (P011f1)". No new ADR: the provider, the adapter boundary and the contract style
are unchanged, and the contract change is additive. The decisions that were mine to make:

- **Whole words, no fuzzy matching**, and only the misspellings listed in the dictionary.
- **A bare word after a category is not a place hint** ("college street" stays a name); after
  a brand it is ("sbi exampletown").
- **A brand is filtered on our side** from 20 candidates of its category. The provider's own
  `name` parameter is not used: its matching rules are not documented.
- **Without a point and without a hint there is no circle.** The word is then searched by
  name among named places only. The default area is still never used as a centre.
- **`searchedAround` was added beyond the prompt's list**, so that the app cannot say "within
  10 km of your location" about a circle around a typed town. Additive, optional.
- **Log key names** are `match_type` and `searched_radius_km`, in the snake case of the
  existing line, not `matchType` as the prompt wrote it.
- **`public_transport`** as one category for the "Bus & train" chip.
- New fields are optional in the schema although the server always sends `matchType`.

## 8. Security & privacy notes

- No new personal data, no storage, no cache, no new secret, no new permission.
- The API's own log line gains `match_type` (one of three words) and `searched_radius_km`
  (10 or 25). The text, the category, the brand, the hint, coordinates, distances and results
  are not logged; a test captures every line for a Bengali word, a category and a brand with
  a hint and looks for them. **This covers the API's own log lines only.** The platform's
  request log records the URL `/v1/search`, which carries nothing (ADR 0019); the body is not
  logged by either.
- What leaves SafeRoute is unchanged in kind: the coarse point (two decimals) and, now, a
  category key instead of the typed word. A place hint is sent to the geocoder as text, as
  any typed search always was; the resulting centre is rounded to two decimals.
- The provider key still travels in the request URL of the adapter; the Places call goes
  through the same `providerGet`, and a test checks that its errors carry neither the key nor
  a coordinate.
- The dictionary and the template hold public words, brand names and town centres only. A
  test looks for digits runs, `@` and address words in the template.
- Public repo check: no personal data, local paths or non-public ids added.

## 9. Known issues & risks

- **Unverified against the live service.** A category key the provider refuses would make
  that category answer 503 "search unavailable". The evaluation run shows this at once
  (provider errors in every row of that category).
- **Deploys with the merge.** The staging API starts using the Places API for typed category
  words and brands. Whether the existing staging key is allowed to call it is not known to
  Claude Code.
- **Budget.** A category search in a thin area costs two credits, three with a place hint.
  How far the shared 2,500 a day goes: not recorded.
- **Brand in a dense area:** only the 20 nearest places of the category are considered.
- **The dictionary is unreviewed**, and its Bengali entries were written by a non-native
  author. A wrong entry sends a real name search to the category flow.
- **Words that are also names.** "food", "police", "petrol", "doctor", "school" typed alone
  are now category searches. A place literally named so is reached by typing more of its name.
- **"Found only far"** for a category row is weak evidence: the expected words are generic.
- `backend/.env.example` cannot be read or edited by Claude Code (deny rule): the new variable
  is documented in `backend/README.md` only.
- The pull request is far over the size guide (section 4).
- `scripts/container-smoke.mjs` hangs on this machine in its wait for the API (section 6). Not
  caused by this change, not investigated here (follow-up). Two smoke containers from an
  earlier session are still running locally; they were left alone.

## 10. Follow-ups & prerequisites for next prompt

Recorded in the Notion Follow-ups database:

- Grow and review the small-town fixture; fill in `inOsm`; run the evaluation and record the
  table in ADR 0018.
- OpenStreetMap mapping effort for small towns (P011f2 links users to openstreetmap.org).
- Evaluate additional place data sources and their terms before the public beta.
- Refresh cadence of the provider's data from OpenStreetMap: ask the provider; not recorded.
- Native-speaker review of the Bengali dictionary entries.
- Brand search in dense areas: measure in Kolkata; decide on more candidates or the
  provider's name filter.
- Confirm the Places API rate limit of the plan in use.
- `container-smoke.mjs` stops in its wait for `/health` on the Windows host.

**For P011f2 (Android):** this pull request merged and deployed; regenerate the client and
confirm `category`, `matchType`, `searchedRadiusKm` and `searchedAround`. The header must use
`searchedAround` to choose between "your location / the map centre" and the typed place. Chip
categories: bank, atm, pharmacy, hospital, fuel, restaurant, grocery, public_transport.

## 11. How Rahul can verify

1. CI on the pull request: `repo-checks`, `backend-ci`, `contracts-ci` (no breaking change),
   `container-ci`, `android-ci` (the client still builds from 0.8.0).
2. Read `backend/src/modules/search/intents.json`. Delete or correct anything doubtful,
   especially the Bengali entries and the brand aliases.
3. Optional, add to `backend/.env.example` (Claude Code cannot edit it):
   `SEARCH_CATEGORY_RADIUS_KM=10`.
4. **Evaluation**, in `backend/`, with your key in `backend/.env` as before:

   ```sh
   pnpm search:eval
   SEARCH_EVAL_FIXTURE=small-town-template.json pnpm search:eval
   ```

   PowerShell: `$env:SEARCH_EVAL_FIXTURE = 'small-town-template.json'; pnpm search:eval`, then
   `Remove-Item Env:SEARCH_EVAL_FIXTURE`. The second run uses 27 to 108 credits.

   What to look for in "Category/brand intent":
   - **Provider errors in every row:** the Places request is refused (key, or the request
     itself). Report it; the adapter is from documentation.
   - **Provider errors for one kind of query only:** that category key is wrong.
   - **Found within radius** high: the change works for those towns.
   - **Found only far / Not found:** look the place up on openstreetmap.org and set `inOsm`;
     the next run then says data gap or search failure.
   - The first run (default fixture) must still show the earlier tables about as before:
     entries without the new intents are searched as they were.
   Share the tables only, never the key, the `out/` folder or a raw response.
5. After the merge and the staging deploy (unverified until you report the Actions run): on
   the phone, in your town and in Kolkata, type "bank", "pharmacy", "SBI", "SBI" plus the
   town's name, "Apollo pharmacy", "Bankura". Note what is found, what is missing and whether
   anything far away or merely similar in name still appears for a category word.

## 12. Learning notes

No Android code in this part. Server-side ideas used:

- **Classifying before searching.** A geocoder matches text to names. Deciding first what
  kind of question the text is (a name, a kind of place, a brand) lets each kind use the
  search that fits it.
- **Tokens, not substrings.** Comparing whole words is what keeps "bankura" from matching
  "bank". Unicode normalisation (NFC) makes two spellings of the same Bengali letter equal
  before the comparison.
- **A filter versus a bias.** A bias reorders results; a filter removes them. The category
  search uses a filter (a circle), which is why an empty answer is possible and honest.
- **Optional interface methods.** `searchCategory?` lets one provider offer a capability
  without forcing the other to fake it; the caller checks and falls back.
- **Additive contract changes.** New optional fields and a newly optional request field do
  not break existing clients; `oasdiff` checks this mechanically.
