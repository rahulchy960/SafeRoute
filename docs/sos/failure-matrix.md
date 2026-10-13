# SOS failure matrix

What must still work when things go wrong during an SOS: the rows of Plan v7 §7.5, of
[addendum v7.1](../plan/addendum-v7.1.md), section B, and rows added since. For each row:
what is expected, where it is built, which tests cover it, which
[manual script](manual-test-scripts.md) covers it on a phone, and an honest status.

**This is a living document.** Every prompt that touches SOS, emergency contacts, live
sharing or reminders updates it: tests added, statuses changed, scripts changed.

- SafeRoute is not an emergency service. Every SOS screen offers Call 112.
- **Nothing in this document was run on a phone by Claude Code**, which has none. A manual
  result is written only when a person reported it.
- **No SMS alert has been reported as sent or received by anyone.** See
  [Device results](#device-results).

## How to read the status

| Status | Meaning |
| --- | --- |
| Not started | Nothing built and no test |
| Automated pass | Tests named under [Evidence](#evidence) exist **and** the latest `android-ci` run on `main` is green. The tests run on the JVM (Robolectric), never on a phone |
| Manual pending | A person has to run the script on a phone, and no result is reported |
| Manual pass | A person ran the script and reported that it passed (see Device results) |
| Fail | A test or a reported manual run failed |
| Waiting on P015 | The row, or a part of it, needs the server orchestration of P015 |
| Waiting on P014c | Used until P014c3 for rows that needed the tile, the widget, the notification shortcut, the readiness screen or the notice after a restart. No row carries it any more |

A row can carry more than one status, because its parts are in different states: the logic
can be tested while the behaviour of a real phone is not, and a part can be unbuilt. A row
is **done** only at "Manual pass". "Automated pass" says that the logic holds in tests; it
says nothing about a phone maker's battery manager, a SIM, a network or a lock screen.

Levels named in the notes: *data* = the records in Room; *logic* = the runner, the trail,
the alerts and the engine, with fakes for the phone's parts, in virtual time; *component* =
the real service, notification or job under Robolectric; *screen* = the Compose screens and
their ViewModels.

**Evidence for "Automated pass" on this date:** `android-ci` run 38321661199 on `main`
(commit `5bd7092`, 2026-10-12): success. Tests added in P014c3 (the notice after a restart,
sign-out during an SOS) are not on `main` yet and are marked so under [Evidence](#evidence). An earlier run on `main` failed once on a flaky
test and passed when run again (P014b5).

## State of the build

**The device side of SOS is complete with P014c3 (2026-10-13).** What an SOS does on the
phone alone is built and covered by tests that run on the JVM: starting it (the app, the
tile, the widget, the notification), the countdown, the alerts by SMS and their retries,
the location record, recovery after the app was closed or the phone restarted, the notice
after a restart, the checklist beforehand, and what happens when the account goes away
during an SOS. **What remains:**

- **P015:** the server half (telling the server, one session per SOS, push messages). Rows
  1, 3, 9 and 10 wait for it.
- **P016:** the live-location link and its viewer. Rows 1 and 8 wait for it.
- **Manual results.** No script of this matrix has been run in full on a phone, so **no
  row is "Manual pass" and no row is done**. One phone has reported a part of one script
  (Device results). The launch rule asks for three phones.
- The drafts for a lawyer and a native speaker (the notice, the SMS texts, the Bengali),
  and the open points under Known gaps.

A user who has agreed to the SOS alerts notice (version 4 since P014c2b; a consent for
an older version no longer counts) can start an SOS from the SOS control in the app (hold for 2
seconds), and with one tap from the Quick Settings tile, from the "Start SOS" button of
the pinned notification (both also on a locked phone) or, since P014c1b, from the
home-screen widget "SafeRoute SOS". Every one of them goes through the same
use case, the same consent check and the same five-second countdown. **It messages their emergency contacts by SMS**:
by itself in a build with the SMS permission once the user allowed it (debug builds by
default), otherwise through the phone's SMS app. Without that consent no SOS can be
started at all: the app and the shortcuts then offer Call 112, a practice run and the way
to the setup. Since P014c2a a checklist, "SOS setup" under Emergency contacts, says before
an emergency what an SOS from this phone would and would not do: "Ready" or "Not ready: N
things to fix". Since P014c2b the user can set there a name for the alert, its language
and a sound for the countdown (off by default). Since P014c2c the checklist is offered once
on the contacts screen after the first contact, and Home shows "Not ready: N things to
fix" while something is to fix. Since P014c3 an SOS that was active when the phone
restarted is announced by a notification without opening the app, and signing out is
refused while an SOS is going on. No server takes part in an SOS yet (P015), and there is no live-location
link (P016). **A debug build texts only the test numbers it was built with** (P014b6): with
no such list it sends nothing by itself and uses the SMS app, and a banner on the SOS
screens says which. **An alert that cannot be sent is tried again for as long as the SOS is
active** (P014b7): the user is told once after two hours, and trying stops 24 hours after
the trigger.

## The matrix

Rows 1 to 11 are Plan v7 §7.5. Rows A1 to A4 are from addendum v7.1, section B (SOS entry
points). Row A5 is from ADR 0024. Rows A6 to A12 were added in P013c; the plan has no text
for them, so their expected behaviour is the design recorded in
[ADR 0027](../adr/0027-sos-device-flow.md), and says so where the design is silent. Row A13
was added in P014b6, rows A14 and A15 in P014c3.

| ID | Scenario | Expected behaviour | Implemented in | Automated tests | Manual script | Status | Last result date | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | No mobile data, signal present | The alert goes out by SMS from the phone itself, with no data connection. Telling the server waits until the phone is online; the SMS with the live-location link follows once the server knows the SOS. | P014b1, P014b2, P014b3 (SMS from the phone). P015 (telling the server), P016 (the live link): not built | `SosCoreBoundaryTest`, `SosAlertsTest`, `RoomSosActionStoreTest`, `SosSmsTest`, `SosMessageTest`, `SmsSegmentsTest`, `SosSmsTextDocumentTest`, `SosDispatchTest`, `SosAlertLinesTest`, `SosViewModelTest`, `SosAlertsDeviceTest`, `ApiContactsRepositoryTest`, `SosFlowTest` ([names](#row-1-no-mobile-data-signal-present)) | [M0](manual-test-scripts.md#m0-set-up), [M11](manual-test-scripts.md#m11-automatic-sending), [M12](manual-test-scripts.md#m12-mobile-data-off-signal-present), [M28](manual-test-scripts.md#m28-the-live-location-link-by-sms) | Automated pass · Manual pending · Waiting on P015 | 2026-10-12 (automated); manual: none | Automated for the SMS path, at data, logic, component and screen level. No message has been sent on a phone by anyone. The server half of the row does not exist. |
| 2 | No signal at all | The SOS runs on the phone alone. Every SOS screen offers Call 112. The record stays on the phone, and an alert that has not left is tried again for as long as the SOS is active: every 30 seconds at first, then every 5 minutes, and at once when the phone leaves airplane mode, a network returns, or the user asks. | P014a1, P014a2, P014a3, P014b1, P014b2, P014b7 | `SosCoreBoundaryTest`, `SosDeviceTest`, `SosRunnerTest`, `SosScreensTest`, `SosAlertsTest`, `SosSmsTest`, `SosDispatchTest` ([names](#row-2-no-signal-at-all)) | [M3](manual-test-scripts.md#m3-full-countdown-and-locking-the-phone), [M5](manual-test-scripts.md#m5-airplane-mode-and-location-off), [M22](manual-test-scripts.md#m22-every-message-fails) | Automated pass · Manual pending | 2026-10-12 (automated); manual: none | Automated at logic, component and screen level. The retry tests of P014b7 are on `main` and ran green there (run 38285365927). What a phone reports with no signal is not known (Known gaps). |
| 3 | API down or 5xx | The SOS on the phone is not affected. The phone keeps trying to tell the server, and repeated tries create one session, not several. | P015: not built. Today an SOS uses no server | None | [M24](manual-test-scripts.md#m24-the-saferoute-api-is-down) | Waiting on P015 | None | That the SOS path holds no network code is tested (row 2, `SosCoreBoundaryTest`); nothing of the server side exists. |
| 4 | App swiped away or killed by the OEM | The foreground service keeps the SOS running where the phone allows it. If the process dies, the state is read from the record on the phone when the app is next opened, and the SOS goes on. | P014a1, P014a2, P014a3. P014c2a (guidance about battery limits, before an emergency) | `SosEngineTest`, `SosRecoveryTest`, `SosRunnerTest`, `SosForegroundServiceTest`, `AndroidSosHostTest`, `SosViewModelTest`, `SosFlowTest`, `SosScreensTest`, `SosSetupScreenTest`, `SosSetupRulesTest` ([names](#row-4-app-swiped-away-or-killed-by-the-oem)) | [M4](manual-test-scripts.md#m4-swipe-the-app-away-while-active), [M6](manual-test-scripts.md#m6-kill-during-the-countdown-and-reboot), [M30](manual-test-scripts.md#m30-the-sos-setup-checklist) | Automated pass · Manual pending | 2026-10-12 (automated); manual: none | Automated at data, logic, component and screen level. Whether the service survives a swipe from recents or a phone maker's battery manager can only be seen on phones. Since P014c2a "SOS setup" tells the user that the app cannot see what the phone's battery manager does, with starting points per phone maker; those are drafts, not checked on the phones. |
| 5 | Phone rebooted during SOS | At boot or when the app is opened, the SOS that was running is found in the record and the user is asked whether to continue or to end it. After a restart a notification says so without the app being opened; it starts nothing by itself. | P014a1, P014a2, P014a3 (when the app is opened). P014c3 (the notice after a restart) | `SosRecoveryTest`, `SosEngineTest`, `SosRunnerTest`, `SosViewModelTest`, `SosScreensTest`, `SosRestartNoticeTest`, `SosSafeFromNotificationTest` ([names](#row-5-phone-rebooted-during-sos)) | [M6](manual-test-scripts.md#m6-kill-during-the-countdown-and-reboot), [M27](manual-test-scripts.md#m27-the-notice-after-a-restart) | Automated pass · Manual pending | 2026-10-12 (automated); manual: none | For the tests a reboot is a process death with a later clock. The notice after a restart is built in P014c3; its tests count once `android-ci` is green on `main` with them. Whether a phone delivers the restart broadcast to the app is not known (Known gaps). |
| 6 | Double tap or duplicate trigger | One emergency, identified by one id made on the phone. A second trigger shows the one that exists and starts nothing new. | P014a1, P014a2, P014a3, P014b1, P014c1a (a second start from another entry point) | `SosEngineTest`, `SosRunnerTest`, `SosViewModelTest`, `SosScreensTest`, `SosAlertsTest`, `StartSosTest` ([names](#row-6-double-tap-or-duplicate-trigger)) | [M2](manual-test-scripts.md#m2-cancel) | Automated pass · Manual pending | 2026-10-12 (automated); manual: none | One accepted exception: a message handed to the phone whose answer was lost in a process death is tried again, so that contact may get the alert twice (ADR 0027). |
| 7 | SEND_SMS denied or not approved | The phone's SMS app opens with the recipients and the alert filled in; the user presses Send. Nothing is recorded as sent. | P014b1 (the build switch), P014b2 (the SMS app), P014b3 (the permission card), P014c2a (the checklist says which way of sending this phone uses) | `SosSmsTest`, `SosAlertsTest`, `MainActivityTest`, `SosDispatchTest`, `SosAlertsDeviceTest`, `SosAlertLinesTest`, `SosViewModelTest`, `SmsPermissionCardTest`, `SosReadinessTest`, `SosSetupViewModelTest`, `SosSetupScreenTest` ([names](#row-7-send_sms-denied-or-not-approved)) | [M0](manual-test-scripts.md#m0-set-up), [M13](manual-test-scripts.md#m13-the-sms-app-instead), [M14](manual-test-scripts.md#m14-refusals), [M30](manual-test-scripts.md#m30-the-sos-setup-checklist) | Automated pass · Manual pending | 2026-10-12 (automated); manual: none | Also checked in CI: the release APK does not ask for SEND_SMS. Which separator an SMS app accepts for several recipients is not verified (Known gaps). |
| 8 | No GPS fix | The last known position is used, with its age, and marked as old in the SMS. (The viewer of the live link is P016.) The SOS never waits for a position. | P014a2 (the trail), P014a3 (the screen), P014b1 (the text), P014b2 (the wait of at most 3 seconds). P016 (the viewer): not built | `SosTrailTest`, `SosRunnerTest`, `SosScreensTest`, `SosViewModelTest`, `SosMessageTest`, `SosDispatchTest` ([names](#row-8-no-gps-fix)) | [M5](manual-test-scripts.md#m5-airplane-mode-and-location-off) | Automated pass · Manual pending | 2026-10-12 (automated); manual: none | Automated at logic and screen level. Real GPS behaviour (indoors, a cold start) can only be seen on phones. |
| 9 | FCM failure | The push is recorded as failed and to be retried, and is tried again. The SMS has already reached the contacts. | P015: not built | None | [M25](manual-test-scripts.md#m25-push-messages-fail) | Waiting on P015 | None | No push message exists yet. |
| 10 | Worker crash mid-action | The job is delivered again; the call to the provider carries the action's idempotency key, so no contact gets a second push. | P015: not built | None | [M26](manual-test-scripts.md#m26-a-server-worker-stops-mid-action) | Waiting on P015 | None | A server check, not a phone script. |
| 11 | Accidental trigger | Cancel during the countdown ends it and sends nothing. After the alert went out there is no Cancel: "I'm safe" ends the SOS and tells the contacts who were alerted. | P014a1, P014a2, P014a3, P014b2 | `SosStateMachineTest`, `SosEngineTest`, `SosRunnerTest`, `SosForegroundServiceTest`, `SosScreensTest`, `SosViewModelTest`, `SosEmergencyScreenTest`, `SosMessageTest`, `SosAlertsTest`, `SosDispatchTest`, `SosAlertLinesTest` ([names](#row-11-accidental-trigger)) | [M1](manual-test-scripts.md#m1-hold-to-arm-and-early-release), [M2](manual-test-scripts.md#m2-cancel), [M3](manual-test-scripts.md#m3-full-countdown-and-locking-the-phone), [M8](manual-test-scripts.md#m8-practice), [M9](manual-test-scripts.md#m9-do-not-disturb-bengali-dark-mode-large-font-talkback) | Automated pass · Manual pending | 2026-10-12 (automated); manual: none | Automated at data, logic, component and screen level. |
| A1 | SOS started from the tile while the phone is locked | The tile starts the same five-second countdown as the app, shown over the lock screen with Cancel and Call 112. Nothing of the account is shown there. | P012f1 (the tile), P014a3 (the emergency screen over the lock screen), P014c1a (the tile starts the countdown through the one use case), P014c1b (the home-screen widget) | `EmergencyShortcutTest`, `SosTileServiceTest`, `EmergencyActivityTest`, `EmergencyShortcutsManifestTest`, `SosScreensTest`, `SosAlertLinesTest`, `StartSosTest`, `SosViewModelTest`, `SosEmergencyScreenTest`, `SosWidgetTest` ([names](#row-a1-sos-started-from-the-tile-while-the-phone-is-locked)) | [M3](manual-test-scripts.md#m3-full-countdown-and-locking-the-phone), [M17](manual-test-scripts.md#m17-sos-from-the-tile-or-the-widget-while-the-phone-is-locked) | Automated pass · Manual pending | 2026-10-13 (automated); manual: none | The tests of the countdown from the tile (P014c1a) are on `main` since `5e57495`, and `android-ci` ran green there (run 38296767979). The widget's tests (P014c1b) are on `main` since `a16c443` and ran green there (run 38301695368). On one phone the widget was reported as listed, 4 cells, not resizable and not offered for the lock screen (Device results); taps on it are not reported. The row names the tile; the widget is recorded here because it is the same start from outside the app, and it is offered for the home screen only, not the lock screen. The tests run on the JVM: that a phone shows the countdown over its lock screen, and that the location service starts there, can only be seen on phones. Observed on one phone before this change (Device results): the dialog opened from the lock screen. With SOS alerts not set up the tile and the widget show Call 112 and the way to the setup, and no countdown. |
| A2 | Pinned notification dismissed or killed | The SOS still starts from the tile and from the app. The notification is posted again when the app is next opened and after a restart. | P012f2, P014c1a (its button "Start SOS") | `EmergencyNotificationTest`, `SosFlowTest`, `SosEmergencyScreenTest` ([names](#row-a2-pinned-notification-dismissed-or-killed)) | [M17](manual-test-scripts.md#m17-sos-from-the-tile-or-the-widget-while-the-phone-is-locked), [M18](manual-test-scripts.md#m18-the-pinned-notification-dismissed-or-killed) | Automated pass · Manual pending | 2026-10-12 (automated); manual: none | Automated for posting again. Starting an SOS from the notification (P014c1a): its tests are on `main` and ran green there (run 38296767979). Observed on one phone (Device results): posted again after a swipe, a restart and a force stop. That was reported before these scripts existed, so the row stays Manual pending. |
| A3 | Tile unavailable on the device | Settings explains how to add the tile by hand and says that some phones have none. The SOS control in the app always works. | P012f1 | `SettingsShortcutsTest`, `EmergencyShortcutTest`, `SosFlowTest` ([names](#row-a3-tile-unavailable-on-the-device)) | [M19](manual-test-scripts.md#m19-a-phone-without-the-tile) | Automated pass · Manual pending | 2026-10-12 (automated); manual: none | Not seen on a phone without tiles from apps. |
| A4 | Notification permission denied | No notification and no error. An SOS never depends on a notification; the active screen says that it is the only sign. | P012f2, P014a2, P014a3 | `EmergencyNotificationTest`, `SettingsShortcutsTest`, `SosScreensTest`, `SosViewModelTest` ([names](#row-a4-notification-permission-denied)) | [M7](manual-test-scripts.md#m7-notifications-refused) | Automated pass · Manual pending | 2026-10-12 (automated); manual: none | Automated at logic and screen level. |
| A5 | Opted-out contact excluded | A contact who opted out is never alerted (ADR 0024). | P013a (the opt-out), P013b1 (the copy on the phone), P014b1, P014b2 | `ContactsDatabaseTest`, `SosAlertsTest`, `SosDispatchTest`, `SosAlertsDeviceTest`, `ApiContactsRepositoryTest` ([names](#row-a5-opted-out-contact-excluded)) | [M16](manual-test-scripts.md#m16-a-contact-who-opted-out) | Automated pass · Manual pending | 2026-10-12 (automated); manual: none | Known gap: with a position ready the alert leaves at once, so an opt-out made shortly before the trigger and not yet on the phone is missed (ADR 0027). |
| A6 | Contacts empty at SOS time | The SOS runs as always. The active screen says that there is no emergency contact to alert and offers Call 112; nothing is sent and nothing fails. Before an emergency, "SOS setup" says that no contact would be texted. | P014b1, P014b2, P014c2a (the checklist) | `SosAlertsTest`, `SosDispatchTest`, `SosViewModelTest`, `SosAlertLinesTest`, `ContactsDatabaseTest`, `SosReadinessTest`, `SosSetupViewModelTest`, `SosSetupScreenTest` ([names](#row-a6-contacts-empty-at-sos-time)) | [M20](manual-test-scripts.md#m20-no-emergency-contact), [M30](manual-test-scripts.md#m30-the-sos-setup-checklist) | Automated pass · Manual pending | 2026-10-12 (automated); manual: none | The plan has no row for this; the expected behaviour is the design of ADR 0027. The checklist's tests (P014c2a) are on `main` since `aff95a5` and ran green there (run 38306321302). Since P014c2c Home shows "Not ready" for this case once the user agreed to the notice; those tests are on `main` since `5bd7092` and ran green there (run 38321661199). |
| A7 | Contacts never synced to the phone | An SOS reads the contacts from the phone only and never waits for the server. If the list never arrived, nobody is alerted and the screen says so. The user should learn of this before an emergency (readiness screen). | P013b1 (the copy and its sync), P014b2 (the refresh that is started and never awaited), P014b3 (the consent note). P014c2a (the checklist, with the time the list was last fetched) | `ApiContactsRepositoryTest`, `ContactsSessionSyncTest`, `SosDispatchTest`, `SosReadinessTest`, `SosSetupViewModelTest`, `SosSetupScreenTest` ([names](#row-a7-contacts-never-synced-to-the-phone)) | [M21](manual-test-scripts.md#m21-contacts-that-never-reached-the-phone), [M30](manual-test-scripts.md#m30-the-sos-setup-checklist) | Automated pass · Manual pending | 2026-10-12 (automated); manual: none | Automated for what happens during the SOS. Since P014c2a "SOS setup" says "Never updated" and that it cannot tell whether there are contacts, with a Refresh button; its tests are on `main` since `aff95a5` and ran green there (run 38306321302). Since P014c2c Home shows "Not ready" for this case once the user agreed to the notice; those tests are on `main` since `5bd7092` and ran green there (run 38321661199). |
| A8 | SMS fails for every contact (no SIM, no service, airplane mode) | The SOS stays active and Call 112 stays on the screen. An alert that is not sent is tried again until the SOS ends: every 30 seconds for the first ten tries, then after 1, 2 and 4 minutes, then every 5 minutes; at once on "Send again now", when the emergency screen comes to the front, when airplane mode is switched off and when a network becomes available. An error that cannot pass is final and shown as "could not be sent". After 2 hours with an alert unsent the user is told once (a notification and the screen): call 112 or send a text yourself. 24 hours after the trigger trying stops and the screen says so. A sent alert is never sent again. | P014b1, P014b2, P014b7 | `SosAlertsTest`, `SosSmsTest`, `SosDispatchTest`, `SosAlertLinesTest`, `SosViewModelTest`, `SosRetryDeviceTest` ([names](#row-a8-sms-fails-for-every-contact-no-sim-no-service-airplane-mode)) | [M5](manual-test-scripts.md#m5-airplane-mode-and-location-off), [M22](manual-test-scripts.md#m22-every-message-fails) | Automated pass · Manual pending | 2026-10-12 (automated); manual: none | The tests of P014b7 are on `main` since `3225e12`, and `android-ci` ran green there (run 38285365927). Which answer a phone gives without a SIM or without service is not known; every such answer is treated as "try again" (ADR 0027). |
| A9 | Dual-SIM phone with the wrong default SIM | The alert leaves from the SIM the phone has set as default for SMS. The app offers no SIM choice of its own: listing the SIMs would need a permission the app does not ask for (ADR 0027). | P014b1 (the phone's default SIM is used) | None | [M15](manual-test-scripts.md#m15-dual-sim) | Manual pending | None | No automated test. What happens when the phone is set to "ask every time", or the default SIM has no balance, is not known (Known gaps). |
| A10 | Contact list changed mid-SOS | Who is alerted is decided once, at the trigger. A later change of the list does not add anyone to this SOS; a contact who opts out before their message left is skipped. The follow-up goes only to those whose alert was sent. | P014b1, P014b2 | `SosAlertsTest`, `RoomSosActionStoreTest`, `SosDispatchTest` ([names](#row-a10-contact-list-changed-mid-sos)) | [M23](manual-test-scripts.md#m23-the-contact-list-changes-during-an-sos) | Automated pass · Manual pending | 2026-10-12 (automated); manual: none | The plan has no row for this; the expected behaviour is the design of ADR 0027. A contact removed (not opted out) before their message left: see Known gaps. |
| A11 | Process death inside the 5-second countdown | With time left, the same countdown goes on. If its end has passed, nothing is sent: the user is asked "Send now" or Cancel. | P014a1, P014a2, P014a3 | `SosRecoveryTest`, `SosEngineTest`, `SosRunnerTest`, `SosViewModelTest`, `SosScreensTest`, `SosDispatchTest` ([names](#row-a11-process-death-inside-the-5-second-countdown)) | [M6](manual-test-scripts.md#m6-kill-during-the-countdown-and-reboot) | Automated pass · Manual pending | 2026-10-12 (automated); manual: none | Automated at data, logic and screen level. A countdown that did not finish is **not** announced after a restart (nothing was sent); the question comes when the app is opened (`SosRestartNoticeTest`, P014c3). |
| A12 | SOS location unavailable (no permission or location off) | The SOS triggers on time and never asks for a permission. The screen says in words why there is no position; the SMS says that the location is unavailable and still asks to call 112. | P014a2, P014a3, P014b1, P014b2 | `SosRunnerTest`, `SosForegroundServiceTest`, `SosScreensTest`, `SosMessageTest`, `SosDispatchTest` ([names](#row-a12-sos-location-unavailable-no-permission-or-location-off)) | [M5](manual-test-scripts.md#m5-airplane-mode-and-location-off) | Automated pass · Manual pending | 2026-10-12 (automated); manual: none | Automated at logic, component and screen level. |
| A13 | Debug build whose account holds real contacts | A debug build texts by itself only the test numbers it was built with; every other contact is not texted and is recorded as skipped. With no list, or none of the contacts on it, nothing is sent by the app and the SMS app is used. A banner on the SOS screens says which, with a count. A release build is unchanged. | P014b6 | `DebugSmsAllowlistTest`, `DebugSmsAllowlistLayoutTest`, `BuildSmsNoticeTest`, `SmsRecipientLimitTest`, `SosDispatchTest`, `SosAlertsTest`, `SosSmsTest`, `SosAlertLinesTest` ([names](#row-a13-debug-build-whose-account-holds-real-contacts)) | [M29](manual-test-scripts.md#m29-the-list-of-test-numbers-of-a-debug-build) | Automated pass · Manual pending | 2026-10-12 (automated); manual: none | The tests are on `main` since `0298557`, and `android-ci` ran green there (run 38247544266). |
| A14 | Sign-out or account block while an SOS is active | Signing out is refused while an emergency is going on, with the reason and the way to the SOS. If the account is blocked, deleted or signed out by the server during an SOS, the SOS goes on and finishes on the phone, the alerts and the message "I am safe" included; the phone is wiped only after the user cancelled it or said "I'm safe". | P014c3 | `SosWipeAfterEmergencyTest`, `ContactsSessionSyncTest`, `OnboardingViewModelTest`, `ScreensTest` ([names](#row-a14-sign-out-or-account-block-while-an-sos-is-active)) | [M31](manual-test-scripts.md#m31-sign-out-or-a-blocked-account-during-an-sos) | Manual pending | None | Built in P014c3: the tests are on `main` only after that merge, and this row becomes "Automated pass" when `android-ci` has run green there with them. Before P014c3 a sign-out or a block deleted the running emergency (ADR 0027 had accepted that "for now"). A block arriving from the server can only be acted out on a phone with a test account on staging. |
| A15 | A shortcut used while SOS alerts are not set up | The tile, the widget and the notification's "Start SOS" start no countdown without agreement to the notice the app shows today. They show Call 112, a practice run and the way to "Set up SOS alerts"; nothing is written down and nothing vibrates. | P014c1a (the gate for every entry point), P014c1b (the widget) | `StartSosTest`, `SosViewModelTest`, `SosEmergencyScreenTest` ([names](#row-a15-a-shortcut-used-while-sos-alerts-are-not-set-up)) | [M17](manual-test-scripts.md#m17-sos-from-the-tile-or-the-widget-while-the-phone-is-locked), [M32](manual-test-scripts.md#m32-a-shortcut-while-sos-alerts-are-not-set-up) | Automated pass · Manual pending | 2026-10-13 (automated); manual: none | The row is new in P014c3; the behaviour and its tests are from P014c1a, on `main` since `5e57495`, and `android-ci` ran green there. Every change of the notice version closes the gate for everyone until they agree again (four versions so far). |

## Known gaps

What the plan or the design requires and no test covers, or what nobody knows yet.

### Not built

1. **The server half of an SOS** (rows 1, 3, 9, 10): telling the server, one session per
   SOS, push messages, retried jobs. P015.
2. **The live-location link and its viewer** (rows 1, 8). P016.

Nothing else of the device side is unbuilt. Two items stood here until P014c: a warning
before an emergency (built: the checklist "SOS setup", P014c2a to P014c2c) and the notice
after a restart (built in P014c3). What they cannot do is under "Built, but no test can
show it", items 7 and 8.

### Built, but no test can show it

1. **No test runs on a phone.** Whether the foreground service survives a swipe from
   recents, a phone maker's battery manager or a night in Doze (row 4) is unknown for every
   phone.
2. **No SMS has left a phone.** Sending, arrival, the cost, how a long message is shown,
   and whether the map link opens (rows 1, 7) are untested outside the JVM.
3. **What a phone answers without a SIM, without service or in airplane mode** (rows 2,
   A8) is not stated in Android's documentation; every such answer is treated as "try
   again", for as long as the SOS is active. What the user then sees is written, not
   observed.
4. **Two SIMs** (row A9): no automated test. The phone's default SIM for SMS is used; a
   phone set to "ask every time", or a default SIM without balance, is not covered, and the
   app cannot offer a choice without a permission it does not ask for.
5. **The SMS app with several recipients** (row 7): which separator an SMS app accepts
   between numbers is not verified.
6. **"Sent" is not "delivered".** The phone knows only that the network took the message.
   There is no delivery report by design, so no test and no screen can say that a contact
   got or read an alert.

7. **The warning on Home needs Home.** It is read when Home comes to the front and is
   shown inside the bottom sheet, below what the sheet shows at its lowest position. A user
   who never opens the app, or never pulls the sheet up, does not see it. It appears only
   once SOS is being set up (agreed to the notice, or a contact on the phone), and "Not
   now" hides it for three days. Whether people notice it is not known.

8. **The notice after a restart depends on the phone delivering the restart broadcast**
   (row 5, P014c3). Android sends it after the first unlock. A phone maker's battery
   manager may withhold it from an app it has restricted, and an app the user
   force-stopped gets none until it is opened. With notifications switched off nothing can
   be shown. In each case the SOS is shown when the app is next opened, as before. Not
   seen on any phone.

9. **Failures in the field are visible only for testers who opt in** (P014d1b, ADR 0028).
   Crash reports are off by default, and a report never says that an SOS started or went
   well. A crash or a failed alert on the phone of someone who left the choice off reaches
   nobody unless that person writes. No row's status may be taken from crash reports.

### Accepted risks in the design (ADR 0027)

1. **An opt-out made shortly before the trigger** and not yet on the phone is missed when a
   position is ready, because the alert leaves at once and never waits for the server
   (row A5).
2. **A contact may get the alert twice** in one case: the message was handed to the phone,
   the process died before the answer, and it is tried again (row 6).
3. **An alert can arrive hours after the trigger** (rows 2, A8, P014b7): it is tried for as
   long as the SOS is active, up to 24 hours. A retried message is written anew at each try, with the newest
   position and the clock time at which that position was taken. A user who
   forgets to end an SOS can therefore alert a contact much later than they meant to.
4. **The retry timer holds no wake lock** (P014b7): it runs in the app while the
   emergency's foreground service keeps the app alive. If the phone sleeps deeply, a try
   can be late; it is made when the phone wakes, and at once when the screen is opened.
5. **Every try counts**, also one asked for by a tap or by the phone: after the tenth the
   wait before the next regular try grows, to five minutes at most.
6. **The signal that matters most is not used**: the mobile network going from "no
   service" to "in service". Its broadcast needs `READ_PHONE_STATE`, which the app does not
   ask for. Airplane mode switched off and a data network becoming available are used
   instead; with neither, the next regular try is at most five minutes away.

7. **A start nobody meant, from outside the app** (rows A1, A2, 11; P014c1a). In the app an
   SOS needs a two-second hold; the tile and the notification's button need one tap, also
   on a locked phone, by anyone who holds it, and the widget (P014c1b) one tap on the home
   screen, where a thumb passes more often than in Quick Settings. The five-second countdown with its vibration
   and Cancel is the only safeguard, by decision (ADR 0027, "Entry points"). How often it
   happens is not known; a follow-up measures it in the closed test.

8. **After a restart nothing runs until the user acts** (row 5, P014c3): the notice says
   so. No location is recorded and no unsent alert is retried between the restart and
   "Continue". Starting the location service from a restart is not allowed by Android,
   and resuming an emergency in the background is not allowed by this design.
9. **A blocked or deleted account does not stop a running SOS** (row A14, P014c3): the SOS
   finishes on the phone, alerts and the "safe" message included, and the phone is wiped
   afterwards. An SOS that the user never ends keeps its data until they do, or until the
   30-day purge.

### Not specified

1. **A contact removed (not opted out) after the trigger and before their message left**
   (row A10): the tests fix that the people decided at the trigger are alerted once; the
   plan does not say whether a removal should stop a message.
2. **How long to keep trying** (rows 2, A8): the plan says the device keeps retrying and
   names no end. P014b7 chose: until the SOS ends, and at most 24 hours after the trigger;
   the user is told after 2 hours. Both numbers are choices, not plan text.

### About the tests themselves

1. How the tests wait, since P014e. A screen test no longer polls with a time limit of its
   own: in a Hilt test the app's background work (the app-lifetime scope, the dispatchers,
   Room's threads) is counted (`BackgroundWork` in `TestCoreModule.kt`), and the test goes
   on when the count is zero (`compose.settled { }`, `Espresso.onIdle()`). No test sleeps
   (`HiltTestCleanupTest`, "no test sleeps, and only the real sign-in test polls with a
   time limit"). Every Hilt test stops its app-lifetime scope and closes its database when
   it ends (`HiltTestCleanup`). What still depends on real time, by design:
   `SignInToHomeTest` (real HTTP to a local server with the real session code; it polls
   with a ten-second limit) and one test in `ApiErrorMappingTest` (a real socket timeout).
   A coroutine that sleeps is not counted as work, so a test that needs time to pass still
   has to move the time itself. **No test starts the real application any more**: a test
   without a named application gets a plain one, so a fault in the application's own
   start-up is not seen by a JVM test. A red `android-ci` run for a reason of this kind
   takes "Automated pass" away from every row until it is green again.
2. The texts a contact receives, the notice and the Bengali wording are drafts: no lawyer
   and no native speaker has reviewed them.

## Device results

**The launch rule (Plan v7 §3.4, as stated in the P013c prompt; the PDF was not read for
this document):** every row of the matrix passes on at least **one Pixel or stock-Android
phone** and on **two phones with aggressive battery management** (for example
Xiaomi/Redmi and Vivo/Oppo/Realme), and the closed test meets the plan's goal for
crash-free sessions (the figure is in the plan and is not repeated here). Addendum v7.1
adds: the SOS entry points tested on the same set of phones.

**Where this stands:** no phone has run a script of this matrix. One phone has reported
results for two earlier checks. A Samsung Galaxy S21 FE on Android 16 (the phone maker's
own Android, not stock) has reported a part of one script: adding the widget (M17, part 7).

| Device (make and model) | Android version | Build | SMS mode | Date | Scripts run | Result | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Samsung, model not recorded | 17 | Not recorded | Not applicable (before SMS alerts existed) | Recorded 2026-10-08; the day of the checks is not recorded | None of M0 to M28. The checks of P012f1 and P012f2: tile added, tile tapped with the screen on and from the lock screen, pinned notification on and off, after a swipe, a restart and a force stop | As reported: each worked | Reported by Rahul, no details. Source: the "Revision 2026-10-08" sections of `docs/prompt-logs/012f1-sos-tile.md` and `012f2-emergency-notification.md`. Not a run of any script here, so no row is "Manual pass" |
| Samsung Galaxy S21 FE | 16 | Not reported | Not reported | 2026-10-13 | None of M0 to M29 (first report) | No script result. As reported: no SafeRoute widget could be added on this phone | Reported by Rahul on 2026-10-13: the phone's make, model and Android version, and that a widget could not be made. That is expected for that build: the widget did not exist before P014c1b, so this is not a failure of a script. **The Android version reported here (16) differs from the row above (17, model not recorded).** Whether the earlier checks were made on this phone is not recorded, so the older row is left as it was written |
| Samsung Galaxy S21 FE | 16 | Not reported (a build with the widget, P014c1b) | Not reported | 2026-10-13 | M17, part 7, in part: adding the widget | As reported: the widget is in the launcher's list; it takes 4 cells; its size cannot be changed; the lock screen does not offer it | Reported by Rahul on 2026-10-13, after P014c1b. These four points are what the design asks for. **Not reported:** a tap on the button (does the countdown open), a tap beside the button, the widget and the tile during one countdown, the widget without the consent, dark mode, Bengali, TalkBack. So row A1 stays "Manual pending" |
| Not reported | Not reported | Not reported | Not reported | 2026-10-12 | M0 to M16 | Not reported | The P013c prompt asked for the manual SMS results so far, the phone's make and Android version, and whether a second phone was used as the contact. The lines were empty |

Other checks, not on a phone's SOS path:

- **P013a staging checks** (the opt-out page's headers, 401 without a token, 404 for a
  made-up token, the fragment removed, request-log paths without a token): reported by
  Rahul in the P014 prompt and recorded in the "Revision (P014a1, 2026-10-09)" section of
  `docs/prompt-logs/013a-contacts-backend.md`. The date of the checks, the browser and the
  Bengali switch of the page are not recorded. The P013c prompt's line for them was empty,
  so nothing is added here.
- **P013b phone results** (the contacts screens, the system contact picker, the invite by
  SMS): **not reported**.

When a result arrives, add a row (never edit an old one), set the matrix row to "Manual
pass" or "Fail" with the date, and name the script. How to record:
[manual-test-scripts.md](manual-test-scripts.md#how-to-record-a-result).

## Evidence

The test classes and test names behind each row, as they are in the repository at commit
`4334232`. A class listed with "all N tests" is cited as a whole. The names are generated
from the test sources, not typed.

### Row 1: No mobile data, signal present

- `SosCoreBoundaryTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosEngineTest.kt`):
  - "it never logs, never touches the network and sends no message"
- `SosAlertsTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosAlertsTest.kt`):
  - "every contact the phone knows gets one message, and the result is recorded"
  - "preparing twice, or after the contact list changed, alerts the same people once"
  - "with no contact nothing is sent and nothing fails"
- `RoomSosActionStoreTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosAlertsTest.kt`):
  - "actions are stored with their snapshot and move only from the expected states"
  - "cancelling the emergency deletes its actions, and a late insert is dropped"
- `SosSmsTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosSmsTest.kt`):
  - "it hands one multipart message to the phone and waits for an answer per part"
- `SosMessageTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosMessageTest.kt`):
  - "the English alert says who, where, how exact, when, the battery, the sender and 112"
  - "the Bengali alert keeps every number in Latin digits and fits three parts"
  - "every alert fits three parts, whatever the name, language and position"
  - "the link is plain, unshortened, and written the same whatever the phone's language"
- `SmsSegmentsTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosMessageTest.kt`):
  - "plain text fits 160 characters in one part and 153 per part after that"
  - "the characters of the SMS alphabet that cost two places are counted twice"
  - "one letter from outside the SMS alphabet makes the whole message 70 per part"
  - "punctuation used by the messages is inside the SMS alphabet"
- `SosSmsTextDocumentTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosSmsTextDocumentTest.kt`):
  - "every fixed sentence of the messages is in the document, in both languages"
  - "the sentences that carry a name are in the document with a placeholder"
  - "the document says that it is a draft and that nothing is sent yet"
- `SosDispatchTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosDispatchTest.kt`):
  - "with a position ready the alert leaves at the trigger, once per contact, with the link"
  - "a message that cannot be sent never holds up or undoes the emergency, and is retried"
  - "with the gate closed an SOS runs and nobody is messaged"
  - "the Bengali setting sends the Bengali message, and no name sends the no-name wording"
- `SosAlertLinesTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosAlertsDeviceTest.kt`):
  - "sent, waiting, failed and skipped are shown as counts, and a sent message is not called delivered"
- `SosViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosViewModelTest.kt`):
  - "the active screen shows the counts of the alerts as they change"
  - "without consent to the alerts notice no SOS can be started from the screen"
- `SosAlertsDeviceTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosAlertsDeviceTest.kt`):
  - "the gate opens only for consent to the notice the app shows today"
  - "the notice version is the fourth one, and differs from the first three" (renamed in P014c1a and in P014c2b)
- `ApiContactsRepositoryTest` (`android/app/src/test/java/com/saferoute/app/feature/contacts/ApiContactsRepositoryTest.kt`):
  - "consent counts only when it is granted for the notice the app shows today"
  - "the phone's note of the consent follows the server, and a failed check changes nothing"
  - "granting notes the consent on the phone only after the server recorded it"
  - "withdrawing and signing out take the note of the consent away"
- `SosFlowTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "without consent to the alerts notice the SOS control offers Call 112 and the way to the setup"

Added in P014c2b for row 1, what the alert says (on `main` since `0065ee9`):

- `SosAlertsDeviceTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosAlertsDeviceTest.kt`):
  - "the messages carry the name the user set and the language the user chose"
  - "without a choice the messages use the app's language and have no name"
- `RoomSosSettingsStoreTest` (`android/app/src/test/java/com/saferoute/app/core/data/SosSettingsDatabaseTest.kt`): all 7 tests.
- `SosSettingsMigrationTest` (the same file): all 2 tests.
- `ApiContactsRepositoryTest` (`android/app/src/test/java/com/saferoute/app/feature/contacts/ApiContactsRepositoryTest.kt`):
  - "emptying the copy also erases the name for alerts and the two choices"

### Row 2: No signal at all

- `SosCoreBoundaryTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosEngineTest.kt`):
  - "it never logs, never touches the network and sends no message"
- `SosDeviceTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosDeviceTest.kt`):
  - "the device code of a running SOS never logs and holds no means to send a message"
  - "during the countdown its one button opens the dialer with 112 and a tap opens the emergency screen"
- `SosRunnerTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosRunnerTest.kt`): all 14 tests.
- `SosScreensTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "the countdown shows the number, a 72 dp cancel, Call 112 and what SafeRoute is not"
  - "the active screen says in words what is and is not happening"
  - "the question after a restart offers start now, cancel and Call 112"
  - "without a phone app the screens show the number instead of the button"
- `SosAlertsTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosAlertsTest.kt`):
  - "a failed message is tried again every 30 seconds, and a sent one never again"
  - "an alert is tried again past 20 tries, for as long as the emergency is active" (P014b7)
  - "retries stop the moment the emergency is over"
  - "each try carries the text of that moment"
- `SosSmsTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosSmsTest.kt`):
  - "only an OK from the phone counts as sent"
  - "what may pass is retried and what cannot is final"
- `SosDispatchTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosDispatchTest.kt`):
  - "a message that cannot be sent never holds up or undoes the emergency, and is retried"
  - "after a process death the retries go on, and nobody who has the alert gets it again"

### Row 3: API down or 5xx

No automated test: nothing of this row is built, or nothing of it can be tested without a phone.

### Row 4: App swiped away or killed by the OEM

- `SosEngineTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosEngineTest.kt`):
  - "process death during the countdown with time left resumes with what remains"
  - "process death past the end of the countdown asks, and sends only on send now"
  - "process death past the end of the countdown, then cancel, leaves nothing"
  - "process death after the trigger and while active is found as still active"
  - "process death after resolved leaves nothing to recover"
- `SosRecoveryTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosStateMachineTest.kt`):
  - "no record, or one that is over, needs nothing"
  - "a countdown with time left is shown again with what remains"
  - "a countdown whose end passed is always asked about, never sent or dropped"
  - "a countdown that starts in the future means the clock changed, so it is asked about"
  - "an alert that went out is still active until the user says they are safe"
- `SosRunnerTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosRunnerTest.kt`):
  - "process death during the countdown - the new process finishes the SAME countdown"
  - "process death past the end of the countdown - nothing is sent until the user says so"
  - "process death past the end of the countdown, then cancel - nothing remains"
  - "process death while active - the new process picks it up and I am safe still works"
  - "process death between the trigger and the first action - the new process goes on"
- `SosForegroundServiceTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosDeviceTest.kt`):
  - "started with nothing running, the service stops itself at once"
  - "during an SOS it is in the foreground with the fixed notification, and ends on cancel"
  - "without a location permission the plain notification stands in for the service"
- `AndroidSosHostTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosDeviceTest.kt`):
  - "with a location permission it starts the foreground service and posts nothing itself"
  - "ending stops the service and removes the notification"
- `SosViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosViewModelTest.kt`):
  - "after a process death in the countdown the screen shows the SAME countdown"
  - "a countdown that ended while the app was closed is asked about - start now"
  - "a countdown that ended while the app was closed is asked about - cancel"
  - "an SOS found still active says so, and continue keeps it running"
  - "a rotated or re-created screen never starts a second emergency"
- `SosFlowTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "an emergency nobody is running opens the emergency screen when the app comes to the front"
- `SosScreensTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "an SOS found after a restart says so and offers continue or I am safe"

Added in P014c2a for row 4, the guidance beforehand (on `main` since `aff95a5`):

- `SosSetupScreenTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosSetupTest.kt`):
  - "the battery row names the phone makers, says it is unchecked, and opens the app settings"
- `SosSetupRulesTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosSetupTest.kt`):
  - "the screen asks for no permission, asks for no battery exemption, runs nothing in the background and logs nothing"

### Row 5: Phone rebooted during SOS

- `SosRecoveryTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosStateMachineTest.kt`):
  - "no record, or one that is over, needs nothing"
  - "a countdown with time left is shown again with what remains"
  - "a countdown whose end passed is always asked about, never sent or dropped"
  - "a countdown that starts in the future means the clock changed, so it is asked about"
  - "an alert that went out is still active until the user says they are safe"
- `SosEngineTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosEngineTest.kt`):
  - "process death after the trigger and while active is found as still active"
  - "process death past the end of the countdown asks, and sends only on send now"
- `SosRunnerTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosRunnerTest.kt`):
  - "process death while active - the new process picks it up and I am safe still works"
  - "a clock set back during the countdown neither sends early nor gets stuck"
- `SosViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosViewModelTest.kt`):
  - "an SOS found still active says so, and continue keeps it running"
- `SosScreensTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "an SOS found after a restart says so and offers continue or I am safe"
  - "the question after a restart offers start now, cancel and Call 112"

Added in P014c3 for row 5, the notice after a restart (on `main` after that merge):

- `SosRestartNoticeTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosRestartTest.kt`): all 7 tests.
- `SosSafeFromNotificationTest` (the same file): all 2 tests.
- `SosViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosViewModelTest.kt`):
  - "I am safe on a notification only asks the screen, which takes it through the unlock"
    (was "I am safe on the notification opens the confirmation, and does not end anything by itself")

### Row 6: Double tap or duplicate trigger

- `SosEngineTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosEngineTest.kt`):
  - "a second start in any unresolved state shows the existing emergency"
  - "ten starts at once make one emergency"
  - "ten triggers at once fire once"
- `SosRunnerTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosRunnerTest.kt`):
  - "a second start during the countdown starts nothing new"
  - "a start while an SOS is active shows the active one"
- `SosViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosViewModelTest.kt`):
  - "a rotated or re-created screen never starts a second emergency"
  - "a practice run never replaces or hides a real SOS"
- `SosScreensTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "a hold of two seconds arms exactly once, and a tap does nothing"
- `SosAlertsTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosAlertsTest.kt`):
  - "two senders at once never send the same message twice"
  - "after a process death a sent message stays sent and an unanswered one is tried again"

Added in P014c1a for row 6 (on `main` since `5e57495`):

- `StartSosTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/StartSosTest.kt`):
  - "a second start from another entry point shows the same emergency"
  - "a start in a new process picks up the emergency the old process left"
- `SosViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosViewModelTest.kt`):
  - "a start from a second entry while an SOS runs shows the one that is running"
  - "a re-created screen never starts an SOS from a shortcut's intent a second time"

### Row 7: SEND_SMS denied or not approved

- `SosSmsTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosSmsTest.kt`):
  - "without the permission it refuses and never reaches the phone's SMS service"
  - "the main manifest never asks for SEND_SMS, only the extra one of builds made with it"
  - "this build asks for SEND_SMS exactly when it was built with it"
- `SosAlertsTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosAlertsTest.kt`):
  - "a failure that cannot pass is not tried again"
- `MainActivityTest` (`android/app/src/test/java/com/saferoute/app/MainActivityTest.kt`):
  - "the app asks for exactly these permissions and never for background location"
- `SosDispatchTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosDispatchTest.kt`):
  - "without the permission the SMS app is opened once with every number and the alert"
  - "after a process death in composer mode the SMS app is not opened a second time"
  - "in composer mode I am safe opens the SMS app with the follow-up"
- `SosAlertsDeviceTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosAlertsDeviceTest.kt`):
  - "the composer intent goes to an SMS app with every number and the text, and sends nothing"
  - "the composer notification shows fixed text only and carries the message unchangeable"
  - "showing posts the notification on a high-importance channel, and dismissing removes it"
  - "the way of sending follows the build and the permission"
- `SosAlertLinesTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosAlertsDeviceTest.kt`):
  - "in composer mode it says that nothing is sent until Send is pressed, and offers the SMS app again"
- `SosViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosViewModelTest.kt`):
  - "in composer mode the screen offers the SMS app again"
- `SmsPermissionCardTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SmsPermissionCardTest.kt`): all 10 tests.

Added in P014c2a for row 7, the checklist (on `main` since `aff95a5`):

- `SosReadinessTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosReadinessTest.kt`):
  - "the SMS app is to fix only in a build that could send by itself"
- `SosSetupViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosSetupTest.kt`):
  - "the SMS row has two states for the SMS app - to fix, or how this build works"
- `SosSetupScreenTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosSetupTest.kt`):
  - "the SMS row says automatic, your SMS app to fix, or your SMS app as how this build works"

### Row 8: No GPS fix

- `SosTrailTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosRunnerTest.kt`): all 8 tests.
- `SosRunnerTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosRunnerTest.kt`):
  - "location never blocks an SOS - without permission it triggers on time"
  - "location never blocks an SOS - with location off and no fix it triggers on time"
- `SosScreensTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "every way of having no good position has its own sentence"
- `SosViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosViewModelTest.kt`):
  - "the active screen reports the location in words and says when notifications are off"
- `SosMessageTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosMessageTest.kt`):
  - "without a position it says so and still asks to call 112"
  - "an old position carries its age in minutes and the clock time it was taken"
  - "an unknown accuracy and an unknown battery are left out, not invented"
- `SosDispatchTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosDispatchTest.kt`):
  - "without a position the alert waits 3 seconds at most, and the trigger not at all"
  - "a position that arrives inside the 3 seconds is used at once"
  - "sent without a position, ONE update follows when a position arrives within 60 seconds"
  - "a position that arrives after 60 seconds is not sent after the alert"
  - "without a location permission there is nothing to wait for"

### Row 9: FCM failure

No automated test: nothing of this row is built, or nothing of it can be tested without a phone.

### Row 10: Worker crash mid-action

No automated test: nothing of this row is built, or nothing of it can be tested without a phone.

### Row 11: Accidental trigger

- `SosStateMachineTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosStateMachineTest.kt`):
  - "an alert that went out cannot be cancelled, only marked safe"
- `SosEngineTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosEngineTest.kt`):
  - "cancel during the countdown deletes the record and its points"
  - "after the alert went out cancel is refused and I am safe resolves it"
- `SosRunnerTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosRunnerTest.kt`):
  - "cancel is local only - record gone, trail stopped, host ended, nothing fires later"
  - "after the alert went out cancel is refused and I am safe ends everything"
- `SosForegroundServiceTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosDeviceTest.kt`):
  - "during an SOS it is in the foreground with the fixed notification, and ends on cancel"
- `SosScreensTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "a hold released before two seconds arms nothing"
  - "the countdown shows the number, a 72 dp cancel, Call 112 and what SafeRoute is not"
  - "at 200 percent font cancel and Call 112 can still be reached and stay big"
  - "in landscape cancel and Call 112 can still be reached"
  - "I am safe is confirmed in a dialog"
- `SosViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosViewModelTest.kt`):
  - "cancel in the countdown closes the screen and leaves nothing"
  - "I am safe asks first, and only the confirmation ends the SOS"
  - "I am safe on a notification only asks the screen, which takes it through the unlock" (renamed in P014c3; on `main` after that merge)
  - "I am safe tells the alerted contacts by default"
  - "with tell my contacts unticked I am safe sends nothing more"
  - "practice runs the whole flow and touches nothing real"
- `SosEmergencyScreenTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "started after the hold it shows the countdown over the lock screen, and cancel leaves nothing"
- `SosMessageTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosMessageTest.kt`):
  - "the safe follow-up reports what the sender said, in both languages"
- `SosAlertsTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosAlertsTest.kt`):
  - "the safe follow-up goes only to the contacts whose alert was really sent"
- `SosDispatchTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosDispatchTest.kt`):
  - "I am safe with tell my contacts sends the follow-up to those whose alert was sent"
  - "I am safe without tell my contacts sends nothing more"
  - "cancel during the countdown sends nothing, also with the gate open"
- `SosAlertLinesTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosAlertsDeviceTest.kt`):
  - "the confirmation offers tell my contacts, ticked, only when someone was told"

Added in P014c2b for row 11, the countdown's sound (on `main` since `0065ee9`):

- `SosRunnerTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosRunnerTest.kt`):
  - "the countdown makes no sound unless the user switched the sound on"
  - "a new process that continues a countdown keeps the sound setting"
- `SosViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosViewModelTest.kt`):
  - "a practice run sounds like the real one when the sound is on, and is silent otherwise"
- `SosAlertsDeviceTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosAlertsDeviceTest.kt`):
  - "the countdown tone needs no permission and never stops a countdown"

### Row A1: SOS started from the tile while the phone is locked

- `EmergencyShortcutTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/EmergencyShortcutsTest.kt`):
  - "every entry leads to the one emergency destination, as a new task"
  - "the pending intent starts an activity, is immutable, and differs per entry"
  - "a tile uses a pending intent from Android 14 and a plain intent before"
  - "Android's answer to the add-tile request is read by its number"
- `SosTileServiceTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/EmergencyShortcutsTest.kt`):
  - "the tile is tappable, not shown as switched on, and named for TalkBack"
  - "the label fits a tile"
- `EmergencyActivityTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/EmergencyShortcutsTest.kt`):
  - "it shows the emergency dialog with the not-an-emergency-service note"
  - "call 112 opens the dialer with 112, never calls, and closes this screen"
  - "cancel closes this screen and starts nothing"
  - "without a dialer app it shows the number instead of crashing, and stays open"
  - "it is not the main activity and carries nothing of the app's session"
- `EmergencyShortcutsManifestTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/EmergencyShortcutsTest.kt`):
  - "the tile service is declared the way Android asks"
  - "only the emergency screen may show over the lock screen, and it is not exported"
- `SosScreensTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "on the lock screen the way out says that the unlock comes first"
- `SosAlertLinesTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosAlertsDeviceTest.kt`):
  - "on the lock screen the same counts are shown and nothing else about the contacts"

Added or renamed in P014c1a for row A1 (on `main` since `5e57495`):

- `StartSosTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/StartSosTest.kt`): all 6 tests.
- `EmergencyShortcutTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/EmergencyShortcutsTest.kt`):
  - "an intent that names no entry, or one that does not exist, counts as the app"
- `SosTileServiceTest` (the same file):
  - "a tap asks the emergency destination to start an SOS from the tile, and nothing else"
    (was "a tap opens the emergency destination and nothing else")
  - "on a locked phone the tap does the same, and never waits for the unlock"
- `SosViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosViewModelTest.kt`):
  - "the tile, the widget and the notification start the same countdown as the hold"
  - "with alerts not set up every entry shows the options and the way to the setup, and no countdown"
  - "practice from the not-set-up dialog runs a practice and touches nothing real"
- `SosEmergencyScreenTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "the tile, the widget and the notification each open the countdown, and the record says which"
  - "with alerts not set up a shortcut shows Call 112 and the way to the setup, and no countdown"
  - "set up SOS alerts opens the app at emergency contacts and closes the emergency screen"
  - "on a locked phone set up SOS alerts asks for the unlock first"
  - "in Bengali at double font size the not-set-up dialog still reaches Call 112 and the setup"

Added in P014c1b for row A1, the widget (on `main` since `a16c443`):

- `SosWidgetTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosWidgetTest.kt`): all 7 tests.
- `EmergencyShortcutsManifestTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/EmergencyShortcutsTest.kt`):
  - "one receiver, not exported, for a restart and an update of this app only" (it now
    counts two receivers: that one and the widget's provider)

### Row A2: Pinned notification dismissed or killed

- `EmergencyNotificationTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/EmergencyNotificationTest.kt`):
  - "posting shows exactly one notification, posting again replaces it, cancel removes it"
  - "app open, restart and update all use the same rule - swiped away, it comes back"
  - "with the switch off nothing is posted at boot, at update or at app open"
  - "once started it follows the switch - off removes it at once, and so does a cleared store"
  - "the receiver answers to a restart and to an update of this app, and to nothing else"
- `SosFlowTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "the SOS control opens the dialog, and a completed hold leads to the emergency screen"

Added in P014c1a for row A2 (on `main` since `5e57495`):

- `EmergencyNotificationTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/EmergencyNotificationTest.kt`):
  - "call 112 opens the dialer and start SOS asks the emergency screen to start, both immutable"
- `SosEmergencyScreenTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "the tile, the widget and the notification each open the countdown, and the record says which"

### Row A3: Tile unavailable on the device

- `SettingsShortcutsTest` (`android/app/src/test/java/com/saferoute/app/feature/settings/SettingsShortcutsTest.kt`):
  - "the row is a button of at least 48 dp, says what the tile does, and reports the tap"
  - "added and already added say so, and OK closes the notice"
  - "when Android does not add it, the four steps for doing it by hand are shown"
  - "in Bengali at double font size the row and the steps are still reachable"
- `EmergencyShortcutTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/EmergencyShortcutsTest.kt`):
  - "Android's answer to the add-tile request is read by its number"
- `SosFlowTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "the SOS control opens the dialog, and a completed hold leads to the emergency screen"

### Row A4: Notification permission denied

- `EmergencyNotificationTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/EmergencyNotificationTest.kt`):
  - "on Android 13 the permission decides, then the app switch, then the channel"
  - "it is shown only when the switch is on and Android allows it"
