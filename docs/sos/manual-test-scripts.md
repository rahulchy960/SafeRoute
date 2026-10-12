# SOS manual test scripts

The scripts a person runs on a phone for the rows of the
[SOS failure matrix](failure-matrix.md). Claude Code has no phone: everything here is done
and reported by a person.

- **M0 to M16** were written in P014a1 to P014b3 inside the matrix file and moved here in
  P013c with the **same numbers and steps**; only the layout changed (preconditions, steps,
  what to record, what to do if it fails, rows covered).
- **M17 to M28** are new in P013c, for rows that had no script.
- **M29** is new in P014b6, for the list of test numbers of debug builds.
- **M30** is new in P014c2a, for the checklist "SOS setup"; M21 was updated with it.
- **M22** was rewritten in P014b7: an unsent alert is now tried for as long as the SOS is
  active.
- **M17** was written in P014c1a (it was a draft): the tile and the pinned notification
  start an SOS. M18 and M19 were updated. Its widget part (part 7) was written in P014c1b.
- A script marked **Draft until the feature exists** describes something that is not built
  yet. Do not run it, and do not record a result for it.
- Legal wording quoted here (the notice, the SMS texts) is a draft, to be verified by a
  lawyer; the Bengali also by a native speaker.

## Safety rules: read before every session

**From P014b3 an SOS sends real SMS.** A countdown that finishes messages every emergency
contact of the signed-in account.

**From P014c1a one tap outside the app starts that countdown**: the tile "SafeRoute SOS"
in Quick Settings and the button "Start SOS" of the pinned notification, also on a locked
phone. A tester who added the tile can start an SOS without meaning to. Know where Cancel
is before you add it, and remove the tile and switch the notification off when the session
is over if other people handle the phone.

1. **Only send to numbers you control and have warned.** The emergency contacts of the test
   account are phones you own (a second phone on the table), and nobody else's.
2. **Before any trigger, open Settings → Emergency contacts and check that it holds ONLY
   your test phone.** Do this every session, not once.
