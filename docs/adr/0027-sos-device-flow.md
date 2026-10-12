# ADR 0027: SOS device flow

- **Status:** Accepted
- **Date:** 2026-10-09
- **Prompt:** P014a1 (records, state machine, recovery, retention); notes of P014a2 (runner, service, trail), P014a3 (screens), P014b1 (SMS engine), P014b2 (sending, behind a gate) and P014b3 (consent and the switch-on), P014b6, P014b7 and P014c1a (entry points) below. The rest of P014c adds notes here.
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

### Note of 2026-10-09 (P014b3): consent, the permission, and the switch-on

From this note on a user who has agreed to the notice version 2 can start an SOS that
messages their emergency contacts. Nothing was verified on a phone.

1. **The gate is the consent, read on the phone.** `ConsentSosAlertPolicy` is open only when
   the version of the `sos_alerts` notice the user agreed to equals the one the app shows
   today. That version is a flag in the app's DataStore, written when the server recorded the
   consent (`grantConsent`) or reported it (`hasConsent`, also once after every sign-in), and
   removed on withdrawal and sign-out. An SOS therefore needs no network to ask, and a
   failed check neither gives nor takes consent.
2. **Notice version 2** (`2026-10-alerts-draft2`,
   [`docs/legal/sos-alerts-notice-v2.md`](../legal/sos-alerts-notice-v2.md)) describes what
   happens: SMS from the user's SIM, the map link, a possible second and third SMS, the
   possible cost, the SMS app as the fallback, the positions kept on the phone, not an
   emergency service. Version 1 stays in the repository as the record of what earlier users
   were shown.
3. **A consent for version 1 does not count.** Such a user keeps their contacts, and is
   shown the new notice at "Add contact" or "Read how SOS alerts work". Until they agree,
   alerts are off.
4. **Without the consent no SOS can be started from the app.** The emergency dialog then
   shows Call 112, "Practice SOS", the sentence that alerts are not set up, and (on Home) a
   button to the setup. The prompt says "declining leaves only Call 112 available"; a
   practice run is kept because it touches nothing. The emergency screen asks the gate again
   before it starts anything. This reverses P014a3, where any signed-in user could start an
   SOS that messaged nobody.
5. **The words changed with the facts**: "Hold to send SOS", "Send now", and the texts that
   said "in a later version" or "not messaged in this version" are gone. A test fails on a
   text that calls a message delivered or read, or the user safe.
