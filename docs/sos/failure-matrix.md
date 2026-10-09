# SOS failure matrix

The rows of Plan v7 §7.5 and of [addendum v7.1](../plan/addendum-v7.1.md), section B, with the
test or manual script that covers each and an honest status. Every prompt that touches SOS,
live location or emergency contacts updates this file.

- **Created in P014a1** (2026-10-09) from the failure-matrix table of that prompt's log; no
  earlier version existed. **Updated in P014a2, P014a3, P014b1, P014b2 and P014b3** (2026-10-09).
- **Automated pass** is written only where tests exist and pass in the Android gate, and it
  says at which level. Tests run on the JVM (Robolectric); none runs on a phone.
- **Manual pending** means a person has to do it on a phone and has not reported it.
- A row can be both: the logic is tested, the behaviour of the platform is not.
- Levels: *data* = the records in Room; *logic* = the runner, the trail and the engine with
  fakes for the phone's parts, in virtual time; *component* = the real service, notification
  or job under Robolectric; *screen* = the Compose screens and the ViewModel.

Last gate run: P014b3, 2026-10-09, 1071 tests, 0 failures.

**State of the build:** a user who has agreed to the SOS alerts notice (version 2) can start
an SOS from the SOS control in the app (hold for 2 seconds). **It now messages their emergency
contacts by SMS**: by itself in a build with the SMS permission once the user allowed it
(debug builds by default), otherwise through the phone's SMS app. Without that consent no SOS
can be started; Call 112 and a practice run always work. The tile, the pinned notification
and the widget do not start an SOS yet (P014c). **Nothing of this was run on a phone.**

## Plan v7 §7.5

