# SOS alerts notice v1 (emergency contacts)

> **DRAFT. Not reviewed by a lawyer.** This text must be reviewed by a lawyer, and the Bengali
> text by a native speaker, before any release. It is published here so that it can be
> reviewed, not because it is final.
>
> Licensed under CC BY-NC-ND 4.0 like everything under `docs/`: see
> [`../LICENSE.md`](../LICENSE.md).

| | |
| --- | --- |
| Purpose | `sos_alerts`: keep the user's emergency contacts and, in a later version, let the phone text them in an emergency |
| Notice version sent by the app | `2026-10-alerts-draft1` |
| Shown | Just in time: when the user opens "Add a contact" and the server has no granted `sos_alerts` consent for them. Never in onboarding |
| Languages | English and Bengali; the notice follows the language of the app |
| Source of truth | The app's string resources (`contacts_notice_*` in `android/app/src/main/res/values/strings.xml` and `values-bn/strings.xml`). A test (`ContactsNoticeDocumentTest`) fails if this file and the app differ |

## How it is used

- Nothing is pre-selected; the notice has no checkbox. "I agree" is one deliberate tap.
- "Not now" closes the screen. Nothing was stored and nothing was sent.
- When the user agrees, the app records the consent on the server with the notice version and
  the language (`PUT /v1/me/consents/sos_alerts`,
  [ADR 0010](../adr/0010-adults-only-and-consent-records.md)). Without it the server stores no
  contact ([ADR 0024](../adr/0024-emergency-contacts-and-opt-out.md)).
- "Stop SOS alerts and remove all contacts" withdraws the consent. The server then deletes
  every contact, and the app empties its copy.
- **Changing the text means changing the notice version** (`SOS_ALERTS_NOTICE_VERSION` in
  `feature/contacts/ContactsRepository.kt`).

## Open points for the lawyer

- The contacts are people who are not users and did not agree to anything. Is the user's
  consent, with the invite and the opt-out link, a sufficient basis for keeping their numbers?
- The notice describes alerts that the app does not send yet ("in a later version"). Is it
  right to ask for this consent now, or should the purpose be split?
- Is "you have a good reason to add them and they know you" the right test to put to the user?
- The wording of the invite SMS (below) is sent by the user in their own name. Does it need
  anything else, for example who operates SafeRoute?

## The notice, in English

Title: Before you add a contact

- You are about to save other people's phone numbers in SafeRoute.
- Add only people you have a good reason to add and who know you.
- The app does not message them by itself. You send each one an invite from your own phone.
- Each invite has a link with which they can opt out. Someone who opted out is never alerted.
- In an emergency, in a later version of the app, your phone can text them your location.
- SafeRoute is not an emergency service. In an emergency, call 112.
- You can stop at any time: open Emergency contacts and choose "Stop SOS alerts and remove all contacts". That deletes them all.

Buttons: "I agree" · "Not now"

## The notice, in Bengali (draft translation)

Title: যোগাযোগ যোগ করার আগে

- আপনি SafeRoute-এ অন্য মানুষের ফোন নম্বর সংরক্ষণ করতে যাচ্ছেন।
- কেবল তাঁদেরই যোগ করুন যাঁদের যোগ করার যথাযথ কারণ আছে এবং যাঁরা আপনাকে চেনেন।
- অ্যাপ নিজে থেকে তাঁদের কোনো বার্তা পাঠায় না। প্রত্যেককে আপনি নিজের ফোন থেকে আমন্ত্রণ পাঠান।
- প্রতিটি আমন্ত্রণে একটি লিংক থাকে, যা দিয়ে তাঁরা অপ্ট আউট করতে পারেন। যিনি অপ্ট আউট করেছেন তাঁকে কখনও সতর্কবার্তা পাঠানো হয় না।
- জরুরি অবস্থায়, অ্যাপের পরের সংস্করণে, আপনার ফোন তাঁদের কাছে আপনার অবস্থান এসএমএস করতে পারবে।
- SafeRoute কোনো জরুরি পরিষেবা নয়। জরুরি অবস্থায় 112 নম্বরে ফোন করুন।
- আপনি যেকোনো সময় থামতে পারেন: জরুরি যোগাযোগ খুলে "SOS সতর্কবার্তা বন্ধ করুন ও সব যোগাযোগ সরান" বেছে নিন। তাতে সবাই মুছে যাবে।

Buttons: "আমি সম্মত" · "এখন নয়"

## The invite SMS (sent by the user from their own SMS app)

The app prepares this text and the user sends it, or changes it, or does not send it.
`<link>` is the opt-out link for that contact.

English:

> Hi, I've added you as an emergency contact in SafeRoute, a personal-safety app. If I ever trigger an emergency alert, my phone may text you my location. No problem if you'd rather not: opt out here: `<link>`

Bengali (draft translation):

> নমস্কার, আমি আপনাকে SafeRoute নামের একটি ব্যক্তিগত নিরাপত্তা অ্যাপে আমার জরুরি যোগাযোগ হিসেবে যোগ করেছি। আমি কখনও জরুরি সতর্কবার্তা চালু করলে আমার ফোন আপনাকে আমার অবস্থান এসএমএস করতে পারে। আপনি না চাইলে কোনো অসুবিধা নেই, এখানে অপ্ট আউট করুন: `<link>`
