# Crash reports notice, version 1 (DRAFT)

> **DRAFT. To be verified by a lawyer, and the Bengali by a native speaker, before
> release.** This is the text the app shows when it asks whether crash reports may be sent
> (P014d1b, [ADR 0028](../adr/0028-diagnostics-and-crash-reporting.md)).

- Version in the code: `2026-10-diagnostics-draft1` (`DIAGNOSTICS_NOTICE_VERSION`).
- Shown: once in onboarding, after the account notice and before sign-in; in Settings; and
  once on Home to a person who finished onboarding before this version.
- The choice is optional, separate from every other consent, and not ticked. It is kept on
  the phone only. The app works the same either way.
- `CrashReportsNoticeDocumentTest` keeps this document and the app's strings identical.

Open points for the lawyer: the legal basis (consent) and how it is recorded when the
choice never reaches the server; Google as a processor and where the data is stored; the
90 days, which is Google's published figure and not something the app controls; how a
person asks for reports that were already sent to be deleted.

## English

### Help fix crashes

If SafeRoute stops working, it can send a crash report to the developer. This is your choice, and the app works the same either way.

#### What is sent

Crash details (where in the app’s code it stopped), your phone’s model, your Android version, the app’s version and a random ID for this installation.

#### What is never sent

Your location, your contacts, your messages and your searches. A crash report does not carry your name, your phone number or your account.

#### Where it goes

Reports go to Firebase Crashlytics, a service of Google, which stores them for the developer. Google states that it keeps them for 90 days.

#### Turning it off

You can turn this off at any time in Settings. New reports stop. Reports that were already sent stay until the provider deletes them.

The choice on the screen: "Send crash reports", not ticked.

## Bengali (বাংলা)

### ক্র্যাশ ঠিক করতে সাহায্য করুন

SafeRoute হঠাৎ বন্ধ হয়ে গেলে অ্যাপটি ডেভেলপারকে একটি ক্র্যাশ রিপোর্ট পাঠাতে পারে। এটি আপনার ইচ্ছা; যেটাই বেছে নিন, অ্যাপ একইভাবে কাজ করবে।

#### যা পাঠানো হয়

ক্র্যাশের বিবরণ (অ্যাপের কোডের কোথায় থেমেছে), আপনার ফোনের মডেল, আপনার Android সংস্করণ, অ্যাপের সংস্করণ এবং এই ইনস্টলেশনের জন্য একটি এলোমেলো আইডি।

#### যা কখনও পাঠানো হয় না

আপনার অবস্থান, আপনার পরিচিতি, আপনার বার্তা এবং আপনার অনুসন্ধান। ক্র্যাশ রিপোর্টে আপনার নাম, ফোন নম্বর বা অ্যাকাউন্ট থাকে না।

#### কোথায় যায়

রিপোর্টগুলি Google-এর পরিষেবা Firebase Crashlytics-এ যায়, যা ডেভেলপারের জন্য সেগুলি সংরক্ষণ করে। Google জানিয়েছে যে তারা এগুলি 90 দিন রাখে।

#### বন্ধ করা

আপনি যেকোনো সময় সেটিংসে এটি বন্ধ করতে পারেন। নতুন রিপোর্ট পাঠানো বন্ধ হবে। আগে পাঠানো রিপোর্টগুলি প্রদানকারী মুছে না ফেলা পর্যন্ত থেকে যায়।

The choice on the screen: "ক্র্যাশ রিপোর্ট পাঠান", not ticked.