- `SettingsShortcutsTest` (`android/app/src/test/java/com/saferoute/app/feature/settings/SettingsShortcutsTest.kt`):
  - "denied once and denied for good each have their notice, and the switch stays off"
  - "notifications or the category blocked - a notice, no request, the switch stays off"
- `SosScreensTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "the active screen says in words what is and is not happening"
- `SosViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosViewModelTest.kt`):
  - "the active screen reports the location in words and says when notifications are off"

### Row A5: Opted-out contact excluded

- `ContactsDatabaseTest` (`android/app/src/test/java/com/saferoute/app/core/data/ContactsDatabaseTest.kt`):
  - "sos contacts leave out everyone who opted out"
  - "sos contacts are empty when there are none or all opted out"
- `SosAlertsTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosAlertsTest.kt`):
  - "a contact who opted out before their message left is skipped and never texted"
  - "an opt-out that arrives after the message was sent changes nothing about it"
- `SosDispatchTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosDispatchTest.kt`):
  - "a contact who opted out is taken off when the fresh list arrives before their message left"
  - "the alert never waits for the server - a slow or absent answer changes nothing"
- `SosAlertsDeviceTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosAlertsDeviceTest.kt`):
  - "the fresh look returns who may still be alerted, or nothing when the list could not be fetched"
- `ApiContactsRepositoryTest` (`android/app/src/test/java/com/saferoute/app/feature/contacts/ApiContactsRepositoryTest.kt`):
  - "an opted-out contact cannot be invited, and the copy learns it"

### Row A6: Contacts empty at SOS time

- `SosAlertsTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosAlertsTest.kt`):
  - "with no contact nothing is sent and nothing fails"
- `SosDispatchTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosDispatchTest.kt`):
  - "with no contact the screen says so and nothing is sent"
- `SosViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosViewModelTest.kt`):
  - "with no contact the active screen says so and I am safe offers nothing to tell"
- `SosAlertLinesTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosAlertsDeviceTest.kt`):
  - "while preparing and with no contact it says so"
- `ContactsDatabaseTest` (`android/app/src/test/java/com/saferoute/app/core/data/ContactsDatabaseTest.kt`):
  - "sos contacts are empty when there are none or all opted out"

Added in P014c2a for row A6, the checklist (on `main` since `aff95a5`):

- `SosReadinessTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosReadinessTest.kt`):
  - "no contact on the phone is to fix, and a list that was never fetched cannot be known"
  - "the sum counts every row to fix, and never the battery"
- `SosSetupViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosSetupTest.kt`):
  - "every return to the screen reads the phone again"
- `SosSetupScreenTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosSetupTest.kt`):
  - "the contacts row says none, cannot tell, or how many, and how old the list is"

Added in P014c2c for row A6, the card on Home (on `main` since `5bd7092`):

- `SosSetupCardViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosSetupOffersTest.kt`):
  - "it counts what is to fix, and looks again only when Home comes to the front"
  - "before SOS is being set up at all the card stays away"
  - "not now hides it for three days and no longer"
- `SosSetupOffersFlowTest` (the same file):
  - "while not ready Home leads to the checklist, with the SOS control still there"

### Row A7: Contacts never synced to the phone

- `ApiContactsRepositoryTest` (`android/app/src/test/java/com/saferoute/app/feature/contacts/ApiContactsRepositoryTest.kt`):
  - "reading works with no server at all, and so does the sos list"
  - "a failed fetch never deletes or changes what is on the phone"
  - "the phone's note of the consent follows the server, and a failed check changes nothing"
- `ContactsSessionSyncTest` (`android/app/src/test/java/com/saferoute/app/feature/contacts/ContactRulesTest.kt`):
  - "ready fetches the list once, and again after the next sign-in"
  - "a failed fetch after sign-in keeps what is on the phone"
- `SosDispatchTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosDispatchTest.kt`):
  - "the alert never waits for the server - a slow or absent answer changes nothing"
  - "with no contact the screen says so and nothing is sent"

Added in P014c2a for row A7, the checklist (on `main` since `aff95a5`):

- `SosReadinessTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosReadinessTest.kt`): all 9 tests.
- `SosSetupViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosSetupTest.kt`): all 8 tests (7 of P014c2a, 1 of P014c2b).
- `SosSetupScreenTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosSetupTest.kt`): all 17 tests (11 of P014c2a, one of them rewritten in P014c2b, and 6 of P014c2b).
- `SosSetupRulesTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosSetupTest.kt`): all 3 tests.
- `ApiContactsRepositoryTest` (`android/app/src/test/java/com/saferoute/app/feature/contacts/ApiContactsRepositoryTest.kt`):
  - "a fetched list notes the time, and a failed fetch leaves the time as it was"
  - "emptying the copy forgets when it was fetched"
- `SosFlowTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "emergency contacts leads to the SOS setup checklist, which says that alerts are not set up"

