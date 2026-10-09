# ADR 0027: SOS device flow

- **Status:** Accepted
- **Date:** 2026-10-09
- **Prompt:** P014a1 (records, state machine, recovery, retention). P014a2, P014b and P014c build on it and add notes here.
- **Plan refs:** Plan v7 §3.2 F-08, §5.3, §7.1–7.5, §12.2, §12.3; [addendum v7.1](../plan/addendum-v7.1.md), section B; [ADR 0008](0008-android-foundation.md), [ADR 0010](0010-adults-only-and-consent-records.md), [ADR 0015](0015-map-stack-and-location-policy.md), [ADR 0024](0024-emergency-contacts-and-opt-out.md)

Legal statements here are drafts, **to be verified by a lawyer**.

## Context

- An SOS must reach the user's emergency contacts with no SafeRoute server and no mobile data
  (Plan v7 §7). The phone is therefore the first place where an emergency exists.
- Android may end the app's process at any moment, and phone makers' battery managers do so
  more often than stock Android. An emergency that lives only in memory is lost with it.
- A wrong alert frightens people; a missed one is worse. The flow has a 5-second countdown
  that can be cancelled, and after it only "I'm safe" ends the emergency.
- The server part (P015) and the live link (P016) come later and must fit the same records.
- The repository is public, and the app must hold as little personal data as it can.

### What the spike found (A0, read on 2026-10-09)

Sources: the Android developer guides on foreground service types, on background start
restrictions and on the notification permission, and the platform sources of API level 37
(`TileService`, `KeyguardManager`, `Activity`, `Service`, `VibrationAttributes`).

