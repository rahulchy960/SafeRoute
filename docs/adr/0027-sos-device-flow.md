# ADR 0027: SOS device flow

- **Status:** Accepted
- **Date:** 2026-10-09
- **Prompt:** P014a1 (records, state machine, recovery, retention); notes of P014a2 (runner, service, trail), P014a3 (screens), P014b1 (SMS engine) and P014b2 (sending, behind a gate) below. P014b3 and P014c add notes here.
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

### Note of 2026-10-09 (P014a2): what runs the emergency

Decision 8 said "the countdown is owned by the foreground service". Built slightly
differently, for a reason the spike gave: **a `location` service cannot start without a
location permission, and location must never block an SOS.**

1. **The countdown is owned by `SosRunner`, an object of the app's process** (app-lifetime
   scope), not by a screen and not by the service class. It rebuilds the timer from
   `countdownEndsAt`, asks `SosEngine` for every step and acts only on a "yes".
2. **The foreground service is the runner's host**: it keeps the process alive and in the
   foreground, shows the notification and follows the runner's state. With a location
   permission the host starts the service; without one, or when Android refuses the start,
   the same notification is posted as a plain one and the emergency runs without the
   service. Known limit of that case: Android may end the process sooner, and positions
   arrive only while the app is on screen.
3. **`START_NOT_STICKY`.** The system does not restart the service after killing the
   process. Recovery from the record is the one way back (decision 7).
4. **Nothing resumes by itself in the background.** `SosRunner.resume()` is called when a
   screen of the app becomes visible (P014a3) and by a tap on the notification. A process
   that Android starts for another reason leaves the record alone.
5. **The trail** (`SosTrail`): a position every 5 s, every 15 s below 10 % battery, checked
   again at every position. It starts with the countdown so that a position is ready when
   the alert goes out. Fallbacks, none of which waits or fails: no permission → no trail;
   location switched off or no Play services → unavailable; approximate permission → used
   and reported as approximate; no fix yet → the phone's last known position, stored with
   its real time so that its age shows; older than 30 s → reported as stale with its age.
   A position from a mock provider is kept and flagged. The SOS never asks for a permission.
