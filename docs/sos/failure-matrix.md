# SOS failure matrix

The rows of Plan v7 §7.5 and of [addendum v7.1](../plan/addendum-v7.1.md), section B, with the
test or manual script that covers each and an honest status. Every prompt that touches SOS,
live location or emergency contacts updates this file.

- **Created in P014a1** (2026-10-09) from the failure-matrix table of that prompt's log; no
  earlier version existed. **Updated in P014a2** (2026-10-09).
- **Automated pass** is written only where tests exist and pass in the Android gate, and it
  says at which level. Tests run on the JVM (Robolectric); none runs on a phone.
- **Manual pending** means a person has to do it on a phone and has not reported it.
- A row can be both: the logic is tested, the behaviour of the platform is not.
- Levels: *data* = the records in Room; *logic* = the runner, the trail and the engine with
  fakes for the phone's parts, in virtual time; *component* = the real service, notification
  or job under Robolectric.

Last gate run: P014a2, 2026-10-09, 931 tests, 0 failures.

**Nothing can start an SOS from the app's screens yet** (P014a3 brings them), so no row can
be tried on a phone today.

## Plan v7 §7.5

| # | Scenario | Expected behaviour | Automated tests | Status |
| --- | --- | --- | --- | --- |
| 1 | No mobile data, cellular signal present | SMS sent from the device; server sync queued | None yet (P014b) | Not built. Manual pending |
| 2 | No signal at all | SOS screen shows the 112 button; device keeps retrying; local record kept | No network code exists in the SOS path: `SosCoreBoundaryTest`, `SosDeviceTest` (`the device code of a running SOS never logs and holds no means to send a message`). The whole run works on the phone alone: `SosRunnerTest` (all). The notification's Call 112 button: `SosDeviceTest`, `its one button opens the dialer with 112 and a tap opens the emergency screen` | Automated pass at logic and component level for "runs with no network" and "112 from the notification". The SOS screen: P014a3. Retry of messages: P014b. Manual pending |
| 3 | SafeRoute API down / 5xx | Device path unaffected; no duplicate sessions | P015 | P015 |
| 4 | App swiped away / killed by OEM | Foreground service keeps running where allowed; on restart, state restored from Room | Data: `SosEngineTest`, the five `process death ...` tests; `SosRecoveryTest` (all). Logic: `SosRunnerTest`: `process death during the countdown - the new process finishes the SAME countdown`, `process death past the end of the countdown - nothing is sent until the user says so`, `process death past the end of the countdown, then cancel - nothing remains`, `process death while active - the new process picks it up and I am safe still works`, `process death between the trigger and the first action - the new process goes on`. Component: `SosForegroundServiceTest`, `during an SOS it is in the foreground with the fixed notification, and ends on cancel` | Automated pass at data, logic and component level. Whether the service survives a swipe from recents or a phone maker's battery manager: manual pending (P014a3 script). The recovery screens: P014a3 |
| 5 | Phone rebooted during SOS | On boot or app open, the active SOS is detected and the user is asked | The same tests as row 4: a reboot is a process death with a later clock (`process death while active ...` moves the clock by a day) | Automated pass at data and logic level. Boot notice: P014c. Manual pending |
| 6 | Double tap / duplicate trigger | Single session (`clientSosId`); the second trigger shows the existing one | Data: `SosEngineTest`: `a second start in any unresolved state shows the existing emergency`, `ten starts at once make one emergency`, `the trigger does nothing before the end and happens exactly once at the end`, `ten triggers at once fire once`. Logic: `SosRunnerTest`: `a second start during the countdown starts nothing new`, `a start while an SOS is active shows the active one`, `the countdown shows 5 to 1 with one vibration each and triggers once at the end` | Automated pass at data and logic level. The screens: P014a3. Manual pending |
| 7 | SEND_SMS denied or not approved | SMS composer opens pre-filled | None yet (P014b) | Not built. Manual pending |
| 8 | No GPS fix | Last known location with its age; marked stale | `SosTrailTest`: `no fix yet - the last known position is stored with its real time and reported stale`, `a position goes stale after 30 seconds without a new one, and its age is told`, `approximate permission only - it is used and reported as approximate`, `a mock position is kept and flagged, and an unknown accuracy is stored as unknown`. `SosRunnerTest`: `location never blocks an SOS - without permission it triggers on time`, `location never blocks an SOS - with location off and no fix it triggers on time` | Automated pass at logic level. What the screen and the SMS say about it: P014a3, P014b. Real GPS behaviour: manual pending |
| 9 | FCM failure | Action recorded FAILED_RETRYABLE, retried | P015 | P015 |
| 10 | Worker crash mid-action | pg-boss re-delivers; no duplicate push | P015 | P015 |
| 11 | Accidental trigger | Cancel in the countdown; after the trigger, "I'm safe" | Data: `SosEngineTest`: `cancel during the countdown deletes the record and its points`, `after the alert went out cancel is refused and I am safe resolves it`. Logic: `SosRunnerTest`: `cancel is local only - record gone, trail stopped, host ended, nothing fires later`, `after the alert went out cancel is refused and I am safe ends everything`. Component: `SosForegroundServiceTest` (the service ends on cancel) | Automated pass at data, logic and component level. The Cancel button and the follow-up message: P014a3, P014b. Manual pending |