| Topic | Finding | Certainty |
| --- | --- | --- |
| Foreground service of type `location` | Needs `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, the type on the `<service>`, and at run time a granted location permission with location switched on. The type must also be declared in the Play Console. | Certain (guide) |
| Starting it | It cannot be **created** while the app is in the background without `ACCESS_BACKGROUND_LOCATION`; on Android 14+ the system throws `SecurityException` at once. It must be started while an activity is visible. A start from a tap on the app's notification or widget is exempt. | Certain (guide) |
| `ForegroundServiceStartNotAllowedException` | Thrown since Android 12 when a foreground service is started from the background without an exemption. `BOOT_COMPLETED` is an exemption in general, but not for a type that needs a while-in-use permission. | Certain (guide) |
| Location permission missing | A `location` service cannot be started at all. The SOS must then run without that service. | Certain (guide); the fallback is a design task of P014a2 |
| Notification permission denied | The service runs; its notice is in the Task Manager and not in the notification drawer. | Certain (guide) |
| Sticky restarts | The background-start rule does not block the **restart** of a sticky service, but the guide's while-in-use rule still applies to a `location` service. Whether a restarted one gets positions is not stated. | Uncertain; the flow does not rely on it |
| `showWhenLocked`, `turnScreenOn` | The activity stays resumed on top of the lock screen. `requestDismissKeyguard` brings up the unlock for a secure lock and needs the activity to be visible. | Certain (sources) |
| Tile on a lock screen | `isLocked()` and `isSecure()` exist; a tile whose action is safe while locked starts an activity on top of the lock screen, otherwise `unlockAndRun`. Since Android 14 the start needs a `PendingIntent`. | Certain (sources); what each phone maker allows on its lock screen is not recorded |
| Vibration | Usages exist (`USAGE_ALARM`, `USAGE_NOTIFICATION`, ...). Only privileged apps can bypass the user's interruption settings. Whether an alarm-usage vibration plays in Do Not Disturb on each phone is not stated. | Uncertain; a manual check of P014a2 |
| UUID version 7 | The JDK has none. RFC 9562: 48 bits of time, version, 12 random bits, variant, 62 random bits. | Certain; implemented and tested |

## Decision

1. **The phone's record is the emergency.** Three Room tables in the app's private storage:
   `sos_records` (one row per emergency), `sos_actions` (one row per contact and action,
   with a copy of the name and number taken at the start; filled from P014b) and `sos_points`
   (the location trail; filled from P014a2). Deleting a record deletes its actions and points.
   The key is `clientSosId`, a UUID version 7 made on the phone, which P015 uses as the
   idempotency key. `syncState` is `NOT_SYNCED` and nothing is sent anywhere.
2. **States** (Plan v7 §7.2): IDLE → ARMING → COUNTDOWN → TRIGGERED_LOCAL → (SYNCING) →
   ACTIVE → RESOLVED. IDLE is "no record". **ARMING is not stored**: it is a finger on the
   button, and a hold the process did not survive counts as released. SYNCING is unused until
   P015. The transitions are one pure function (`nextSosState`).
3. **Write first, act second.** Every change of state is written before its side effect. The
   write is conditional ("only if it is still in state X") and reports whether it happened, so
   a timer that fires twice, two entry points or a retry after a restart produce one trigger.
4. **Timers are timestamps.** `countdownEndsAt` is stored when the countdown begins. Whoever
   runs the countdown rebuilds it from that value. A trigger before that time is refused.
5. **One emergency at a time.** A start while one is unresolved returns the existing one.
6. **Cancel only before the alert.** During the countdown, cancel deletes the record: nothing
   was sent and nothing remains. After the trigger, cancel is refused and "I'm safe" resolves.
7. **Recovery never decides for the user.** When the app finds a record that nothing is
   running (start, resume, a notification tap, after a restart of the phone):
   - countdown with time left → show the countdown again with the time that remains;
   - countdown whose end has passed → ask "Send now or Cancel". The prompt requires the
     question when the end is more than 60 seconds past; we ask **always**, even one second
     late, because a late alert that nobody chose is the worse error. A countdown that starts
     in the future (the clock was changed) is asked about too;
   - triggered or active → "SOS is still active": continue, or "I'm safe".
8. **The countdown is owned by the foreground service** (built in P014a2), not by the screen:
   a screen is destroyed by rotation, by the lock screen and by leaving the app, and the
   service is the component Android keeps alive longest. The service is started while the
   countdown screen is visible, which is what the platform requires for a `location` service.
9. **Hold to arm, 2 seconds, in the app; countdown only from the shortcuts** (addendum v7.1).
10. **Vibration only by default** during the countdown; sound is a setting. A loud phone can
    put the user at risk.
11. **Lock screen shows counts, never names or numbers.** "I'm safe" needs the unlock.
12. **Practice mode stores nothing.** The `practice` column exists for the data model and is
    always false: a practice run lives in memory, starts no service and reads no contact.
13. **Retention on the phone: 30 days.** Records by their start, points by their time, at
    every app start (and by a daily job from P014a2). Sign-out, the start screen and a
    blocked account delete everything. Never backed up (`allowBackup=false`).

## Alternatives considered

- **Keep the state in memory or in DataStore.** Simpler, but a killed process loses the
  emergency, and DataStore cannot change a value "only if it is still X" together with rows
  of a trail. Rejected.
- **Let the screen own the countdown.** Dies with the screen. Rejected.
- **Rely on a sticky service restart.** The platform does not promise positions to a
  restarted `location` service, and phone makers kill services anyway. Recovery from the
  record works in every case, so the flow uses that and nothing else.
- **Send automatically when a countdown is found a few seconds late.** Fewer taps, but it
  sends an alert at a moment the user did not see. Rejected for now; revisit with test data.
- **A library for UUID version 7.** A dependency for 20 lines. Rejected.
- **Store ARMING.** A write on every touch, and nothing to recover. Rejected.

## Consequences

- An emergency survives process death, a swipe from recents and a restart of the phone as
  data. What the user sees then is P014a2 (recovery screens) and P014c (boot notice).
- **Known limits.** If the process dies inside the 5-second window and nobody opens the app,
  nothing is sent until the user comes back and answers the question: there is no component
  that may wake the app by itself. Phone makers' battery managers can stop the service during
  an active emergency; the record stays, the trail pauses. A wrong system clock moves the
  30-day purge and, for a countdown, leads to the question instead of a silent send. Without
  a location permission no `location` service can run.
- The local database is not encrypted yet; `sos_points` and `sos_actions` make that follow-up
  more urgent. It must be decided before the closed test.
- A sign-out during an active emergency deletes it. Accepted for now: the records belong to
  the account; whether sign-out should be refused while an SOS is active is a follow-up.
- **What P015 adds:** SYNCING, the server session keyed by `clientSosId`, `syncState` values,
  push. The device path must never wait for it.
- Retention periods and what the trail may be used for are drafts for the lawyer.

## References

- Plan v7 §7; [addendum v7.1](../plan/addendum-v7.1.md), section B
- Prompt log: [`014a1-sos-data-and-state-machine.md`](../prompt-logs/014a1-sos-data-and-state-machine.md)
- Failure matrix: [`docs/sos/failure-matrix.md`](../sos/failure-matrix.md)
- Diagram: [`014a-sos-device-state-machine.svg`](../diagrams/014a-sos-device-state-machine.svg)
- Android guides: "Foreground service types", "Restrictions on starting a foreground service
  from the background", "Notification runtime permission"; RFC 9562
