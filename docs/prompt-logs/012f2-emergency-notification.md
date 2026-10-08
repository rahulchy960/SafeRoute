# P012f2: emergency shortcuts, part 2: the opt-in pinned notification

| Field | Value |
| --- | --- |
| Prompt | P012 · part f, second half (**P012f2 the pinned notification**; P012f1 was the tile and the shared destination). P012c2 and P011e2 are still open |
| Milestone | M4 (depends on P012f1, merged as `13fc481`) |
| Branch | `feat/012f2-emergency-notification` |
| PR title | `feat(android): opt-in pinned emergency notification with 112 and SOS actions [P012f2]` |
| Notion | [P012f2 row in the Prompt Log](https://app.notion.com/p/3f3073707720816aa38aff960a7e8605) |
| Date | 2026-10-08 |
| Plan refs | Plan v7 §5.3, §7.1, §7.5; addendum v7.1 B.1; ADR 0008 |

> **Nothing ran on a phone.** A real notification shade, the lock screen, a reboot, an app
> update, a force-stop and phone makers' battery managers cannot be exercised in JVM tests.
> What is tested: what is in the notification, when the app decides to show or remove it, and
> the permission flow, with fakes for Android's answers. The manual checks in section 11 are
> for Rahul's phone.

## 1. Objective

The second emergency shortcut outside the app: an optional pinned notification with "Call 112"
and "Open SOS". Off until the user turns it on; honest about being removable; no foreground
service; put back when the app opens, after a restart and after an update.

## 2. Context & prerequisites

- P012f1 merged (PR #38, `13fc481`): `EmergencyShortcut`, `EmergencyActivity`, the tile. No open
  pull requests. Clean tree, hooks active.
- The spike of P012f1 (its log, section 7) covers the rules used here. Two points it left
  open were read now in the platform sources of the installed SDK (android-37): a channel of
  low importance "shows in the shade ... but is not audibly intrusive"; and a channel's
  lock-screen visibility can be changed "only by the system and notification ranker", so the
  app sets visibility on the notification, not on the channel.
- The Plan PDF cannot be read on this machine; the P012f prompt text is the specification.

## 3. Workflow executed

1. `/start-prompt`: main synced, PR #38 confirmed merged, P012f1 set to Merged in Notion,
   branch, Notion page.
2. Read the DataStore module, the Application class, Settings and Home.
3. Notification, controller, receiver, Settings state machine, offer, wiring; tests (`47a18bd`).
4. The gate and the release build.
5. ADR 0008 note, diagram, P014 notes in Notion, this log; `/ship-prompt`.

## 4. Changes

**`feature/emergency`**

- `EmergencyNotification.kt`: the channel (low importance, no sound, vibration, lights or
  badge), the notification (fixed title and text, ongoing, public, two actions), the
  `EmergencyNotifier` that posts and removes it, and the `NotificationGate` that says whether
  Android allows it (permission, app switch, category).
- `EmergencyNotificationController.kt`: the two stored flags (`EmergencyShortcutPreferences`,
  in the app's one DataStore file); the controller with the one rule; the
  `EmergencyShortcutReceiver` for `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.
- `ShortcutOffer.kt`: the one-time offer and its ViewModel.

**Settings screen:**

- A switch "Emergency shortcut in notifications" with its state in words, the limits in
  words, an explanation dialog, a notice per kind of refusal, and "Open system settings".
- `EmergencyShortcutsViewModel`: the permission state machine.

**App start and Home:**

- `SafeRouteApplication.onCreate` starts the controller following the switch.
  `MainActivity.onStart` applies the rule once more (the "app opened" re-post).
- `HomeRoute`: after the emergency dialog is closed for the first time, the offer.

**Manifest (`AndroidManifest.xml`):**

- `POST_NOTIFICATIONS` and `RECEIVE_BOOT_COMPLETED`; one `<receiver>`, `exported=false`, with
  the two actions. No foreground service, no other permission.

**Resources:** 27 strings in English and Bengali. No new icon (the tile's is reused).

**Not changed:** the tile, `EmergencyActivity`, the in-app SOS control and dialog,
`HomeViewModel`, dependencies (the platform's own notification classes are used), any backend,
contract or workflow file.

**Size:** about 1,730 changed lines in `android/`, about 830 of them tests. Well over the
800-line guide: this half is one feature whose parts (notification, permission flow,
re-posting, offer) do not ship usefully apart.

## 5. Diagram

[`docs/diagrams/012f-emergency-shortcuts.svg`](../diagrams/012f-emergency-shortcuts.svg),
updated: the notification is no longer grey; "Open SOS" joins the tile's path, "Call 112" goes
straight to the dialer. Still grey: P014's countdown.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` (in `android/`) | BUILD SUCCESSFUL; 625 tests, 0 failures (592 before); lint 0 errors |
| `./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://example.invalid/` | BUILD SUCCESSFUL |
| `tools/diagrams` `pnpm generate` | no errors |
| markdownlint, JSON validity, gitleaks, forbidden files | 97 files, 0 errors · tracked JSON and the diagram files valid · no leaks · none |
| `git ls-files android \| grep -iE "local.properties\|\.jks\|google-services\.json"` | prints nothing |

New tests (33):

- `EmergencyNotificationTest` (14):
  - the channel is silent and of low importance;
  - the notification is ongoing, public, not a foreground-service notification, and every
    text in it is a fixed resource;
  - "Call 112" is an activity `PendingIntent` for `ACTION_DIAL` `tel:112` (never
    `ACTION_CALL`), "Open SOS" and a tap lead to `EmergencyActivity`, all immutable;
  - posting twice shows one notification, cancel removes it, nothing is started or logged;
  - Android 13: the permission decides, then the app switch, then the category; Android 12:
    no permission to ask for;
  - the rule for all eight combinations of switch and Android's answer;
  - app open, restart and update use the same rule; with the switch off nothing is posted;
  - the controller follows the switch (off, or a cleared store, removes it at once);
  - the receiver answers to exactly two actions;
  - only two flags are stored, both booleans;
  - the offer appears once, and not when the shortcut is already on.
- `SettingsShortcutsTest` (13 new): opening Settings requests and posts nothing; the
  explanation before any request; granted; denied once and denied for good; app or category
  blocked; already allowed; turning off; on but blocked later; the switch row (role, state,
  48 dp, the limits text); the explanation dialog; each refusal's words; Bengali at font
  scale 2.0.
- `EmergencyShortcutFlowTest` (5, the real `MainActivity` with Hilt): launching offers,
  requests and posts nothing; the offer after the first SOS use, once, leading to Settings;
  "Not now"; the switch down to "posted" and off again; `POST_NOTIFICATIONS` requested exactly
  once, after "Continue".
- `EmergencyShortcutsManifestTest`: two tests changed or added (the two permissions and no
  others; one unexported receiver with two actions; still no foreground service).
- `MainActivityTest`: the exact permission list now has the two new entries.

**What these tests cannot see:** the notification as Android draws it; the lock screen; that
the two broadcasts are really delivered after a restart and an update; what a force-stop or a
battery manager does; Android's permission dialog itself.

**SOS failure matrix (Plan v7 §7.5).** No SOS logic exists yet. The P014 notes in Notion were
extended for "pinned notification dismissed or killed" and "notification permission denied".
Manual scripts for both are in section 11 (steps 6 to 10 and 3 to 4).

## 7. Decisions & ADRs

No new ADR. [ADR 0008](../adr/0008-android-foundation.md)'s note of 2026-10-08 gained "Built
in P012f2".

Choices made here, for Rahul to confirm. Each goes beyond the prompt's wording:

- **One rule, one function.** "Shown if the switch is on and Android allows it" is applied at
  app open, at boot, at update and when the switch changes. There is no separate code for
  "re-post": posting again replaces the notification silently.
- **Signing out turns the shortcut off.** The two flags are in the app's one DataStore file,
  which sign-out clears. The controller sees the switch go off and removes the notification.
  A follow-up asks whether it should survive sign-out instead.
- **The platform's notification classes, not AndroidX's.** `minSdk` is 26, so channels always
  exist, and no dependency had to be added or declared.
- **"Call 112" in the notification goes straight to the dialer**, not through the emergency
  screen: one tap less, and still only `ACTION_DIAL`.
- **A second line of fixed text** ("Tap for the emergency options.") under the title the
  prompt gave. A notification with a title only looks unfinished on some phones.
- **"First successful use" of the SOS control** is read as: its dialog was opened and then
  closed, by "Call 112" or by Cancel. The offer is shown on Home only, not after SOS on the
  search screen.
- **The offer leads to Settings and requests nothing itself**, so the explanation and the
  permission request stay in one place.
- **A refusal for good is inferred**, as for location (ADR 0015): Android's dialog came back
  without the permission and without "should explain" being true. A dialog that was only
  swiped away looks the same; the words then offer system settings, and turning the switch on
  again still asks Android, which shows its dialog if it still can.
- **Blocked at turn-on keeps the switch off**; blocked later keeps it on and says so, with a
  button to system settings.
- **No notification category** is set: the available ones describe calls, alarms or running
  services, none of which this is.
- **`LOCKED_BOOT_COMPLETED` is not handled.** The stored switch is not readable before the
  first unlock after a restart, so the notification appears once the phone is unlocked.

## 8. Security & privacy notes

- **Two new permissions**, both explained in the manifest:
  - `POST_NOTIFICATIONS` (Android 13+, asked at runtime): requested only after the user
    turned the switch on and continued past the app's own explanation. Never at launch or in
    onboarding; a test starts the app and checks that nothing was requested.
  - `RECEIVE_BOOT_COMPLETED` (granted at install): lets the app put the notification back
    after a restart. The receiver does nothing when the switch is off.
- **No personal data.** The notification holds fixed text. No name, number, position, state
  or count; a test compares every text with its resource. It is shown in full on the lock
  screen for that reason.
- **Nothing runs in the background.** No foreground service, no alarm, no job. The receiver
  runs for a moment at boot and after an update, reads one flag, posts or not, and ends. It is
  not exported and starts no activity or service.
- **PendingIntents** are immutable and each starts one activity directly.
- **Stored:** two booleans in DataStore. **Logged:** nothing (a test captures Logcat).
- **112:** `ACTION_DIAL` only; the app has no `CALL_PHONE` permission.
- **To state in the privacy policy and the Play listing** (follow-up, **to be verified by a
  lawyer**): the two permissions and what they are used for.
- Bengali strings written by Claude Code, **needing human review before release:** the 27 new
  `emergency_notification_*`, `settings_notification_*` and `shortcut_offer_*` strings.
- No secrets, keys or local paths in the diff.

## 9. Known issues & risks

- **Not seen on a phone** (top of this log).
- **It can disappear, and the app does not notice.** Until the app is opened again, a swiped
  or removed notification stays gone. This is said in Settings and in the explanation.
- **After a force-stop nothing comes back** until the user opens the app (Android's rule).
- **Phone makers' battery managers** may stop the boot broadcast from reaching the app at all.
- **"Call 112" from the lock screen** may ask for the unlock first on some phones.
- **Before the first unlock after a restart** the notification is not there.
- **The Settings list is longer than a screen now**; three older tests had to scroll.
- **Two entry points now exist that P014 must keep working** when it changes the destination.
- **PR size** (section 4).

## 10. Follow-ups & prerequisites for next prompt

Notion *Follow-ups*, new (source P012f2):

- Phone check of the pinned emergency notification, with Android version and phone maker
  (High).
- Bengali review: the emergency notification and its Settings texts.
- Privacy policy and Play Data safety: the notification permission and the boot receiver.
- Decide whether the emergency shortcut switch should survive sign-out (Low).

Closed: "P012f2: pinned emergency notification ..." (source P012f1). Notion *Prompt Log*, P014
page: notes on the notification and the two failure-matrix rows.

**Next:** P011e2 (Android search by position) or P012c2 (follow-me); neither is started.

## 11. How Rahul can verify

1. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug`.
2. CI green, squash and merge. Nothing deploys.
3. On the phone. **Write down the Android version and the phone maker**, and what differs:
   1. Open the app: no notification, no permission dialog, no offer.
   2. Tap the SOS control, then Cancel: the offer "Add an emergency shortcut?" appears, once.
      "Open Settings" leads to Settings. (Use SOS again later: no second offer.)
   3. Settings → turn "Emergency shortcut in notifications" on: the explanation appears.
      Continue: on Android 13 or newer the system asks for the permission. **Deny**: the app
      says the shortcut is off; the switch is off.
   4. Turn it on again, Continue, **Allow**: the switch is on, and a silent notification
      "SafeRoute emergency shortcut" is in the list. No sound, no vibration.
   5. Tap "Call 112" on it: the phone app opens showing 112. **Do NOT press call.** Go back.
      Tap "Open SOS": the emergency dialog. Cancel.
   6. **Lock the phone**: the notification is visible on the lock screen. Try both buttons;
      note whether the phone asks for the unlock.
   7. **Swipe the notification away** (possible on Android 14 or newer). Open SafeRoute: it is
      back. The switch never went off.
   8. **Reboot**. After unlocking, note whether the notification is back without opening the
      app, and how long it took.
   9. **Force-stop** SafeRoute (system Settings → Apps → SafeRoute → Force stop): the
      notification goes and stays away. Open the app: it is back.
   10. Battery saver on, and your phone's own battery manager if it has one: after an hour,
       and after a night, is it still there?
   11. System Settings → Apps → SafeRoute → Notifications: switch the category "Emergency
       shortcut" off. Back in SafeRoute's Settings: the row says it is on but not shown, with
       a button to system settings. Switch the category on again: it comes back.
   12. Turn the switch off: the notification goes at once.
   13. With the switch on, sign out: the notification goes. Sign in again: the switch is off.
   14. Install a newer build over this one (an update): note whether the notification is back
       without opening the app.
   15. Dark mode, then Bengali: the notification, the explanation and the Settings texts.

## 12. Learning notes

- **Notification channel.** Since Android 8 every notification belongs to a channel, which is
  what the user sees under the app's notification settings and can silence or switch off one
  by one. The app sets a channel's importance when it creates it; afterwards only the user can
  change it. That is why "the category is off" is a state the app must read, not decide.
  [Notification channels](https://developer.android.com/develop/ui/views/notifications/channels)
- **Runtime permission for notifications.** Since Android 13 an app must ask before it may
  show any notification, like for location. The good moment is when the user has just asked
  for something that needs it, here by turning the switch on.
  [Notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- **"Ongoing" is a request, not a lock.** It tells Android that the notification stands for
  something that lasts. Since Android 14 the user can swipe such a notification away anyway
  (not on the lock screen). Only a foreground service keeps one in place, and that would mean
  a part of the app running all the time, which this shortcut does not justify.
- **`BroadcastReceiver`.** A small class Android calls when something happens on the phone,
  here "the phone has started" and "this app was updated". Android starts the app's process
  for it if needed and gives it a few seconds. `goAsync()` lets it finish a short piece of
  work off the main thread before it reports back.
  [Broadcasts](https://developer.android.com/develop/background-work/background-tasks/broadcasts)
- **No "trampoline".** A notification button may not start a receiver that then opens a
  screen; it must carry a `PendingIntent` for the screen itself. Both buttons do.
- **Idempotent.** `sync()` can be called any number of times with the same result: the
  notification is there or it is not. That is why the same call serves app open, boot, update
  and the switch, and why calling it once too often is harmless.
- **An entry point outside Hilt's usual places.** A receiver is created by Android, not by
  Hilt. `EntryPointAccessors.fromApplication` is the door through which such a class reaches
  the objects Hilt holds.
  [Hilt entry points](https://developer.android.com/training/dependency-injection/hilt-android#generated-components)
