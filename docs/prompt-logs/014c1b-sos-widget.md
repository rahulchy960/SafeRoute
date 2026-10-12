# P014c1b: the home-screen widget starts the SOS through the same use case

| Field | Value |
| --- | --- |
| Prompt | P014c, part 1 (entry points), second half: the home-screen widget. The first half is P014c1a |
| Milestone | M5 (follows P014c1a, merged as `5e57495`) |
| Branch | `feat/014c1b-sos-widget` |
| PR title | `feat(sos): home-screen widget starts the SOS through the same use case [P014c1b]` |
| Notion | [P014c1b row in the Prompt Log](https://app.notion.com/p/3f707370772081329775f77ae62fa614) |
| Date | 2026-10-13 |
| Plan refs | Plan v7 §3.2 F-08, §7.1–7.5; addendum v7.1, section B (entry points); ADR 0027 (notes of P014c1a and P014c1b), ADR 0008 |

> **Nothing was run on a phone.** Robolectric stands in for the launcher. Whether a phone's
> launcher lists the widget, how big it draws it and whether a tap opens the countdown is
> not known until script M17, part 7, is run.
>
> **One tap on the home screen starts the five-second countdown.** If nobody cancels, the
> emergency contacts are texted.

## 1. Objective

Add the fourth entry point of P014c, part 1: a home-screen widget with one SOS button that
starts the same five-second, cancellable countdown through `StartSos` and the same consent
gate as the hold, the tile and the notification (requirement 1.3). Also record, in the
device table of the failure matrix, the phone Rahul named.

## 2. Context & prerequisites

- Preflight: `main` synced at `5e57495`; PR #63 (P014c1a) merged; no open pull requests;
  hook path `.githooks`. Notion: P014c1a set to Merged, a new row for this prompt.
- `android-ci` on `main` for `5e57495`: success (run 38296767979). That lets rows A1, A2
  and 6 count the tests of P014c1a.
- From P014c1a: `StartSos`, `EmergencyShortcut.startPendingIntent(SosEntryPoint)`,
  `SosEntryPoint.WIDGET`, the emergency screen's handling of every entry point (already
  tested there with `WIDGET`), notice version 3.
- The widget was written during P014c1a and kept on a local branch when part 1 was split
  for size. It was taken from there unchanged.
- **Reported by Rahul on 2026-10-13:** a Samsung Galaxy S21 FE on Android 16, on which no
  widget could be added. The build he used had no widget (P014c1a), so that is what the
  build should do. No script result was reported.

## 3. Workflow executed

1. `/start-prompt`: sync, PR #63 merged, Notion, branch.
2. The widget's files from the local branch; the manifest entry; three strings in two
   languages; the manifest test now expects two receivers.
