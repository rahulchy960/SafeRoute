# Play Console: SEND_SMS permission declaration (draft)

**Status: draft for Rahul to adapt and submit. Nothing here has been submitted, and no claim
here has been confirmed by Google Play.** Written on 2026-10-09 from the policy page "Use of
SMS or Call Log permission groups" as read that day
([ADR 0027](../adr/0027-sos-device-flow.md), note of P014b1). Check the page again before
submitting: the policy changes.

No secret, account id or tester's detail belongs in this file.

## When this is needed

- A build that carries `SEND_SMS` may be uploaded to Google Play only with an approved
  declaration. **Release builds of SafeRoute are made without the permission by default**
  (the Gradle property `saferoute.sendSmsEnabled`; `android/README.md`), so nothing has to be
  declared until Rahul decides to ship a build with it.
- Without the permission the app still alerts: it opens the phone's SMS app with the message
  and the user presses Send. That path needs no declaration.
- **Not recorded:** whether internal and closed test tracks are exempt from the declaration,
  and whether a demonstration video is required. The policy page read on 2026-10-09 says
  neither. Find out in the Play Console (App content → Sensitive app permissions) before the
  first upload of a build with the permission.

## Which exception

The policy lists exceptions for apps that are not the default SMS, Phone or Assistant
handler. The one that fits, as the page listed it on that day (use, description, eligible
permission):

> Physical safety/emergency alerts to send SMS: apps that send SMS alerts in emergency
> situations. Eligible permission: `SEND_SMS`.

The page calls these exceptions temporary and "subject to Google Play review and approval",
and requires that the permission enables core functionality for which there is no
alternative.

## Core functionality statement (draft)

> SafeRoute is a personal-safety app. Its core feature is an SOS that the user starts on
> their own phone: after a 5-second countdown that can be cancelled, the phone sends an SMS
> to up to five emergency contacts the user has chosen, saying that the user needs help and
> where the phone is.
>
> The SMS is sent from the device because the alert must work when the app's server cannot
> be reached and when the phone has no mobile data: a cellular signal is enough. It must also
> go out when the user cannot operate the phone after starting the SOS, for example because
> the phone is locked, in a pocket or has been taken away. Opening the SMS app and asking the
> user to press Send, which the app does when the permission is missing, does not work in
> those situations.
>
> The app sends SMS only during an SOS the user started, only to the user's own emergency
> contacts, and never reads or receives SMS. It does not request `READ_SMS`, `RECEIVE_SMS`
> or any Call Log permission.

## Why no alternative works

| Alternative | Why it does not replace `SEND_SMS` |
| --- | --- |
| SMS intent (`ACTION_SENDTO`), the user presses Send | Used as the fallback. Fails when the user cannot touch the phone after starting the SOS |
| Push notification through a server | Needs mobile data, the app's server, and the contact to have the app; contacts are people who do not use it |
| SMS sent by the app's server | The server may be unreachable; it would send from a number the contact does not know; the plan (Plan v7 §7) is device-first |
| SMS Retriever / User Consent API | They are for reading one-time codes, not for sending |
| A phone call | The app never places calls; it only opens the dialer with 112 |

## What the user sees before the permission

1. **The notice** (`docs/legal/sos-alerts-notice-v2.md`): shown before the first contact can
   be added. It says that an SOS sends SMS from the user's SIM, what the SMS contains, that
   it may cost, and that nothing is sent unless an SOS is started. The user taps "I agree".
2. **The prominent disclosure**: under Settings → Emergency contacts → "Send alerts
   automatically", the user taps "Allow sending SMS". A dialog titled "SafeRoute will send
   SMS for you" then says:
   - SafeRoute sends SMS from your SIM to your emergency contacts when you start an SOS,
     also while the app is closed or the phone is locked;
   - the messages say that you need help and where your phone is, and go only to the
     emergency contacts you added;
   - your mobile plan may charge you for each SMS, and nothing is sent unless you start an
     SOS;
   - SafeRoute cannot read your messages, and the permission can be taken back in the
     phone's settings.
3. Only after "Continue" does Android's permission dialog appear. "Not now" asks for
   nothing. The permission is never requested during an SOS, at app start or in onboarding.

## User flow to show (for a video, if one is asked for)

Record on a test phone with a test SIM, sending **only to a second phone you own**.

1. Open the app, signed in. Settings → Emergency contacts: one contact is listed.
2. "Send alerts automatically": the card says "Off". Tap "Allow sending SMS".
3. The disclosure appears. Scroll through it slowly. Tap "Continue".
4. Android's dialog appears. Tap "Allow". The card says "On".
5. Home → the SOS control → "Hold to send SOS" for two seconds.
6. The countdown runs from 5 to 1. (Show once that Cancel stops it and nothing is sent.)
7. The active screen shows "Alert sent by SMS: 1 of 1 emergency contacts."
8. Show the second phone: the SMS with the map link.
9. On the first phone: "I'm safe" → "Tell my contacts that I am safe" is ticked → confirm.
10. Show the second phone: the follow-up SMS.
11. Phone settings → Apps → SafeRoute → Permissions → SMS: show that it can be removed.

## Data safety form (related entries)

- **Location:** collected on the device during an SOS; shared by the user's own SMS with
  the contacts the user chose; not sent to the developer's server.
- **Contacts the user enters:** names and phone numbers of emergency contacts are stored on
  the developer's server (to keep the list and the opt-out) and on the device.
- **Messages:** the app composes SMS; it does not read, store on a server or transmit SMS
  content to the developer.

These lines must be checked against the form's own wording and against the privacy policy
when it exists.

## Before submitting

- [ ] The lawyer has reviewed the notice, the disclosure and the SMS texts.
- [ ] The phone scripts M11 to M16 in `docs/sos/failure-matrix.md` have been run.
- [ ] The policy page has been read again; the exception still exists.
- [ ] The video, if required, shows only phones and numbers you own.
- [ ] The build uploaded was made with `-Psaferoute.sendSmsEnabled=true` on purpose.