## Addendum v7.1

| Scenario | Expected behaviour | Automated tests | Status |
| --- | --- | --- | --- |
| SOS started from the tile while the phone is locked | The countdown appears over the lock screen; nothing of the account is shown | The tile opens the emergency dialog today: `EmergencyShortcutsTest`, `EmergencyShortcutFlowTest` (P012f1). Countdown from the tile: P014c | Automated pass for the dialog only. Observed by Rahul on one phone (Samsung, Android 17): the dialog opened from the lock screen. Countdown: manual pending |
| Pinned notification dismissed or killed | SOS still starts from the tile and the app; re-posted at the next open | `EmergencyNotificationTest` (P012f2) | Automated pass for re-posting. Observed on the same phone. "Start SOS" action: P014c, manual pending |
| Tile unavailable on the device | Settings explains; the in-app control always works | `SettingsShortcutsTest` (P012f1) | Automated pass for the explanation. Not observed on a phone without tiles. Manual pending |
| Notification permission denied | No notification and no error; SOS never depends on it | Pinned notification: `EmergencyNotificationTest`, `SettingsShortcutsTest` (P012f2). Running SOS: the run does not depend on the notification (`SosRunnerTest` uses a host that shows nothing); `SosForegroundServiceTest`, `without a location permission the plain notification stands in for the service` | Automated pass at logic level. That the Active screen says so: P014a3. On a phone with notifications refused: manual pending |

## Manual scripts

Record the phone's make, model and Android version with every result. Send messages only to
phones you control and whose owners you have warned.

Until P014a3 nothing on a screen starts an SOS, so only these can be tried:

1. **Upgrade keeps the contacts (database version 1 → 2).** With a build from before P014a1
   installed and at least one emergency contact saved, install the current build over it (no
   uninstall). Open Settings → Emergency contacts in airplane mode: the same contacts are
   listed.
2. **Sign-out still works.** Sign out and in again: the contacts come back from the server.
3. **New permissions ask for nothing (P014a2).** After installing, open the app: no new
   permission dialog appears, no notification is shown, and nothing vibrates. In Settings →
   Apps → SafeRoute → Permissions nothing new is listed (the three new permissions are
   granted at install and have no switch).

Scripts for rows 2, 4, 5, 6, 8 and 11 (hold to arm, cancel, the full countdown, locking the
phone during the countdown, swiping the app away while active, airplane mode, location off,
battery saver, Do Not Disturb and the vibration) need the SOS screens and arrive with P014a3;
rows 1 and 7 with P014b; the addendum rows and the boot row with P014c.
