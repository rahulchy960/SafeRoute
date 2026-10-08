# P012f1: emergency shortcuts, part 1: the Quick Settings tile and one shared destination

| Field | Value |
| --- | --- |
| Prompt | P012 · part f, split in two: **P012f1 the tile and the shared destination** (this log) and P012f2 the pinned notification (not started). P012c2 and P011e2 are also still open |
| Milestone | M4 (depends on P012e2, merged as `6b4b49d`) |
| Branch | `feat/012f1-sos-tile` (the prompt named `feat/012f-emergency-shortcuts`; renamed before any push) |
| PR title | `feat(android): Quick Settings tile for 112 and one emergency destination [P012f1]` |
| Notion | [P012f1 row in the Prompt Log](https://app.notion.com/p/3f307370772081dea247e6827d5c0fd8) |
| Date | 2026-10-08 |
| Plan refs | Plan v7 §5.3, §7.1, §7.5; addendum v7.1 B.1 (SOS entry points; the tile is the dependable entry); ADR 0008 |

> **Part f is split.** This part has the spike (requirement 0), the tile and "Add the SOS tile"
> (requirement 1), and the tile's share of requirements 3 to 8. The pinned notification
> (requirement 2), its permission flow, its receivers and the two manifest permissions are
> P012f2. **This part adds no permission.**
>
> **Nothing ran on a phone.** A tile, the lock screen and Android's add-tile dialog cannot be
> exercised in JVM tests. What is tested: where a tap leads, what the destination does, and
> what the manifest declares. The manual checks in section 11 are for Rahul's phone.

## 1. Objective

Give the user an emergency shortcut outside the app. In this version it opens the same flow as
the in-app SOS control: the dialog that offers "Call 112" through `ACTION_DIAL` with `tel:112`
and says that SafeRoute is not an emergency service. Build it so that P014 only has to change
the destination to the countdown.

## 2. Context & prerequisites

- P012e2 merged (PR #37, `6b4b49d`). No open pull requests. Clean tree, hooks active.
- Addendum v7.1 B.1 planned these entry points for P014 and asked for the lock-screen
  behaviour and the tile APIs to be checked against current Android documentation first.
- The app has `minSdk` 26, `targetSdk` 36, `compileSdk` 37 (`android/gradle/libs.versions.toml`).
- The Plan PDF cannot be read on this machine; the prompt text is the specification.

## 3. Workflow executed

1. `/start-prompt`: main synced, PR #37 confirmed merged, P012e2 set to Merged in Notion,
   branch, Notion page.
2. Spike (section 7): Android's documentation pages and the platform sources of the installed
   SDK (android-37).
3. Split decided; branch renamed.
4. Code and tests (`5672b19`); the gate; the release build.
5. ADR 0008 note, diagram, P014 notes in Notion, this log; `/ship-prompt`.

## 4. Changes

**`feature/emergency` (new package)**

- `EmergencyShortcut`: the one place that names the destination of every shortcut outside the
  app: an explicit `Intent`, an immutable `PendingIntent`, and which of the two a tile must use
  on this Android version.
- `EmergencyActivity`: shows the existing `EmergencyDialog` and nothing else. Not exported, not
  a Hilt entry point, no sign-in needed. May show over the lock screen. "Call 112" opens the
  dialer and closes the screen; Cancel closes it; without a dialer app it shows the number.
- `SosTileService`: the "SafeRoute SOS" tile. Active mode; a tap goes through
  `EmergencyShortcut`.
- `TileAdder` / `AndroidTileAdder`: Android 13's add-tile request, or "not added".

**Settings screen:**

- A section "Emergency shortcuts" with the row "Add the SOS tile", a help text that says what
  the tile does and does not do, and a dialog with the result or with four steps for adding
  the tile by hand. `EmergencyShortcutsViewModel` holds the result; nothing is stored.

**Manifest (`AndroidManifest.xml`):**

- `<activity>` `EmergencyActivity`: `exported=false`, `showWhenLocked=true`,
  `excludeFromRecents=true`, its own task affinity.
- `<service>` `SosTileService`: `permission=BIND_QUICK_SETTINGS_TILE` (required of the system,
  not asked of the user), `exported=true`, the `QS_TILE` action, `ACTIVE_TILE` meta-data.
- **No `<uses-permission>` added. No receiver. No foreground service.**

**Resources:** `ic_sos_tile.xml` (drawn for SafeRoute, AGPL-3.0-only); 17 strings in English and
Bengali.

**Not changed:** the in-app SOS control and its dialog, `MainActivity`, any backend, contract
or workflow file, dependencies.

**Size:** about 990 changed lines in `android/` (about 470 of them tests), plus documents. Over
the 800-line guide even after the split.

## 5. Diagram

[`docs/diagrams/012f-emergency-shortcuts.svg`](../diagrams/012f-emergency-shortcuts.svg): the
entry points, the one destination, and in grey what is not built (the pinned notification,
P014's countdown).

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` (in `android/`) | BUILD SUCCESSFUL; 592 tests, 0 failures (572 before); lint 0 errors |
| `./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://example.invalid/` | BUILD SUCCESSFUL (the release guards for the map key and the Firebase file passed on this machine) |
| `tools/diagrams` `pnpm generate` | no errors |
| markdownlint, JSON validity, gitleaks, forbidden files | 96 files, 0 errors · tracked JSON and the new diagram files valid · no leaks · none |
| `git ls-files android \| grep -iE "local.properties\|\.jks\|google-services\.json"` | prints nothing |

New tests (20):

- `EmergencyShortcutTest` (4): every entry leads to the one destination with an explicit
  intent and no data; the `PendingIntent` starts an activity, is immutable and differs per
  entry; a tile uses a `PendingIntent` from Android 14 and an `Intent` before; Android's add-tile
  result codes, errors included.
- `SosTileServiceTest` (3, on API 33): a tap opens the destination and nothing else, and logs
  nothing; the tile is tappable, not shown as switched on, and named for TalkBack; the label
  fits a tile.
- `EmergencyActivityTest` (5): the dialog and the "not an emergency service" note; "Call 112"
  is `ACTION_DIAL` with `tel:112`, never `ACTION_CALL`, and closes the screen; Cancel; no dialer
  app; it is not the main activity.
- `EmergencyShortcutsManifestTest` (3): the tile service as Android asks, and found by a query
  for tiles; only the emergency screen may show over the lock screen and it is not exported;
  no foreground service, no receiver, no new permission.
- `SettingsShortcutsTest` (5): nothing is requested until the row is tapped; each answer's
  notice; the row is a button of at least 48 dp; the four steps; Bengali at font scale 2.0.
- Unchanged and passing: `MainActivityTest` (the exact permission list), `StringResourceParityTest`,
  `SosColourUsageTest`, `SosPlacementTest`.

**What these tests cannot see:** a real tile in a real panel; the lock screen; Android's
add-tile dialog; what the dialer does on a locked phone; the tile on Android 14 and newer
(Robolectric cannot follow the `PendingIntent` form through the system, so that branch is
tested as "the right kind of launch is chosen", not as a tap).

**SOS failure matrix (Plan v7 §7.5).** No SOS logic exists yet. Rows added to the P014 notes
in Notion (requirement 8): shortcut started while the phone is locked; pinned notification
dismissed or killed; tile unavailable on the device; notification permission denied. For the
row this part touches (started while locked), the manual script is section 11, step 3.

## 7. Decisions & ADRs

No new ADR. [ADR 0008](../adr/0008-android-foundation.md) has the note "emergency shortcuts
outside the app, and their one destination".

### Spike results (requirement 0)

Sources: developer.android.com pages read on 2026-10-08 ("Create custom Quick Settings tiles",
"Notification runtime permission", "Behavior changes: all apps" for Android 14, "Behavior
changes: Apps targeting Android 12", "Implicit broadcast exceptions") and the platform sources
of the installed SDK, android-37 (`TileService.java`, `StatusBarManager.java`, `Intent.java`).

**Certain (documented or read in the platform source):**

| Topic | Finding |
| --- | --- |
| Tile declaration | A `<service>` with `BIND_QUICK_SETTINGS_TILE`, the `QS_TILE` action, an icon (24 dp, solid white, tinted by the system) and a label (about 18 characters). Declaring it does not add it: the user must. |
| Tile lifecycle | `onTileAdded`, `onStartListening`/`onStopListening` (often), `onClick`, `onTileRemoved`. Active mode (`ACTIVE_TILE`) binds the service for taps and requests, not each time the panel opens. |
| Starting an activity | `startActivityAndCollapse(Intent)` throws `UnsupportedOperationException` for apps targeting Android 14 or newer (a compatibility change enabled from that target); the `PendingIntent` form must be used there. Read in `TileService.java`. |
| Lock screen | `isLocked()` tells whether the lock screen is showing. If what the tile does is safe while locked, it may start an activity on top of the lock screen; otherwise `unlockAndRun` asks for the unlock first. `showDialog` shows nothing while locked. |
| Add-tile request | `StatusBarManager.requestAddTileService`, from Android 13. The system shows its own dialog; the app must be in the foreground and the service exported; results are added (2), already added (1), not added (0), or an error (1000 to 1005); the system may stop asking after repeated refusals. |
| Notification permission | `POST_NOTIFICATIONS` is a runtime permission from Android 13. Apps targeting 13 or newer choose when to ask; swiping the dialog away is not a refusal; ask in context, not at first launch. |
| Ongoing notifications | Since Android 14, on every app whatever its target, the user can swipe away a notification marked ongoing. It stays only while the phone is locked and against "Clear all". |
| Trampolines and PendingIntents | Apps targeting Android 12 or newer may not start an activity from a service or receiver in answer to a notification tap; the notification must carry a `PendingIntent` for the activity. Every `PendingIntent` must state its mutability. |
| Boot | `BOOT_COMPLETED` needs `RECEIVE_BOOT_COMPLETED`, is one of the broadcasts a manifest receiver may still receive, and from Android 15 is also delivered when an app leaves the stopped state. `MY_PACKAGE_REPLACED` goes only to the updated app and needs no permission. |

**Not certain (to be seen on phones, or not found in what was read):**

- Whether the phone's dialer opens over the lock screen or asks for the unlock first when
  `ACTION_DIAL` is sent from a screen that shows over the lock. It is the dialer's and the
  phone maker's rule.
- Whether every phone maker's Quick Settings accepts tiles from apps and the add-tile request.
- How many refusals make Android stop showing the add-tile dialog, or the notification
  permission dialog on apps targeting 13 or newer (neither page gives a number).
- **Force-stop:** that a force-stopped app receives no broadcasts until it is opened again is
  long-standing Android behaviour, but the page that states it was not among those read
  successfully (the reference pages were too large to fetch). Only the Android 15 sentence
  about `BOOT_COMPLETED` after leaving the stopped state was read, in the platform source.
- Lock-screen visibility of notifications and channel importance were not read in this part;
  they belong to P012f2.
- API levels of the individual tile methods were not confirmed from the reference page (too
  large to fetch); the code only relies on what the SDK compiles and on the one version check
  read in the source.

### Choices made here, for Rahul to confirm

- **The split** into P012f1 and P012f2.
- **A second activity.** The prompt says "opens the emergency screen/dialog". Showing the main
  activity over the lock screen would show the map and account; so the destination is a
  separate activity that holds only the dialog and may show over the lock screen. This is the
  platform's own mechanism (`showWhenLocked`), not a way round the lock: nothing is unlocked.
- **The shortcut works without sign-in.** It processes no personal data and offers 112.
- **Tile state `STATE_INACTIVE`.** The prompt says "active tile state"; this is read as the
  manifest's active *mode*. An SOS tile drawn as switched on would look like something is
  running. Inactive is the look of a tappable action.
- **The tile is not red.** The system tints tile icons; there is no way to colour one.
- **The add-tile request is made only from the tap in Settings**, never by itself.
- **Any failure of the request shows the manual steps**, including a plain refusal by the user.
  The app cannot tell the cases apart well enough to say more.
- **`EmergencyEntry.Notification` already exists** in the code, unused, so that P012f2 only
  adds the notification.
- **The Settings section sits above "About"**, which moved two older tests' assertions.

## 8. Security & privacy notes

- **No new permission.** `BIND_QUICK_SETTINGS_TILE` is a permission the tile service requires
  of whoever binds it (the system), not one the app requests.
- **Over the lock screen:** only `EmergencyActivity`, which shows fixed text and a button for
  the dialer. No account, map, position, contact or setting is reachable from it. It does not
  dismiss the lock or turn the screen on. `MainActivity` has no such attribute; a test reads
  the manifest for both.
- **Exported components:** the tile service must be exported and is protected by the system
  permission. `EmergencyActivity` is not exported and has no intent filter, so no other app
  can start it.
- **PendingIntent:** immutable, explicit component, one extra that names the shortcut.
- **Nothing is stored or logged.** Whether the tile is added is not recorded; the entry name
  travels in the intent and is not read.
- **112:** the dialer opens with `ACTION_DIAL` and `tel:112`; the app has no `CALL_PHONE`
  permission and can not place a call.
- Bengali strings written by Claude Code, **needing human review before release:** the 17 new
  `sos_tile_*` and `settings_tile_*` / `settings_shortcuts_title` strings (listed in the
  Notion follow-up).
- No secrets, keys or local paths in the diff.

## 9. Known issues & risks

- **Not seen on a phone** (top of this log), and phone makers differ.
- **The pinned notification is not built.** Requirements 2 and the notification parts of 3 to
  7 are open (P012f2).
- **The emergency screen is plain:** a dialog on an empty surface in the app theme. Its window
  theme is light; in dark mode a light flash before the first frame is possible.
- **On Android 14 and newer the tile's tap is not exercised by a test**, only the choice of
  launch.
- **The app does not know whether the tile is added**, so Settings always offers to add it.
- **If the dialer asks for the unlock on some phones**, the shortcut from the lock screen costs
  an extra step there. Nothing in the app can change that.
- **PR size** is over the 800-line guide.

## 10. Follow-ups & prerequisites for next prompt

Notion *Follow-ups*, new (source P012f1):

- P012f2: pinned emergency notification, permission flow, re-post after boot or update (High).
- Phone check of the SOS tile, with Android version and phone maker (High).
- Bengali review: the SOS tile and its Settings texts.
- Decide the look of the emergency screen opened from a shortcut (Low).

Notion *Prompt Log*, P014 page: the four failure-matrix rows and what exists now.

**Next: P012f2** (proposed branch `feat/012f2-emergency-notification`). Also open: P011e2,
P012c2. None is started.

## 11. How Rahul can verify

1. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug`.
2. CI green, squash and merge. Nothing deploys.
3. On the phone. **Write down the Android version and the phone maker**, and what differs:
   1. Settings → "Add the SOS tile". On Android 13 or newer a system dialog offers the tile;
      accept. (On an older phone, or if you refuse, four steps are shown: follow them.)
   2. Pull down Quick Settings: "SafeRoute SOS" is there with a triangle icon. Tap it with the
      screen on: the emergency dialog appears. "Call 112" opens the phone app showing 112.
      **Do NOT press call.** Go back.
   3. **Lock the phone.** Pull down Quick Settings on the lock screen, tap the tile. Expected:
      the dialog appears without unlocking. Tap "Call 112": note whether the dialer appears at
      once or the phone asks for the unlock first. Do not press call. Cancel: you are back on
      the lock screen and nothing of the app is visible.
   4. Tap the tile, then Cancel: the app's main screen did not open, and SafeRoute is not in
      the recent-apps list because of it.
   5. Tap "Add the SOS tile" again: it says the tile is already there.
   6. Dark mode, then Bengali (system language): tile label, dialog and Settings texts.
   7. Battery saver on: the tile still works.
   8. Reboot the phone: the tile is still in Quick Settings and works before the app was opened.
   9. Force-stop SafeRoute (system Settings → Apps → SafeRoute → Force stop), then tap the
      tile: note whether it opens (expected: yes, a tap is the user starting the app).
   10. Remove the tile in the panel's edit mode: nothing breaks; Settings can add it again.

## 12. Learning notes

- **Quick Settings tile and `TileService`.** A tile is a button in the panel under the status
  bar. An app offers one by declaring a `TileService`; the user decides whether to add it.
  Despite the name it is not a long-running service: Android connects to it for a moment when
  the tile is shown or tapped.
  [Quick Settings tiles](https://developer.android.com/develop/ui/views/quicksettings-tiles)
- **`Intent` and `PendingIntent`.** An `Intent` says "start this" and is used now. A
  `PendingIntent` is a sealed envelope with an `Intent` inside that is handed to the system to
  open later, with this app's identity. "Immutable" means nobody can change what is inside.
  From Android 14 a tile must use the envelope.
- **Explicit intents and `exported`.** An explicit intent names the exact class to start.
  `exported=false` means only this app can start that component. Together they make sure the
  shortcut can only lead to SafeRoute's own screen, and that no other app can open it.
- **The lock screen.** A normal activity is hidden behind the lock. An activity may ask to be
  shown on top of it (`showWhenLocked`): alarm clocks and incoming calls do this. It does not
  unlock the phone; whatever it starts next is behind the lock again unless that screen also
  asks. That is why only a screen with nothing private on it may use it.
- **A permission a component requires.** `android:permission` on a service is the reverse of
  `<uses-permission>`: it says who may connect to it. `BIND_QUICK_SETTINGS_TILE` is held only
  by the system, so only the system can talk to the tile service.
- **Why a seam for the tile's launch.** The system part of a tap cannot run in a JVM test, so
  the decision ("which kind of launch, to where") lives in a plain function that can.
