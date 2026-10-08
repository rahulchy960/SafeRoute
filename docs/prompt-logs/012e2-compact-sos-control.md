# P012e2: a compact SOS control that never covers content, and a smaller map credit

| Field | Value |
| --- | --- |
| Prompt | P012 · part e, second half (**P012e2 compact SOS control**; P012e1 was the initial camera), with Rahul's amendment of 2026-10-08. P012c2 (follow-me) and P011e2 are still open |
| Milestone | M4 (depends on P012e1, merged as `7f837e5`) |
| Branch | `fix/012e2-compact-sos-control` |
| PR title | `fix(android): compact SOS control that never covers content, smaller map credit [P012e2]` |
| Notion | [P012e2 row in the Prompt Log](https://app.notion.com/p/3f307370772081ce83dbe7022a86ce8c) |
| Date | 2026-10-08 |
| Plan refs | Plan v7 §1, §5, §7; ADRs 0008, 0015 |

> **No screenshots were taken, and nothing ran on a phone.** Claude Code has no device. The
> amendment asks for new screenshots in the pull request: they are for Rahul to add (section
> 11). What is proved here is geometry, by layout tests on the JVM: where the control sits and
> that its bounds intersect nothing. How it looks is not proved.

## 1. Objective

Evidence, from a screenshot of Rahul's phone: the "Emergency 112" pill covers the first route
card (its duration and distance row and the attribution line), and the map credit is a large
dark box over the map. Make the emergency control compact and give it a place of its own, so
that it can never cover a card, a result or a row; make the credit as small as the providers'
rules allow. The dialog's behaviour stays as it is.

## 2. Context & prerequisites

- P012e1 merged (PR #36, `7f837e5`). No open pull requests. Clean tree, hooks active.
- Requirement 2 of the P012e prompt, plus the amendment's four points: (1) no overlay, ever;
  (2) layout tests that fail on an intersection; (3) the map attribution; (4) screenshots.
- The screenshot itself was not given to Claude Code; its description in the amendment is the
  evidence used.

## 3. Workflow executed

1. `/start-prompt`: main synced, PR #36 confirmed merged, P012e1 set to Merged in Notion,
   branch, Notion page.
2. Read the emergency button, the sheet, the directions sheet, the place card and the search
   screen. Read OpenStreetMap's and MapTiler's attribution guidance (section 7).
3. `SosControl`, its two places on Home, the search top bar, the fixed directions header, the
   smaller credit; existing tests moved from the visible label to the spoken name.
4. `SosPlacementTest`; a mutation run to see it fail (section 6).
5. ADR notes, this log, `/ship-prompt`.

## 4. Changes

**The control (`core/designsystem/component/EmergencyButton.kt`)**

- `SosControl` replaces `EmergencyButton`: a 48 dp circle in the `sos` red showing "SOS";
  content description "SOS and call 112"; the click action is still labelled "show emergency
  options". The letters do not grow with the font setting, so it stays 48 dp.

**Home (`feature/home/HomeScreen.kt`)**

- The overlay is gone. The control is in one of two places, never both:
  - sheet at peek: the last item of the map-controls column, above the sheet's top edge;
  - sheet at, or heading for, half or full height: the end of the sheet's header row, after
    the close button. The switch follows the sheet's target, so the rising sheet never slides
    over the control.
- `PlaceCard`, `DirectionsSheet` and the Home list each take a `headerEnd` slot for it.
- `DirectionsSheet`: the header row no longer scrolls with the route list; only the body does.
- TalkBack order: search, map controls, SOS, sheet. Inside the sheet the control is read first.

**Search (`feature/search/SearchScreen.kt`)**

- `SosControl` at the end of the top bar. `SearchRoute` shows the same `EmergencyDialog` and
  opens the dialer the same way.

**The map credit (`feature/home/MapStatus.kt`)**

- A small label on a light backdrop, one line where it fits (two at very large fonts, never
  cut off); the tappable box around it is still 48 dp and has no background.

**Strings, test tags and what did not change:**

- Strings: `sos_control_label`, `sos_control_description` added in English and Bengali;
  `emergency_button_label` removed in both.
- Test tags for layout tests: `SosControlTag`, `RouteCardTag`, `SheetSurfaceTag`, a tag on
  search result rows.
- No new permission, dependency or manifest change. `ACTION_DIAL` with `tel:112` as before.

**Not changed:** the dialog's text and behaviour, `HomeViewModel`, anything outside `android/`
and `docs/`.

**Size:** about 800 changed lines in `android/` (about 450 of them tests, 360 in `SosPlacementTest`), plus documents.

## 5. Diagram

No diagram needed: no flow, state machine, schema or interaction sequence changed. Where the
control sits is described in ADR 0008's note and pinned by `SosPlacementTest`.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` (in `android/`) | BUILD SUCCESSFUL; 572 tests, 0 failures (560 before); lint 0 errors |
| markdownlint, JSON validity, gitleaks, forbidden files | 95 files, 0 errors · tracked JSON valid · no leaks · none |
| `git ls-files android \| grep -iE "local.properties\|\.jks\|google-services\.json"` | prints nothing |

`assembleRelease` was not run: no build file, `src/release` or `src/debug` changed.

**`SosPlacementTest` (10 tests), new.** The rule: the control is 48 dp, on screen exactly
once, and its bounds intersect no other element that shows text or can be tapped.

- Every sheet content (Home list, place card, route cards, a route message) at every detent
  (peek, half, full): at normal font, at font scale 2.0, in Bengali at 2.0, in the dark theme.
- Landscape (640 × 360 dp): route cards at half and full.
- Route cards by name: neither card nor the routes' attribution line lies under the control,
  at each detent.
- Where it sits: at peek in the map-controls column, below "my location" and above the sheet;
  at half and full in the sheet, on the title's row, after the close button.
- A tap reports the emergency from every position, for every sheet content, in every map
  state including the error states (72 taps).
- The search screen: in the bar, above every result row, at normal and double font; a tap
  reports.
- Elements the sheet itself has slid over (the map controls, at full height the search pill)
  are not compared while the control is in the sheet: they are under the sheet.

**Mutation run.** With the control forced to stay on the map at every detent, four of the ten
tests failed (the font-scale 2.0 checks, the header-row check and the tap check); with the
change restored, all pass. The checks at normal font did not fail under that mutation,
because at that size the misplaced control happened to lie beside the sheet's content, not on
it. So: the tests do detect a misplaced control, and the font-scale 2.0 case is the sharper
one.

**Other tests.** About twenty existing tests found the button by its visible text and now
find it by its spoken name. `AppNavigationTest` gained two tests for the dialog on the search
screen (dialer intent `ACTION_DIAL` `tel:112`, never `ACTION_CALL`; cancel; no dialer app).
`ComponentsTest` pins the control at exactly 48 dp at font scale 2.0. Unchanged and passing:
`SosColourUsageTest` (the red is used by the same two files), `StringResourceParityTest` (key
parity; 112 in Latin digits), `MapLibreBoundaryTest`, `MapFailureEmergencyTest`,
`InitialCameraTest`.

**What these tests cannot see:** how the control and the credit look; whether the credit is
readable on a real map in both themes; TalkBack's real reading order; a real landscape phone
with a display cut-out.

**SOS failure matrix (Plan v7 §7.5):** no SOS logic changed. The one row this touches, "the
SOS surface offers a one-tap call to 112", is covered: the dialog opens from every state and
"Call 112" opens the dialer.

## 7. Decisions & ADRs

No new ADR. [ADR 0008](../adr/0008-android-foundation.md) has a note on the control;
[ADR 0015](../adr/0015-map-stack-and-location-policy.md), "Attribution", has the change and
what was checked.

**The map credit (amendment, point 3).** The amendment asked for a button that expands on tap
if the providers allow it, otherwise smaller text on one line. Checked on 2026-10-08:

- **OpenStreetMap Foundation, "Attribution Guidelines"** (osmfoundation.org wiki; sections
  Requirements, Interactive maps, Attribution text): the credit should not need an
  interaction to be seen; it may be collapsed on a dismiss, on a map interaction or after
  five seconds, with the licence information still findable; the text must be easily
  readable.
- **MapTiler, "Map attribution and how to add it"** (docs.maptiler.com, map-design guides):
  the text attribution with its links must appear on every map, mobile apps included; its
  web example switches the collapsible control off to show the attribution expanded; a logo
  is asked for on free accounts. The first address tried for this page no longer exists.
- **Result:** no tap-to-reveal button. The credit is smaller, on one line where it fits, and
  always shown.
- **Not checked:** MapTiler's Terms and Conditions; nobody at MapTiler was asked. Not legal
  advice (**to be verified by a lawyer**).

Choices made here, for Rahul to confirm:

- **"SOS" as letters, not an icon**, and the letters do not scale with the font setting. A
  control that grew would cover its neighbours again; the spoken name is the accessible one.
  People who rely on large text see the same size of letters as everyone else.
- **The visible word "112" is gone from the control** (it is in the spoken name and in the
  dialog). A 48 dp circle has no room for "Emergency 112".
- **The control changes place when the sheet starts to move**, not when it has arrived.
- **The directions header is fixed** so that the control and the close button cannot scroll
  away. Before, the whole panel scrolled.
- **The search screen keeps its own dialog state** (a saved value in the screen) and uses
  Home's dialog and dialer functions. One shared entry point is left for P014.
- **The credit may take two lines** at very large fonts instead of being cut off.
- **`EmergencyButton` and its label string were removed**, not kept beside the new control.

## 8. Security & privacy notes

- No personal data, permission, network call or storage is added.
- The emergency path is unchanged: the dialer opens with `ACTION_DIAL` and `tel:112`, the
  user starts the call, and the dialog says that SafeRoute is not an emergency service. Tests
  assert the intent on Home and on the search screen.
- Bengali strings written by Claude Code, **needing human review before release:**
  `sos_control_label`, `sos_control_description`.
- No secrets, keys or local paths in the diff.

## 9. Known issues & risks

- **Not seen on a phone, no screenshots** (top of this log).
- **A red circle with "SOS" says less at a glance than "Emergency 112".** That is the price of
  never covering content. Whether people recognise it is a question for the phone check and
  for testers.
- **The letters do not grow at large font sizes.**
- **In landscape at peek** the map-controls column (three controls) is tall for a short
  screen, as the two controls and the pill were before; not checked on a device.
- **The MapTiler logo** asked of free accounts is not shown (open since P010a).
- **Two copies of the dialog state** (Home's ViewModel, the search screen) until P014.
- The amendment's point 4 (screenshots) is not done by this pull request.

## 10. Follow-ups & prerequisites for next prompt

Notion *Follow-ups*, new (source P012e2):

- Phone check and screenshots of the control and the credit (High).
- Bengali review: the SOS control's two strings.
- P014: the control's tap becomes the real SOS flow (hold to arm), plus a Quick Settings tile;
  one shared emergency entry point.
- MapTiler: the logo on the free plan, and confirm the form of the map credit.

Closed: "P012e2: compact SOS control that never covers content" (source P012e1).

**Next:** P011e2 (Android search by position) or P012c2 (follow-me); neither is started.

## 11. How Rahul can verify

1. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug`.
2. CI green, squash and merge. Nothing deploys.
3. On the phone, with a build from the branch or from `main` after the merge. **Please add
   these screenshots to the pull request** (the amendment's point 4): light, dark, Bengali and
   the largest font, each with the sheet at peek, half and full, and with route cards.
   1. Home, sheet at peek: a red "SOS" circle is the last of the round controls on the right,
      above the sheet. Nothing red lies over the sheet.
   2. Pull the sheet to half, then to full: the circle is at the end of the sheet's first row.
      It moves as soon as the sheet starts to rise.
   3. Search a place and tap it: the circle is in the place card's first row, after the X.
   4. Tap Directions: the circle is in the directions header, after the X. **Every route card
      and the attribution line under them are fully visible.** Scroll the routes: the header
      with the X and the circle stays.
   5. Search screen: the circle is at the end of the top bar; no result row is under it.
   6. In each of the places above, tap it: the same dialog; "Call 112" opens the phone app
      with 112 entered and does not call.
   7. Largest font and Bengali: the circle is the same size; titles next to it wrap instead of
      going under it.
   8. The map credit under the search pill is a small label, readable in light and dark; a tap
      on it opens the credits with both links.
   9. TalkBack: swiping goes search, the round controls, "SOS and call 112", then the sheet.
   10. Turn the phone on its side: the circle is still on screen at each sheet height.

## 12. Learning notes

- **Overlay versus layout.** In Compose a `Box` stacks its children on top of each other; a
  child drawn later covers the earlier ones. The old button was such a child, placed by
  alignment and padding, so it knew nothing about what was under it. A `Row` or `Column`
  gives each child its own space: the title gets what is left after the X and the control.
  Something that must never cover content belongs in a row or column, not on a stack.
- **A slot.** `headerEnd: @Composable () -> Unit` is a parameter that holds a piece of UI. The
  place card does not know what it is given; it only says "this goes at the end of my first
  row". That is how Home can put the control into three different contents without any of
  them knowing about SOS.
  [Slot-based layouts](https://developer.android.com/develop/ui/compose/layouts/basics#slot-based-layouts)
- **`sp` and `dp`.** Text sizes are given in `sp`, which grow with the user's font setting;
  sizes of things are given in `dp`, which do not. Turning a `dp` value into `sp` at the
  current font scale gives text of a fixed physical size. Used here on purpose, for three
  letters inside a fixed 48 dp control, and nowhere else.
- **Semantics: what TalkBack reads.** A node's `contentDescription` is its spoken name. The
  letters inside the control have their own semantics cleared, so TalkBack says "SOS and call
  112" once instead of "SOS, SOS and call 112". Tests find the control by that spoken name,
  which is also what a blind user would hear.
  [Semantics in Compose](https://developer.android.com/develop/ui/compose/accessibility/semantics)
- **Testing a layout without a screen.** Robolectric lays the screen out on the JVM, and every
  element reports its bounds as a rectangle. "Does A cover B" is then rectangle arithmetic,
  checked for dozens of combinations in seconds. It proves positions; it cannot say whether
  the result looks right.
- **A mutation run.** To know that a test can fail, break the code on purpose and run it. Here
  the control was forced to the wrong place; the tests that failed are the ones that guard
  it, and the ones that did not showed where the guard is thinner.
