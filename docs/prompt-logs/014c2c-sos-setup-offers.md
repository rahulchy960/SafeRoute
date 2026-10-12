# P014c2c: SOS setup is offered after the first contact and on Home while not ready

| Field | Value |
| --- | --- |
| Prompt | P014c, part 2 (readiness), third and last piece: the two ways to the checklist. P014c2a built the checklist, P014c2b its settings |
| Milestone | M5 (follows P014c2b, merged as `0065ee9`) |
| Branch | `feat/014c2c-sos-setup-offers` |
| PR title | `feat(sos): SOS setup is offered after the first contact and on Home while not ready [P014c2c]` |
| Notion | [P014c2c row in the Prompt Log](https://app.notion.com/p/3f70737077208176a7aae60142161bdc) |
| Date | 2026-10-13 |
| Plan refs | Plan v7 §3.2 F-08, §7.5; ADR 0027 (note of 2026-10-13, P014c2c), ADR 0024, ADR 0015 |

> **Nothing was run on a phone.** Every test runs on the JVM (Robolectric).
>
> **The card on Home is inside the bottom sheet**, below what the sheet shows at its lowest
> position. Whether people notice it there is not known.

## 1. Objective

Close the gap P014c2a left: a user who never opened "SOS setup" was never told that an SOS
from their phone would not work. Requirement 2.1, last sentence: "Offer the screen once
after the first contact is added, and from the Home sheet while not ready." And 2.2: no
background work; state re-read on every resume; nothing logged or uploaded.

## 2. Context & prerequisites

- Preflight: `main` synced at `0065ee9`; PR #66 (P014c2b) merged; no open pull requests;
  hook path `.githooks`. Notion: P014c2b set to Merged, a new row for this prompt.
- `android-ci` on `main` for `0065ee9`: success (run 38315499260). That lets rows 1 and 11
  count the tests of P014c2b.
- From P014c2a: `sosReadiness` (the rules), `SosSetupViewModel` (which read the phone
  itself). From P013b2: the card "Add emergency contacts" in the Home sheet
  (`ContactsHomeCard`, the slot `sheetCard`) with its three-day "Not now".
- **Manual results:** none reported since P014c2a.

## 3. Workflow executed

1. `/start-prompt`: sync, PR #66 merged, Notion, branch.
2. `SosReadinessReader`: the reading of the phone taken out of the screen's ViewModel, so
   that the card on Home uses the same one.
3. Two flags; the two ViewModels and cards; Home, the contacts screen, navigation; strings.
4. Tests. Two of my Home tests first asked to scroll to a button in the Home sheet, which
   is not a scrolling list; they tap it directly now.
5. The matrix (a duplicated item of my own first edit removed), script M30 part 7, ADR
   0027, CLAUDE.md.
6. The Android gate three times in a row; `/ship-prompt`.

## 4. Changes

**`feature/emergency/SosReadinessReader.kt`** (new): `read()` returns the facts and the
checklist. `SosSetupViewModel` uses it (no behaviour change; its tests pass unchanged apart
from how they build it).

**`feature/emergency/SosSetupOffers.kt`** (new):

- `SosSetupOfferViewModel`, `SosSetupOfferCard`: once, on the contacts screen, when a
  contact exists.
- `SosSetupCardViewModel`, `SosSetupHomeCard`: on Home, "Not ready: N things to fix",
  read at each resume; "Not now" for three days.
- `SetupCard`: the shared layout.

**`feature/contacts`**: `ContactsPreferences.setupOfferShown` and `setupCardSnoozedUntil`
(keys `sos_setup_offer_shown`, `sos_setup_card_snoozed_until`); `ContactsRoute` shows the
offer and answers it when the checklist is opened by its own button.

**`feature/home`, `navigation`**: the Home sheet shows the card under "Add emergency
contacts"; `HomeRoute` takes `onOpenSetup`.

**Strings**: 4 new, English and Bengali (`sos_setup_open`, `sos_setup_offer_title`,
`sos_setup_offer_body`, `sos_setup_home_body`). The card's title reuses the checklist's
summary strings.

**Docs**: ADR 0027 (note of P014c2c), the failure matrix, script M30 part 7, CLAUDE.md.

**No change**: the manifest, permissions, the database, build files, the API contract, the
backend, the notice, the checklist's rules, an SOS itself.

## 5. Diagram

No diagram needed: two cards that lead to an existing screen. Their rules are six
sentences in the ADR note.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug --rerun-tasks`, run 1 | Pass: 1231 tests, 0 failures, 0 skipped (5 min 44 s) |
| The same, run 2 | Pass: 1231 tests, 0 failures, 0 skipped (5 min 15 s) |
| The same, run 3 | Pass: 1231 tests, 0 failures, 0 skipped (5 min 33 s) |
| Every test named in the failure matrix exists | Pass: 318 names |
| markdownlint, link check, gitleaks | Pass (142 Markdown files; links and anchors in the changed documents, 0 broken; no leaks) |

`assembleRelease` was not run: no build file, manifest, `src/release` or `src/debug`
changed.

14 new tests (1217 before, 1231 now), all in `SosSetupOffersTest.kt`.

| Requirement | Tests |
| --- | --- |
| Offered once after the first contact | `SosSetupOfferViewModelTest` "it is offered once a contact exists, and never again after an answer"; `SosSetupOffersFlowTest` "after the first contact the contacts screen offers the checklist once", "opening the checklist by its own button answers the offer too" |
| From the Home sheet while not ready | `SosSetupCardViewModelTest` "it counts what is to fix, and looks again only when Home comes to the front", "a user who agreed to an older notice is told on Home", "with everything in order Home shows nothing"; `SosSetupOffersFlowTest` "while not ready Home leads to the checklist, with the SOS control still there" |
| Not before SOS is being set up | `SosSetupCardViewModelTest` "before SOS is being set up at all the card stays away"; `SosSetupOffersFlowTest` "by default in tests, and for a user who has not begun, Home shows no such card" |
| "Not now" | `SosSetupCardViewModelTest` "not now hides it for three days and no longer"; `SosSetupOffersFlowTest` "not now on Home puts the card away" |
| No background work; state re-read on resume; nothing logged (2.2) | `SosSetupCardViewModelTest` (the look is counted between resumes); `SosSetupCardTest` "the cards and the reader run nothing in the background, ask for nothing and log nothing" |
| Bengali, font scale 2.0 | `SosSetupCardTest` "in Bengali at double font size both buttons can be used" |
| The settings file holds flags only | `ContactsBoundaryTest` "the settings file gets five flags from the contacts feature and nothing about a person" |
| The SOS control is never covered | The flow test above; `SosPlacementTest` (unchanged, passes) |
| String parity | `StringResourceParityTest` (unchanged, passes) |

**Failure matrix** (`docs/sos/failure-matrix.md`)

| Row | What changed | Status now |
| --- | --- | --- |
| A6, contacts empty at SOS time | Home shows "Not ready" once the user agreed to the notice; tests added | Automated pass · Manual pending (unchanged; the new tests count after the merge) |
| A7, contacts never synced | The same, and the offer after the first contact; tests added | Automated pass · Manual pending (unchanged) |
| 1, 11 | No code change. The tests of P014c2b are on `main` and ran green (run 38315499260) | Unchanged |
| Known gaps | "Not built", item 4 (a warning before an emergency), removed: it is built. A new item 7 under "Built, but no test can show it": the warning on Home needs Home | n/a |

Script M30 gained part 7 (no SMS is sent in it).

## 7. Decisions & ADRs

ADR 0027, note of 2026-10-13 (P014c2c).

- **One reading for the checklist and the card** (`SosReadinessReader`), so N on Home is
  the checklist's N.
- **The Home card only once SOS is being set up** (agreed to the notice, or a contact on
  the phone). Before that, "Add emergency contacts" says what is needed; two cards would
  say it twice.
- **"Offer once" is a card on the contacts screen**, not a dialog and not a jump to the
  checklist: the user has just added a person and is about to send the invite; a card
  waits for them on the list. A user who already had contacts gets it once too.
- **"Not now" on Home is three days**, like the contacts card, and not "never": a
  permanent off would also silence the case of no consent or no contact.
- **Home looks when it comes to the front.** That is how Home already re-reads the
  location permission. No flow is collected for it, so nothing runs while Home is away.
- **Two flags in the settings file.** No personal data.

## 8. Security & privacy notes

- **No new permission**, no new stored personal data. Two flags: an answer and a point in
  time.
- The cards show fixed text and a count. No name, number or place.
- Reading the phone for the Home card starts no location updates, fetches nothing and
  logs nothing. It reads the consent flag, counts the contacts in the local copy and asks
  Android about permissions and switches.
- Nothing is uploaded; no analytics.

## 9. Known issues & risks

- **Unverified on a phone.**
- **The Home card can be missed**: it is inside the sheet, which starts at its lowest
  position, under the header row. A user who never pulls the sheet up does not see it,
  and a user who never opens the app sees nothing at all (the notice after a restart is
  P014c3; a reminder outside the app is not planned).
- **It returns every three days** while something is to fix, also for a user who chose
  approximate location on purpose.
- **In a debug build without the SMS permission** "How alerts are sent" counts as to fix,
  so a tester sees the card until they allow it; a release build does not count it.
- The count is as fallible as the checklist (no SIM balance, signal or battery manager).
- 4 Bengali strings are new drafts and **need human review before release**.
- About 805 added lines in `android/` (about 325 of app code and strings, about 480 of
  tests).

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P014c2c):

- Phone check of the two ways to the checklist (script M30, part 7): is the Home card
  noticed without being pointed at; Bengali review of 4 strings.
- Matrix rows A6 and A7: count the new tests after `android-ci` is green on `main`.
- Should "not ready" also show outside the sheet (for example a dot on the SOS control)?
  A design question for the closed test, not built.

Updated: the P014c2b follow-up about rows 1 and 11 (done here).

**Part 2 of P014c is complete with this.** Still owed by Rahul from parts 1 and 2: script
M17 part 7 (taps on the widget) and script M30 (all parts).

**For P014c3** (the notice after a restart, sign-out safety; on Rahul's word): the
existing boot receiver `EmergencyShortcutReceiver`, `SosEngine.current()`, the recovery
screens of P014a3, sign-out in `AccountViewModel`, `SosHousekeeping.wipe`, and
`ContactsRepository.clearLocal` (which since P014c2b also clears the alert settings).

## 11. How Rahul can verify

1. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug`.
2. Install this branch's build. Run script M30, part 7; nothing in it sends an SMS:
   - Emergency contacts: the card "Check that an SOS would work" once; "Not now".
   - Switch notifications off for SafeRoute, go to Home, pull the sheet up: "Not ready: N
     things to fix". Open the checklist from it; fix it; back on Home the card is gone.
   - "Not now" on the card.
3. Report: did you notice the Home card by yourself; N on the card and on the checklist;
   did it go away after the fix without restarting the app.

## 12. Learning notes

- **One source of truth.** Two screens that show the same number should get it from the
  same code, or they will one day disagree.
- **`StateFlow` and `stateIn(WhileSubscribed)`.** The offer follows the contact list only
  while its screen is collecting it; when the screen goes, the collection stops.
- **Resume as the moment to look.** `LifecycleResumeEffect` runs each time a screen comes
  to the front; reading there needs no background work.
  [Docs](https://developer.android.com/topic/libraries/architecture/compose#lifecycle-effects)
- **Slots in Compose.** Home takes a `sheetCard` lambda and does not know which cards it
  shows; features put theirs in from the outside.
- **A flag for "once".** "Shown once" must outlive the process, so it is a value in
  DataStore, not a variable.
