# P011b: place search screen, map pin and place card

| Field | Value |
| --- | --- |
| Prompt | P011 · the second and last part (P011a backend, **P011b Android**) |
| Milestone | M4 (depends on P011a, merged as `5b34014`) |
| Branch | `feat/011b-search-ui` |
| PR title | `feat(android): place search screen, map pin and place card [P011b]` |
| Notion | [P011b row in the Prompt Log](https://app.notion.com/p/3f107370772081449d24c27e22fd0927) (umbrella row: [P011](https://app.notion.com/p/3eb0737077208103a853eb74e9d1a84d)) |
| Date | 2026-10-07 |
| Plan refs | Plan v7 §3.2 F-03, §4, §6.2–6.3, §12.2; addenda v7.1–v7.3; ADRs 0008, 0009, 0015, 0018 |

> **Verified on the JVM only.** Claude Code has no phone or emulator. Search was never run
> against staging from the app, and the pin's map layer in `core/map/MapLibreEngine.kt` is
> native-map code that cannot run in a unit test: it compiles and has **not been seen drawing
> a pin**. **No manual check on any device was run**; section 11 lists them.

## 1. Objective

Let signed-in users search for places from the app: a search screen that talks to
`GET /v1/search`, results in English or Bengali, and a chosen result shown on Home with a pin
and a place card. The typed text is never stored or logged, and the emergency button stays
usable whatever search does. Also record Rahul's decisions after the first evaluation run
(provider, thresholds).

## 2. Context & prerequisites

- P011a merged (PR #26, `5b34014`). No open pull requests. Clean tree, hooks active.
- **B0, deployed?** `gh run list` shows two `deploy-staging` runs for `5b34014` with
  `conclusion: success` (the push and a manual run). Claude Code cannot call staging, so "the
  staging API answers 401 on `/v1/search` without a token" is Rahul's check, not verified here.
- **B0, client:** `./gradlew :app:generateApiClient` produced `SearchApi.searchPlaces` and the
  models `Place` and `SearchResults` from the committed contract (0.4.0).
- Rahul's decisions of 2026-10-07: Geoapify by default, LocationIQ as a spare; thresholds
  confirmed with the per-district rule limited to districts with at least 3 queries; a full
  reading of Geoapify's terms; new follow-ups.

## 3. Workflow executed

1. `/start-prompt`: main synced, PR #26 confirmed merged, Notion P011a set to Merged, branch
   `feat/011b-search-ui`, Notion page.
2. Decisions recorded and applied in the backend (`32242b0`): `GEOCODING_PROVIDER` defaults to
   `geoapify`; the harness applies the district rule; ADR 0018 note with the reported rates and
   Geoapify's terms; Revision section in the P011a log.
3. Read the app's Home, map, network and navigation code; generated the client.
4. Repository, ViewModel, network pieces (`209bc07`).
5. Search screen and strings (`382a515`).
6. Selection on Home: pin, place card, back, navigation (`81019ed`).
7. Docs: Android README "Search", `CLAUDE.md` rule, this log.
8. Notion: follow-ups. `/ship-prompt`: quality gate, push, pull request, Notion page.

Found by the new tests and fixed before the commit:

- **A coordinate sent as text was accepted.** The first number serializer used `decodeDouble()`,
  which also reads `"10.5"` in quotes. It now looks at the JSON element and refuses a string.
- **An empty selection left two empty entries in the saved state.** An existing test
  ("positions are not put in the saved state") caught it; nothing is written when no place is
  chosen.

One build oddity: the first `./gradlew lint …` run failed inside lint itself ("Unexpected
failure during lint analysis of AppNavigationTest.kt", a file this prompt did not touch). Running
the same command again passed, twice. Recorded in case it returns in CI.

## 4. Changes

| Area | What |
| --- | --- |
| `core/network` | `SearchApi` provided by `NetworkModule`; `BigDecimalAsNumberSerializer` (the generator types JSON numbers as `BigDecimal`); `ApiFailure.Problem.retryAfterSeconds` from the `Retry-After` header; `ProblemCodes.SEARCH_UNAVAILABLE` and `SEARCH_NOT_CONFIGURED` |
| `feature/search/SearchRepository.kt` | `SearchRepository`, `ApiSearchRepository` (the only user of `SearchApi`), `FoundPlace`, `SearchError`, `SearchOutcome`; the area is rounded to two decimals on the phone |
| `feature/search/SearchViewModel.kt` | States Idle, TooShort, Loading, Results, Empty, Error; 300 ms wait; at least 2 code points; `collectLatest` |
| `feature/search/SearchScreen.kt` | `SearchRoute` and the stateless `SearchScreen`: clear button, keyboard Search action, 100 code points at most, results, messages, attribution, Try again |
| `feature/search/di/SearchModule.kt` | Binds the repository; replaced by a fake in tests |
| `core/map/MapSelection.kt` | `SelectedPlace` and `MapSelection` (`@ActivityRetainedScoped`): the chosen place and the map's centre, shared by Search and Home |
| `core/map` | `MarkerStyle.Place`; a `pin` kind in the overlay GeoJSON; a pin layer in `MapLibreEngine.kt`; `place` in the overlay palette |
| Design system | `SafeRouteColors.place` (purple; not the location blue, never the SOS red) |
| `feature/home` | `HomeViewModel`: camera to a newly chosen place once, pin drawn with the location dot, clear, save and restore; `PlaceCard.kt`; `HomeScreen`: the card in the sheet and a `BackHandler` that is active only while a place is shown |
| `navigation` | `SearchRoute`; a chosen result pops back to Home |
| Strings | 15 strings and 2 plurals, English and Bengali; `search_empty_title` and `search_empty_body` reworded |
| Backend | `GEOCODING_PROVIDER` defaults to `geoapify`; harness: district rule, default provider |
| Docs | ADR 0018 (status, "Android behaviour", note of 2026-10-07), P011a log Revision, `android/README.md` "Search", `backend/README.md`, `backend/test/search-eval/README.md`, `CLAUDE.md`, this log |

No contract change. No migration. No new dependency (`android/THIRD_PARTY.md` unchanged). No
new permission.

**Deviations from the prompt:**

- **`collectLatest` instead of `debounce` and `flatMapLatest`.** Both are experimental coroutine
  APIs and need an `@OptIn`, which ADR 0008 forbids in production code. `collectLatest` with a
  `delay` is stable and gives the same two guarantees; the race test proves the second.
- **The selection is held in an `@ActivityRetainedScoped` object** (`MapSelection`), plus the
  Home ViewModel's saved state, rather than a shared ViewModel: Search and Home each keep their
  own ViewModel, and neither feature depends on the other.
- **401 and 403 show the generic "something went wrong" message** on the search screen. The
  prompt says no custom handling; the session machine is untouched and a test asserts it.
- **Attribution is shown as text**, not as a link (no URL in the contract; follow-up).
- **Size:** about 2,280 changed lines: Android source 990, Android tests 1,150, the rest
  backend, strings and documents. Over the ~800-line guide. Not split: the screen without the
  selection flow would end in a list that leads nowhere.

## 5. Diagram

No diagram needed (as the prompt says). The request flow is in
[`011a-search-request-flow.svg`](../diagrams/011a-search-request-flow.svg).

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` (in `android/`) | BUILD SUCCESSFUL |
| → unit tests | **486 tests, 0 failures, 0 skipped** (439 before; 47 more) |
| → lint | 0 errors, 2 warnings (both `OldTargetApi`, known, ADR 0008) |
| `./gradlew assembleRelease` with the dummy base URL, dummy map key and allow flag | BUILD SUCCESSFUL |
| `git ls-files android \| grep -iE "local.properties\|\.jks\|google-services\.json"` | prints nothing |
| Backend: `pnpm typecheck` · `lint` · `format:check` · `build` · `openapi:check` | clean · clean · clean · clean · up to date (`db:check` clean too) |
| Backend: `pnpm test` (unit + db) | 395 tests, 0 failures (250 unit, 145 db) |
| markdownlint-cli2 · JSON validity · gitleaks 8.30.1 | 79 files, 0 errors · 49 files valid · no leaks |

What the new tests prove:

| Test | What it proves |
| --- | --- |
| `SearchViewModelTest` (14, virtual time) | typing "ab", "abc", "abcd" quickly makes one call, after 300 ms; one character makes none; trimming; the same text again does not search again; Bengali with a zero-width non-joiner is sent as typed and counted in code points; the 100 code-point cut never splits a character; **a slow answer to an old query arriving late changes nothing**; **clearing the ViewModel cancels the search in flight**; every outcome has its state; the Search key searches at once and not twice; Try again; the area is the map's centre and the language follows the app; a chosen result reaches the map; nothing in the log or in any `toString()` |
| `ApiSearchRepositoryTest` (7) | number coordinates are read and unknown fields ignored; a coordinate sent as text is refused; **the request carries the area rounded to two decimals**, the language and a limit of 6; 429 with and without a usable `Retry-After`; every 503 code is "unavailable"; no connection; 401 and 403 get no special treatment |
| `SearchScreenTest` (10) | focus on open; Bengali input; the clear button; the 100 code-point limit; the keyboard's Search action; results as one button per place with a 48 dp target; the result count and every message are polite live regions; attribution only when provided; Try again; a described spinner; 200% font size; Bengali strings |
| `HomePlaceTest` (11) | a chosen place moves the camera once (keeping a closer zoom) and adds a pin as an overlay description; closing removes it and leaves the camera; **the pin and the location dot coexist**, and losing the location permission removes only the dot; choosing a place stops "follow"; the map centre is shared; the place survives process death without a second camera move; the pin is its own GeoJSON kind and colour; the card has a close button and a disabled, described Directions button; the emergency button and the search pill still work |
| `SearchFlowTest` (5, the real `MainActivity`) | Home → search → result → Home with pin, card and camera; **back clears the place first, the next back leaves**; the search uses the map's centre and requests no permission; **after a failed search the emergency button still opens the dialer with `tel:112`**, and the session is still `Ready`; the typed text and the place never reach Logcat |
| Existing | `MapLibreBoundaryTest` (no MapLibre import outside `core/map`), `StringResourceParityTest` (same keys in both languages), `MainActivityTest` (permission list unchanged) all pass |

**SOS failure matrix (Plan v7 §7.5):** not applicable; no SOS, live-location or contacts code.
The related checks: search trouble never blocks the emergency button, and nothing is stored.

**Manual checks: none were run.** Devices available to Claude Code: 0.

## 7. Decisions & ADRs

[ADR 0018](../adr/0018-search-and-geocoding.md): status updated, "Android behaviour" written,
and a note of 2026-10-07 added.

**Evaluation, provider, thresholds (Rahul, 2026-10-07).** Top-3 name-match rates over the 74
starter queries, as Rahul reported them:

| Provider | Overall | Bengali script | English | Transliteration |
| --- | --- | --- | --- | --- |
| Geoapify | 81% | 78% | 84% | 75% |
| LocationIQ | 73% | 22% | 91% | 83% |

- **Geoapify is the default** (config and workflow); LocationIQ stays as a spare adapter,
  selectable with `GEOCODING_PROVIDER`.
- **Thresholds:** overall top-3 ≥ 80%; Bengali script ≥ 70%; ≥ 60% per district **only for
  districts with at least 3 queries** until the fixture grows.
- **Caveat, recorded as asked:** the 74-query starter set is small, was written by Claude Code
  from memory and is not checked against a map; Jalpaiguri and Malda are being reviewed.
  Per-district and top-1 figures were not reported and are not recorded.

**Geoapify's terms, read in full** (Terms and Conditions version 5 of 2 February 2024, pricing
page and FAQ, Geocoding API page), in our own words; not legal advice:

| Question | Finding |
| --- | --- |
| Attribution | "Attribution": OpenStreetMap credit always; Geoapify credit mandatory on the free plan; the pricing FAQ asks for a "Powered by Geoapify" link near the information shown |
| Does the adapter return it? | Yes: `attribution` is "Powered by Geoapify · © OpenStreetMap contributors" |
| Does the Android search screen show it? | Yes, under the results, whenever the API sends one (`SearchScreenTest`). As text, not yet a link |
| Caching and storage | Not in the terms. The Geocoding API page says results may be stored, with the data-source attribution kept. The app and the API store nothing anyway |
| Safety-critical or high-risk clause | None. General "as is" disclaimers ("No Warranties", "Disclaimer") and a liability cap |
| Per-second limits | Free plan: up to 5 requests a second, 3,000 credits a day, one credit per autocomplete request; limits described as soft. "Rules and Conduct" forbids unreasonable load and splitting requests across accounts |
| Commercial use | The terms say the free plan may be used in production "with some limitations" and to contact them; the FAQ says production use is fine within limits. To confirm in writing before commercial use |

Decisions made while building:

- **The search area is the map's centre, never the user's position.** Search works without the
  location permission, and a test asserts none is requested.
- **The place card replaces the sheet's placeholder content** while a place is shown.
- **Back clears the place first.** The handler exists only while a place is shown, so normal
  back (leaving the app from Home) is unchanged.
- **A newly chosen place ends "follow my location"**; otherwise the next position fix would
  pull the camera away from the place.
- **The pin is a larger circle in its own colour**, drawn above the location dot. A real pin
  icon is a design follow-up; size and colour both differ from the dot, and the card names the
  place.

## 8. Security & privacy notes

- **No geocoding key in the app.** The app calls only the SafeRoute API.
- **What leaves the phone for a search:** the typed text, the map's centre rounded to two
  decimals (about 1 km), and the language. Not the user's position.
- **Nothing is stored.** The query lives in `rememberSaveable`; the chosen place in memory and
  in Android's saved instance state (system-managed, like the map camera), until its card is
  closed. No history, no analytics.
- **Nothing is logged.** `FoundPlace`, `SelectedPlace`, `SearchUiState.Results`,
  `SearchOutcome.Found` and the ViewModel's request print "hidden"; two tests capture Logcat.
- **No new permission, no new consent purpose.** The query and the coarse area do reach the
  geocoding provider through our server: to be named in the privacy policy, consent notice and
  Data safety form (follow-up, lawyer).
- Public-repository check: test places are invented ("Main Station", round coordinates); one
  Bengali test string is a public station name. No keys, no local paths.

## 9. Known issues & risks

- **Nothing was run on a device**, and search was never exercised from the app against staging.
- **The pin layer is untested MapLibre code.** If its style expression is wrong the pin does
  not appear; the card, the camera move and the rest of the app are unaffected.
- **A 401 or 403 during a search shows a generic error**; the user is not sent to sign-in from
  the search screen.
- **Attribution is not a link** yet.
- **The chosen place survives sign-out within the same activity** until its card is closed
  (the holder lives as long as the activity). Low impact; noted.
- **Bengali strings are drafts**: all 17 new entries and the two reworded ones.
- **The evaluation proves little yet** (section 7).
- The transient lint failure (section 3) may recur in CI.
- Over the size guide (section 4).

## 10. Follow-ups & prerequisites for next prompt

New in Notion (*Follow-ups*, source P011b):

- Review the search failures in Jalpaiguri, Malda and Kolkata.
- Re-run the search evaluation before each region is published.
- Rotate the geocoding keys used for the evaluation and staging.
- Privacy policy, consent notice and Data safety form: place search (High).
- Check search on a phone against staging (High).

Updated: "Grow the search fixture to at least 100 queries and at least 3 per district";
"Show the search attribution as a link (needs a contract field)". Closed: "Run the search
evaluation per provider and confirm the thresholds". Already open from P011a and unchanged: the
MapTiler email about the tile clause; `bootstrap-staging.ps1` and the geocoding secret; recent
searches and saved places.

Bengali strings that **need human review before release** (drafts by Claude Code):
`search_empty_title`, `search_empty_body`, `search_clear`, `search_too_short`,
`search_loading`, `search_result_count`, `search_no_results_title`, `search_no_results_body`,
`search_error_offline`, `search_error_rate_limited`, `search_error_rate_limited_seconds`,
`search_error_unavailable`, `search_error_unexpected`, `search_retry`, `place_card_close`,
`place_card_directions`, `place_card_directions_unavailable`.

**Next: P012** (`feat/012-osrm-routing`). Not started. Inputs Rahul needs for it (addendum v7.2
section D):

- **The OSM extract:** which West Bengal boundary extract (source, licence, date), and whether
  a Kolkata-metro extract is cut from it for the comparison.
- **Hosting sizing:** Cloud Run with enough memory or a small VM; the budget ceiling per month.
- **Memory measurements:** where they are run (staging) and who reads them; the acceptance is
  p95 ≤ 2 s including the exposure metric, a start-up time the hosting choice can live with, and
  the monthly cost recorded.
- Profiles needed (walking, driving) and whether both are built in P012.
- The P011b phone checks, so that routing is not built on an unconfirmed search flow.

## 11. How Rahul can verify

1. Read the pull request and the note of 2026-10-07 in ADR 0018.
2. Confirm staging: `/v1/search` without a token answers 401 (not 404).
3. Run on the phone against staging. Tap the search pill, type a known place in English, then
   one in Bengali: results appear, with a credit line under them.
4. Type fast: the list settles on the last text. Clear with the ✕.
5. **Airplane mode:** the offline message and "Try again"; turn it off and try again.
6. Tap a result: Home returns, the map moves there, **a purple pin is on the place** and the
   sheet shows its name. Check the pin in dark mode too. Tell me if there is no pin.
7. The card: "Directions" is greyed out. Close with ✕: pin and card go. Choose a place again and
   press back: the place goes first; back again leaves the app.
8. Search a district town far from Kolkata. Rotate the phone while results show.
9. 200% font size; TalkBack: it reads "N places found" and each result as one item.
10. The emergency button works on Home with a place shown.
11. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug` (486 tests).
12. CI green, squash and merge. The merge also deploys the backend (one config default changed).

## 12. Learning notes

- **Debounce.** Waiting a moment after the last keystroke before acting. Without it, typing
  "station" would send seven searches, six of them wasted. Here: `delay(300)` inside a block
  that is restarted on every change, so only the last change gets past the wait.
- **Cancellation.** A coroutine can be stopped at its next suspension point (a `delay`, a
  network call). Stopped work throws no error to the user and writes no result; it just ends.
  Leaving the screen cancels `viewModelScope`, and with it the search in flight.
  <https://developer.android.com/kotlin/coroutines/coroutines-best-practices>
- **Why stale answers must be ignored.** Two searches are in the air; the first is slow. If its
  answer arrives last and is shown, the list no longer matches the text field. `collectLatest`
  cancels the older block the moment a newer value arrives, so the older answer has nowhere to
  land.
- **Experimental APIs and `@OptIn`.** Some library functions are marked "may change". Kotlin
  makes you write `@OptIn` to use them. The project avoids them in production code (ADR 0008);
  tests may use them.
- **`StateFlow` drops repeats.** Setting a `MutableStateFlow` to a value equal to the current
  one tells nobody. That is why typing a trailing space starts no new search.
- **Rate limit and token bucket** (from the app's side). The server allows a burst and then a
  steady pace. When it answers 429 it adds `Retry-After: seconds`; the app shows "please wait a
  moment" instead of trying again by itself.
- **Why proxy the provider, and why a key in an APK is not secret.** An APK is a zip file;
  anyone can read a key inside it. The geocoding key therefore stays on our server, and the app
  only ever talks to our API.
- **Unicode, code points and joiners.** A Bengali word can contain an invisible "joiner" that
  changes how letters combine, and some characters take two memory units. Counting and cutting
  text by code points (`codePointCount`, `offsetByCodePoints`) never splits one.
- **`rememberSaveable`.** Like `remember`, but the value also survives a rotation. Used for the
  text in the field. It is not a file and not a history.
- **`BackHandler`.** Lets a screen take over the back gesture while a condition holds. Here
  only while a place is shown, so the system's normal back comes back by itself.
  <https://developer.android.com/develop/ui/compose/system/predictive-back>
- **Live region.** A piece of text marked so that TalkBack reads it when it changes, without
  the user moving to it. Used for "6 places found" and for the messages.
- **`@ActivityRetainedScoped`.** Tells Hilt to keep one instance for the activity's whole life,
  across rotations: the same lifetime as the activity's ViewModels.
  <https://developer.android.com/training/dependency-injection/hilt-android#component-scopes>