Added in P014c2c for row A7, the two ways to the checklist (on `main` since `5bd7092`):

- `SosSetupOfferViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosSetupOffersTest.kt`): its 1 test.
- `SosSetupCardViewModelTest` (the same file): all 5 tests.
- `SosSetupCardTest` (the same file): all 3 tests.
- `SosSetupOffersFlowTest` (the same file): all 5 tests.

### Row A8: SMS fails for every contact (no SIM, no service, airplane mode)

- `SosAlertsTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosAlertsTest.kt`):
  - "a failed message is tried again every 30 seconds, and a sent one never again"
  - "a failure that cannot pass is not tried again"
  - "retries stop the moment the emergency is over"
  - Added or changed in P014b7 (on `main` after that merge):
  - "an alert is tried again past 20 tries, for as long as the emergency is active"
  - "the wait is 30 seconds for the first ten tries, then 1, 2 and 4 minutes, then 5 minutes"
  - "24 hours after the trigger what is still unsent is given up, on time, and says why"
  - "a try asked for now is made at once, whatever the wait, and counts as a try"
  - "a try asked for while one is in flight is ignored"
  - "a final failure stays final, also when a try is asked for now"
  - "a new process continues the same schedule instead of starting it again"
  - "the user is told about unsent alerts once, only while one is unsent, also across a new process"
  - "nobody is told about unsent alerts when every alert was sent or given up"
  - "a follow-up is given up after 20 tries, about ten minutes in"
