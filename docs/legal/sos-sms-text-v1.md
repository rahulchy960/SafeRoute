# SOS alert messages, version 1 (draft)

**Status: draft, to be verified by a lawyer. The Bengali is a draft, to be reviewed by a
native speaker.** No message is sent by any build yet: the texts are built and tested
(P014b1), and they are connected to a running SOS only together with the `sos_alerts` notice
version 2 (P014b2).

These are the SMS texts the app writes when a user starts an SOS. They are sent from the
user's own phone and SIM to the user's own emergency contacts; the SafeRoute server sends
nothing. The source is
[`SosMessage.kt`](../../android/app/src/main/java/com/saferoute/app/core/emergency/SosMessage.kt);
`SosSmsTextDocumentTest` fails when a sentence here and the code differ.

Words in angle brackets are filled in. Numbers are always written in Latin digits.

## The alert

English:

> &lt;Name&gt; needs help. Location: `https://maps.google.com/?q=<lat>,<lng>` (about &lt;N&gt; m, at
> &lt;HH:mm&gt; IST, &lt;N&gt; min old). Battery &lt;N&gt;%. Sent by the SafeRoute app. If you think they are in
> danger, call 112.

Bengali:

> &lt;Name&gt;-এর সাহায্য দরকার। অবস্থান: `https://maps.google.com/?q=<lat>,<lng>` (প্রায় &lt;N&gt; মি,
> &lt;HH:mm&gt; IST, &lt;N&gt; মিনিট আগের)। ব্যাটারি &lt;N&gt;%। SafeRoute অ্যাপ থেকে পাঠানো। তাঁরা বিপদে আছেন মনে
> হলে 112-এ কল করুন।

Variations:

- **No name was set:** "Someone who listed you as an emergency contact needs help." /
  "যিনি আপনাকে জরুরি পরিচিতি করেছেন, তাঁর সাহায্য দরকার।"
- **No position:** "Location unavailable." / "অবস্থান পাওয়া যায়নি।" in place of the location
  sentence.
- **Accuracy unknown:** "about N m" is left out. **Position under a minute old:** "N min
  old" is left out. **Battery unknown:** the battery sentence is left out.
- **Too long for three SMS parts:** the battery sentence goes first, then "Sent by the
  SafeRoute app", then the name is shortened. Who needs help, where, and "call 112" stay.

## The follow-up after "I'm safe"

> &lt;Name&gt; says they are safe now. Sent by the SafeRoute app.
>
> &lt;Name&gt; জানিয়েছেন যে তিনি এখন নিরাপদ। SafeRoute অ্যাপ থেকে পাঠানো।

Without a name: "The person who alerted you says they are safe now." /
"যিনি আপনাকে সতর্ক করেছিলেন, তিনি জানিয়েছেন যে তিনি এখন নিরাপদ।"

It reports what the sender said. It is sent only to contacts whose alert was sent.

## The location update

> Update from &lt;Name&gt;. Location: `https://maps.google.com/?q=<lat>,<lng>` (about &lt;N&gt; m, at
> &lt;HH:mm&gt; IST).
>
> &lt;Name&gt;-এর নতুন তথ্য। অবস্থান: ...

Without a name: "Update to the alert." / "সতর্কবার্তার নতুন তথ্য।"

At most one, when a better position arrives shortly after the alert.

## Points for the lawyer

1. The message names the sender and shows where they are to people who are not users of the
   app. They were added as contacts by the sender and can opt out
   ([ADR 0024](../adr/0024-emergency-contacts-and-opt-out.md)); the message itself carries no
   opt-out link, to keep it short. Is that acceptable?
2. "If you think they are in danger, call 112": the app does not call anyone and does not
   tell the receiver what to do beyond this sentence.
3. The link points to a map service of a third party. The app sends nothing to that service;
   the receiver's phone does when the link is opened.
4. "says they are safe now" reports a tap on the sender's phone, which another person holding
   the unlocked phone could make.
5. The sender's mobile plan may charge for each message, up to three SMS parts per contact
   and message; a Bengali name makes an English message longer.
6. The messages are not encrypted and stay in the receiver's SMS inbox.