6. **The notification** holds fixed text only ("SOS countdown" or "SOS active", "SafeRoute
   is not an emergency service: call 112") and one button, Call 112. Low importance, silent,
   public on the lock screen. "I'm safe" is added with the screen that can ask for the
   unlock (P014a3).
7. **Vibration** uses the alarm usage. The user's Do Not Disturb and silent settings still
   decide; only privileged apps may override them. Not verified on a phone.
8. **The daily clean-up** is a WorkManager job (KEEP policy, once a day), besides the one at
   app start. WorkManager brings `WAKE_LOCK` into the merged manifest; the app's own
   manifest gains `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` and `VIBRATE`.

### Note of 2026-10-09 (P014a3): what the user sees

1. **The arm step is the emergency dialog**, the one the SOS control already opened on Home
   and Search, with three additions: "Hold to start SOS" (2 seconds, a ring that fills, a
   light vibration at each quarter, an early release cancels), a note on what an SOS does and
   what location is used for, and "Practice SOS". Call 112 stays where it was. The prompt
   says "arm sheet"; a dialog was kept because every screen and test already leads to it.
2. **The words do not promise more than the app does.** Until the SMS alerts exist (P014b)
   the button says "Hold to **start** SOS", the question says "**Start** SOS now", and the
   arm note and the active screen say that the emergency contacts are not messaged in this
   version. A test fails on a text that says a contact is or will be messaged.
3. **The hold has an accessibility action.** A person who cannot keep a finger down
   (TalkBack, a switch) starts the countdown with the button's action; the countdown and its
   Cancel button are then the safeguard, as for the shortcuts.
4. **The emergency screen is `EmergencyActivity`**: the countdown (the number, a Cancel
   button of at least 72 dp, Call 112), the active emergency, and the questions after a
   restart. It is shown over the lock screen and switches the screen on. It owns nothing:
   every tap goes to `SosRunner`, in the app's scope.
5. **Back is not Cancel.** During the countdown and on the question the back gesture does
   nothing: only the Cancel button cancels, and only an answer answers.
6. **"I'm safe" needs the unlock, then a confirmation.** On a locked phone the screen asks
   Android to dismiss the lock (`requestDismissKeyguard`); the confirmation appears only
   after a successful unlock. The notification's "I'm safe" only opens this screen.
7. **The lock screen shows statuses in words.** The active screen holds no name, number or
   place, so it is the same locked and unlocked. When the SMS alerts add a line per contact
   (P014b), the locked screen must show counts only.
8. **Recovery.** When the main screen comes to the front and the phone remembers an
   emergency that the runner of this process is not running, the emergency screen opens: a
   countdown with time left continues, one past its end is asked about, an active one says
   "SOS is still active" with Continue and I'm safe. Once the runner has it, the main screen
   stays usable. A screen that Android re-creates never starts a new emergency.
9. **Practice mode lives in the screen's ViewModel**: the same screens with a PRACTICE
   banner, the same vibration, and no runner, record, service, contact or position. A real
   emergency always wins over a practice run.
10. **The location line is read when the screen appears or the trail changes.** The age of a
    stale position is therefore the age at that moment; it does not tick on the screen.
11. **The location disclosure for the trail** is the note in the arm dialog (ADR 0015). A
    draft for the lawyer.
12. **Not built here:** the sound setting for the countdown (vibration only is the default
    and, for now, the only mode); it belongs to the readiness screen of P014c.

### Note of 2026-10-09 (P014b1): the SMS engine

Built and tested, **not connected to a running SOS**: no build sends a message yet. The
connection, the permission flow and the notice version 2 are P014b2.

**What the spike found (B0, read on 2026-10-09).** Sources: the platform sources of API
level 37 (`SmsManager`) and Google Play's policy page "Use of SMS or Call Log permission
groups".

| Topic | Finding | Certainty |
| --- | --- | --- |
| Getting the SMS service | `Context.getSystemService(SmsManager.class)` since Android 12, then `createForSubscriptionId`; `SmsManager.getDefault()` and `getSmsManagerForSubscriptionId` are deprecated and still needed below Android 12 | Certain (sources) |
| Which SIM | `getDefaultSmsSubscriptionId()` returns the SIM the user chose for SMS, or the only active one, or "invalid". Listing the SIMs to offer a choice needs `READ_PHONE_STATE` | Certain (sources) |
| Sending | `sendMultipartTextMessage(destination, null, parts, sentIntents, deliveryIntents)` with the parts from `divideMessage`; one "sent" PendingIntent per part; throws `UnsupportedOperationException` on a device without telephony messaging | Certain (sources) |
| Results | The "sent" broadcast carries `RESULT_OK` or one of about 60 error codes (`RESULT_ERROR_*`, `RESULT_RIL_*`: radio off, no service, limit exceeded, SIM absent, short code refused, invalid format ...). `RESULT_OK` means "handed to the network", not "delivered" | Certain (sources) |
| No SIM, no service, airplane mode | Reported through those codes; which code each phone gives is not stated | Uncertain; every such code is treated as "try again" |
| Message length | 160 characters of the SMS alphabet in one part, 153 per part when split; one other character (any Bengali letter) makes it 70 and 67 | Certain (GSM 03.38); implemented and tested |
| Google Play | SMS permissions are restricted. The policy page lists an exception "Physical safety/emergency alerts to send SMS: apps that send SMS alerts in emergency situations", eligible permission `SEND_SMS`; it is temporary, "subject to Google Play review and approval", and must be declared in the Play Console's Permissions Declaration Form; the use must be core functionality | Certain (policy page) |
| Play: demo video, closed testing | The page read does not say whether a video is required or whether internal and closed test tracks are exempt | Not recorded; to be checked in the Play Console when the declaration is made |
| The composer (`ACTION_SENDTO` with several recipients) | Not examined in this half | P014b2 |

1. **One file may send an SMS**: `feature/emergency/SosSms.kt` (`AndroidSmsGateway`). The
   rest of the app sees `SmsGateway`. A test fails if another file names the platform's SMS
   service.
2. **`SEND_SMS` is a build switch.** The main manifest never declares it. The Gradle
   property `saferoute.sendSmsEnabled` merges a small extra manifest: by default debug builds
   have the permission and release builds do not. `BuildConfig.SEND_SMS_DECLARED` tells the
   code; CI checks that the release APK does not ask for it. A release with the permission
   is made on purpose, after Play approved the declaration.
3. **Who is told is decided once** (`SosAlerts.prepare`): the contacts the phone knows when
   the alert is prepared, with a copy of name and number in `sos_actions`. Later changes of
   the list add nobody. A contact who is no longer allowed after a fresh fetch is marked
   SKIPPED if their message has not left.
4. **Write first, act second, again.** PENDING → IN_PROGRESS is written before the phone is
   asked; SENT only on the phone's `RESULT_OK`. A SENT message is never sent again.
5. **Retries**: every 30 seconds, at most 20 tries, only while the caller says the emergency
   is still wanted. A code that cannot pass (invalid number, refused by the network, no
   permission) is final at once.
6. **A message that was IN_PROGRESS when the process died is tried again.** The phone may
   have sent it, so that contact can get the alert twice. Accepted: a second alert is the
   smaller harm than none.
7. **Follow-ups go only to contacts whose alert was sent.**
8. **The message** (`SosMessage.kt`, mirrored in
   [`docs/legal/sos-sms-text-v1.md`](../legal/sos-sms-text-v1.md)): who, a map link, accuracy,
   the clock time in India, the age if a minute or more, the battery, "Sent by the SafeRoute
   app", "call 112". At most three SMS parts; when longer, the battery goes, then the sender
   line, then the name is shortened. Numbers in Latin digits in both languages.
9. **Why a plain map link.** `https://maps.google.com/?q=<lat>,<lng>` is opened by the map
   app of nearly every phone, and the coordinates can be read in the link by someone whose
   phone opens nothing. The app sends nothing to that service; it writes text. Alternatives:
   a `geo:` link (not a link in most SMS apps), an OpenStreetMap link (longer, and fewer
   phones open it in a navigation app), SafeRoute's own live link (P016, needs the server),
   a link shortener (a third party would see every alert; never).
10. **The coordinates are not repeated outside the link.** The prompt asked for them "in
    text as well"; a second copy would push a Bengali message past three parts.
11. **No SIM choice in the app.** It would need `READ_PHONE_STATE`. The phone's own default
    SIM for SMS is used.
12. **The message texts are Kotlin constants, not string resources**: they are sent in the
    language the sender chose, not in the language the phone displays, and they are not
    shown on a screen.
13. **No delivery reports** are requested: networks answer them unevenly, and "delivered"
    would be shown as a fact the app cannot stand behind.

### Note of 2026-10-09 (P014b2): sending, behind a closed gate

The engine is connected to a running SOS. **No alert can be sent yet**: the gate
(`SosAlertPolicy`) answers "no" until the `sos_alerts` notice version 2 exists and the user
has agreed to it (P014b3; ADR 0010, no processing before consent). Tests open the gate with
a fake.

1. **The messages never hold up the emergency.** `SosRunner` writes the trigger, starts the
   host and the trail, and then tells `SosDispatch`, which works in the app's scope. Nothing
   it does is waited for by the countdown or the trigger, and a failure changes nothing
   about the SOS.
2. **Order of an alert:** the gate; who (`SosAlerts.prepare`, once); at most 3 seconds for a
   position taken since the countdown began; then send. Without a location permission or
   with location off there is nothing to wait for.
3. **One location update.** If the alert left without such a position and one arrives within
   60 seconds of the trigger, one update goes to the contacts whose alert was sent. Never a
   second one. A retried alert carries the newest position by itself.
4. **The fresh contact list is never waited for.** The prompt asked for "a non-blocking
   refresh with a 1.5 s limit". It is started at the trigger and limited to 1.5 seconds; if
   its answer arrives while a message is still unsent (for example during the wait for a
   position, or between retries), a contact who is no longer allowed is marked SKIPPED.
   **Residual risk:** with a position ready, the messages leave at once, so an opt-out made
   shortly before the trigger and not yet on the phone is missed. The phone's copy is
   refreshed at every app start and on the contacts screen. A point for the lawyer.
5. **Two ways of sending** (`SmsModeSource`): automatic when the build declares `SEND_SMS` and
   the user granted it, otherwise the composer.
6. **Composer mode.** The phone's SMS app is opened with all numbers (`smsto:` with `;`
   between them) and the text; the user presses Send. Because Android does not let an app
   open another app from the background, a high-importance notification "Send your SOS
   alert" with the same destination is always posted as well, with Call 112 as its button;
   the active screen has "Open SMS app again". The app records nothing as sent in this mode
   and says so: "It is sent only when you press Send there." Which separator each SMS app
   accepts for several recipients was **not verified** (B0); it is a phone check.