- `SosSmsTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosSmsTest.kt`):
  - "only an OK from the phone counts as sent"
  - "what may pass is retried and what cannot is final"
  - "on a device that cannot send SMS it says so instead of crashing"
- `SosDispatchTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosDispatchTest.kt`):
  - "a message that cannot be sent never holds up or undoes the emergency, and is retried"
- `SosAlertLinesTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosAlertsDeviceTest.kt`):
  - "sent, waiting, failed and skipped are shown as counts, and a sent message is not called delivered"

Added in P014b7 for row A8 (on `main` after that merge):

- `SosDispatchTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosDispatchTest.kt`):
  - "an alert that cannot be sent is still tried an hour later, and leaves when the signal returns"
  - "two hours after the trigger with an alert unsent the user is told once, also across a process death"
  - "a process that starts after the two hours tells the user, if the old one never did"
  - "with every alert sent nobody is told anything after two hours"
  - "24 hours after the trigger trying stops, the screen is told, and the emergency goes on"
  - "send again now makes one try at once, and a second tap during that try is ignored"
  - "a signal from the phone asks for one more try, at most every ten seconds, and none after the end"
  - "in composer mode and with the gate closed there is nothing to retry"
- `SosViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosViewModelTest.kt`):
  - "send again now tries the unsent alert at once, and coming back to the screen does too"