6. **"Send alerts automatically"** is a card under Emergency contacts, shown once alerts
   are set up. States: on; off (with "Allow sending SMS"); blocked (Android no longer asks:
   a button to the app's system settings); and "this version cannot send SMS by itself" for
   a build without the permission. "Allow sending SMS" opens the app's own disclosure first;
   only "Continue" leads to Android's dialog. The state is read again on every return to the
   screen. The permission is never requested during an SOS, at app start or in onboarding;
   a source test pins the one file that may request it.
7. **Play declarations** are drafted in `docs/play/` for Rahul to adapt. Release builds stay
   without `SEND_SMS` until a declaration is approved.

**Still open after part b:** the name in the message and the SOS language setting (P014c);
which separator SMS apps accept; every phone check.

### Note of 2026-10-12 (P014b6): debug builds text only named test numbers

**The problem.** A debug build carries `SEND_SMS` so that alerts can be tried on a phone, and
from P014b3 an SOS that finishes its countdown texts every emergency contact of the account.
A developer's or tester's account can hold the numbers of real people. One forgotten
countdown, or a test of something else, would alert them.

**Decided.**

1. **A build can limit who it texts by itself** (`core/emergency/SmsRecipientLimit`). A
   release build sets no limit. A debug build allows only the numbers in the Gradle property
   `saferoute.debugSmsAllowlist`, which lives in the user-level `gradle.properties` and
   becomes a `BuildConfig` field of the debug build type only.
2. **Two locks.** `SosDispatch` marks every waiting message to a number outside the list as
   SKIPPED with the category `debug_not_allowlisted` before the first message leaves; and
   the SMS gateway itself refuses such a number, whoever asks.
3. **No list means no automatic sending.** With an empty or missing list, or a list that
   matches none of the contacts, the phone's SMS app is used for every contact, as in a build
   without the permission. Nothing leaves without the user reading it and pressing Send.
   The SMS app is not limited by the list: there the user sees every recipient.
4. **The build says what it does.** A banner on the arm step, the countdown and the active
   screen gives the number of contacts that will really be texted, or says "composer mode"
   and why. The banner, its strings, the list and the class that reads it are in `src/debug`;
   `src/release` holds stand-ins that do nothing. CI checks the release APK for them.
5. **Counts only.** The numbers on the list are never shown, logged or printed; a malformed
   property fails the build with a message that does not contain it.

**What does not change.** The consent gate, the notice and its version (this is a property
of a test build, not of what an SOS does for a user), who is decided at the trigger, the
retries, the follow-ups (they go only to contacts whose alert was sent), and every release
build.

**Rejected.** Building debug without the permission by default: the automatic path could then
be tried only with an extra flag, and a build made with that flag would again text everyone.
A confirmation dialog in debug builds: one more tap in the flow under test, and it protects
nobody who taps through. Hiding the limit inside the gateway only: the record would then say
"failed" for something that was never meant to be sent.

**Residual risks.** A debug APK built with a list contains those numbers. In composer mode a
tester can still press Send to real people; the SMS app shows them who. A tester who puts a
real person's number on the list has chosen to text them.

### Note of 2026-10-12 (P014b7): an unsent alert is retried while the SOS is active

**The problem.** Point 5 of the note of P014b1 gave up a message after 20 tries, about ten
minutes. Plan v7 section 7.5, row "No signal at all", says that the device keeps retrying
and that the SMS is retried when signal returns. A user who starts an SOS in a place
without signal and reaches signal fifteen minutes later had alerted nobody.

**Decided.** This replaces point 5 of the note of P014b1 for the alert itself.

1. **An alert that is not sent is tried again until the SOS ends.** The wait after a try is
   30 seconds for the first ten tries, then 1, 2 and 4 minutes, then 5 minutes each time
   (`alertRetryDelayMillis`). After five minutes of failure the next thirty seconds are
   unlikely to differ, and every try wakes the radio.
2. **The schedule is in the record, not in memory.** When a message is due again follows
   from its stored number of tries and the time of the last one. A new process continues
   the same schedule; the database did not change.
3. **An extra try at once**, without waiting: the button "Send again now" on the active
   screen; the emergency screen coming to the front; the app opened again after a process
   death; airplane mode switched off; a network becoming available. Signals from the phone
   are followed at most every ten seconds. A try asked for while one is in flight is
   ignored, so no message is handed to the phone twice.
4. **Final stays final.** An error that cannot pass is never tried again and is shown as
   "could not be sent". A sent message is never sent again.
5. **After 2 hours with an alert unsent the user is told once**: a notification with fixed
   text ("Some alerts have not been sent. Call 112 or send a text yourself.") and the same
   sentence on the active screen. That it was said is written into the action table (a row
   of the type `UNSENT_NOTICE`, with no contact, number or name), so a new process does not
   say it again.
6. **24 hours after the trigger trying stops.** What is still unsent becomes "could not be
   sent" with the category `expired`, and the screen says that trying has stopped. The SOS
   itself goes on until the user ends it.
7. **Follow-ups keep their limit.** The "safe" message and the location update are given up
   after 20 tries as before: they run beside or after the emergency and must end.

**What was verified for the signals** (Android SDK sources, API 37, read on 2026-10-12):

| Signal | What it needs | Used |
| --- | --- | --- |
| `Intent.ACTION_AIRPLANE_MODE_CHANGED` | A broadcast only the system can send; no permission to receive it with a receiver registered in code | Yes |
| `ConnectivityManager.registerDefaultNetworkCallback` | `ACCESS_NETWORK_STATE`, granted at install. The app's own manifest does not declare it; it is in the merged manifest through the sign-in libraries (ADR 0012). The code checks for it and leaves the signal out if it is ever missing | Yes |
| `Intent.ACTION_SERVICE_STATE` (the mobile network's own service) | `READ_PHONE_STATE` | No |
| `TelephonyCallback.ServiceStateListener` | Its documentation names no permission, but what it delivers without one could not be checked without a phone | No |

A data network becoming available is not the same as the mobile network accepting an SMS;
it is a hint that conditions changed. The regular tries never depend on a signal.

**Battery.** No new service, alarm or wake lock. The timer is a coroutine in the app's
scope; the emergency's foreground service, which already runs for the location trail, keeps
the app alive. An unsent alert costs 11 tries in the first five minutes, 15 in the first
seventeen, then 12 an hour: about 36 in two hours and about 300 in 24 hours per message,
each a short use of the radio. This is an estimate from the schedule; **nothing was
measured on a phone**. It is small beside the location trail, which asks for a position
every 5 seconds (every 15 below 10% battery) for the whole SOS. Without a wake lock a try
can be late while the phone sleeps deeply; it is made when the phone wakes.

**What does not change.** The consent gate; who is alerted (decided at the trigger); the
notice the user agreed to, which names no duration for retries; the number of messages a
contact gets (one alert, at most one location update, at most one follow-up); the debug
list of P014b6; the permissions.

**Rejected.** A wake lock or exact alarms for the timer: a new permission and a real
battery cost for a message that goes out at the next wake-up anyway. `READ_PHONE_STATE` for
the service state: a sensitive permission for a hint. WorkManager for the retries: it
would run them after the SOS screen and service are gone, with its own delays, and "nothing
resumes an emergency in the background" (note of P014a2). Retrying for ever: an SOS that
was forgotten would text a contact days later.

**Residual risks.** An alert can arrive hours after the trigger; it is written anew at each
try, with the newest position and the clock time at which that position was taken. A try asked for by a tap or a signal counts as a try, so the
wait before the next regular one can grow sooner. The two numbers (2 hours, 24 hours) are
choices of this prompt, not plan text.

### Note of 2026-10-12 (P014c1a): entry points

Decision 9 said "hold to arm in the app; countdown only from the shortcuts". Until this
note the shortcuts opened the emergency options and started nothing. Nothing was verified
on a phone.

**What the spike found (read on 2026-10-12).** Sources: the Android guide "Restrictions on
starting activities from the background", the AndroidX release page of Glance, and the
platform sources of API level 37 (`TileService`, `AppWidgetProviderInfo`).

| Topic | Finding | Certainty |
| --- | --- | --- |
| A tile starting an activity | `startActivityAndCollapse(PendingIntent)` from Android 14; the `Intent` form throws there and is the only form before. The app already chose by version (P012f1) | Certain (sources) |
| A tile on a locked phone | `isLocked()` says so in its own documentation: an action that is safe while locked starts an activity on top of the lock screen; anything else uses `unlockAndRun`. `isSecure()` tells whether a PIN, pattern or password is set | Certain (sources). What each phone maker's lock screen shows of Quick Settings is not recorded |
| The emergency activity over the lock screen | `showWhenLocked` in the manifest and `setShowWhenLocked`, `setTurnScreenOn` in code (P014a3). Unchanged | Certain (sources) |
| Starting the `location` service | It must be started while an activity of the app is visible (the spike of P014a1). The emergency screen is that activity for every entry point, locked or not: the runner starts the host from the screen's start | Certain (guide) for a visible activity. That an activity shown over the lock screen counts as visible on every phone: a phone check |
| A notification action starting an activity | Allowed: "the activity is started from a PendingIntent that was sent by the system (for example, from a notification tap)". It must be an activity PendingIntent: since Android 12 an app may not start an activity from a receiver or service in answer to a tap | Certain (guide) |
| A widget starting an activity | Allowed: "the launch is initiated by the device's launcher app, such as when a user ... interacts with a widget" | Certain (guide) |
| PendingIntent rules of Android 14 and 15 | A launch needs the creator or the sender to lend its right to start activities; since the app targets 15+, the app as creator lends none by default. The tile, the notification and the widget are sent by the system or the launcher, which have that right themselves | Certain (guide) for notifications and widgets. The guide does not mention tiles; the tile was reported to open the screen on one Android 17 phone (P012f1) |
| RemoteViews or Glance | Glance is stable (1.2.0). It would bring a library and its own Compose runtime for one button that never changes | Certain (release page). RemoteViews chosen: no dependency |
| A widget on the lock screen | A widget is offered for the home screen unless it declares the keyguard category; the sources say that some lock-screen features may ignore that | Uncertain per phone; the widget declares the home screen only |

**Decided.**

1. **One use case.** `core/emergency/StartSos(entryPoint)` is the only way an SOS starts.
   It asks the consent gate (`SosAlertPolicy`) and then `SosRunner.start`. Its answers:
   not set up, started, already running.
2. **One screen calls it.** Every entry point opens `EmergencyActivity` with "start" and
   what was touched (`EmergencyShortcut.startIntent`): the hold in the app (`IN_APP`), the
   tile (`TILE`), the "Start SOS" button of the pinned notification (`NOTIFICATION`) and
   the home-screen widget (`WIDGET`, since P014c1b). The screen's ViewModel hands it to
   the use case in the app's scope. So the countdown and the foreground service start while
   that screen is visible, for every entry point, exactly as after the hold.
3. **The entry point is stored** on the record (`sos_records.entryPoint`, plain text since
   P014a1; all four values existed, so the database did not change).
4. **Gate closed: no countdown.** The screen shows the emergency dialog with Call 112, the
   sentence that SOS alerts are not set up, "Practice SOS" and "Set up SOS alerts". Nothing
   is written down. "Set up SOS alerts" opens the app at Emergency contacts; on a locked
   phone Android asks for the unlock first, because the main screen is never shown over the
   lock screen.
5. **A second start shows the first.** A start while an emergency exists returns it; the
   record keeps the entry point that started it, and the countdown is not started again.
   Android replaces the emergency screen when a shortcut is used again; the new one shows
   what the runner is running.
6. **Only a newly created screen starts.** A screen that Android re-creates from its saved
   state (rotation, a process restart) carries the same intent and starts nothing.
7. **The tile never waits for the unlock.** On a locked phone the countdown appears on top
   of the lock screen with Cancel and Call 112. `unlockAndRun` is not used: an emergency
   must not wait for a PIN. What the screen shows there is fixed text, a number and counts;
   "I'm safe" still asks for the unlock (P014a3).
8. **The pinned notification**: the button "Open SOS" became "Start SOS". A tap on the
   notification's body still only opens the emergency screen, as its text says. Title,
   text, channel and importance did not change, nor when it is posted (P012f2).
9. **No confirmation step outside the app.** The five-second countdown, with a vibration
   each second and a Cancel button of at least 72 dp, is the safeguard against a start that
   nobody meant. A question before the countdown would cost the seconds that matter and
   would be tapped through.
10. **Notice version 3.** Version 2 did not say that an SOS can be started from outside the
    app, with one tap and on a locked phone. One sentence was added
    ([`docs/legal/sos-alerts-notice-v3.md`](../legal/sos-alerts-notice-v3.md)), so the
    version changed and a consent given for version 2 no longer opens the gate: such a user
    is asked again before any SOS can start. The sentence names "a SafeRoute shortcut
    outside the app" and gives the tile and the notification as examples, so that the
    widget needs no further version.

**Residual risks.**

- **A start nobody meant.** In the app an SOS needs a two-second hold. Outside it one tap
  is enough: a tile next to the torch, a notification button, a widget on the home
  screen. A phone in a pocket with the screen on, a child, or a stranger holding a locked
  phone can start the countdown; if nobody cancels within five seconds, the contacts are
  texted. How often that happens is not known. A follow-up measures it in the closed test
  (alerts followed by "I'm safe" within a minute, by entry point, counted on the phone and
  reported by the testers, not uploaded).
- **Anyone holding the locked phone can start an SOS and cannot end it.** That is the
  design: starting must not need the unlock, ending must.
- **The not-set-up dialog can be seen on a locked phone.** It says that SOS alerts are not
  set up: a status, with no name, number or place.
- **A user who agreed to version 2 loses the SOS until they agree again**, and learns it
  only when they open the dialog or use a shortcut. The readiness screen (P014c2) shows it
  beforehand.

**Rejected.** A broadcast receiver or a service between the tap and the screen: Android
forbids it for notification taps, and the `location` service could not be started from
it. Starting the runner from the tile service itself and opening the screen afterwards: the
service would start before any activity is visible. `unlockAndRun` for the tile: see 7.
A second activity for shortcuts: two screens that could differ.

### Note of 2026-10-13 (P014c1b): the home-screen widget

The fourth entry point of the note above, built as decided there. Nothing was verified on
a phone.

1. **A plain `AppWidgetProvider` with RemoteViews**, no new library. The widget shows fixed
   text, the same for everyone: a red button "SOS" and the line "Tap to start SOS".
2. **A tap starts nothing by itself.** It carries the same immutable activity PendingIntent
   as the tile, with the entry point `WIDGET`; the emergency screen asks `StartSos`.
3. **Against a tap nobody meant:** the widget is 2 by 2 cells and cannot be resized, so it
   cannot be shrunk to one cell; only the button reacts, and the card around it is dead
   space. There is no confirmation step (decided in the note above); the countdown is the
   safeguard. A widget on the home screen is touched by passing thumbs more often than a
   tile in Quick Settings: the follow-up that measures unintended starts counts it apart.
4. **Never updated, no network, nothing of the account.** `updatePeriodMillis` is 0; there
   is no configuration screen. The provider is not exported and needs no permission.
5. **Home screen only.** The widget does not declare the lock-screen category. The Android
   sources say that some lock-screen features may offer a widget without it; what each
   phone does is a phone check (script M17, part 7).
6. **Colours.** A launcher draws the widget and cannot read the app's Compose theme, so
   four colours repeat the theme in `res/values/colors.xml` (light and night). A test
   keeps the red equal to the emergency red and fails if anything but the button uses it.
7. **The notice did not change.** Version 3 says "a SafeRoute shortcut outside the app,
   such as its Quick Settings tile or its pinned notification"; the widget is such a
   shortcut. Whether it must be named is a question for the lawyer.

**Not built:** a button in Settings that offers the widget (`requestPinAppWidget`). The
widget is found in the launcher's widget list; a follow-up.

### Note of 2026-10-13 (P014c2a): the readiness checklist

**The problem.** An SOS can run and reach nobody: no agreement to the current notice, no
contact on this phone, a list that never arrived, a phone maker's battery manager. Until
now the user learned of it during the emergency (failure matrix, rows A6 and A7, and its
known gap "no warning").

**Decided.**

1. **A checklist, "SOS setup"**, under Settings → Emergency contacts. Each row has a status
   in a word and, where the user can change something, a button that leads there. The sum
   is "Ready" or "Not ready: N things to fix". It never says "safe", and under the sum
   stands that "Ready" does not promise that an alert reaches anyone, with 112.
2. **The rules are one pure function** (`core/emergency/sosReadiness`). An SOS never asks
   it: the checklist informs, it gates nothing. The gate stays the consent alone.
3. **What counts as "to fix":** no agreement to the current notice; no contact on this
   phone who has not opted out; a list never fetched with no contact on the phone (the app
   cannot tell "none" from "never arrived", and Refresh settles it); the SMS app instead of
   automatic sending, **only in a build that could send by itself**; less than precise
   location, or location switched off; notifications not allowed for the app.
4. **What never counts:** the battery row. No app can see what a phone maker's battery
   manager will do, so it is always "SafeRoute cannot check this", with guidance. In a
   build without the SMS permission the SMS row is "For your information". An old copy of
   the contact list is said in words ("Updated 12 days ago") and is not by itself a fault:
   the plan names no age at which a list is too old.
5. **Read at every resume, nothing in the background.** The screen reads the phone when
   it comes to the front, also on the way back from the phone's settings. It stores
   nothing, logs nothing and uploads nothing. The only request it can cause is the fetch
   of the contact list when the user taps Refresh.
6. **The time of the last fetch** is a new flag in the app's DataStore
   (`contacts_synced_at`, a point in time). It is written together with the list, only
   when the list was really written, set to "never" when the copy is emptied, and cleared
   at sign-out with the file.
7. **No new place that asks for a permission.** The project's location policy (ADR 0015)
   allows Android's location dialog only after the user tapped "my location" on the map
   and read the disclosure. The checklist therefore opens the app's page in the phone's
   settings for location and for notifications, and the phone's Location page when
   location is switched off. The SMS row shows the existing card (`SmsPermissionCard.kt`),
   the one file that may ask for the SMS permission, after its own disclosure.
8. **Battery: guidance only.** A button opens the app's page in the phone's settings; an
   intent to a settings page needs no permission. The app does not request
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (a non-goal of the prompt; Play restricts it).
   Four lines name starting points for Samsung; Xiaomi and Redmi; Vivo; Oppo and Realme.
   **They are written from general knowledge and were not checked on any phone**; the
   screen says so. No link to a community reference: no official address could be
   verified, so none is shown.
9. **Three facts instead of three settings.** The countdown has no sound, the alert is
   written in the app's language, and the alert carries no name. The checklist says so.
   The settings for them do not exist; they are P014c2b.

**Split.** Part 2 of P014c asked also for a name for alerts, the alert language and the
countdown sound as settings, for an offer of the screen after the first contact and for an
entry on Home while not ready. None of the three settings exists, and a name changes what
an SOS sends and what the phone stores, which needs its own decision about the notice.
They are P014c2b.

**Residual risks.** "Ready" can be wrong about things the app cannot see: a SIM without
balance, no signal, a battery manager, a contact whose phone is off. A user who never
opens the screen is not warned (until P014c2b offers it). The battery hints can be wrong
for a given phone and version.

### Note of 2026-10-13 (P014c2b): the name, the language and the sound

Decision 10 of this ADR said "vibration only by default; sound is a setting", the note of
P014b2 said "the name is not asked for yet", and the note of P014c2a showed all three as
facts. They are settings now. Nothing was verified on a phone, and no sound was heard.

1. **Where they are kept.** A table with one row, `sos_settings`, in the app's private
   Room database (version 3; a migration that only adds the table). The name is a piece
   of personal data, and the app's settings file holds flags only (ADR 0012). The language
   and the sound are flags and could have gone into that file; one place for the three was
   chosen so that they are read, written and erased together.
2. **The name** is what the user types for this purpose, at most 30 characters, one line
   (the rule the message engine has had since P014b1). The app has no other name for the
   user and asks for no proof of one. It is optional: without it the alert says, as
   before, that someone who listed the contact needs help.
3. **Never to the server.** The name leaves the phone only inside the SMS the phone sends
   to the user's own contacts. It is read from the database at the moment an alert is
   written, so a name removed during an SOS is gone from the next message.
4. **Erased with the contacts**: when the user stops SOS alerts, signs out, or the account
   is blocked (`ContactsRepository.clearLocal`). The two choices go back to their defaults
   with it. Not backed up (`allowBackup=false`).
5. **The language of the alert** is the app's language (the default), English or Bengali:
   a contact may read only one of them, whatever language the user's own screen is in.
6. **The countdown sound is off by default** and stays a deliberate choice, with the
   sentence that a phone that makes a noise can put its owner at risk. When on, a short
   built-in tone is played each second of the countdown, besides the vibration, on the
   alarm volume (`ToneGenerator`, `STREAM_ALARM`). It needs no permission. The user's
   alarm volume, silent mode and Do Not Disturb still decide; the app cannot and does not
   override them. A phone that refuses to make a tone gets none, and the countdown goes
   on. A practice run sounds the same, so that the setting can be tried.
7. **The runner reads the sound setting once**, when a countdown begins or is continued in
   a new process, from the phone's own database. A setting that cannot be read means "no
   sound".
8. **Notice version 4.** A name is new in what an SOS sends and in what the phone stores,
   so one clause was added to the third paragraph
   ([`docs/legal/sos-alerts-notice-v4.md`](../legal/sos-alerts-notice-v4.md)) and the
   version changed: a consent for version 3 no longer opens the gate. The language and the
   sound change nothing the notice describes.
9. **None of the three is a row of the checklist.** They change what an alert says and
   how a countdown is felt, never whether an SOS can start.
10. **The field is inside the card, not in a dialog.** A text field in a dialog never came
    to rest under the test framework; the editor follows the one that renames a contact.

**Residual risks.** The fourth notice version in five days: every tester is asked again
(there are no released users). A user can type any name, also someone else's; the alert
still comes from their own phone number. A sound in an emergency can give a person away;
the default and the warning are the mitigation. Whether the tone is audible, and how it
behaves in silent mode and Do Not Disturb, is known for no phone.

### Note of 2026-10-13 (P014c2c): the checklist is offered

The note of P014c2a ended with "a user who never opens the screen is not warned". Two
places lead there now. Nothing was verified on a phone.

1. **Once, after the first contact.** When the phone knows of at least one contact, the
   Emergency contacts screen shows a card "Check that an SOS would work", once. "Open SOS
   setup", "Not now" and opening the checklist by its own button all end it for good. A
   user who had contacts before this change sees it once too.
2. **On Home, while something is to fix.** The bottom sheet shows "Not ready: N things to
   fix" with the way to the checklist. N is the checklist's own number: one class,
   `SosReadinessReader`, reads the phone for both, so they cannot disagree.
3. **Only once SOS is being set up**: the user agreed to the notice, or a contact is on
   the phone. Before that the card "Add emergency contacts" (P013b2) is the right one,
   and two cards would say the same thing twice. A user whose consent was for an older
   notice version has contacts and a closed gate: they are told here, which the notes of
   P014c1a and P014c2b asked for.
4. **No background work.** Home looks at the phone when it comes to the front, as it
   already does for the location permission. Nothing is watched while Home is not shown,
   no location updates are started for it, nothing is fetched and nothing is logged.
5. **"Not now" hides the Home card for three days**, the period the contacts card uses.
   It comes back while something is still to fix. It is not a dialog and never covers the
   SOS control: it is a card in the sheet's content.
6. **Two flags** in the app's settings file: the offer was answered, and until when the
   Home card is put off. Both are cleared at sign-out with the file.

**Residual risks.** The Home card is inside the sheet, below what the sheet shows at its
lowest position: a user who never pulls the sheet up does not see it. A user who chose
approximate location on purpose is reminded every three days; the alternative, a "never
show again", would also silence the case that matters most (no contact, no consent). The
count on the card is as fallible as the checklist: it sees no SIM balance, signal or
battery manager.

### Note of 2026-10-13 (P014c3): after a restart, and when the account goes away

Two sentences of this ADR are superseded by this note (the text above stays as it was
written). "Consequences" said that what the user sees after
a restart is "P014c (boot notice)", and that "a sign-out during an active emergency
deletes it. Accepted for now". Nothing was verified on a phone.

**What was verified for the restart** (the Android guide "Support Direct Boot mode", read
on 2026-10-13, and the platform sources of API 37):

| Topic | Finding | Certainty |
| --- | --- | --- |
| When the app's data can be read | The default storage of an app is "credential encrypted storage ... only available after the user has unlocked the device". The app's database and settings file are there | Certain (guide) |
| Which broadcast | `ACTION_LOCKED_BOOT_COMPLETED` is for components that declared themselves aware of direct boot; "by default, apps don't run during Direct Boot mode". An app that can wait listens for `ACTION_BOOT_COMPLETED` | Certain (guide). The guide does not state the moment of delivery in one sentence; that it follows the first unlock is what the two statements together mean |
| Starting the location service from that broadcast | Not allowed for a type that needs a while-in-use permission (the spike of P014a1) | Certain (guide) |
| A phone maker withholding the broadcast | Not documented | Unknown; a phone check (script M27) |

**Decided: after a restart.**

1. **The receiver that already runs after a restart also looks at the record.** It is the
   one receiver of P012f2 and still answers only to a restart of the phone and to an update
   of this app. The app does not take part in direct boot, so it runs after the first
   unlock, when the database can be read.
2. **Only an emergency whose alert went out is announced** (triggered or active). A
   countdown that did not finish sent nothing; the app asks about it when it is opened
   (decision 7), and a notification about "an SOS that was starting" would alarm for
   nothing.
3. **One notification, high importance**, with fixed text: "SOS was active when the phone
   restarted" (or "... when SafeRoute was updated": an update ends the process in the same
   way), and that it is not running now. Three buttons, each an immutable activity
   PendingIntent: Continue, I'm safe, Call 112. It is shown on the lock screen; it holds
   no name, number or place.
4. **It continues nothing.** The receiver holds no way to reach the runner, the service or
   the location trail (a test reads its sources). Between the restart and "Continue" no
   location is recorded and no unsent alert is retried, and the notification says so. This
   keeps the rule of the note of P014a2: nothing resumes an emergency in the background.
5. **The notice goes when the SOS is run again or ends** (the host removes it).
6. **"I'm safe" from a notification goes through the unlock.** Until this note the
   emergency screen, opened by that button, showed the confirmation at once. The screen
   may be drawn over the lock screen, so a person holding a locked phone could have
   reached "Yes, I'm safe" without unlocking it, which decision 11 and the note of P014a3
   forbid. The button now only asks the screen, and the screen does what its own "I'm
   safe" does: the unlock first, then the confirmation. This also applies to the
   notification of a running SOS (P014a3). Whether Android would in fact have shown that
   screen over the lock screen from a notification button was not tested on a phone; the
   rule no longer depends on it.

**Decided: when the account goes away.**

1. **Signing out is refused while an emergency is going on** (a countdown counts). Settings
   says "An SOS is active. End it first", with the way to the SOS. It is asked again at
   the moment of signing out, because an SOS can start after the screen was opened.
2. **A block, a deletion or a sign-out forced by the server does not stop a running SOS.**
   The two clean-ups that follow the session (the emergency records; the contacts, the
   note of the consent and the name for alerts) wait until no emergency is going on
   (`SosEngine.awaitNoEmergency`), and then wipe as before. So the alerts, their retries and
   the message "I am safe" go out as the user was told they would.
3. **It holds across a process death**: a new process finds the same session state and the
   same record, and waits again.
4. **Signed in again before the SOS ended**, the waiting wipe is dropped; the finished SOS
   is then kept like every other, for 30 days.
5. **A new SOS cannot be started by a blocked or signed-out account**: the consent gate
   reads a flag that the deferred clean-up removes once the running SOS has ended, and
   Settings and Home are replaced by the onboarding screens at once.

**Residual risks.** A phone that withholds the restart broadcast shows nothing until the
app is opened. A user who never ends an SOS keeps its data on a phone whose account is
gone, until they end it or the 30-day purge takes it. Between a block and the end of the
SOS the phone still holds the contacts of an account that no longer exists; they are used
for that SOS only. A stranger with the unlocked phone can still end an SOS; that was
always so.

**Rejected.** Continuing the SOS from the receiver: Android forbids the location service
there, and an emergency that restarts itself unseen is what decision 7 excludes. Announcing
a countdown too: see 2. Signing out and keeping the SOS: two accounts' data on one phone.
Ending the SOS at a block: the person may be in danger whatever the account's state.

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
- Entry points: [`014c-sos-entry-points.svg`](../diagrams/014c-sos-entry-points.svg), prompt log [`014c1a-sos-entry-points.md`](../prompt-logs/014c1a-sos-entry-points.md)
- Diagram: [`014a-sos-device-state-machine.svg`](../diagrams/014a-sos-device-state-machine.svg)
- Android guides: "Foreground service types", "Restrictions on starting a foreground service
  from the background", "Notification runtime permission"; RFC 9562