| # | Scenario | Expected behaviour | Automated tests | Status |
| --- | --- | --- | --- | --- |
| 1 | No mobile data, cellular signal present | SMS sent from the device; server sync queued | The engine needs no data connection (no network code: `SosCoreBoundaryTest`). Logic: `SosAlertsTest` (`every contact the phone knows gets one message, and the result is recorded`, `preparing twice, or after the contact list changed, alerts the same people once`, `with no contact nothing is sent and nothing fails`). Data: `RoomSosActionStoreTest`. Component: `SosSmsTest` (`it hands one multipart message to the phone and waits for an answer per part`). The text: `SosMessageTest`, `SmsSegmentsTest`, `SosSmsTextDocumentTest` | In a running SOS: `SosDispatchTest` (`with a position ready the alert leaves at the trigger, once per contact, with the link`, `a message that cannot be sent never holds up or undoes the emergency, and is retried`, `with the gate closed an SOS runs and nobody is messaged`, `with no contact the screen says so and nothing is sent`). On the screen: `SosAlertLinesTest`, `SosViewModelTest` (`the active screen shows the counts of the alerts as they change`). The gate itself: `SosAlertsDeviceTest` (`the gate opens only for consent to the notice the app shows today`), `ApiContactsRepositoryTest` (the four consent tests), `SosViewModelTest` (`without consent to the alerts notice no SOS can be started from the screen`), `SosFlowTest` (`without consent ... the SOS control offers Call 112 and the way to the setup`). Automated pass at data, logic, component and screen level. Server sync: P015. Manual pending (scripts M11, M12) |
| 2 | No signal at all | SOS screen shows the 112 button; device keeps retrying; local record kept | No network code in the SOS path: `SosCoreBoundaryTest`, `SosDeviceTest` (`the device code of a running SOS never logs ...`). The whole run works on the phone alone: `SosRunnerTest` (all). Call 112 on every SOS screen and without a phone app: `SosScreensTest` (`the countdown shows the number, a 72 dp cancel, Call 112 ...`, `the active screen says in words ...`, `the question after a restart offers ...`, `without a phone app the screens show the number ...`); on the notification: `SosDeviceTest` | Automated pass at logic, component and screen level for "runs with no network" and "112 everywhere". Retry of messages: `SosAlertsTest` (`a failed message is tried again every 30 seconds, and a sent one never again`, `after 20 tries a message is given up ...`, `retries stop the moment the emergency is over`, `each try carries the text of that moment`); result codes: `SosSmsTest` (`only an OK from the phone counts as sent`, `what may pass is retried and what cannot is final`). In a running SOS: `SosDispatchTest` (`a message that cannot be sent ... is retried`, `after a process death the retries go on, and nobody who has the alert gets it again`). Automated pass at logic level. Manual pending (script M5) |
| 3 | SafeRoute API down / 5xx | Device path unaffected; no duplicate sessions | P015 | P015 |
| 4 | App swiped away / killed by OEM | Foreground service keeps running where allowed; on restart, state restored from Room | Data: `SosEngineTest`, the five `process death ...` tests; `SosRecoveryTest`. Logic: `SosRunnerTest`, the five `process death ...` tests. Component: `SosForegroundServiceTest`. Screen: `SosViewModelTest` (`after a process death in the countdown the screen shows the SAME countdown`, `a countdown that ended while the app was closed is asked about - start now`, `... - cancel`, `an SOS found still active says so, and continue keeps it running`, `a rotated or re-created screen never starts a second emergency`); `SosFlowTest` (`an emergency nobody is running opens the emergency screen when the app comes to the front`); `SosScreensTest` (`an SOS found after a restart says so ...`) | Automated pass at data, logic, component and screen level. Whether the service survives a swipe from recents or a phone maker's battery manager: manual pending (script M4) |
| 5 | Phone rebooted during SOS | On boot or app open, the active SOS is detected and the user is asked | The same tests as row 4 (a reboot is a process death with a later clock) | Automated pass at data, logic and screen level for "on app open". The notice at boot: P014c. Manual pending (script M6) |
| 6 | Double tap / duplicate trigger | Single session (`clientSosId`); the second trigger shows the existing one | Data: `SosEngineTest` (`a second start ...`, `ten starts at once ...`, `ten triggers at once ...`). Logic: `SosRunnerTest` (`a second start during the countdown starts nothing new`, `a start while an SOS is active shows the active one`). Screen: `SosViewModelTest` (`a rotated or re-created screen ...`, `a practice run never replaces or hides a real SOS`); `SosScreensTest` (`a hold of two seconds arms exactly once ...`) | Automated pass at data, logic and screen level. No duplicate message: `SosAlertsTest` (`two senders at once never send the same message twice`, `after a process death a sent message stays sent and an unanswered one is tried again`; in that one case a contact may get the alert twice, see ADR 0027). Manual pending (script M2) |
| 7 | SEND_SMS denied or not approved | SMS composer opens pre-filled | The gateway refuses without the permission and never reaches the phone's SMS service: `SosSmsTest` (`without the permission it refuses ...`); a refusal is final, not retried: `SosAlertsTest` (`a failure that cannot pass is not tried again`). A build without the permission: `SosSmsTest` (`the main manifest never asks for SEND_SMS ...`, `this build asks for SEND_SMS exactly when it was built with it`), `MainActivityTest`, and the CI check of the release APK | Automated pass for "refuses and does not retry" and for the build switch. The composer: `SosDispatchTest` (`without the permission the SMS app is opened once with every number and the alert`, `after a process death in composer mode the SMS app is not opened a second time`, `in composer mode I am safe opens the SMS app with the follow-up`); `SosAlertsDeviceTest` (`the composer intent goes to an SMS app with every number and the text, and sends nothing`, `the composer notification shows fixed text only ...`, `showing posts the notification on a high-importance channel ...`, `the way of sending follows the build and the permission`); `SosAlertLinesTest` (`in composer mode it says that nothing is sent until Send is pressed ...`). Automated pass at logic, component and screen level. The permission flow: `SmsPermissionCardTest` (all: the explanation before Android's dialog, not now, on, blocked, a build without the permission, 200% font, the one file that may ask). Automated pass at screen level. Which separator an SMS app accepts for several recipients: not verified. Manual pending (scripts M13, M14) |
| 8 | No GPS fix | Last known location with its age; marked stale | Logic: `SosTrailTest` (all); `SosRunnerTest` (the two `location never blocks an SOS ...`). Screen: `SosScreensTest` (`every way of having no good position has its own sentence`); `SosViewModelTest` (`the active screen reports the location in words ...`) | Automated pass at logic and screen level. What the SMS says: `SosMessageTest` (`without a position it says so and still asks to call 112`, `an old position carries its age in minutes and the clock time it was taken`, `an unknown accuracy and an unknown battery are left out, not invented`), automated pass. The 3-second rule and the one update: `SosDispatchTest` (`without a position the alert waits 3 seconds at most, and the trigger not at all`, `a position that arrives inside the 3 seconds is used at once`, `sent without a position, ONE update follows when a position arrives within 60 seconds`, `a position that arrives after 60 seconds is not sent after the alert`, `without a location permission there is nothing to wait for`), automated pass at logic level. Real GPS behaviour: manual pending (script M5) |
| 9 | FCM failure | Action recorded FAILED_RETRYABLE, retried | P015 | P015 |
| 10 | Worker crash mid-action | pg-boss re-delivers; no duplicate push | P015 | P015 |
| 11 | Accidental trigger | Cancel in the countdown; after the trigger, "I'm safe" | Data: `SosEngineTest` (`cancel during the countdown ...`, `after the alert went out cancel is refused ...`). Logic: `SosRunnerTest` (`cancel is local only ...`, `after the alert went out cancel is refused and I am safe ends everything`). Component: `SosForegroundServiceTest`. Screen: `SosScreensTest` (`a hold released before two seconds arms nothing`, the cancel tests); `SosViewModelTest` (`cancel in the countdown closes the screen and leaves nothing`, `I am safe asks first, and only the confirmation ends the SOS`); `SosEmergencyScreenTest` (`started after the hold it shows the countdown ..., and cancel leaves nothing`) | Automated pass at data, logic, component and screen level. The follow-up message: its text (`SosMessageTest`, `the safe follow-up reports what the sender said, in both languages`) and its recipients (`SosAlertsTest`, `the safe follow-up goes only to the contacts whose alert was really sent`), and sending it: `SosDispatchTest` (`I am safe with tell my contacts sends the follow-up to those whose alert was sent`, `I am safe without tell my contacts sends nothing more`, `cancel during the countdown sends nothing, also with the gate open`); `SosViewModelTest` (`I am safe tells the alerted contacts by default`, `with tell my contacts unticked I am safe sends nothing more`); `SosAlertLinesTest` (`the confirmation offers tell my contacts, ticked, only when someone was told`). Automated pass at logic and screen level. Manual pending (scripts M1, M2, M3) |