- `SosAlertLinesTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosAlertsDeviceTest.kt`):
  - "while an alert is unsent the screen says that it keeps trying and offers send again now"
  - "after two hours the screen says that alerts are unsent and what to do, with the button still there"
  - "after 24 hours the screen says that trying has stopped, and offers no send again"
- `SosRetryDeviceTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosRetryDeviceTest.kt`): all 9 tests.

### Row A9: Dual-SIM phone with the wrong default SIM

No automated test: nothing of this row is built, or nothing of it can be tested without a phone.

### Row A10: Contact list changed mid-SOS

- `SosAlertsTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosAlertsTest.kt`):
  - "preparing twice, or after the contact list changed, alerts the same people once"
  - "a contact who opted out before their message left is skipped and never texted"
  - "the safe follow-up goes only to the contacts whose alert was really sent"
- `RoomSosActionStoreTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosAlertsTest.kt`):
  - "actions are stored with their snapshot and move only from the expected states"
- `SosDispatchTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosDispatchTest.kt`):
  - "a contact who opted out is taken off when the fresh list arrives before their message left"
  - "after a process death the retries go on, and nobody who has the alert gets it again"

### Row A11: Process death inside the 5-second countdown

- `SosRecoveryTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosStateMachineTest.kt`):
  - "a countdown with time left is shown again with what remains"
  - "a countdown whose end passed is always asked about, never sent or dropped"
  - "a countdown that starts in the future means the clock changed, so it is asked about"
