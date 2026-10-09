# SOS failure matrix

The rows of Plan v7 §7.5 and of [addendum v7.1](../plan/addendum-v7.1.md), section B, with the
test or manual script that covers each and an honest status. Every prompt that touches SOS,
live location or emergency contacts updates this file.

- **Created in P014a1** (2026-10-09) from the failure-matrix table of that prompt's log; no
  earlier version existed.
- **Automated pass** is written only where tests exist and pass in the Android gate, and it
  says at which level. Tests run on the JVM (Robolectric); none runs on a phone.
- **Manual pending** means a person has to do it on a phone and has not reported it.
- A row can be both: the logic is tested, the behaviour of the platform is not.

Last gate run: P014a1, 2026-10-09, 896 tests, 0 failures.

## Plan v7 §7.5

| # | Scenario | Expected behaviour | Automated tests | Status |
| --- | --- | --- | --- | --- |
| 1 | No mobile data, cellular signal present | SMS sent from the device; server sync queued | None yet (P014b) | Not built. Manual pending |
| 2 | No signal at all | SOS screen shows the 112 button; device keeps retrying; local record kept | Record kept without any network: `SosCoreBoundaryTest` (the core has no network code), `SosEngineTest` (every step runs on the database alone) | Automated pass for "local record kept" only. Screen, 112 and retry: not built (P014a2, P014b). Manual pending |
| 3 | SafeRoute API down / 5xx | Device path unaffected; no duplicate sessions | P015 | P015 |
| 4 | App swiped away / killed by OEM | On restart, state restored from Room | `SosEngineTest`: `process death during the countdown with time left resumes with what remains`, `process death past the end of the countdown asks, and sends only on send now`, `process death past the end of the countdown, then cancel, leaves nothing`, `process death after the trigger and while active is found as still active`, `process death after resolved leaves nothing to recover`; `SosRecoveryTest` (all) | Automated pass at data and logic level. Service survival and the recovery screens: not built (P014a2). Manual pending |
| 5 | Phone rebooted during SOS | On boot or app open, the active SOS is detected and the user is asked | The same tests as row 4: a reboot is a process death with a later clock (`... is found as still active` moves the clock by a day) | Automated pass at data and logic level. Boot notice: not built (P014c). Manual pending |
| 6 | Double tap / duplicate trigger | Single session (`clientSosId`); the second trigger shows the existing one | `SosEngineTest`: `a second start in any unresolved state shows the existing emergency`, `ten starts at once make one emergency`, `the trigger does nothing before the end and happens exactly once at the end`, `ten triggers at once fire once` | Automated pass at data and logic level. The screens: not built (P014a2). Manual pending |
| 7 | SEND_SMS denied or not approved | SMS composer opens pre-filled | None yet (P014b) | Not built. Manual pending |
| 8 | No GPS fix | Last known location with its age; marked stale | Storage of a point with no accuracy and a mock flag: `SosEngineTest`, `points come back oldest first and never print where the user was` | Storage only. Fallback logic: not built (P014a2). Manual pending |
| 9 | FCM failure | Action recorded FAILED_RETRYABLE, retried | P015 | P015 |
| 10 | Worker crash mid-action | pg-boss re-delivers; no duplicate push | P015 | P015 |
| 11 | Accidental trigger | Cancel in the countdown; after the trigger, "I'm safe" | `SosEngineTest`: `cancel during the countdown deletes the record and its points`, `after the alert went out cancel is refused and I am safe resolves it`; `SosStateMachineTest`: `an alert that went out cannot be cancelled, only marked safe` | Automated pass at data and logic level. The follow-up message to contacts: not built (P014b). Manual pending |

## Addendum v7.1

| Scenario | Expected behaviour | Automated tests | Status |
| --- | --- | --- | --- |
| SOS started from the tile while the phone is locked | The countdown appears over the lock screen; nothing of the account is shown | The tile opens the emergency dialog today: `EmergencyShortcutsTest`, `EmergencyShortcutFlowTest` (P012f1). Countdown from the tile: P014c | Automated pass for the dialog only. Observed by Rahul on one phone (Samsung, Android 17): the dialog opened from the lock screen. Countdown: manual pending |
| Pinned notification dismissed or killed | SOS still starts from the tile and the app; re-posted at the next open | `EmergencyNotificationTest` (P012f2) | Automated pass for re-posting. Observed on the same phone. "Start SOS" action: P014c, manual pending |
| Tile unavailable on the device | Settings explains; the in-app control always works | `SettingsShortcutsTest` (P012f1) | Automated pass for the explanation. Not observed on a phone without tiles. Manual pending |
| Notification permission denied | No notification and no error; SOS never depends on it | `EmergencyNotificationTest`, `SettingsShortcutsTest` (P012f2) | Automated pass for the pinned notification. The running-SOS notice: P014a2, manual pending |

## Manual scripts

Record the phone's make, model and Android version with every result. Send messages only to
phones you control and whose owners you have warned.

P014a1 has no screen, so nothing in it can be tried on a phone except the upgrade:

1. **Upgrade keeps the contacts (database version 1 → 2).** With the previous build installed
   and at least one emergency contact saved, install this build over it (no uninstall). Open
   Settings → Emergency contacts in airplane mode: the same contacts are listed.
2. **Sign-out still works.** Sign out and in again: the contacts come back from the server.

Scripts for rows 2, 4, 5, 6, 8 and 11 need the countdown and active screens and arrive with
P014a2; rows 1 and 7 with P014b; the addendum rows and the boot row with P014c.