## Addendum v7.1

| Scenario | Expected behaviour | Automated tests | Status |
| --- | --- | --- | --- |
| SOS started from the tile while the phone is locked | The countdown appears over the lock screen; nothing of the account is shown | The tile opens the emergency screen, which shows Call 112 while nothing runs: `EmergencyShortcutsTest`, `EmergencyActivityTest`. The emergency screen holds statuses only: `SosScreensTest` (`on the lock screen the way out says that the unlock comes first`). Countdown from the tile: P014c | Automated pass for the screen. Observed by Rahul on one phone (Samsung, Android 17): the dialog opened from the lock screen. Countdown from the tile: P014c. Lock during a countdown: manual pending (script M3) |
| Pinned notification dismissed or killed | SOS still starts from the tile and the app; re-posted at the next open | `EmergencyNotificationTest` (P012f2) | Automated pass for re-posting. Observed on the same phone. "Start SOS" action: P014c, manual pending |
| Tile unavailable on the device | Settings explains; the in-app control always works | `SettingsShortcutsTest` (P012f1); the in-app control: `SosFlowTest` | Automated pass. Not observed on a phone without tiles. Manual pending |
| A contact opted out (ADR 0024) | An opted-out contact is never alerted | `ContactsDatabaseTest` (`sos contacts leave out everyone who opted out`); `SosAlertsTest` (`a contact who opted out before their message left is skipped and never texted`, `an opt-out that arrives after the message was sent changes nothing about it`) | The fresh fetch: `SosDispatchTest` (`a contact who opted out is taken off when the fresh list arrives before their message left`, `the alert never waits for the server - a slow or absent answer changes nothing`); `SosAlertsDeviceTest` (`the fresh look returns who may still be alerted ...`). Automated pass at data and logic level. **Known gap:** with a position ready the alert leaves at once, so an opt-out made shortly before the trigger and not yet on the phone is missed (ADR 0027) |
| Notification permission denied | No notification and no error; SOS never depends on it | Pinned notification: `EmergencyNotificationTest`, `SettingsShortcutsTest`. Running SOS: `SosRunnerTest` (a host that shows nothing), `SosForegroundServiceTest`; the active screen says so: `SosScreensTest` (`the active screen says in words ...`), `SosViewModelTest` (`... says when notifications are off`) | Automated pass at logic and screen level. On a phone with notifications refused: manual pending (script M7) |