- `SosEngineTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosEngineTest.kt`):
  - "process death during the countdown with time left resumes with what remains"
  - "process death past the end of the countdown asks, and sends only on send now"
  - "process death past the end of the countdown, then cancel, leaves nothing"
- `SosRunnerTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosRunnerTest.kt`):
  - "process death during the countdown - the new process finishes the SAME countdown"
  - "process death past the end of the countdown - nothing is sent until the user says so"
  - "process death past the end of the countdown, then cancel - nothing remains"
  - "a clock set back during the countdown neither sends early nor gets stuck"
- `SosViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosViewModelTest.kt`):
  - "after a process death in the countdown the screen shows the SAME countdown"
  - "a countdown that ended while the app was closed is asked about - start now"
  - "a countdown that ended while the app was closed is asked about - cancel"
- `SosScreensTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "the question after a restart offers start now, cancel and Call 112"
- `SosDispatchTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosDispatchTest.kt`):
  - "cancel during the countdown sends nothing, also with the gate open"

Added in P014c3 for row A11 (on `main` after that merge):

- `SosRestartNoticeTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosRestartTest.kt`):
  - "a countdown that never finished, and an SOS that is over, are not announced"

### Row A12: SOS location unavailable (no permission or location off)

- `SosRunnerTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosRunnerTest.kt`):
  - "location never blocks an SOS - without permission it triggers on time"
  - "location never blocks an SOS - with location off and no fix it triggers on time"
- `SosForegroundServiceTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosDeviceTest.kt`):
  - "without a location permission the plain notification stands in for the service"
- `SosScreensTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "every way of having no good position has its own sentence"
- `SosMessageTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosMessageTest.kt`):
  - "without a position it says so and still asks to call 112"
