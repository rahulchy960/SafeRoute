# SOS alerts notice v2 (emergency contacts and SMS alerts)

> **DRAFT. Not reviewed by a lawyer.** This text must be reviewed by a lawyer, and the Bengali
> text by a native speaker, before any release. It is published here so that it can be
> reviewed, not because it is final.
>
> Licensed under CC BY-NC-ND 4.0 like everything under `docs/`: see
> [`../LICENSE.md`](../LICENSE.md).

| | |
| --- | --- |
| Purpose | `sos_alerts`: keep the user's emergency contacts and let the user's phone text them when the user starts an SOS |
| Notice version sent by the app | `2026-10-alerts-draft2` |
| Replaces | [Version 1](sos-alerts-notice-v1.md) (`2026-10-alerts-draft1`), which described the alerts as coming "in a later version" |
| Shown | Just in time: when the user opens "Add a contact", or "Read how SOS alerts work", and the server has no granted `sos_alerts` consent **for this version**. Never in onboarding |
| Languages | English and Bengali; the notice follows the language of the app |
| Source of truth | The app's string resources (`contacts_notice_*` in `android/app/src/main/res/values/strings.xml` and `values-bn/strings.xml`). A test (`ContactsNoticeDocumentTest`) fails if this file and the app differ |

## What changed from version 1

Version 1 asked for consent to keep contacts and said that alerts would come "in a later
version". The alerts exist now, so the notice says what they are: an SMS from the user's own
SIM with a link to where the phone is, a possible second and third SMS, a possible cost, the
SMS app as the fallback, and a record of the phone's positions kept on the phone.

## How it is used

- Nothing is pre-selected; the notice has no checkbox. "I agree" is one deliberate tap.
- "Not now" closes the screen. Nothing was stored and nothing was sent.
- When the user agrees, the app records the consent on the server with the notice version and
  the language (`PUT /v1/me/consents/sos_alerts`,
  [ADR 0010](../adr/0010-adults-only-and-consent-records.md)), and notes the version on the
  phone, so that an SOS can check it without the network.
- **A consent given for version 1 does not count for alerts.** Such a user keeps their
  contacts; SOS alerts stay off and the SOS cannot be started until they have read and
  accepted this version. Until then the emergency dialog offers Call 112 and a practice run.
- Without consent for this version: no SOS can be started from the app, and no alert is sent
  ([ADR 0027](../adr/0027-sos-device-flow.md)). Calling 112 never needs consent.
- "Stop SOS alerts and remove all contacts" withdraws the consent. The server then deletes
  every contact, the app empties its copy, and alerts are off.
- **Changing the text means changing the notice version** (`SOS_ALERTS_NOTICE_VERSION` in
  `feature/contacts/ContactsRepository.kt`).

## Open points for the lawyer

- The contacts are people who are not users and did not agree to anything. They receive an
  SMS that names the user and shows where the user's phone is. Is the user's consent, with
  the invite and the opt-out link, a sufficient basis?
- An opt-out made shortly before an SOS can be missed: the phone does not wait for the
  server before it sends ([ADR 0027](../adr/0027-sos-device-flow.md), note of P014b2).
- Is "SafeRoute's server receives neither your alerts nor your position" precise enough,
  given that the server holds the contact list and that the map link in the SMS belongs to a
  third party, to which the receiver's phone connects when the link is opened?
- Is the sentence about cost sufficient, and should the notice name the number of SMS?
- The record of positions on the phone (up to 30 days) is described here and in the dialog
  that starts an SOS. Is that the right place and the right period?
- The wording of the SMS themselves is in [`sos-sms-text-v1.md`](sos-sms-text-v1.md).
- Is "you have a good reason to add them and they know you" the right test to put to the user?

## The notice, in English

Title: How SOS alerts work

- You are about to save other people's phone numbers in SafeRoute. Add only people who know you and whom you have a good reason to add.
- You send each one an invite from your own phone. It has a link with which they can opt out. Someone who opted out is never alerted.
- When you start an SOS, your phone sends each of them an SMS from your own SIM. It says that you need help and gives a map link to where your phone is, how exact and how old that position is, and your battery level. One more SMS may follow with a better position, and one if you say you are safe.
- Your mobile plan may charge you for these SMS. Nothing is sent unless you start an SOS. If the app is not allowed to send SMS, it opens your SMS app with the message and you press Send.
- During an SOS the app also records where your phone is. That record stays on this phone for up to 30 days. SafeRoute's server receives neither your alerts nor your position.
- SafeRoute is not an emergency service. A message can arrive late or not at all. In an emergency, call 112.
- You can stop at any time: open Emergency contacts and choose "Stop SOS alerts and remove all contacts". That deletes them all.

Buttons: "I agree" · "Not now"

## The notice, in Bengali (draft translation)

Title: SOS সতর্কবার্তা কীভাবে কাজ করে