## Manual scripts

Record the phone's make, model and Android version with every result. If a step fails, write
down what the screen showed.

**From P014b3 an SOS sends real SMS.** Before any script that lets a countdown finish:

- the emergency contacts in the test account are **only numbers of phones you own** (a
  second phone on the table), and nobody else's;
- or the build is in the mode where nothing leaves without you pressing Send (no SMS
  permission), and you do not press Send.

To run M1 to M9 without any SMS: use an account with **no contacts**. The SOS then runs and
the active screen says "You have no emergency contact to alert".

**M0. Set-up (new in P014b3).** Settings → Emergency contacts. An account that agreed to
the old notice, or to none, sees "SOS alerts are off" and "Read how SOS alerts work". Open
it: the notice "How SOS alerts work" with seven paragraphs. "Not now": nothing changes, and
the SOS control on Home shows Call 112, "Practice SOS" and "Set up SOS alerts", with no hold
button. "I agree": the card "Send alerts automatically" appears, and the SOS control now
shows "Hold to send SOS".

**M1. Hold to arm and early release.** Home → SOS control. In the dialog, press "Hold to
send SOS" for about one second and lift: the ring empties and nothing happens. Tap it
briefly: nothing. Hold for two seconds: the ring fills, the phone ticks, and the countdown
screen opens. *Record:* whether the ticks could be felt.

**M2. Cancel.** During the countdown tap Cancel: the screen closes, no notification stays,
the phone stops vibrating, **and no SMS was sent** (check the second phone). Open the SOS
control again: the dialog is the normal one. Start again and press the phone's back gesture
during the countdown: nothing happens (back is not Cancel). Then tap Cancel.

**M3. Full countdown, and locking the phone.** Start and let the countdown finish: five
vibrations, then a longer one, then "SOS active", and a silent notification "SOS active"
with Call 112 and I'm safe. Tap "I'm safe" → confirm: the screen closes and the notification
goes. Then start again and **press the power button during the countdown**: wait ten
seconds, wake the phone. *Expected:* the SOS is active (it triggered while locked) and the
active screen is shown over the lock screen with "I'm safe (unlock first)". Tap it: the
phone asks for the unlock, then for the confirmation. *Record:* what the lock screen showed.

