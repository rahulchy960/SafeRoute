# Play Console: Data safety form (draft, diagnostics part)

**Status: draft for Rahul to adapt and submit. Nothing here has been submitted, and no
answer here has been confirmed by Google Play or by a lawyer.** Written on 2026-10-13 from
the help page "Provide information for Google Play's Data safety section" as read that day
([ADR 0028](../adr/0028-diagnostics-and-crash-reporting.md)). Check the page again before
submitting: the form changes.

No secret, account id or tester's detail belongs in this file.

## What this file covers

Only what **P014d1 added**: opt-in crash reports and "Report a problem". The rest of the
form (the phone number for sign-in, location, emergency contacts, search) is **not written
yet**; see "Still to be written" at the end. Do not submit the form from this file alone.

## How the form's words apply

As the help page put it on that day:

- "Collect" means transmitting data from the app off the person's phone. Data that is only
  processed on the phone is not collected.
- "Share" means transferring collected data to a third party. A transfer to a "service
  provider" that processes it on the developer's behalf is not sharing.
- A data type is "optional" where the person controls its collection and can use the app
  without it.

## Crash reports (opt-in, Firebase Crashlytics)

Collected only after the person switched "Send crash reports" on. Off by default.

| Form's data type | Collected | Shared | Optional | Purpose | What it is here |
| --- | --- | --- | --- | --- | --- |
| App info and performance: **Crash logs** | Yes | No (Google as a service provider; to be confirmed) | Yes | App functionality (finding and fixing crashes) | A stack trace with the error's class name and a fixed category. The error's own message is removed before anything is sent. |
| App info and performance: **Diagnostics** | Yes | No (as above) | Yes | App functionality | Fixed event categories such as `SMS_SEND_FAILED(no_service)` or `API_ERROR(unavailable)`, and ten allowlisted facts: app version, build type, Android API level, phone-maker family, language, kind of network, SMS mode, three permission states as words. |
| **Device or other IDs** | Yes | No (as above) | Yes | App functionality | The Crashlytics installation id and the Firebase installation id: random ids of this installation of the app. No advertising id, no IMEI, no phone number. |

Also sent with a crash report by the library, and to be placed in the form where Google
expects it: the phone's model, the Android version and the app's version.

Answers that follow from the design:

- **Is all of the data encrypted in transit?** Yes, as far as the library's own transport
  is concerned (HTTPS). Not verified by us on a network.
- **Is this data processed ephemerally?** No. Google states that it keeps crash data for
  90 days.
- **Can the person ask for deletion?** There is no mechanism yet. Switching the setting
  off stops new reports; reports already sent stay until Google's retention ends. A
  deletion path is an open follow-up, and the form's answer must say what is true on the
  day of submission.
- **Never in a report:** location, contacts, messages, search text, routes, the phone
  number, an account id, a name. The app sets no user id.

To be confirmed before submitting:

- That Google acts as a service provider (a processor) for Crashlytics under the terms
  the Firebase project accepted. If not, the "Shared" column changes to yes.
- Whether the phone's model and OS version belong under "Diagnostics" or need no entry.

## "Report a problem"

The app sends nothing. The person reads a plain text (app version, phone make and model,
Android version, language, kind of network, permission states, SOS setup status words,
the last 20 event categories, and a note they may type) and hands it to an e-mail app or
to another app through Android's share sheet.

- Nothing is transmitted by SafeRoute and nothing is stored, so the working assumption is
  that this is **not "collected"**.
- **To be checked on the help page before submitting:** the page says that data moving
  from the app directly to another app on the phone can count as sharing, and it lists
  exceptions for transfers a person starts themselves. Which of the two applies to a
  share sheet the person opens and completes was not settled when this was written.
- What the developer receives by e-mail is personal data in the developer's mailbox (the
  sender's address, and whatever the person typed). That belongs in the privacy policy,
  not in this form.

## Still to be written (not part of P014d1)

One section per data type the app handles, each checked against the code and its ADR:

- Phone number (sign-in, Firebase Authentication): ADR 0006, ADR 0012.
- Location (the start of a route; a rounded position for search; positions kept on the
  phone during an SOS and put into the SOS text the phone sends): ADR 0015, ADR 0018,
  ADR 0027.
- Emergency contacts (names and numbers on the SafeRoute server): ADR 0024.
- Search text (sent to the SafeRoute server, not stored): ADR 0018.
- SMS (sent by the phone to the person's own contacts; see
  [`send-sms-declaration-draft.md`](send-sms-declaration-draft.md)).
- Whatever P015 and later prompts add (push messages, SOS sessions on the server).