- `SosDispatchTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosDispatchTest.kt`):
  - "without a location permission there is nothing to wait for"
  - "without a position the alert waits 3 seconds at most, and the trigger not at all"

### Row A13: Debug build whose account holds real contacts

Added in P014b6; on `main` since commit `0298557`.

- `DebugSmsAllowlistTest` (`android/app/src/testDebug/java/com/saferoute/app/feature/emergency/DebugSmsAllowlistTest.kt`): all 7 tests.
- `DebugSmsAllowlistLayoutTest` (the same file):
  - "the debug code of the list never logs or prints"
  - "the release build sets no limit and reads no list"
  - "the build file gives the list to the debug build type only and never prints it"
  - "no tracked properties file holds a list of numbers"
  - "nothing in the main source set reads the list or the debug class"
- `BuildSmsNoticeTest` (the same file): all 7 tests.
- `SmsRecipientLimitTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SmsRecipientLimitTest.kt`):
  - "no limit means every number, and nothing to tell the screens"
  - "a limited build is told how many of its contacts would be texted"
  - "an empty list, or one that matches no contact, counts nobody"
  - "when the app cannot send by itself nobody is counted, whatever the list holds"
  - "the notice holds counts only"
- `SosDispatchTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosDispatchTest.kt`):
  - "a build that limits its recipients texts only the allowlisted contacts and records the others as skipped"
  - "with an empty list of test numbers nothing is sent by the app and the SMS app gets everyone"
  - "with test numbers that match none of the contacts the SMS app is used, as with an empty list"
  - "without a limit every contact is texted and nothing is held back"
  - "the safe follow-up of a limited build goes only to the contact who was texted"
  - "after a process death a limited build still texts nobody outside its list"
