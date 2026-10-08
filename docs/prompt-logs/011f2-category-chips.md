# P011f2: Android: category chips, distances and honest empty states for nearby search

| Field | Value |
| --- | --- |
| Prompt | P011 · part f, second half: **P011f2 Android** (this log). P011f1 (server, contract, evaluation) is merged |
| Milestone | M4 (depends on P011f1, merged as `5c2ae91` and deployed to staging) |
| Branch | `feat/011f2-category-chips` |
| PR title | `feat(android): category chips, distances and honest empty states for nearby search [P011f2]` |
| Notion | [P011f2 row in the Prompt Log](https://app.notion.com/p/3f3073707720814ab512cc9ef0c5baf9) |
| Date | 2026-10-09 |
| Plan refs | Plan v7 §3.2 (F-03); addenda v7.2 E, v7.3 I; ADRs 0008, 0015, 0018, 0019 |

> **Verified with JVM tests only (Robolectric).** Claude Code has no phone or emulator. How the
> chips look, how the list reads at the largest font and what TalkBack says are steps for
> Rahul in section 11.
>
> **What a chip finds is not known.** The server's category search was written from the
> provider's documentation and has not been measured (`pnpm search:eval` is still owed from
> P011f1). This prompt makes the app ask correctly and report honestly; it does not make more
> places exist on the map.

## 1. Objective

Give the search screen a way to ask for a kind of place with one tap, show how far around what
the server looked, and say something true when it found nothing: that local shops are often
missing from the map, with a way to add them. No safety, risk or ranking claim, and no new
permission.

## 2. Context & prerequisites

- P011f1 is merged (PR #44, `5c2ae91`). The `deploy-staging` run for that commit shows
  **completed, success** in GitHub Actions. That is the workflow's own result (migration,
  candidate, smoke tests, promotion); nobody has yet searched on staging with a phone.
- No open pull requests, clean tree, hooks active.
- The client was regenerated from contract 0.8.0 by the build. Confirmed in the generated
  models: `SearchRequest.category`, `Place.matchType`, `SearchResults.searchedRadiusKm` and
  `SearchResults.searchedAround`.

## 3. Workflow executed

1. `/start-prompt`: main synced, PR #44 and its deploy confirmed, branch, Notion row.
2. Repository and view model, then the screen, strings and the link helper. First local
   commit (`31295d7`) after the search tests passed.
3. Docs: ADR 0018 ("Android behaviour"), `CLAUDE.md`, `android/README.md`, diagram, this log.
4. Quality gate, push, pull request, Notion.

Commands, in `android/` with `JAVA_HOME` set to Android Studio's JDK for the command only:
`./gradlew lint testDebugUnitTest assembleDebug`;
`./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://example.invalid/`;
`git ls-files android | grep -iE "local.properties|\.jks|google-services\.json"` (printed
nothing). Diagram: `pnpm generate` in `tools/diagrams`. markdownlint and gitleaks through Docker.

## 4. Changes

Android, `feature/search/`:

| File | What |
| --- | --- |
| `SearchRepository.kt` | `SearchCategory` (eight chips and their API keys), `SearchedCircle`, `CircleCentre`; `search(…, category)`; a chip sends `category` and no `q`; the answer's `searchedRadiusKm` and `searchedAround` become `SearchOutcome.Found.circle` |
| `SearchViewModel.kt` | `onCategoryClick`, `category`; a chip searches at once; typing ends it; `Results.circle`; `Empty` is now a data class that carries the circle; "Try again" repeats a chip's search |
| `SearchScreen.kt` | The chip row (shown while the field is empty), the "Within N km of …" line, the circle's empty state with its button, distances spoken as "from the place you typed" where that is the centre |
| `MapSite.kt` (new) | `mapSiteIntent()` and `openMapSite()`: `ACTION_VIEW` on `https://www.openstreetmap.org/` |
| `res/values/strings.xml`, `res/values-bn/strings.xml` | 19 new strings each, same keys |

What the screen says:

| Situation | Text |
| --- | --- |
| Circle around the user's position | "Within 10 km of your location. Distances are approximate." |
| Circle around the map centre | "Within 10 km of the centre of the map. …" |
| Circle around a place typed in the text | "Within 10 km of the place you typed. …" |
| The server names a centre this app version does not know, or no area was sent | "Within 10 km. …" (no claim about the centre) |
| Nothing in the circle | "Nothing found within 25 km." / "Local shops are often missing from the map. You can add a place on OpenStreetMap." / button "Open OpenStreetMap" |
| No browser on the phone | "No browser app was found on this phone." under the button |
| A name search that finds nothing | Unchanged: "No places found" and the spelling advice |

**Chips → `category`:** Banks `bank`, ATMs `atm`, Pharmacies `pharmacy`, Hospitals `hospital`,
Petrol `fuel`, Food `restaurant`, Groceries `grocery`, Bus & train `public_transport`.