7. **After the app was closed** (`resumed`): retries continue from the records; the SMS app is
   not opened a second time; no second update is sent.
8. **"I'm safe"** has a checkbox "Tell my contacts that I am safe", ticked, shown only when
   a message was sent or handed to the SMS app. The emergency is written down as resolved
   first; then the follow-up goes to the contacts whose alert was sent (in composer mode the
   SMS app opens with it). The host stays up to 10 seconds so that the message can leave,
   then ends; a follow-up that failed is retried for a while in the background.
9. **The active screen shows counts**: sent of total, still trying, could not be sent, not
   sent because the contact opted out, and the sentence that a sent message may still not
   arrive. No name and no number of a contact is on the screen, locked or unlocked: the
   prompt allowed names when unlocked, counts are enough and cannot leak.
10. **The name is not asked for yet**: the message uses its wording for "no name" until the
    SOS setup of P014c. The language is the app's language.

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
- **Put the countdown inside the service class.** Then an SOS without a location permission
  has no countdown at all, and the logic can only be tested through Android. Rejected
  (P014a2).
- **A second service type for the case without location** (`shortService`, `specialUse`).
  The first ends after about three minutes; the second needs a Play declaration for a use
  Play has a type for. Rejected for now; the readiness checklist (P014c) asks for the
  permission instead.
- **Wait up to 1.5 seconds for the fresh contact list before sending.** Fewer missed
  opt-outs, but a device-side SOS action would wait for the server, which Plan v7 §7 and the
  project rules forbid. Rejected (P014b2).
- **Mark composer messages as sent.** The app cannot know; it would show a fact it does not
  have. Rejected.
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