3. The Android gate; `assembleRelease` (the manifest and resources changed).
4. The matrix (row A1, the device row, the rows that now count P014c1a's tests), script
   M17 part 7, ADR 0027, the Play draft, CLAUDE.md.
5. The Android gate three times in a row; `/ship-prompt`.

## 4. Changes

**`feature/emergency/SosWidgetProvider.kt`** (new): `SosWidgetProvider`, an
`AppWidgetProvider`, and `buildSosWidgetViews`, which sets the tap on the button to
`EmergencyShortcut.startPendingIntent(context, SosEntryPoint.WIDGET)`.

**Resources** (new): `layout/emergency_widget.xml` (a card, a red button "SOS", the line
"Tap to start SOS"), `drawable/emergency_widget_card.xml` and `emergency_widget_button.xml`,
`xml/emergency_widget_info.xml` (2 by 2 cells, not resizable, no periodic update, home
screen, a preview). Four colours in `values/colors.xml` and `values-night/colors.xml`.

**Manifest**: one `<receiver>` for the provider, not exported, with the widget-update
action and the pointer to the info file. **No permission added.**

**Strings** (English and Bengali): `sos_widget_label`, `sos_widget_description` ("Start
SOS", for TalkBack), `sos_widget_picker_description`.

**Tests**: `SosWidgetTest` (new, 7 tests); one manifest test adjusted.

**Docs**: ADR 0027 (note of P014c1b), the failure matrix, script M17 part 7, the Play
foreground-service draft, CLAUDE.md.

**No change**: the use case, the emergency screen, the tile, the notification, the notice
and its version, the database, build files, the API contract, the backend.

## 5. Diagram

No new diagram: [`docs/diagrams/014c-sos-entry-points.svg`](../diagrams/014c-sos-entry-points.svg)
of P014c1a already shows the widget as the fourth entry point (it says "Built in
P014c1b").

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug --rerun-tasks`, run 1 | Pass: 1162 tests, 0 failures, 0 skipped (5 min 5 s) |
| The same, run 2 | Pass: 1162 tests, 0 failures, 0 skipped (4 min 47 s) |
| The same, run 3 | Pass: 1162 tests, 0 failures, 0 skipped (4 min 34 s) |
| `./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://example.invalid/` | Pass |
| Every test named in the failure matrix exists | Pass: 295 names |
| markdownlint, link check, gitleaks | Pass (138 Markdown files; links and anchors in the changed documents, 0 broken; no leaks) |

The release build was also made once with the dummy map key and
`-Psaferoute.allowDummyFirebase=true`. That last flag is meant for CI only; I should not
have used it. The run in the table is the one without it, and nothing of either build was
kept or committed.

7 new tests (1155 before, 1162 now).

| Requirement | Tests (`SosWidgetTest` unless named) |
| --- | --- |
| Tap = the use case with `WIDGET` | "a tap on the button starts the SOS as the widget entry, and nothing else on it reacts"; the gate, the countdown and duplicates for `WIDGET` are in `StartSosTest`, `SosViewModelTest` and `SosEmergencyScreenTest` (P014c1a) |
| One button, content description "Start SOS", a size that avoids accidental taps | "the button is named Start SOS for TalkBack, shows fixed text, and is big enough"; "the widget is two cells square, cannot be resized, is never updated and is for the home screen" |
| Immutable PendingIntent, no trampoline | "its pending intent starts an activity directly, is immutable, and is its own" |
| No periodic updates, no network | The size test (`updatePeriodMillis` 0); "the widget and the ways to the emergency screen never log, print or touch the network" |
| Manifest declaration, widget info | "the manifest declares the provider for widget updates only, and not exported"; `EmergencyShortcutsManifestTest` (two receivers) |
| No new permission | `MainActivityTest` (the merged list, unchanged, passes) |
| The red only for the emergency action | "the red of the button is the emergency red, and only the button uses it" |
| String parity EN/BN | `StringResourceParityTest` (unchanged, passes with the 3 new keys) |
| Logging | The tap test (nothing in Logcat) and the source test |

Not tested, because a JVM cannot: how a launcher draws the widget, font scale inside a
launcher, and the widget's look in dark mode. They are in script M17, part 7.

**Failure matrix** (`docs/sos/failure-matrix.md`)

| Row | What changed | Status now |
| --- | --- | --- |
| A1, SOS from the tile while locked | The widget is built and recorded here; its tests listed. The tests of P014c1a are on `main` and ran green | **Automated pass · Manual pending** (the widget's tests count after this merge) |
| A2, pinned notification | The tests of P014c1a count now | Automated pass · Manual pending |
| 6, duplicate trigger | The tests of P014c1a count now | Automated pass · Manual pending |
| All others | Unchanged | Unchanged |

**Device results** gained one row: Samsung Galaxy S21 FE, Android 16, reported on
2026-10-13, no script run. The older row ("Samsung, model not recorded", Android 17) was
left as written: rows are never edited, and whether it was the same phone is not recorded.

"Known gaps" no longer lists the widget as not built; accepted risk 7 names it. Script
M17, part 7, was written.

## 7. Decisions & ADRs

ADR 0027, note of 2026-10-13 (P014c1b).

- **RemoteViews, not Glance.** Glance is stable (1.2.0, read in P014c1a), but it brings a
  library for one button that never changes.
- **2 by 2 cells, not resizable, only the button reacts.** The prompt asks for a size that
  avoids accidental taps and for no confirmation step. A widget that can be shrunk to one
  cell would be all button.
- **Home screen only.** The lock-screen category is not declared.
- **Colours repeated in XML**, as the splash screen already does; a test ties the red to
  the theme and to the button.
- **The notice stays at version 3**: its sentence was worded in P014c1a so that the widget
  is covered.
- **Not added:** an "Add the widget" row in Settings. The prompt asks for it for the tile
  only. A follow-up exists.

## 8. Security & privacy notes

- **No new permission.** The provider is not exported.
- The widget shows fixed text, the same for everyone. It has no configuration screen and
  is never updated, so nothing of the account can reach the home screen.
- Its PendingIntent is immutable and starts the emergency activity directly; the intent
  carries a mode and the entry point.
- No network, no logging.
- The device row holds a phone's make, model and Android version: no number, name or place.

## 9. Known issues & risks

- **Unverified on a phone.** Launchers differ: the cell size, the preview, whether the
  widget list shows the description.
- **A tap nobody meant.** A home screen is touched far more often than Quick Settings. One
  tap on the red button starts the countdown; five seconds later the contacts are texted
  (a debug build: only the numbers on its list). Not measured.
- A phone maker's lock screen may offer the widget although it is declared for the home
  screen only (Android's sources allow that). Not known for any phone.
- The widget's text does not follow the phone's font size beyond what the launcher does.
- 2 Bengali strings are new drafts and **need human review before release**
  (`sos_widget_description`, `sos_widget_picker_description`).
- The device table now has two Samsung rows with different Android versions; only Rahul
  can say whether they are one phone.
- About 360 added lines in `android/` (about 170 of code and resources, about 190 of tests).

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P014c1b):

- Phone check of the widget (script M17, part 7) and Bengali review of 2 strings.
- Matrix row A1: count the widget's tests after `android-ci` is green on `main`.
- Rahul: is the "Samsung, Android 17" row of the device table the S21 FE?

Updated: the P014c1a follow-up about rows A1, A2 and 6 (done here). Open from P014c1a and
now including the widget: unintended starts in the closed test; a way to add the widget
from Settings; the lawyer on notice version 3.

**Part 1 of P014c is complete with this.** Next, on Rahul's word: P014c2 (the readiness
screen), then P014c3 (the notice after a restart, sign-out safety).

## 11. How Rahul can verify

1. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug`.
2. Install the build of this branch on the S21 FE. Use an account whose only contact is
   your second phone (or none); read the banner of the debug build.
3. Hold an empty place on the home screen → Widgets → search "SafeRoute" → "SafeRoute SOS"
   → Add.
4. Run script M17, part 7: tap beside the button (nothing), tap the button (countdown, then
   Cancel), the widget plus the tile during one countdown (one SOS), without the consent
   (Call 112 and "Set up SOS alerts"), dark mode, Bengali, TalkBack.
5. Report: whether the widget was in the list and under which name, its size, whether it
   could be resized, whether the lock screen offered it, any tap you did not mean; and
   whether the older "Android 17" row is this phone.

## 12. Learning notes

- **App widget.** A small view of an app that lives on the home screen. The launcher draws
  it; the app is usually not running.
  [Docs](https://developer.android.com/develop/ui/views/appwidgets)
- **AppWidgetProvider.** A broadcast receiver that Android calls when a widget is added or
  needs its views. Ours answers with the same views every time.
- **RemoteViews.** A description of a layout that another app inflates. Only a few simple
  view types are allowed, and a tap can only carry a PendingIntent.
- **Widget info file** (`appwidget-provider`): the size in cells, whether it can be
  resized, how often Android should ask for an update (never, here) and the preview.
- **Why the colours are in XML.** The launcher cannot run the app's Compose theme, so the
  widget reads colour resources; `values-night` holds the dark ones.
- **Merged manifest and receivers.** A widget needs a `<receiver>` entry and no permission.
