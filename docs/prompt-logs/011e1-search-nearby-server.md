# P011e1: local-first search ranking and distances (server, contract, evaluation)

| Field | Value |
| --- | --- |
| Prompt | P011 · part e, split in two: **P011e1 server, contract and evaluation** (this log) and P011e2 Android (not started) |
| Milestone | M4 (depends on P011b and P011d, both merged) |
| Branch | `fix/011e1-search-nearby-server` (the prompt named `fix/011e-search-nearby`; renamed before any push) |
| PR title | `fix(search): local-first ranking and distances for nearby results [P011e1]` |
| Notion | [P011e1 row in the Prompt Log](https://app.notion.com/p/3f207370772081d38ba9cf5fedfbf72e) |
| Date | 2026-10-08 |
| Plan refs | Plan v7 §3.2 (F-03), §4, §6.2, §12.2; addendum v7.2 E; ADRs 0004, 0018, 0019 |

> **Part e is split.** This part changes the server, the contract and the evaluation
> (requirements 1, 3, 4, 6 and the server half of 7). The Android half (requirements 2 and 5)
> is P011e2. **Until P011e2 ships, the app still sends the map centre**, so this fix helps only
> after the user has moved the map to where they are or tapped "my location".
>
> **Nothing ran against a geocoding provider.** Claude Code has no key. The provider parameters
> come from documentation; the tests use a fake provider. Whether nearby places now come first
> for real queries is for Rahul's evaluation run and phone check.

## 1. Objective

Evidence from Rahul's phone test against staging: a generic or chain name such as "SBI Bank",
typed in a small town in Uttar Dinajpur, returned a place in Darjeeling and no nearby one; many
small-town places were not found. Make search prefer places near the area the app sends, show
how far each result is, and make the evaluation able to see this kind of failure.

## 2. Context & prerequisites

- P012d merged (PR #34, `8f8049d`). No open pull requests. Clean tree, hooks active.
- P012d recorded "nearby-first search ranking" as "done in P011e" on Rahul's word, with a
  follow-up because no P011e existed. It had not been done; this is it. The backlog row and
  the follow-up are corrected.
- P012c2 (follow-me) is still open and is not a prerequisite here.
- The Plan PDF cannot be read on this machine; the prompt text is the specification.

## 3. Workflow executed

1. `/start-prompt`: main synced, PR #34 confirmed merged, P012d set to Merged in Notion, branch,
   Notion page.
2. Diagnosis from the code (section 7), then Geoapify's and LocationIQ's documentation.
3. Server: `local-first.ts`, both adapters, service, route, schema, configuration; contract
   0.7.0; tests (`1d05af5`).
4. Evaluation: fixture schema, 24 local-intent entries, harness, README (`18e5827`).
5. ADR 0018 "Local ranking", backend README, diagram (`293434b`).
6. Split decided when the diff passed 1,200 lines without any Android change; branch renamed.
7. `/ship-prompt`: gates, this log, push, pull request, Notion.

## 4. Changes

**Server (`backend/src/modules/search/`)**

- `local-first.ts` (new): `searchLocalFirst`. Pass 1 with an area filter; pass 2 without it
  when fewer than the minimum are near; nearby first, de-duplicated, cut to the limit; each
  result gets `distanceMeters`. It names no provider.
- `types.ts`: `GeocoderQuery.withinMeters` (optional area filter) and
  `PlaceResult.distanceMeters`.
- `providers/geoapify.ts`: `filter=circle:lon,lat,metres|countrycode:in` when `withinMeters`
  is set. `providers/locationiq.ts`: the square around the circle as `viewbox`, `bounded=1`.
- `service.ts`: each provider call spends the user's burst and daily buckets and the shared
  budget. A refused or failed second call returns the nearby results alone. One log line with
  three new count fields.
- `routes.ts`: local-first only when the request carries an area; the default area stays a
  bias.
- `config.ts`, `app.ts`: `SEARCH_NEARBY_RADIUS_KM` (50, 1 to 500) and
  `SEARCH_MIN_LOCAL_RESULTS` (3, 1 to 10).

**Contract (`contracts/openapi.json`, 0.6.0 → 0.7.0, additive)**

- `Place.distanceMeters`: optional integer, metres from the rounded `near` point, rounded to
  100 m.
- Descriptions of `nearLatitude`, the results list and the operation reworded. No path,
  parameter, required field or response code changed.

**Evaluation (`backend/test/search-eval/`)**

- `fixture.ts`: optional `near` (two decimals at most) and `intent` (`generic` or `named`),
  always together.
- `fixture.json`: 24 local-intent entries from ten towns in nine districts (98 entries now).
- `harness.ts`: such an entry is searched local-first from its `near`; a hit needs a matching
  name within 25 km; a "Local intent" table; these entries stay out of the earlier tables and
  thresholds.
- `README.md`: how local intent is scored, and "Adding small-town entries".

**Docs:** ADR 0018 "Local ranking: note of 2026-10-08", `backend/README.md`, the diagram, this
log.

**Not changed:** anything under `android/`, `CLAUDE.md`, migrations, workflows, dependencies,
`backend/.env.example` (Claude Code cannot read or edit it; see section 10).

**Size:** about 1,270 changed lines without generated files and the fixture; over the 800-line
guide even after the split, mostly tests (about 560 lines) and documents (about 300).

## 5. Diagram

[`docs/diagrams/011e-local-first-search.svg`](../diagrams/011e-local-first-search.svg): the two
passes, where the limits are charged, the merge, and the one log line.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `pnpm typecheck` · `pnpm lint` · `pnpm format:check` | clean |
| `pnpm test` (unit and database) | 518 passed, 0 failed |
| `pnpm build` | ok |
| `pnpm openapi:check` · `pnpm openapi:lint` | up to date; valid |
| `node scripts/container-smoke.mjs` | 39 checks passed |
| Android `./gradlew lint testDebugUnitTest assembleDebug` (client regenerated from 0.7.0) | BUILD SUCCESSFUL on the second run, 539 tests, 0 failures. The first run stopped in `lintAnalyzeDebugUnitTest` with an internal lint error ("a bug in lint") on a test file this change does not touch; no Android file changed |
| `tools/diagrams` `pnpm generate` | no errors |
| markdownlint-cli2 v0.18.1 over `**/*.md` | 93 files, 0 errors |
| JSON validity · gitleaks 8.30.1 (full history) · forbidden files | 61/61 valid · no leaks · none |

New tests:

- `test/search/local-first.test.ts` (10): one call when enough are near; the second call and
  the merge order; de-duplication by id and by name with position; the limit; results outside
  the radius dropped; the hook between the calls; refusal with and without nearby results; a
  provider failure on each pass.
- `test/db/search.test.ts` (6 new): no area means one call and no distance; one token for one
  call and two for two, in all three buckets; the budget running out between the passes; a
  provider failure on the wide pass; the radius and minimum from configuration.
- The log test now also forbids `distanceMeters` and `withinMeters` in a log line and pins the
  exact field list, on a search that takes two provider calls.
- `providers.test.ts`: the exact filter parameters of both adapters.
- `search-eval.test.ts`: the fixture rules for `near` and `intent`, local scoring, the table.
- `contract.test.ts`: `distanceMeters` is optional and an integer; version 0.7.0.

**The oasdiff breaking-change gate runs in CI** (`contracts-ci`), not locally. The change adds
one optional response field and edits descriptions.

**What the log test covers:** the API's own log lines only. The platform's request log sees
the URL `/v1/search`, which carries nothing (ADR 0019).

**SOS failure matrix (Plan v7 §7.5):** not applicable.

## 7. Decisions & ADRs

No new ADR; [ADR 0018](../adr/0018-search-and-geocoding.md) gains "Local ranking: note of
2026-10-08".

**Diagnosis (requirement 1), from the code:**

- **`near` sent by the app:** `MapSelection.viewCentre`, the map centre when the camera last
  came to rest; never the user's position. The map opens on the default region centre. If the
  user neither panned nor tapped "my location", the app sent that default centre, or nothing
  before the map reported (the server then used the same default, 22.57, 88.36).
- **The Geoapify adapter:** `bias=proximity:lon,lat` and `filter=countrycode:in`. A bias only.
- **Re-ranking:** none.
- Why the provider put a Darjeeling result first was not observed; it is its own ranking.

**Pages checked** (2026-10-08): Geoapify "Address Autocomplete API"; Geoapify "Forward
Geocoding API" (sections "Location filters" and "Location bias"); LocationIQ "Autocomplete API"
reference. What each says is in the ADR.

**Choices made here, for Rahul to confirm:**

- **The split** into P011e1 and P011e2.
- **No local-first without an area in the request.** A filter around the default centre would
  put Kolkata results first for someone who sent nothing.
- **A refused or failed second call is not an error when something was found nearby.**
- **Results are not re-sorted by distance.** Nearby results keep the provider's order; "local
  first" is the two groups, not a sort.
- **The distance is ours, not the provider's**, and is measured from the rounded point.
- **Local-intent hit radius: 25 km**, and these entries are kept out of the earlier tables so
  the 2026-10-07 numbers stay comparable. No threshold is set.
- **Uniqueness in the fixture** is now per query and town, so "bank" can be asked from several
  towns.
- **The 50 km and 3** are the prompt's defaults; nothing measured supports them yet.

## 8. Security & privacy notes

- **No new personal data.** The provider receives the same coarse point as before, now also as
  a circle. The distance in a response is computed from that coarse point and rounded to 100 m.
- **Logs:** counts and one fixed word were added. No query, coordinate, distance or result.
- **The key** still travels only inside the adapter's URL; adapter errors carry a kind only.
- **Fixture:** `near` is a town centre at two decimals, a public place; the test refuses a
  third decimal and anything outside the box around West Bengal. Chains and public services
  only.
- **For P011e2, not this part:** sending the user's coarse position as `near` changes what
  location is used for. The disclosure, `CLAUDE.md` ("never the user's position"), ADR 0015
  and ADR 0018 must change in that prompt, and the wording is **to be verified by a lawyer**.
- No secrets, project ids, service URLs or local paths in the diff.

## 9. Known issues & risks

- **Not measured.** No local-intent hit rate exists. That the live Geoapify autocomplete
  endpoint accepts the circle filter is taken from documentation.
- **The reported problem is only half fixed** until P011e2: without a map move the app sends
  the default centre, and the server cannot know where the user is.
- **Cost:** two provider credits per search where fewer than 3 places are near, which is the
  normal case in thin-data areas. The shared budget of 2500 a day and the burst of 30 are used
  up faster; by how much is not recorded.
- **Latency:** two calls in a row, each with a 3 s timeout. Not measured.
- **Missing places stay missing.** Ranking cannot find what is not in the map data.
- **The town centres in the fixture are from general knowledge**, not checked against a map.
- **`expectedNameContains` for generic queries is loose** ("Bank" matches any bank): the
  distance does the judging.
- **PR size** is over the 800-line guide (section 4).
- **`backend/.env.example` does not list the two variables** (section 10).

## 10. Follow-ups & prerequisites for next prompt

Notion *Follow-ups*, new (source P011e1):

- P011e2: Android sends the coarse position as `near` and shows distances (High).
- Run the search evaluation with local intent and report the tables: the baseline (High).
- Check nearby search on a phone against staging after the deploy (High).
- Grow the search fixture with small-town places (local intent).
- Measure provider credits per search and revisit `SEARCH_GLOBAL_DAILY_LIMIT`.
- Add the two variables to `backend/.env.example` (Rahul; Claude Code cannot touch `.env*`).

Updated: "Re-run the search evaluation before each region is published" (now includes the
local-intent table); "Confirm P011e" closed; Ideas backlog "Nearby-first search ranking"
corrected to in progress.

**Next: P011e2** (proposed branch `feat/011e2-search-nearby-android`), or P012c2 if Rahul
prefers; neither is started.

## 11. How Rahul can verify

1. In `backend/`: `pnpm db:up`, then `pnpm typecheck`, `pnpm lint`, `pnpm test` (518 tests).
2. **Run the evaluation** (your key, your machine). `backend/.env` needs
   `GEOCODING_PROVIDER=geoapify` and `GEOCODING_API_KEY=<your key>`; then in `backend/`:

   ```sh
   pnpm search:eval
   ```

   It makes 98 to 122 requests at one per second (about two minutes). Share the printed tables
   only. What to look for:

   - **"Local intent" table, row `generic`, column "Nearby top-3":** the share of generic
     queries that found a matching place within 25 km. This is the number the fix is about;
     there is no earlier value to compare with.
   - **"Needed the wide pass":** how many of the 24 found fewer than 3 places nearby. A high
     number means thin map data around those towns and two credits per search there.
   - **"Provider errors" should be 0.** If every local-intent query errors while the others
     work, the provider refused the circle filter: tell Claude Code, do not merge.
   - **Overall, by script, by district:** should be about what you measured on 2026-10-07
     (81% overall, 78% Bengali for Geoapify); these queries are searched as before.
   - `test/search-eval/out/detail-geoapify.json` (local only, never shared) shows the first
     three names per query, if you want to see why one missed.
3. CI green (`repo-checks`, `backend-ci`, `contracts-ci` with the oasdiff gate, `container-ci`,
   `android-ci`), then squash and merge. **The merge deploys to staging** (`backend/**`
   changed): check the `deploy-staging` run and report it; the deploy is unverified until then.
4. On the phone against staging, after the deploy:
   1. Move the map to a small town you know (or tap "my location" there), open search, type a
      generic name such as "bank" or "hospital". Nearby places should come first.
   2. Type a place far away by its full name: it should still be found.
   3. Without moving the map from its opening position, the results are biased to the default
      centre as before: that is the part P011e2 fixes.
   4. No distance is shown yet (P011e2).

## 12. Learning notes

No Android or Kotlin concept in this part. Four ideas from the server:

- **Bias and filter.** A bias tells the search engine "prefer places near here" and leaves the
  final order to it. A filter says "only places in here". A bias alone loses to a famous place
  far away; a filter alone finds nothing when the place really is far away. Asking twice, first
  with the filter and then without, gets both.
- **Why the second question costs a second token.** The provider bills each request. If only
  the search were counted, the daily budget could be overspent by a factor of two without
  anyone noticing. Charging per provider call keeps the budget an honest ceiling.
- **Degrading instead of failing.** When the second call cannot be made, three nearby results
  are better than an error message. The rule: fail only when there is nothing to show.
- **Additive contract change.** A new optional field breaks no existing client: an app built
  from 0.6.0 ignores `distanceMeters`. Removing or renaming a field, or making one required,
  would break it, and needs a label and an ADR here.
