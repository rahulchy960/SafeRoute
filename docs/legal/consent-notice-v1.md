# Consent notice v1 (account creation)

> **DRAFT. Not reviewed by a lawyer.** This text must be reviewed by a lawyer, and the Bengali
> text by a native speaker, before any release. It is published here so that it can be
> reviewed, not because it is final.
>
> Licensed under CC BY-NC-ND 4.0 like everything under `docs/`: see
> [`../LICENSE.md`](../LICENSE.md).

| | |
| --- | --- |
| Purpose | `account_core`: verify the phone number and keep the account |
| Notice version sent by the app | `2026-10-draft1` |
| Shown | In onboarding, after the age question and **before** the phone number is asked for |
| Languages | English and Bengali; the person can switch on the notice screen |
| Source of truth | The app's string resources (`notice_*` in `android/app/src/main/res/values/strings.xml` and `values-bn/strings.xml`). A test (`ConsentNoticeDocumentTest`) fails if this file and the app differ |

## How it is used

- Nothing is pre-selected. "I agree" becomes available once the notice has been scrolled to its
  end.
- "I don't agree" stores and sends nothing; the app explains that it cannot work without the
  agreement.
- When the person agrees, the app records the notice version and the language it was shown in.
  They are sent to the server when the account is created
  ([ADR 0010](../adr/0010-adults-only-and-consent-records.md)).
- **Changing the text means changing the notice version** (`NOTICE_VERSION` in
  `core/session/SessionState.kt`). Every user is then asked again.

## Placeholders to fill in before release

| Placeholder | What goes there |
| --- | --- |
| `[operator name]` | The legal name of whoever operates SafeRoute (the company, once registered) |
| `[grievance contact]` | How to reach the grievance contact (an e-mail address or a form) |

## Open points for the lawyer

- Is a self-declared "18 or older" enough, and is the wording right?
- Is the description of what Google's Firebase service receives accurate and sufficient?
- Is "withdrawing means deleting your account" acceptable while in-app deletion does not exist
  yet?
- Which rights must be listed, and how must the grievance contact be named?
- Is the statement about where data is kept correct for every service used?
- Is the Bengali text a faithful translation?

## English

### Your privacy

Please read this before you continue. It says what SafeRoute needs to create your account, and why.

#### Who we are

SafeRoute is operated by [operator name]. It is a navigation app with safety features. It is not an emergency service. In an emergency, call 112.

#### What we need now, and why

To create your account we will:

- verify your phone number with a one-time code sent by SMS, and keep the number;
- keep the language you use and basic account details;
- record that you said you are 18 or older, and that you agreed to this notice, with the date.

#### Your location

When you use the map and navigation, SafeRoute uses your location to show where you are and to find routes. This starts only when you use those features and allow location access on your phone.

#### Where your data is kept

Your account details are kept on Google Cloud servers in India. Your phone number is verified by Google’s Firebase service, which receives the number and some information about your phone to prevent misuse.

#### What will ask you separately

Emergency contacts, SOS alerts, live location sharing and safety reports use more information. Each one will ask for your permission the first time you use it. You can use SafeRoute without them.

#### Your rights

You can ask to see your information, to have it corrected or erased, and you can raise a grievance. Contact: [grievance contact].

#### Changing your mind

You can withdraw your agreement at any time. Your account can’t work without it, so withdrawing means deleting your account. Deleting an account inside the app arrives in a later version; until then, write to [grievance contact].

#### Age

SafeRoute is for people aged 18 or older.

## Bengali (বাংলা), draft translation

### আপনার গোপনীয়তা

এগিয়ে যাওয়ার আগে এটি পড়ুন। আপনার অ্যাকাউন্ট তৈরি করতে SafeRoute-এর কী দরকার এবং কেন, তা এখানে বলা আছে।

#### আমরা কারা

SafeRoute পরিচালনা করে [operator name]। এটি নিরাপত্তা সংক্রান্ত সুবিধা-সহ একটি পথনির্দেশক অ্যাপ। এটি কোনো জরুরি পরিষেবা নয়। জরুরি অবস্থায় 112 নম্বরে কল করুন।

#### এখন আমাদের কী দরকার, এবং কেন

আপনার অ্যাকাউন্ট তৈরি করতে আমরা:

- SMS-এ পাঠানো একবার ব্যবহারযোগ্য কোড দিয়ে আপনার ফোন নম্বর যাচাই করব এবং নম্বরটি রাখব;
- আপনি যে ভাষা ব্যবহার করেন তা এবং অ্যাকাউন্টের সাধারণ তথ্য রাখব;
- আপনি যে বলেছেন আপনার বয়স 18 বা তার বেশি, এবং এই বিজ্ঞপ্তিতে সম্মতি দিয়েছেন, তা তারিখ-সহ লিখে রাখব।

#### আপনার অবস্থান

আপনি মানচিত্র ও পথনির্দেশ ব্যবহার করলে, আপনি কোথায় আছেন তা দেখাতে এবং পথ খুঁজতে SafeRoute আপনার অবস্থান ব্যবহার করে। আপনি ওই সুবিধাগুলো ব্যবহার করলে এবং ফোনে অবস্থানের অনুমতি দিলে তবেই এটি শুরু হয়।

#### আপনার তথ্য কোথায় রাখা হয়

আপনার অ্যাকাউন্টের তথ্য ভারতে অবস্থিত Google Cloud সার্ভারে রাখা হয়। আপনার ফোন নম্বর যাচাই করে Google-এর Firebase পরিষেবা; অপব্যবহার ঠেকাতে এটি নম্বরটি এবং আপনার ফোন সম্পর্কে কিছু তথ্য পায়।

#### যেগুলো আলাদা করে অনুমতি চাইবে

জরুরি যোগাযোগের ব্যক্তি, SOS সতর্কবার্তা, লাইভ অবস্থান শেয়ার এবং নিরাপত্তা সংক্রান্ত রিপোর্টে আরও তথ্য লাগে। প্রতিটি সুবিধা প্রথমবার ব্যবহারের সময় আপনার অনুমতি চাইবে। এগুলো ছাড়াও আপনি SafeRoute ব্যবহার করতে পারবেন।

#### আপনার অধিকার

আপনি নিজের তথ্য দেখতে, তা সংশোধন করাতে বা মুছে ফেলাতে চাইতে পারেন, এবং অভিযোগ জানাতে পারেন। যোগাযোগ: [grievance contact]।

#### মত বদলালে

আপনি যেকোনো সময় সম্মতি প্রত্যাহার করতে পারেন। সম্মতি ছাড়া আপনার অ্যাকাউন্ট চলতে পারে না, তাই প্রত্যাহার করা মানে অ্যাকাউন্ট মুছে ফেলা। অ্যাপের ভেতর থেকে অ্যাকাউন্ট মোছার সুবিধা পরের সংস্করণে আসবে; ততদিন [grievance contact]-এ লিখুন।

#### বয়স

SafeRoute শুধু 18 বছর বা তার বেশি বয়সীদের জন্য।