**Strings that need human review before release (Bengali drafts written by Claude Code):**
`search_chips_label`, `search_chip_bank`, `search_chip_atm`, `search_chip_pharmacy`,
`search_chip_hospital`, `search_chip_fuel`, `search_chip_food`, `search_chip_grocery`,
`search_chip_transit`, `search_within`, `search_within_position`, `search_within_map`,
`search_within_place`, `search_distance_from_place`, `search_distance_note`,
`search_nothing_within`, `search_missing_from_map`, `search_open_osm`, `search_no_browser`.

**Not changed:** the manifest (no permission, no `<queries>`), build files, the contract, the
backend, `SearchArea.kt`, Home, directions.

**docs:** ADR 0018 "Android behaviour" (dated addition), `CLAUDE.md` Android search rule,
`android/README.md` "Search" (the new behaviour; its "what is sent" table still described
P011b and said the position is never sent, which has been untrue since P011e2: corrected),
diagram, this log.

## 5. Diagram

[`docs/diagrams/011f2-nearby-search-screen.svg`](../diagrams/011f2-nearby-search-screen.svg)
(spec: `011f2-nearby-search-screen.json`; 10 nodes): chip or text, the area, the API, the three
outcomes, the browser link.

## 6. Quality gate & test results

| Command (in `android/`) | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` | pass: lint without errors, **739 tests, 0 failed, 0 skipped**, debug APK assembled |
| `./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://example.invalid/` | pass (no build file changed; run because the prompt asks for the release guards) |
| `git ls-files android \| grep -iE "local.properties\|\.jks\|google-services\.json"` | prints nothing |
| `pnpm generate` in `tools/diagrams` | pass |
| markdownlint (Docker, v0.18.1), JSON validity, gitleaks (Docker, v8.30.1) | 0 errors; valid; no leaks |

All tests are JVM tests with Robolectric. No device.

New tests (25):

- `ApiSearchRepositoryTest` (+4): **the request body carries `category`** and no `q` for a
  chip, and no `category` for typed text; the eight keys; the circle is read, and an unknown
  or missing `searchedAround` is never taken for the user's location; `matchType` with an
  unknown value does not break the answer.
- `SearchViewModelTest` (+9): a chip searches at once from the user's position and its answer
  carries the circle; a chip without a position uses the map centre; **typed category** and
  **typed brand** with a place hint; **empty** with and without a circle; **offline, 429 and
  503** for a chip, and "Try again" repeats it; typing ends a chip and an empty field does
  not (the rotation case); a newer chip replaces a slow one; **choose-start mode**;
  **log capture** for a chip's search.
- `SearchNearbyScreenTest` (new, 12): eight chips as buttons of at least 48 dp; the selected
  chip; chips hidden while typing; **the header text** for each centre; **distances** on rows
  and their spoken form; the empty circle with its button; the "no browser" line;
  **choose-start mode** with chips, results and the emergency control in four states;
  **layout at font scale 2.0**; Bengali with Bengali digits; no rating or safety words in the
  new strings; **the link intent** (action, fixed address, no query, no extras) and a phone
  without a browser.
- **String parity** is covered by the existing resource test, which the gate ran.
- Changed: two existing tests now write `SearchUiState.Empty()`; the fake repository records
  the category. Nothing deleted or skipped.

One existing test caught a regression while I worked: a distance must not be shown when the
app does not know what it is measured from (`SearchDistanceTest`). The rule is kept: a row
shows its distance only when the search was sent with an area or the server reports a circle.

**Failure matrix (Plan v7 §7.5):** not applicable; no SOS, live-location or contacts code. The
emergency control on the search screen is untouched and tested to stay reachable.

## 7. Decisions & ADRs

Recorded in [ADR 0018](../adr/0018-search-and-geocoding.md), "Android behaviour", addition of
2026-10-09. No new ADR. Mine to make:

- **Chips are shown while the text field is empty**, which includes the whole of a chip's own
  search, so another kind is one tap away and the chosen chip shows what the list is. While
  text is typed they give their space to the results.
- **A chip is not written into the text field.** The search is by `category`; putting "Banks"
  into the field would turn it into a text search that depends on the server's dictionary.
- **A row of chips that scrolls sideways**, not a wrapping grid: every chip keeps its full
  size at any font scale and the results stay near the top.
- **`matchType` is read but not shown.** A "brand" or "category" tag on a row tells a person
  nothing they need.
- **An unknown `searchedAround` claims nothing** about the centre.
- **One line about distances**: with a circle the header replaces the older "Distances are
  approximate, from …" note.
- **No `<queries>` entry in the manifest.** The link is started and the failure caught; the
  app never asks which browsers are installed.
- The chip label is "Petrol" as the prompt wrote it; the category covers any fuel station.

## 8. Security & privacy notes

- **No new permission**, no manifest change, no new personal data, nothing stored.
- A chip sends a category key and the same coarse area as a typed search (two decimals, the
  rules of P011e2). Search still never asks for the location permission and never starts
  location updates.
- The OpenStreetMap button opens a **fixed address** with no query, fragment or extras: the
  app passes nothing about the user, the search or a place. Once the browser is open, what
  that site learns is between the browser and the site.
- The log-capture test covers a chip's search: nothing about the category, the results or the
  position is in Android's log or in a `toString()`. It sees this process's log only.