- আপনি SafeRoute-এ অন্য মানুষের ফোন নম্বর সংরক্ষণ করতে যাচ্ছেন। কেবল তাঁদেরই যোগ করুন যাঁরা আপনাকে চেনেন এবং যাঁদের যোগ করার যথাযথ কারণ আছে।
- প্রত্যেককে আপনি নিজের ফোন থেকে আমন্ত্রণ পাঠান। তাতে একটি লিংক থাকে, যা দিয়ে তাঁরা অপ্ট আউট করতে পারেন। যিনি অপ্ট আউট করেছেন তাঁকে কখনও সতর্কবার্তা পাঠানো হয় না।
- আপনি SOS শুরু করলে আপনার ফোন আপনার নিজের SIM থেকে তাঁদের প্রত্যেককে একটি SMS পাঠায়। তাতে বলা থাকে যে আপনার সাহায্য দরকার, এবং থাকে আপনার ফোনের অবস্থানের মানচিত্রের লিংক, সেই অবস্থান কতটা নির্ভুল ও কত পুরোনো, আর আপনার ব্যাটারির মাত্রা। আরও ভালো অবস্থান পেলে আরও একটি SMS যেতে পারে, এবং আপনি নিরাপদ বলে জানালে আরও একটি।
- এই SMS-গুলির জন্য আপনার মোবাইল প্ল্যান অনুযায়ী খরচ হতে পারে। আপনি SOS শুরু না করলে কিছুই পাঠানো হয় না। অ্যাপের SMS পাঠানোর অনুমতি না থাকলে সে আপনার SMS অ্যাপে বার্তাটি খুলে দেয়, আর আপনি পাঠান চাপেন।
- SOS চলাকালীন অ্যাপটি আপনার ফোন কোথায় আছে তাও রেকর্ড করে। সেই রেকর্ড সর্বোচ্চ 30 দিন এই ফোনেই থাকে। SafeRoute-এর সার্ভার আপনার সতর্কবার্তা বা আপনার অবস্থান কোনোটিই পায় না।
- SafeRoute কোনো জরুরি পরিষেবা নয়। বার্তা দেরিতে পৌঁছাতে পারে, বা একেবারেই না পৌঁছাতে পারে। জরুরি অবস্থায় 112 নম্বরে ফোন করুন।
- আপনি যেকোনো সময় থামতে পারেন: জরুরি যোগাযোগ খুলে "SOS সতর্কবার্তা বন্ধ করুন ও সব যোগাযোগ সরান" বেছে নিন। তাতে সবাই মুছে যাবে।

Buttons: "আমি সম্মত" · "এখন নয়"

## The disclosure before the SMS permission

Shown under Emergency contacts, "Send alerts automatically", **before** Android's own
permission dialog, only after the user tapped "Allow sending SMS", and never during
an SOS. Title: "SafeRoute will send SMS for you" / "SafeRoute আপনার হয়ে SMS পাঠাবে".

English:

- If you allow it, SafeRoute sends SMS from your SIM to your emergency contacts when you start an SOS, also while the app is closed or the phone is locked.
- The messages say that you need help and where your phone is. They go only to the emergency contacts you added.
- Your mobile plan may charge you for each SMS. Nothing is sent unless you start an SOS.
- SafeRoute cannot read your messages. You can take this permission back at any time in the phone's settings.

Bengali (draft translation):

- আপনি অনুমতি দিলে, আপনি SOS শুরু করলে SafeRoute আপনার SIM থেকে আপনার জরুরি পরিচিতিদের SMS পাঠায়, অ্যাপ বন্ধ থাকলে বা ফোন লক থাকলেও।
- বার্তাগুলিতে বলা থাকে যে আপনার সাহায্য দরকার এবং আপনার ফোন কোথায় আছে। এগুলি কেবল আপনার যোগ করা জরুরি পরিচিতিদের কাছেই যায়।
- প্রতিটি SMS-এর জন্য আপনার মোবাইল প্ল্যান অনুযায়ী খরচ হতে পারে। আপনি SOS শুরু না করলে কিছুই পাঠানো হয় না।
- SafeRoute আপনার বার্তা পড়তে পারে না। আপনি যেকোনো সময় ফোনের সেটিংস থেকে এই অনুমতি ফিরিয়ে নিতে পারেন।

Buttons: "Continue" · "Not now"

## The invite SMS (sent by the user from their own SMS app)

Unchanged from version 1. The app prepares this text and the user sends it, or changes it,
or does not send it. `<link>` is the opt-out link for that contact.

English:

> Hi, I've added you as an emergency contact in SafeRoute, a personal-safety app. If I ever trigger an emergency alert, my phone may text you my location. No problem if you'd rather not: opt out here: `<link>`

Bengali (draft translation):

> নমস্কার, আমি আপনাকে SafeRoute নামের একটি ব্যক্তিগত নিরাপত্তা অ্যাপে আমার জরুরি যোগাযোগ হিসেবে যোগ করেছি। আমি কখনও জরুরি সতর্কবার্তা চালু করলে আমার ফোন আপনাকে আমার অবস্থান এসএমএস করতে পারে। আপনি না চাইলে কোনো অসুবিধা নেই, এখানে অপ্ট আউট করুন: `<link>`
