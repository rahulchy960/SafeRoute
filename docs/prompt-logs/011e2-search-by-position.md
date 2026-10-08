# P011e2: Android search near the user's position, with distances

| Field | Value |
| --- | --- |
| Prompt | P011 · part e, second half (**P011e2 Android**; P011e1 was the server, the contract and the evaluation) |
| Milestone | M4 (depends on P011e1, merged as `79ed6f8`; the previous pull request, #40, merged as `a0ea6d6`) |
| Branch | `feat/011e2-search-by-position` |
| PR title | `feat(android): search near the user's position with distances [P011e2]` |
| Notion | [P011e2 row in the Prompt Log](https://app.notion.com/p/3f3073707720819b8893e5fc0ad7e966) |
| Date | 2026-10-08 |
| Plan refs | Plan v7 §3.2 (F-03), §12.2, §12.3; ADRs 0015, 0018, 0019 |

> **Nothing ran on a phone or against the server.** Verified with JVM tests and fakes. Whether
> "bank" typed in a small town now finds a bank there depends on the server's ranking and the
> map data, which this prompt does not touch; the manual checks are for Rahul.
>
> **This prompt reverses a recorded rule** ("the search area is the map's centre, never the
> user's position"), on Rahul's instruction. `CLAUDE.md`, ADR 0018, ADR 0015 and the location
> disclosure were changed with it.

## 1. Objective

Make a search start from where the user is: send the user's recent position (rounded) as the
area, fall back to the map centre, and show how far each result is and from what.

## 2. Context & prerequisites

- `/start-prompt` was run twice. The first run stopped: pull request #40 (the P012f phone
  results) was still open and the prompt requires the previous pull request to be merged.
  Rahul merged it; the second run went through.
- **Client check (the prompt's stop condition):** `./gradlew :app:generateApiClient` from
  `contracts/openapi.json` 0.7.0 gives `Place.distanceMeters: Int?` in the generated model.
  The field exists. That the server is deployed is Rahul's statement; Claude Code cannot see
  it.
- P012e1 (the map opens on the user's position) and P011e1 (local-first ranking) are merged.

## 3. Workflow executed

1. `/start-prompt` (twice, see above): main synced, branch, Notion page.
2. Diagnosis from the code (section 7). Client regenerated; the field confirmed.
3. `SearchAreaProvider`, the distance on rows, the disclosure text; tests (`e07d865`).
4. `CLAUDE.md`, ADR 0018, ADR 0015, this log; `/ship-prompt`.

## 4. Changes

**`feature/search`**

- `SearchArea.kt` (new): `SearchAreaProvider` decides the area of a search: the user's
  position (permission granted now, fix at most 5 minutes old by the clock), else the map
  centre, else none. `NearSource` says which. It only reads `LocationRepository.state`.
- `SearchViewModel`: asks the provider once per search and keeps the source with the answer
  (`Results.distancesFrom`).
- `SearchRepository`: `FoundPlace.distanceMeters`, from the generated model. The rounding to
  two decimals before the request is unchanged.
- `SearchScreen`: a distance at the end of each row; its spoken text says what it is measured
  from; one line above the list says it for everyone.

**Strings (English and Bengali):**

- Six new `search_distance_*` strings, English and Bengali.
- `location_disclosure_why` and `location_disclosure_not` changed in both languages: the
  position is also used to put nearby places first in a search, and a search sends it rounded
  to about 1 km to SafeRoute's server and on to the map search service.

**Docs:** `CLAUDE.md` (the location and the search rule), ADR 0018 "Android behaviour", ADR
0015 "Location policy", this log.

**Not changed:** the server and its ranking, the contract, the manifest (no permission), the
permission flow, dependencies. No new storage, no logging.

**Size:** about 630 changed lines in `android/` (about 430 of them tests), plus documents.

## 5. Diagram

No diagram needed. The request flow is the one in
[`011e-local-first-search.svg`](../diagrams/011e-local-first-search.svg) ("a coarse area in
the body"); only where the app takes that area from changed, and no state machine was added.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` (in `android/`) | BUILD SUCCESSFUL; 646 tests, 0 failures (625 before); lint 0 errors |
| markdownlint, JSON validity, gitleaks, forbidden files | 98 files, 0 errors · tracked JSON valid · no leaks · none |
| `git ls-files android \| grep -iE "local.properties\|\.jks\|google-services\.json"` | prints nothing |

`assembleRelease` was not run: no build file, `src/release` or `src/debug` changed.

New tests (21):

- `SearchAreaProviderTest` (9): a recent position with the permission; exactly 5 minutes and
  a moment more (**the stale-fix case: the map centre**); a position that grows stale while the
  search screen is open (the clock decides, not the state's label); a position drawn as old
  but younger than 5 minutes; no permission with a position still in memory; approximate
  permission; no position at all, with and without a map centre; a position dated in the
  future; nothing is started, requested or printed.
- `SearchViewModelTest` (4 new): **each source** reaches the request and labels the answer
  (position; map centre after a stale fix; none); a position that arrives later does not
  relabel an answer already on screen.
- `SearchDistanceTest` (6): the number format in three locales; "under 1 km", "2.3 km",
  "12 km" on rows; TalkBack text for each source; no distance or note without a figure or an
  area; the words make no claim about a place; Bengali at font scale 2.0, every row keeping
  its name and its distance.
- `ApiSearchRepositoryTest` (1 new): the distance is passed on; a missing one is not a zero.
- `SearchFlowTest` (1 new, the real `MainActivity`): with location allowed and a recent
  position the search is sent from there with no permission dialog; older than 5 minutes, the
  map centre again.
- `HomeLocationScreenTest`: the disclosure's promises were re-pinned to the new wording.
- Unchanged and passing: `StringResourceParityTest` (key parity), `MainActivityTest` (the
  permission list), the "newer answer never replaced by an older one" test.

**What these tests cannot see:** the real server's answers and distances; a real position;
TalkBack; whether the distance fits on every phone at the largest font.

**SOS failure matrix (Plan v7 §7.5):** not applicable.

## 7. Decisions & ADRs

No new ADR. ADR 0018 "Android behaviour" and ADR 0015 "Location policy" each gained a dated
change.

**Diagnosis: what `near` the app sent before this prompt.**

- Never the position. `SearchViewModel` passed `MapSelection.viewCentre`, which Home sets from
  the map's camera each time the map comes to rest.
- **Camera at the overview** (the whole state, where the map opens since P012e1 when there is
  no permission or no position in time): the view centre is cleared below zoom 9, so the app
  sent **no area**. The server then used its default bias (a point in Kolkata), applied no
  area filter and returned no distances.
- **Camera moved by the app to the user** (P012e1, permission granted): the centre was the
  user's position, so a search happened to start from there, until the user panned.
- **Camera panned or showing a chosen place:** the search started from wherever the map
  looked, which is what was meant then and not what "bank near me" needs.
- Before P012e1 the map opened on a city camera, so a user who never moved the map searched
  around that city (the P011e1 diagnosis).

Choices made here, for Rahul to confirm:

- **Age by the clock.** Location updates stop while Home is not on screen, so the state can
  still say "current" for a position that has grown old. The provider compares the fix's time
  with the clock.
- **The permission is read again for every search.** A position still in memory is not used
  once the permission is gone.
- **Search never starts location.** If Home never got a position, search uses the map centre.
  Starting location from the search screen would be a new use with its own battery and
  disclosure questions.
- **Below one kilometre the row says "under 1 km".** The server measures from a point rounded
  to about 1 km; "400 m" would be false precision. One decimal up to 10 km ("2.3 km"), whole
  kilometres beyond.
- **A visible line above the results** ("Distances are approximate, from your location.")
  besides the spoken text the prompt asked for, so that sighted users are told as well.
- **Digits follow the app's language**: with the phone in Bengali the figure is in Bengali
  digits ("২.৩ কিমি"), as route distances already are.
- **The disclosure names the map search service** as a receiver of the rounded point, not only
  SafeRoute's server, because that is what happens.
- **The disclosure test changed.** It pinned "nothing is sent"; that sentence would now be
  false, so the test pins the new statements instead.

## 8. Security & privacy notes

- **A search now sends the user's own position, rounded to two decimals (about 1 km).** It
  goes to SafeRoute's API in the POST body (ADR 0019) and the server passes the rounded point
  to the geocoding provider. Before, this happened only while the map was centred on the user.
- **Conditions:** only when the user searches; only with the location permission granted at
  that moment; only a position at most 5 minutes old; never finer than two decimals (rounded
  on the phone, and again on the server).
- **No new permission, no permission prompt, no location updates started, nothing stored,
  nothing logged.** Tests cover each; the type that holds the area hides it in `toString()`.
- **The disclosure changed in this prompt,** as ADR 0015 requires when location gets a new
  use. A user who allowed location before this version agreed to the older text; whether they
  must be told again is **to be verified by a lawyer** (follow-up).
- **Not consent-gated.** Search uses no consent purpose today; whether the rounded position
  for search needs one is part of the same legal question.
- The server's handling is unchanged: queries, coordinates and results are not logged or
  stored there (ADR 0018). The geocoding provider's own retention is governed by its terms.
- Bengali strings written by Claude Code, **needing human review before release:** the six
  `search_distance_*` strings and the two changed `location_disclosure_*` strings.
- No secrets, keys or local paths in the diff.

## 9. Known issues & risks

- **Not seen on a phone or against the server.**
- **Ranking is unchanged.** Brand aliases ("SBI" and "State Bank of India") and category words
  ("bank", "pharmacy") depend on the provider's matching and on the map data. This prompt only
  makes the search start from the right place.
- **The distance is rough**: from a point about 1 km coarse, in a straight line, not along
  roads.
- **Without a position and with the map on the state overview, a search still has no area**
  and gets the server's default bias.
- **Users who granted location earlier saw the older disclosure.**
- **The row has less room for long names** at the largest font, since the distance takes its
  share; names wrap.

## 10. Follow-ups & prerequisites for next prompt

Notion *Follow-ups*, new (source P011e2):

- Searches that still go wrong: collect Rahul's examples (brand aliases, category searches).
  Examples first; no fix guessed (High, waiting for Rahul).
- Phone check: "bank", "pharmacy", "hospital" from two towns, location on and off (High).
- Lawyer: the search disclosure, earlier grants, and whether a consent purpose is needed
  (High).
- Bengali review of the distance strings and the changed disclosure.

Closed: "P011e2: Android sends the coarse position as near and shows distances" (source
P011e1). Updated: "Lawyer: the map centre is the user's position without a tap ..." (source
P012e1), which this prompt overtakes.

**Next:** P012c2 (follow-me), then P013; neither is started.

## 11. How Rahul can verify

1. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug`.
2. CI green, squash and merge. Nothing deploys.
3. On the phone against staging. Do it **in two different towns**, and note the town, whether
   location was on, and what came first:
   1. **Location on** (permission allowed, the dot is on the map). Search **"bank"**, then
      **"pharmacy"**, then **"hospital"**. Each row should show a distance, the line above the
      list should say "from your location", and near places should come first.
   2. Pan the map to another town, search "bank" again: still from **your location**, not from
      where the map looks.
   3. **Location off** (deny the permission in system Settings, reopen the app). The map shows
      the state; search "bank": no distances (no area was sent). Zoom in on a town and search
      again: distances "from the centre of the map".
   4. Allow location again, leave the phone for more than five minutes on the search screen,
      search again: note whether it says "from the centre of the map" (the position is stale).
   5. For each search that goes wrong, write down: the town, the exact text typed, location on
      or off, what came first, and what you expected. Send those; they go into the follow-up.
   6. Bengali: the distance line and the figures. Largest font: names wrap, distances stay.
   7. TalkBack on a result: it reads the name, the label and "... km from your location".
   8. Tap "my location" for the first time on a fresh install: read the explanation; it now
      mentions search and the rounding to about 1 km.

## 12. Learning notes

- **Reading state without starting anything.** `LocationRepository.state` is a `StateFlow`: it
  always has a current value, and `state.value` reads it at once. The search screen uses what
  Home has already learned and never starts location itself. That is why search needs no
  permission prompt of its own.
- **Why the age is computed and not trusted.** The state says "Fix" until someone updates it,
  and nobody does while Home is off screen. A value that describes "now" has to be checked
  against a clock at the moment it is used.
- **Injecting a `Clock`.** Code that needs the time asks for a `Clock` and tests hand it one
  they can move forward by hand, so "five minutes later" takes no time at all.
  [java.time.Clock](https://developer.android.com/reference/java/time/Clock)
- **Keeping a label with its data.** The answer on screen remembers what it was measured from.
  If the label were read from "the current situation" while drawing, a position arriving later
  would relabel old distances wrongly.
- **Content description on part of a row.** The row is one TalkBack stop; giving the distance
  its own `contentDescription` changes what is spoken ("2.3 km from your location") without
  changing what is shown ("2.3 km").
- **Locale-aware numbers.** `NumberFormat.getNumberInstance(locale)` writes 2.3 with the
  decimal mark and the digits of that language. Building the text by hand would show Latin
  digits and a point everywhere.