- No safety, risk, rating or "nearest" wording: a test scans the new strings.
- Coverage wording follows addendum v7.3 section I: the empty state says nothing was found on
  the map within the radius, never that no such place exists.
- Public repo check: no personal data, local paths or non-public ids added.

## 9. Known issues & risks

- **No device check.** Chip appearance, the sideways scroll, the largest font and TalkBack
  are untested on a phone.
- **Bengali strings are drafts**, to be reviewed by a native speaker (list in section 4).
  "মুদি দোকান" for Groceries and "ওষুধের দোকান" for Pharmacies are colloquial choices.
- **A chip without a known area** (location off and the map showing the whole state) sends no
  point. The server then answers with a plain name search for the word, without a radius or
  distances; the screen shows those results without the "within" line.
- **What the chips find depends on the map data and on the unmeasured server search.**
  "Food" is restaurants only; "Bus & train" depends on how stops and stations are mapped.
- A chip's search is lost when Android ends the process in the background (the view model is
  gone); the screen returns to the empty state. A rotation keeps it.
- The chip row is hidden while text is typed; a user must clear the field to see it again.

## 10. Follow-ups & prerequisites for next prompt

Recorded in the Notion Follow-ups database:

- Phone checks for P011f2 in Rahul's town and in Kolkata; record what is found and missing.
- Native-speaker review of the 19 new Bengali strings.
- Decide what a chip should do when no area is known (ask the user to zoom in or to turn on
  location, instead of a plain name search).
- Still open from P011f1: the evaluation run, the dictionary review, the fixture.

Next prompt: P013 (emergency contacts). Nothing here blocks it.

## 11. How Rahul can verify

1. CI green on the pull request (`android-ci`, `repo-checks`).
2. Install the debug build on the phone (Android Studio: Run). In **your town**, with location
   on and the blue dot visible:
   1. Open search. Eight chips under the field; swipe the row sideways to see all of them.
   2. Tap **Banks**: results at once, the chip highlighted, a line "Within 10 km of your
      location" (or 25 km), a distance on every row. Note what is found and what you know is
      missing.
   3. Tap **Pharmacies**, **ATMs**, **Petrol**, **Bus & train** in turn. Tap the highlighted
      chip again: back to the empty screen.
   4. A chip that finds nothing: "Nothing found within 25 km." and the sentence about local
      shops. Tap **Open OpenStreetMap**: the browser opens openstreetmap.org.
   5. Type **bank**, **pharmacy**, **SBI** followed by your town's name, **Apollo pharmacy**.
      For the one with the town's name the line should say "of the place you typed".
   6. Type **Bankura**: an ordinary name search, no "within" line.
3. The same in **Kolkata** (or with the map moved there and location off: the line then says
   "of the centre of the map").
4. Location **off** and the map zoomed out to the whole state, tap a chip: results without a
   "within" line and without distances. Tell me whether that is acceptable (follow-up).
5. Directions: open a place, **Change** the start, use a chip, pick a result: it becomes the
   start of the route.
6. The SOS control in the top bar is visible and works in every one of these states.
7. Bengali (Settings → language): chips, the line with Bengali digits, the empty state.
8. Largest font size (system Settings → Display): chips still readable and scrollable, rows
   wrap, the button of the empty state is reachable.
9. TalkBack: a chip reads as "Banks, button" (and "selected" when chosen); the "within" line
   is read when results arrive; a row reads its distance with what it is measured from.
10. Write down per search: town, what you tapped or typed, location on or off, what came
    first, what is missing. Those go into ADR 0018 as a dated note.

## 12. Learning notes

- **Chips.** A chip is a small rounded button from Material Design, used for choices and
  filters. `FilterChip` has a selected look; here it is announced to TalkBack as a button
  (`semantics { role = Role.Button }`) because tapping it does something at once.
  [Material 3 chips](https://developer.android.com/develop/ui/compose/components/chip).
- **`horizontalScroll`.** A `Row` normally squeezes its children into the screen's width.
  `Modifier.horizontalScroll(rememberScrollState())` lets the row be wider than the screen and
  be swiped. [Scroll modifiers](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/scroll).
- **State in the ViewModel versus in the screen.** The typed text lives in the screen
  (`rememberSaveable`); the chosen chip lives in the ViewModel as a `StateFlow`. A ViewModel
  survives a rotation, so the chip and its results do too. After the rotation the screen
  reports its (empty) text again; the ViewModel ignores that, or the chip would be lost.
- **Intents and `ACTION_VIEW`.** An `Intent` is a message to Android: "someone show this
  address". The system chooses the app (the browser). SafeRoute needs no internet permission
  of its own for that and passes only the address. If no app can handle it, `startActivity`
  throws `ActivityNotFoundException`, which is caught.
  [Common intents](https://developer.android.com/guide/components/intents-common#Browser).
- **Open sets from the server.** `searchedAround` is a string, not a fixed list. The app maps
  the values it knows and treats anything else as "unknown" instead of failing, so a newer
  server does not break an older app.
- **Live regions.** `liveRegion = LiveRegionMode.Polite` asks TalkBack to read a text when it
  appears or changes, without the user having to find it: the "within" line and the empty
  message use it.