- `SosAlertsTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/SosAlertsTest.kt`):
  - "numbers a limited build may not text are skipped with their own reason, and counted apart"
  - "a build without a limit skips nobody"
- `SosSmsTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosSmsTest.kt`):
  - "a number outside the build's list is refused before anything else and never reaches the phone"
- `SosAlertLinesTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosAlertsDeviceTest.kt`):
  - "messages held back by a build's list of test numbers have their own line and are not called opted out"

### Row A14: Sign-out or account block while an SOS is active

Added in P014c3; on `main` after that merge.

- `SosWipeAfterEmergencyTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosRestartTest.kt`): all 7 tests.
- `ContactsSessionSyncTest` (`android/app/src/test/java/com/saferoute/app/feature/contacts/ContactRulesTest.kt`):
  - "a block or a forced sign-out during an SOS waits for the SOS to end before it empties the copy"
  - "a countdown that is cancelled also lets the waiting clean-up through"
- `OnboardingViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/onboarding/OnboardingViewModelTest.kt`):
  - "while an SOS is going on sign out is refused, and nothing is signed out or deleted"
  - "an SOS that starts after Settings was opened is still seen at the moment of signing out"
- `ScreensTest` (`android/app/src/test/java/com/saferoute/app/feature/ScreensTest.kt`):
  - "while an SOS is active sign out says so, offers the SOS, and never asks to confirm"

### Row A15: A shortcut used while SOS alerts are not set up

The row is new in P014c3; these tests are from P014c1a and on `main` since `5e57495`.

- `StartSosTest` (`android/app/src/test/java/com/saferoute/app/core/emergency/StartSosTest.kt`):
  - "with the gate closed no entry point starts a countdown or writes anything down"
  - "the gate is asked at every start, not once"
- `SosViewModelTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosViewModelTest.kt`):
  - "with alerts not set up every entry shows the options and the way to the setup, and no countdown"
  - "practice from the not-set-up dialog runs a practice and touches nothing real"
- `SosEmergencyScreenTest` (`android/app/src/test/java/com/saferoute/app/feature/emergency/SosScreensTest.kt`):
  - "with alerts not set up a shortcut shows Call 112 and the way to the setup, and no countdown"
  - "set up SOS alerts opens the app at emergency contacts and closes the emergency screen"
  - "on a locked phone set up SOS alerts asks for the unlock first"

## History

- **P014a1** (2026-10-09): created from the failure-matrix table of that prompt's log.
- **P014a2, P014a3, P014b1, P014b2, P014b3** (2026-10-09): rows and scripts M0 to M16 added
  as each part was built.
- **P014b5** (merged 2026-10-12): no row changed; one test of the daily clean-up was made
  independent of a background thread.
- **P013c** (2026-10-12): restructured as a living document. Rows numbered (1 to 11, A1 to
  A12), seven rows added (A6 to A12), the columns "Implemented in", "Manual script",
  "Status" and "Last result date" added, every test name checked against the test sources,
  the sections "Known gaps" and "Device results" added, and the manual scripts moved to
  [manual-test-scripts.md](manual-test-scripts.md) with their numbers unchanged (M17 to M28
  new). No test, status or result was invented: a status is "Automated pass" only where a
  named test exists and `android-ci` on `main` is green.
- **P014b6** (2026-10-12): row A13 and script M29 added (debug builds text only the test
  numbers they were built with). Scripts M11, M12, M15, M16 and M22 now need the list for
  automatic sending in a debug build. No other row's status changed.
- **P014b7** (2026-10-12): an unsent alert is tried again for as long as the SOS is active
  (rows 2 and A8 rewritten, script M22 rewritten). Row A8 is "Manual pending" until
  `android-ci` is green on `main` with the new tests. Row A13 is "Automated pass" now: its
  tests are on `main` and the run was green. "Accepted risks" gained four items and "Not
  specified" item 2 was replaced.
- **P014c1a** (2026-10-12): the tile and the pinned notification's "Start SOS" start the
  five-second countdown through one use case and the consent check of the app (rows A1,
  A2, 6; script M17 written, M18 and M19 updated). Notice version 3. Row A1 is "Manual
  pending" until `android-ci` is green on `main` with the new tests; the widget is
  P014c1b. Row A8 is "Automated pass" now and row 2's retry tests count: the tests of
  P014b7 are on `main` and the run was green. "Accepted risks" gained item 7 (a start
  nobody meant). Two tests were renamed; their old names are noted under Evidence.
- **P014c1b** (2026-10-13): the home-screen widget starts the same countdown through the
  same use case (row A1; script M17, part 7 written). Rows A1, A2 and 6 count the tests of
  P014c1a now: they are on `main` and the run was green, so row A1 is "Automated pass ·
  Manual pending". The widget's tests count after this merge. Device results gained a row:
  a Samsung Galaxy S21 FE on Android 16, as reported, with no script result.
- **P014c2a** (2026-10-13): the checklist "SOS setup" (rows A6, A7, 4, 7; known gap 4
  rewritten; script M30 new, M21 updated). The widget's tests (P014c1b) count now: they are
  on `main` and the run was green. Device results gained a row: what Rahul reported about
  adding the widget on the S21 FE. The tests of the checklist count after this merge.
- **P014c2b** (2026-10-13): a name for the alert, its language and a countdown sound,
  set under "SOS setup"; notice version 4 (rows 1 and 11 gained tests; script M30, part 6
  new). No row's expected behaviour changed. The tests of the checklist (P014c2a) count
  now: they are on `main` and the run was green. The two offers of the checklist are
  P014c2c.
- **P014c2c** (2026-10-13): the checklist is offered once after the first contact and
  shown on Home while something is to fix (rows A6 and A7; "Not built", item 4, removed; a new
  item 7 under "Built, but no test can show it"; script M30, part 7 new). The tests of
  P014c2b count now: they are on `main` and the run was green. With this, part 2 of P014c
  (readiness) is complete; the notice after a restart is P014c3.
- **P014c3** (2026-10-13): the notice after a restart (row 5; "Waiting on P014c" removed
  from the last row that carried it), sign-out refused and the wipes deferred while an SOS
  is going on (new row A14), and a row for a shortcut used with the consent gate closed
  (new row A15, tests of P014c1a). "I'm safe" from a notification goes through the unlock.
  Scripts: M27 written (it was a draft), M31 and M32 new. The tests of P014c2c count now.
  "State of the build" says that the device side is complete and what remains.
- **P014d1b** (2026-10-13): no row changed. Known gap 9 added: crash reports are opt-in,
  so field failures are visible only for testers who opt in.
- **P014d1c** (2026-10-13): no row changed and no SOS behaviour changed. The SMS gateway
  is wrapped by `ReportingSmsGateway`, which passes every outcome back unchanged, and the
  SOS screen notes `RECOVERY_SHOWN`; `SosViewModelTest` checks that a start and a sent
  alert note nothing.
- **P014e** (2026-10-13): no row changed and no production code changed. "About the tests
  themselves" rewritten: screen tests wait for the app's counted background work instead
  of polling, no test sleeps, every Hilt test closes its database, and no test starts the
  real application. `EmergencyActivityTest` became a Hilt test; the test of the clean-up
  job moved from `SosDeviceTest` to `SosForegroundServiceTest`.