3. **Use practice mode first** ([M8](#m8-practice)) to learn the screens. A practice run
   sends, stores and records nothing.
4. **Never place a real call.** "Call 112" opens the phone app with 112 entered. Look at it,
   then leave the phone app. **Never press the call button on the dialer.**
5. **Note the SMS cost.** The messages are ordinary SMS from the SIM of the phone that runs
   SafeRoute, at that SIM's price. An alert can be up to three SMS parts, and a follow-up
   and a location update may follow.
6. **Use a test account**, never an account whose contacts are real people.
7. **Stop and tell the developer** if a person you did not intend could receive a message,
   or did receive one. Do not continue the session.

**A debug build texts only the numbers it was built with** (since P014b6): the Gradle
property `saferoute.debugSmsAllowlist` in your user-level `gradle.properties` (see
`android/README.md`). Put only phones you own on it. A debug build made **without** the list
sends nothing by itself and opens your SMS app instead; the banner on the SOS screens says
which of the two it is. Read the banner before every countdown.

To run a script **without any SMS**: use an account with **no contacts**. The SOS then runs
and the active screen says "You have no emergency contact to alert. If you are in danger,
call 112." Or use a build without the SMS permission (or with it refused) and **do not
press Send** in the SMS app.

## How to record a result

Add one line per session to the "Device results" table in the
[matrix](failure-matrix.md#device-results), and tell the developer:

- the phone's make and model, and the Android version;
- the build (debug or release) and the SMS mode (automatic or SMS app);
- the date, the scripts run, and pass or fail for each;
- for a fail: the step, and what the screen showed (the words, not a guess);
- what each script asks for under "Record".

**Never write a phone number, a person's name or a place into the result.** "The second
phone" is enough. A screenshot must not show a number, a name or a map position.

## When a script fails

1. End anything that is running: "I'm safe" and its confirmation, or Cancel in the
   countdown.
2. Check the second phone: did a message arrive that should not have?
3. Write down the script, the step, what was expected and what the screen showed.
4. Do not repeat the script until you know why, if a message reached or could have reached
   an unexpected person (safety rule 7).
5. If the second phone received nothing, read
   [the troubleshooting section](#troubleshooting-the-second-phone-received-nothing) first.

## Scripts for what is built

### M0. Set-up

*New in P014b3.* **Rows:** 1, 7 (the consent gate and the way to the permission card).

- **Preconditions:** a signed-in test account. Best seen with an account that agreed to
  the old notice, or to none.
- **Steps:** Settings → Emergency contacts. An account that agreed to the old notice, or to
  none, sees "SOS alerts are off" and "Read how SOS alerts work". Open it: the notice "How
  SOS alerts work" with seven paragraphs. "Not now": nothing changes, and the SOS control
  on Home shows Call 112, "Practice SOS" and "Set up SOS alerts", with no hold button. "I
  agree": the card "Send alerts automatically" appears, and the SOS control now shows "Hold
  to send SOS".
- **Record:** pass or fail; whether the notice could be read to its end.
- **If it fails:** note which screen differed. No SOS can be running at this point; nothing
  to end.

### M1. Hold to arm and early release

**Rows:** 11.

- **Preconditions:** M0 done ("Hold to send SOS" is offered). Contacts checked (safety
  rule 2).
- **Steps:** Home → SOS control. In the dialog, press "Hold to send SOS" for about one
  second and lift: the ring empties and nothing happens. Tap it briefly: nothing. Hold for
  two seconds: the ring fills, the phone ticks, and the countdown screen opens. Tap Cancel.
- **Record:** whether the ticks could be felt.
- **If it fails:** if a countdown started after a short press or a tap, tap Cancel at once
  and report it: that is a safety problem.

### M2. Cancel

**Rows:** 11, 6.

- **Preconditions:** as M1.
- **Steps:** during the countdown tap Cancel: the screen closes, no notification stays, the
  phone stops vibrating, **and no SMS was sent** (check the second phone). Open the SOS
  control again: the dialog is the normal one. Start again and press the phone's back
  gesture during the countdown: nothing happens (back is not Cancel). Then tap Cancel.
- **Record:** pass or fail; whether anything arrived on the second phone.
- **If it fails:** an SMS after Cancel is a serious fault. Stop the session and report it.

### M3. Full countdown, and locking the phone

**Rows:** 11, 2 (Call 112 on the screens), A1 (the lock screen during a countdown).

- **Preconditions:** as M1. With contacts and the SMS permission this **sends an SMS**; use
  an account with no contacts unless you mean to test sending.
- **Steps:** start and let the countdown finish: five vibrations, then a longer one, then
  "SOS active", and a silent notification "SOS active" with Call 112 and I'm safe. Tap "I'm
  safe" → confirm: the screen closes and the notification goes. Then start again and
  **press the power button during the countdown**: wait ten seconds, wake the phone.
  *Expected:* the SOS is active (it triggered while locked) and the active screen is shown
  over the lock screen with "I'm safe (unlock first)". Tap it: the phone asks for the
  unlock, then for the confirmation.
- **Record:** what the lock screen showed.
- **If it fails:** end the SOS from inside the app ("I'm safe"). If no screen shows it,
  open SafeRoute: an SOS that is still running is shown again.

### M4. Swipe the app away while active

**Rows:** 4.

- **Preconditions:** as M3.
- **Steps:** with an SOS active, open the recent apps and swipe SafeRoute away. Open
  SafeRoute again. *Expected:* either the SOS screen is simply still there, or the
  emergency screen opens with "SOS is still active" (Continue / I'm safe). End with "I'm
  safe". Repeat once with **battery saver** on.
- **Record:** whether the "SOS active" notification stays (the service survived) or goes;
  the phone maker's battery setting for SafeRoute, if it has one.
- **If it fails:** if SafeRoute opens with nothing running although you did not end the
  SOS, report it with the phone's make and model: this is the row that depends most on the
  phone maker.

### M5. Airplane mode and location off

**Rows:** 2, 8, A8, A12.

- **Preconditions:** as M3.
- **Steps:** airplane mode on, start an SOS: the countdown and the active screen work as in
  M3; with contacts, the screen shows the alert as "Still trying" and it is sent when
  airplane mode is switched off again (the app keeps trying while the SOS is active, and
  switching airplane mode off asks for a try at once; see M22). End it. Then switch the
  phone's Location off and start an SOS: it triggers on time, the active screen says
  "Location: none. Location is switched off ...", and the SMS says "Location unavailable".
  If SafeRoute has no location permission, the active screen says how to allow it
  afterwards, and no permission dialog appears at any point.
- **Record:** how long after airplane mode was switched off the SMS arrived; what the
  location line said in each case.
- **If it fails:** a permission dialog during an SOS, or an SOS that waits for a position,
  is a fault: note at which step.

### M6. Kill during the countdown, and reboot

**Rows:** 5, A11, 4.

- **Preconditions:** as M3.
- **Steps:** start an SOS and, during the countdown, force stop the app (Settings → Apps →
  SafeRoute → Force stop). Wait a minute, open SafeRoute. *Expected:* "SOS was starting
  when the app closed" with "Send now" and Cancel; nothing was sent in between. Try both
  answers on two runs. Then start an SOS, let it become active, **restart the phone**,
  unlock, open SafeRoute. *Expected:* "SOS is still active", and the second phone did not
  get the alert a second time. (Nothing appears before the app is opened: the notice at
  boot is P014c, see [M27](#m27-the-notice-after-a-restart).)
- **Record:** which answer was tried in each run; whether the second phone got one alert,
  two, or none.
- **If it fails:** an alert sent without "Send now", or a second alert after the restart,
  is a fault. (One known exception is described in the matrix, row 6.)

### M7. Notifications refused

**Rows:** A4.

- **Preconditions:** as M3.
- **Steps:** refuse notifications for SafeRoute in the system settings, start an SOS.
  *Expected:* no notification, and the active screen says that the screen is the only sign
  that SOS is active.
- **Record:** whether the service still shows in the phone's list of active apps.
- **If it fails:** an SOS that does not start, or an error, because notifications are off
  is a fault.

### M8. Practice

**Rows:** 11 (learning the flow without a real SOS).

- **Preconditions:** none; works without contacts and without the notice.
- **Steps:** SOS control → "Practice SOS": every screen carries the PRACTICE banner; no
  notification appears; "I'm safe" → confirm → "Practice finished". Afterwards nothing is
  running, **and no SMS was sent**.
- **Record:** pass or fail; whether anything arrived on the second phone.
- **If it fails:** an SMS or a notification from a practice run is a serious fault. Stop
  and report it.

### M9. Do Not Disturb, Bengali, dark mode, large font, TalkBack

**Rows:** 11 (the countdown and Cancel for every user).

- **Preconditions:** as M3.
- **Steps:** with Do Not Disturb on, run M3. Switch the app to Bengali, to dark mode and to
  the largest font size: every button can be reached (the screens scroll) and Cancel stays
  large. With TalkBack on, the hold button is announced with its name and "double tap to
  start the SOS countdown"; the countdown reads each second.
- **Record:** whether the phone vibrates with Do Not Disturb on; any text that is cut off,
  and in which language.
- **If it fails:** note the setting, the screen and the button that could not be reached.

### M10. Upgrade and install

*From P014a1 and P014a2.* **Rows:** none (it protects the contacts a later SOS needs).

- **Preconditions:** an older build installed, with emergency contacts.
- **Steps:** install the new build over the older one. *Expected:* the emergency contacts
  are kept; no new permission dialog appears at install or at first start.
- **Record:** the two build versions.
- **If it fails:** do not clear the app's data; report what the contacts list shows.

### M11. Automatic sending

**Rows:** 1. **A debug build; contacts are only your second phone.**

- **Preconditions:** M0 done; a build with the SMS permission (a debug build by default)
  **made with your second phone's number in `saferoute.debugSmsAllowlist`** (since P014b6;
  without it a debug build uses the SMS app, as in M13); safety rules 1 and 2. On the SOS
  dialog the banner must read "DEBUG build: SMS is sent for real to 1 allowlisted contacts."
- **Steps:** Settings → Emergency contacts → "Send alerts automatically" → "Allow sending
  SMS": the app's explanation appears first; "Continue"; Android's dialog; "Allow"; the
  card says "On". Start an SOS (M3). *Expected:* within a few seconds of the trigger the
  second phone gets one SMS: "Someone who listed you as an emergency contact needs help.
  Location: [a map link] (about N m, at HH:mm IST). Battery N%. Sent by the SafeRoute app.
  If you think they are in danger, call 112." The active screen shows "Alert sent by SMS: 1
  of 1 emergency contacts." Open the link on the second phone: a map at your position.
  "I'm safe" with "Tell my contacts that I am safe" ticked: the second phone gets "The
  person who alerted you says they are safe now."
- **Record:** the time from trigger to arrival, whether the long SMS arrived as one
  message, and **what the SMS cost** (operator, plan, price per message).
- **If it fails:** read
  [the troubleshooting section](#troubleshooting-the-second-phone-received-nothing); note
  what the active screen said about the alert (sent, still trying, could not be sent).

### M12. Mobile data off, signal present

**Rows:** 1.

- **Preconditions:** as M11.
- **Steps:** switch mobile data and Wi-Fi off, keep the SIM active. Run M11: the SMS still
  arrives.
- **Record:** as M11.
- **If it fails:** as M11; also note whether the phone showed signal bars.

### M13. The SMS app instead

**Rows:** 7.

- **Preconditions:** M0 done; contacts are only your own phones.
- **Steps:** phone settings → Apps → SafeRoute → Permissions → SMS → don't allow; the card
  says "Off". Start an SOS. *Expected:* after the countdown your SMS app opens with the
  second phone's number and the alert text; a notification "Send your SOS alert" is also
  there. **Nothing is sent until you press Send.** The active screen says "Your SMS app has
  the alert for 1 emergency contacts. It is sent only when you press Send there." and
  offers "Open SMS app again". With **two** test contacts (both your own phones): see
  "Record". Then lock the phone during the countdown: after the trigger, the notification
  is the way to the SMS app.
- **Record:** the SMS app's name; with two contacts, whether the SMS app shows both numbers
  or only one (the separator between recipients is not verified).
- **If it fails:** if a message left without Send being pressed, stop and report it.

### M14. Refusals

**Rows:** 7.

- **Preconditions:** a fresh install (or the app's data cleared), M0 done.
- **Steps:** on the card tap "Allow sending SMS" → "Not now": no Android dialog appears.
  Again → "Continue" → "Don't allow" twice: the card says that Android no longer asks and
  offers "Open app settings". Allow SMS there, come back: the card says "On" without a
  restart. A release build (if you have one): the card says that this version cannot send
  SMS by itself.
- **Record:** which Android version; how many refusals it took until Android stopped
  asking.
- **If it fails:** note what the card said in the state that differed.

### M15. Dual SIM

**Rows:** A9.

- **Preconditions:** a phone with two active SIMs; as M11.
- **Steps:** set the default SIM for SMS in the phone's settings, run M11. Then set the
  phone to "ask every time" and run M11 again.
- **Record:** from which SIM the SMS arrived in the first run; what happened in the second
  (a question on the screen, an SMS from one of the SIMs, or nothing), and what the active
  screen said.
- **If it fails:** SafeRoute offers no SIM choice of its own (the matrix says why). Report
  what the phone did; do not look for a setting in the app.

### M16. A contact who opted out

**Rows:** A5.

- **Preconditions:** two test contacts, both your own phones; an invite sent to one of
  them, so that it has its opt-out link.
- **Steps:** open the opt-out link of one on the second phone and opt out. In SafeRoute
  refresh the contacts list: it says "Opted out". Start an SOS: only the other phone gets
  the SMS, and the screen shows "1 of 1".
- **Record:** pass or fail; which phone received what.
- **If it fails:** an alert to a phone that opted out is a serious fault. Stop and report
  it, with whether the contacts list had already shown "Opted out".

### M18. The pinned notification dismissed or killed

*New in P013c.* **Rows:** A2.

- **Preconditions:** the pinned emergency notification switched on in Settings (P012f2).
- **Steps:** swipe the notification away, if the phone allows it. Open the app's SOS
  control: it works as before. Leave the app and open it again: the notification is back.
  Force stop the app (Settings → Apps → SafeRoute → Force stop): the notification goes;
  open the app: it is back. Restart the phone and unlock it.
- **Record:** whether the phone let the notification be swiped away; after the restart,
  whether it was back before the app was opened, and how long after the unlock.
- **If it fails:** the SOS control in the app must work in every case; if it does not,
  report that first.
- **Starting an SOS from the notification** (since P014c1a) is in
  [M17](#m17-sos-from-the-tile-or-the-widget-while-the-phone-is-locked). Here, check only
  that the notification that came back has both buttons, "Call 112" and "Start SOS", and
  **do not tap "Start SOS"** unless you mean to run M17.

### M19. A phone without the tile

*New in P013c.* **Rows:** A3.

- **Preconditions:** a phone on which "SafeRoute SOS" cannot be added to Quick Settings
  (some phone makers offer no tiles from apps), or a phone on which you do not add it.
- **Steps:** Settings → "Add the SOS tile". *Expected:* if Android does not add it, the
  steps "Add the tile by hand" are shown, with the note that some phones have no Quick
  Settings tiles from apps and that the SOS control in the app always works. Close it and
  use the SOS control on Home: it opens the dialog with "Hold to send SOS" (or, without the
  consent, "Set up SOS alerts") and Call 112. The text under "Add the SOS tile" says that
  one tap on the tile starts the countdown.
- **Record:** the phone's make and model; what the screen offered.
- **If it fails:** note whether the app said nothing at all, or said that the tile was
  added although it was not.

### M20. No emergency contact

*New in P013c.* **Rows:** A6.

- **Preconditions:** a test account that agreed to the notice (M0) and has **no** contact.
- **Steps:** start an SOS and let the countdown finish. *Expected:* the SOS becomes active
  on time; the active screen says "You have no emergency contact to alert. If you are in
  danger, call 112."; Call 112 is offered; no SMS app opens and nothing is sent. "I'm safe"
  → confirm: the confirmation offers nothing to tell contacts.
- **Record:** pass or fail.
- **If it fails:** an SOS that refuses to start, or an error, because there is no contact
  is a fault.

### M21. Contacts that never reached the phone

*New in P013c.* **Rows:** A7.

- **Preconditions:** a test account that has contacts on another install, and a fresh
  install of the app on this phone (or the app's data cleared). Contacts are only your own
  phones.
- **Steps:** sign in. As soon as Home appears, switch airplane mode on, before opening
  Settings. Open the SOS control. Then switch airplane mode off, open Settings → Emergency
  contacts, wait for the list, and open the SOS control again.
- **Record:** what the SOS control offered while the phone was offline. One of two things
  is expected, depending on what arrived before the phone went offline: no hold button and
  "Set up SOS alerts" (the phone has no note of the consent yet), or the hold button, and
  then an active screen that says "You have no emergency contact to alert" (the consent
  arrived, the list did not). Record also what it offered after the list had arrived.
- **If it fails:** an alert to anyone while the list on the phone was empty is a fault.
- **Also (since P014c2a):** while the phone is still offline, open Settings → Emergency
  contacts → "SOS setup". *Expected:* the row "Emergency contacts on this phone" says
  "SafeRoute cannot check this" and "Never updated" if the list never arrived, and the
  summary says "Not ready". Tap Refresh while offline: "Couldn't update the list ... The
  copy on this phone is unchanged." Go online and tap Refresh: the row says how many
  contacts would be texted and "Updated today". The steps are in
  [M30](#m30-the-sos-setup-checklist).

### M22. Every message fails

*New in P013c, rewritten in P014b7.* **Rows:** A8, 2.

- **Preconditions:** as M11 (automatic sending on; contacts only your second phone).
- **Steps, part 1 (it keeps trying):** airplane mode on, start an SOS, let it become
  active and **leave airplane mode on for fifteen minutes**. *Expected, the whole time:*
  "Still trying to send: 1.", "SafeRoute keeps trying while this SOS is active." and the
  button "Send again now"; Call 112 stays on the screen; the SOS stays active; the line
  does **not** change to "Could not be sent". Tap "Send again now" once: nothing visible
  changes (the phone still cannot send).
- **Steps, part 2 (the signal returns):** switch airplane mode off and do not touch the
  phone. *Expected:* the second phone gets the alert, and the screen changes to "Alert
  sent by SMS: 1 of 1 emergency contacts." without a tap. End with "I'm safe" and check
  that nothing more arrives.
- **Steps, part 3 (coming back to the screen):** repeat part 1 for two minutes, press
  Home, switch airplane mode off in the phone's settings, wait until the phone shows
  signal, and open SafeRoute again.
- **Steps, part 4 (no SIM):** take the SIM out, or switch it off in the phone's settings,
  and start an SOS.
- **Steps, part 5 (two hours, only if you have the time):** as part 1, but leave the SOS
  active in airplane mode for two hours and five minutes, the phone on a charger.
  *Expected:* one notification "Some alerts have not been sent" with "Call 112 or send a
  text yourself." and a Call 112 button, and the same sentence on the active screen. It
  appears once. Do not test the 24-hour stop on a phone: it is covered by tests only.
- **Record:** for part 2, the seconds from airplane mode off to the SMS arriving, and
  whether the phone was awake or its screen off. For part 3, whether the alert had already
  left before the app was opened. For part 4, exactly what the active screen said: which
  answer a phone gives without a SIM is not known (the matrix lists it under "Known
  gaps"). For part 5, the minute at which the notification came, and the battery level at
  the start and the end.
- **If it fails:** an SOS that ends, hangs or shows an error because no message could be
  sent is a fault. So is "Could not be sent" while the phone merely had no signal, an alert
  that does not leave within about five minutes of the signal returning, a second alert
  to the same phone, and an alert that arrives after "I'm safe".

### M23. The contact list changes during an SOS

*New in P013c.* **Rows:** A10.

- **Preconditions:** two test contacts, both your own phones; automatic sending on;
  airplane mode available.
- **Steps:** airplane mode on, start an SOS and let it become active ("Still trying to
  send: 2."). If the app lets you reach Settings → Emergency contacts while the SOS is
  active, remove one contact there, or add a third phone you own (this needs a connection:
  switch airplane mode off for it). Switch airplane mode off.
- **Record:** whether the contacts screen could be reached during the SOS at all; which
  phones received the alert. *Expected:* the people to alert were decided once, at the
  trigger: a contact added afterwards is not alerted in this SOS. What happens to a contact
  removed before their message left is not stated in the plan: record it.
- **If it fails:** an alert to a phone that was never a contact, or two alerts to one
  phone, is a fault.

### M29. The list of test numbers of a debug build

*New in P014b6.* **Rows:** A13.

- **Preconditions:** a debug build; a test account with **two** contacts, both phones you
  own; "Send alerts automatically" is "On" (M11).
- **Steps, part 1 (no list):** build without `saferoute.debugSmsAllowlist` (or with it
  empty). Open the SOS control. *Expected:* the banner "DEBUG build: no allowlisted
  contacts: composer mode. ..." on the dialog and on the countdown. Let the countdown
  finish: your SMS app opens with both numbers and **nothing is sent** until you press
  Send; do not press it. End with "I'm safe".
- **Steps, part 2 (one of two):** put the number of **one** of the two phones on the list
  and rebuild. *Expected:* the banner "DEBUG build: SMS is sent for real to 1 allowlisted
  contacts." Let the countdown finish: only the listed phone gets the SMS; the active
  screen shows "Alert sent by SMS: 1 of 2 emergency contacts." and "Not sent because this
  test build may not text them: 1."; no SMS app opens. "I'm safe" with "Tell my contacts
  that I am safe" ticked: only the listed phone gets the follow-up.
- **Steps, part 3 (a number that is nobody's contact):** put a number on the list that is
  none of the account's contacts (another phone you own) and rebuild. *Expected:* as part
  1, composer mode.
- **Record:** pass or fail for each part; what each phone received; the banner's words.
- **If it fails:** an SMS to the phone that is **not** on the list is a serious fault: stop
  the session and report it. So is a banner that names a different number of contacts than
  were texted.

### M30. The SOS setup checklist

*New in P014c2a.* **Rows:** A6, A7, 4, 7. **Nothing in this script sends an SMS**, unless
you run a real SOS afterwards.

- **Preconditions:** the build after P014c2a, signed in. For part 1 an account that has
  agreed to the current notice and has at least one contact (your own second phone).
- **Part 1, everything in order.** Settings → Emergency contacts → "SOS setup: check that
  an SOS would work". *Expected:* a summary at the top, "Ready" or "Not ready: N things to
  fix", and under it the sentence that "Ready" does not promise that an alert reaches
  anyone, with 112. Then the rows, each with a status in words ("OK", "To fix",
  "SafeRoute cannot check this", "For your information"): SOS alerts; Emergency contacts
  on this phone (how many would be texted, and "Updated today" or how many days ago); How
  alerts are sent; Location; Notifications; Battery and background limits; How an SOS
  behaves; Practice SOS. Count the rows that say "To fix": the number must be the N of the
  summary. The battery row always says that SafeRoute cannot check it and is never counted.
- **Part 2, each row, there and back.** Change one thing at a time in the phone's
  settings, come back to the screen (do not close it) and read the row again; the screen
  must show the new state **without being reopened**:
  - Location: set the permission to "approximate", then to "don't allow", then switch the
    phone's Location off. *Expected:* "To fix" each time with its own sentence; "Open app
    settings" opens SafeRoute's page in the phone's settings, "Open location settings" the
    phone's Location page. The app itself shows no permission dialog on this screen.
  - Notifications: switch them off for SafeRoute. *Expected:* "To fix" and "Open
    notification settings".
  - How alerts are sent: in a debug build without the SMS permission, "To fix" and the
    card "Send alerts automatically" with "Allow sending SMS" (its own explanation comes
    before Android's dialog). In a release build: "For your information", not counted.
  - SOS alerts: with an account that has not agreed to the current notice, "To fix" and
    "Read how SOS alerts work", which leads to the notice.
- **Part 3, the contact list.** Switch airplane mode on and tap Refresh. *Expected:*
  "Couldn't update the list. Check your connection and try again. The copy on this phone
  is unchanged.", and the number of contacts stays. Switch it off, tap Refresh: "Updated
  today". With an account whose contacts all opted out, or with none: "No contact would be
  texted" and "To fix".
- **Part 4, the battery guidance.** Read the row. Tap "Open app settings" and look for the
  battery setting of SafeRoute on this phone. **Record what the setting is really called
  on this phone and where it is**, and whether the line for this phone's maker on the
  screen led you there: the lines are drafts and have not been checked on any phone.
- **Part 5, practice and reading.** Tap "Practice SOS": the practice run of
  [M8](#m8-practice). Then repeat part 1 in Bengali, in dark mode, with the font at its
  largest setting (every button must still be reachable by scrolling), and with TalkBack
  (the summary is read as a heading; each row's status is read as a word).
- **Record:** the phone and its Android version; the summary line and the status of each
  row; for part 2, whether each change showed after coming back without reopening the
  screen; part 4 as described; anything the screen said that was not true for this phone.
- **If it fails:** the screen saying "Ready" while an SOS would in fact text nobody
  (no contacts, or no agreement to the notice) is a serious fault: report it first. A row
  that does not change after coming back is a fault; note which. A wrong battery hint is
  not a fault of the script: record it, it is what part 4 is for.

### M17. SOS from the tile or the widget while the phone is locked

*Written in P014c1a; it was a draft before.* **Rows:** A1, A2, 6, 11. The heading keeps its
old words so that links to it still work; the script covers the tile and the pinned
notification, and since P014c1b the home-screen widget (part 7).

- **Preconditions:** as [M0](#m0-set-up). The build after P014c1a. The tile added (Settings
  → "Add the SOS tile") and the pinned notification switched on. A screen lock (PIN,
  pattern or password) set on the phone. **Every part that lets the countdown finish sends
  an SMS** to the contacts of the account in automatic mode: use an account with no
  contacts, or only your second phone, and read the banner of a debug build first.
- **Part 1, the tile, unlocked.** Pull down Quick Settings and tap "SafeRoute SOS".
  *Expected:* the panel closes and the countdown appears at once (5, 4, 3 ... with a
  vibration each second), with a large Cancel, Call 112 and "SafeRoute is not an emergency
  service. Call 112." There is no hold and no question first. Tap Cancel: the screen
  closes; nothing was sent; no notification stays.
- **Part 2, the tile, locked.** Lock the phone. Wake it, pull down Quick Settings on the
  lock screen and tap the tile. *Expected:* the countdown appears **on top of the lock
  screen**, without asking for the PIN. Look at the whole screen: it shows no name, no
  phone number, no place and nothing of the account. Tap Cancel.
- **Part 3, the tile, locked, to the end.** As part 2, but let the countdown finish.
  *Expected:* "SOS active" over the lock screen, the notification "SOS active", and (with a
  location permission) the location line on the screen. Tap "I'm safe (unlock first)": the
  phone asks for the unlock, then for the confirmation. A stranger cannot end it.
- **Part 4, the notification.** Pull down the notifications and tap **the button "Start
  SOS"** of "SafeRoute emergency shortcut". *Expected:* the same countdown. Cancel. Then
  tap **the body** of the notification, not a button: *expected:* the dialog with Call 112
  only, and no countdown. Repeat the button from the lock screen if the phone shows
  notification buttons there.
- **Part 5, twice.** Start from the tile; while the countdown runs, pull down the
  notifications and tap "Start SOS". *Expected:* the same countdown goes on with the time
  that was left; not a new five seconds, and not two emergencies. Let it finish and tap the
  tile again: "SOS active" is shown, nothing new starts. End with "I'm safe".
- **Part 6, without the consent.** With an account that has not agreed to the current
  notice (a fresh account, or one that agreed before P014c1a: the notice has a new
  version), tap the tile, then the notification's "Start SOS". *Expected:* no countdown and
  no vibration; a dialog with Call 112, "SOS alerts are not set up yet ...", "Set up SOS
  alerts" and "Practice SOS". Tap "Set up SOS alerts": on a locked phone the unlock is
  asked for first, then SafeRoute opens at Emergency contacts.
- **Part 7, the widget** (the build after P014c1b). Long-press an empty place on the home
  screen → Widgets → find "SafeRoute" → "SafeRoute SOS" → add it. (On a Samsung: hold the
  home screen, tap Widgets, search for SafeRoute, tap the widget, then Add.) *Expected:* a
  square of 2 by 2 cells with a red button "SOS" and the line "Tap to start SOS"; it
  cannot be made smaller. Tap **beside** the red button, on the card's edge: nothing
  happens. Tap the red button: the same countdown as from the tile, at once. Cancel. Start
  it again and, during the countdown, tap the tile: one countdown, not two. With an
  account that has not agreed to the notice: Call 112 and "Set up SOS alerts", no
  countdown. With TalkBack on, the button is read as "Start SOS". Switch the phone to dark
  mode and to Bengali: the widget can still be read. Look for the widget in the lock
  screen's own widget list, if the phone has one: it should not be offered there.
- **Also record for part 7:** whether the widget was in the launcher's list at all, and
  under which name; its size on the home screen; whether it could be resized; whether the
  phone offered it for the lock screen; any tap on it that you did not mean.
- **Record:** the phone and its Android version; for parts 1 to 4, the time from the tap to
  the first number of the countdown (count "one, two"; under a second is expected);
  whether the tile was reachable on the lock screen at all (some phones hide Quick
  Settings there, or ask for the unlock themselves); what the lock screen showed; in part
  3, whether the location line appeared (it tells whether the location service started
  from the lock screen); whether the notification's buttons are shown on the lock screen;
  any start you did not mean during the session, and from where.
- **If it fails:** if a countdown runs that you cannot cancel, let it finish and end it
  with "I'm safe" inside the app. If the tile does nothing on the lock screen, note that
  and whether it works after the unlock: the SOS control in the app is the fallback. If a
  countdown started although the consent was missing, stop and report it first.

## Drafts for what is not built yet

Do not run these. Each is rewritten with real steps in the prompt that builds the feature.

### M24. The SafeRoute API is down

**Draft until the feature exists (P015).** **Rows:** 3.

- **What exists today:** an SOS uses no server at all, so there is nothing to observe.
- **To check then:** with the API unreachable or answering with errors, the SOS on the
  phone runs exactly as in M11; the phone keeps trying to tell the server and does so once
  it can; the server then holds **one** session for the SOS, not two.

### M25. Push messages fail

**Draft until the feature exists (P015).** **Rows:** 9.

- **To check then:** when a push message to a contact who uses the app cannot be
  delivered, the SMS has already covered that contact, and the server records the push as
  failed and tries again.

### M26. A server worker stops mid-action

**Draft until the feature exists (P015).** **Rows:** 10.

- This is a check on the staging server, not on a phone; its steps belong to a runbook
  written with P015. **To check then:** a job that is interrupted is delivered again and no
  contact gets a second push for the same action.

### M27. The notice after a restart

**Draft until the feature exists (P014c).** **Rows:** 5.

- **What exists today:** after a restart, an SOS that was active is shown when SafeRoute is
  opened (M6). Nothing appears before that.
- **To check then:** after a restart with an SOS active, a notice appears without opening
  the app, and it leads to "SOS is still active" (Continue / I'm safe).

### M28. The live-location link by SMS

**Draft until the feature exists (P015, P016).** **Rows:** 1 (its second half).

- **To check then:** started without mobile data, the SOS sends the SMS at once (M12); when
  the phone is online again and the server knows the SOS, the contacts get the link to the
  live location.

## Troubleshooting: the second phone received nothing

Work through these in order; most sessions end at one of the first four.

1. **A plain SMS between the two phones must work first.** Send an ordinary text from the
   phone that runs SafeRoute to the second phone, with the phone's own SMS app. If that
   does not arrive, the cause is the SIM, the balance, the signal or the number, not
   SafeRoute.
2. **The alert comes from the SIM in the phone that runs SafeRoute.** It does not come
   from the number used to sign in, and never from a Firebase test number: a test number
   exists only for signing in and cannot send anything. A phone without an active SIM
   cannot alert anybody.
3. **Practice mode and Cancel send nothing.** A run with the PRACTICE banner never sends.
   A countdown that was cancelled never sends. Only a real SOS whose countdown finished
   does.
4. **Was it sent at all?** Read the active screen. "Alert sent by SMS: 1 of 1" means the
   phone handed the message to the network; "Still trying to send" means not yet: the
   app keeps trying while the SOS is active, and "Send again now" asks for a try at once.
   "Could not be sent" means it was given up: the network refused it for good, or 24 hours
   passed. "Your SMS app has the alert ..." means **nothing is
   sent until you press Send** in the SMS app (the mode without the SMS permission).
   "Sent" is all the phone knows: it does not mean delivered.
5. **The SMS permission.** Settings → Emergency contacts → "Send alerts automatically" must
   say "On" for automatic sending. A release build has no SMS permission and always uses
   the SMS app.
6. **A debug build and its list of test numbers.** Since P014b6 a debug build texts only
   the numbers in `saferoute.debugSmsAllowlist`. Read the banner on the SOS dialog: "no
   allowlisted contacts: composer mode" means the list is missing or empty, or the second
   phone's number is not on it (a typing mistake, or the build was not made again after the
   list changed). On the active screen, "Not sent because this test build may not text
   them" counts the contacts that were left out.
7. **The phone maker's own switch.** Some phones (Xiaomi and Redmi among them, and similar
   phones of other makers) have a separate per-app setting for sending SMS in their own
   security or permission screens, besides Android's permission. Look for it in the phone's
   settings for SafeRoute. This has not been verified on such a phone for SafeRoute: record
   what you find.
8. **Spam and unknown senders.** On the second phone look in the spam or blocked folder and
   in any "unknown senders" list of its SMS app. The alert contains a link, which some
   filters treat as suspicious.
9. **The contact itself.** In Settings → Emergency contacts: is the second phone in the
   list, with the right number, and not marked "Opted out"? An opted-out contact is never
   alerted. A contact added on another phone must have reached this phone (refresh the
   list).
10. **The consent.** Without agreement to the current notice no SOS can be started from the
    app at all; the SOS control then shows "Set up SOS alerts" and no hold button (M0).
11. **Two SIMs.** The phone's default SIM for SMS is used; if that SIM has no balance or no
    signal, nothing arrives (M15).

If none of these explains it, report the phone, the Android version, the SMS app and what
the active screen said. Do not send more alerts to find out.