**M4. Swipe the app away while active.** With an SOS active, open the recent apps and swipe
SafeRoute away. *Record:* whether the "SOS active" notification stays (the service
survived) or goes. Open SafeRoute again. *Expected:* either the SOS screen is simply still
there, or the emergency screen opens with "SOS is still active" (Continue / I'm safe). End
with "I'm safe". Repeat once with **battery saver** on.

**M5. Airplane mode and location off.** Airplane mode on, start an SOS: the countdown and
the active screen work as in M3; with contacts, the screen shows the alert as "Still trying"
and it is sent when airplane mode is switched off again (within about ten minutes). End it.
Then switch the phone's Location off and start an SOS: it triggers on time, the active
screen says "Location: none. Location is switched off ...", and the SMS says "Location
unavailable". If SafeRoute has no location permission, the active screen says how to allow
it afterwards, and no permission dialog appears at any point.

**M6. Kill during the countdown, and reboot.** Start an SOS and, during the countdown, force
stop the app (Settings → Apps → SafeRoute → Force stop). Wait a minute, open SafeRoute.
*Expected:* "SOS was starting when the app closed" with "Send now" and Cancel; nothing was
sent in between. Try both answers on two runs. Then start an SOS, let it become active,
**restart the phone**, unlock, open SafeRoute. *Expected:* "SOS is still active", and the
second phone did not get the alert a second time. (Nothing appears before the app is
opened: the notice at boot is P014c.)

**M7. Notifications refused.** Refuse notifications for SafeRoute in the system settings,
start an SOS. *Expected:* no notification, and the active screen says that the screen is the
only sign that SOS is active. *Record:* whether the service still shows in the phone's list
of active apps.

**M8. Practice.** SOS control → "Practice SOS": every screen carries the PRACTICE banner;
no notification appears; "I'm safe" → confirm → "Practice finished". Afterwards nothing is
running, **and no SMS was sent**.

**M9. Do Not Disturb, Bengali, dark mode, large font, TalkBack.** With Do Not Disturb on,
run M3 and *record* whether the phone vibrates. Switch the app to Bengali, to dark mode and
to the largest font size: every button can be reached (the screens scroll) and Cancel stays
large. With TalkBack on, the hold button is announced with its name and "double tap to
start the SOS countdown"; the countdown reads each second.

**M10. Upgrade and install (from P014a1, P014a2).** Installing over an older build keeps the
emergency contacts; no new permission dialog appears at install or at first start.

**M11. Automatic sending (row 1). A debug build; contacts are only your second phone.**
Settings → Emergency contacts → "Send alerts automatically" → "Allow sending SMS": the
app's explanation appears first; "Continue"; Android's dialog; "Allow"; the card says "On".
Start an SOS (M3). *Expected:* within a few seconds of the trigger the second phone gets one
SMS: "Someone who listed you as an emergency contact needs help. Location: [a map link]
(about N m, at HH:mm IST). Battery N%. Sent by the SafeRoute app. If you think they are in
danger, call 112." The active screen shows "Alert sent by SMS: 1 of 1 emergency contacts."
Open the link on the second phone: a map at your position. "I'm safe" with "Tell my
contacts that I am safe" ticked: the second phone gets "The person who alerted you says they
are safe now." *Record:* the time from trigger to arrival, whether the long SMS arrived as
one message, and **what the SMS cost** (operator, plan, price per message).

**M12. Mobile data off, signal present (row 1).** Switch mobile data and Wi-Fi off, keep
the SIM active. Run M11: the SMS still arrives.

**M13. The SMS app instead (row 7).** Phone settings → Apps → SafeRoute → Permissions →
SMS → don't allow; the card says "Off". Start an SOS. *Expected:* after the countdown your
SMS app opens with the second phone's number and the alert text; a notification "Send your
SOS alert" is also there. **Nothing is sent until you press Send.** The active screen says
"Your SMS app has the alert for 1 emergency contacts. It is sent only when you press Send
there." and offers "Open SMS app again". With **two** test contacts (both your own phones):
*record* whether the SMS app shows both numbers, or only one (the separator; tell me the
SMS app's name). Then lock the phone during the countdown: after the trigger, the
notification is the way to the SMS app.

**M14. Refusals.** A fresh install: on the card tap "Allow sending SMS" → "Not now": no
Android dialog appears. Again → "Continue" → "Don't allow" twice: the card says that Android
no longer asks and offers "Open app settings". Allow SMS there, come back: the card says
"On" without a restart. A release build (if you have one): the card says that this version
cannot send SMS by itself.

**M15. Dual SIM.** On a phone with two SIMs, set the default SIM for SMS in the phone's
settings, run M11, and *record* from which number the SMS arrived. Then set the phone to
"ask every time" and *record* what happens.

**M16. A contact who opted out.** With two test contacts, open the opt-out link of one on
the second phone and opt out. In SafeRoute refresh the contacts list: it says "Opted out".
Start an SOS: only the other phone gets the SMS, and the screen shows "1 of 1".

The tile, widget and notification entry points and the boot notice arrive with P014c.
