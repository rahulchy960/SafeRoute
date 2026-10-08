# P013b1: Offline copy of the emergency contacts on Android (Room, server-wins sync)

| Field | Value |
| --- | --- |
| Prompt | P013 · part b, first half (P013a backend; **P013b1 Android data layer**; P013b2 Android screens) |
| Milestone | M5 (depends on P013a, merged as `0f143fa`) |
| Branch | `feat/013b1-contacts-local-copy` |
| PR title | `feat(android): offline copy of emergency contacts in Room with server-wins sync [P013b1]` |
| Notion | [P013b1 row in the Prompt Log](https://app.notion.com/p/3f3073707720814eb3d1e108fd07d413) |
| Date | 2026-10-09 |
| Plan refs | Plan v7 §3.2 F-07, §5.1, §7.5, §11, §12.1, §12.2; ADRs 0008, 0009, 0010, 0024 |

> **P013b is split, and this is the first half.** Part b of the prompt (Room, repository,
> consent, five screens, the Home card, Settings, the SMS invite and their tests) is several
> times the ~800-line guide. The clean cut is between data and screens: this part has **no
> screen and no user-visible string**. It can ship alone without harm, because nothing can add
> a contact yet. P013b2 brings B2 (the consent screen), B3 (all screens), the picker, the SMS
> intent, the Home card, B6 (manual checks) and the rest of B5 and B7.
>
> **Size:** about 1,650 added lines outside the generated schema file (source 560, tests 800,
> documents 290). Still over the guide; half of it is tests.
>
> **B0, as far as Claude Code can see it:** PR #48 is merged, and the `deploy-staging` run for
> its merge commit ended in success (`gh run list`). The checks Rahul was asked to make on the
> deployed service (`GET /c`, the 401 and the 404) were **not reported**, and Claude Code
> cannot see the service. Nothing in this part calls the deployed API.
>
> **Not verified here:** anything on a phone. All tests run on the JVM (Robolectric).

## 1. Objective

Give the app a copy of the user's emergency contacts that works offline, with the server as
the source of truth, and the interface SOS (P014) will read with no network. No screens yet.

## 2. Context & prerequisites

- Preflight: `main` synced at `0f143fa`; no open pull requests; hook path `.githooks`.
- The client is generated from contract 0.9.0 at build time; `ContactsApi` has the six
  operations (`listContacts`, `createContact`, `renameContact`, `deleteContact`,
  `createContactInvite`, `confirmContactInvite`).
- Room 2.8.5 is the current stable release on Google Maven (read on 2026-10-09).

## 3. Workflow executed

1. `/start-prompt`: sync, PR #48 merged, Notion (P013a set to Merged), branch.
2. Generated the client and read `ContactsApi`; read the session, network and DI code.
3. Decided the split (above). Added Room (KSP, schema export, migration test harness).
4. Wrote `core/data` (table, DAO, database, `ActiveSosContacts`) and `feature/contacts`
   (repository, phone rules, session sync), then the tests.
5. Docs, diagram, `/ship-prompt`.

## 4. Changes

**Build**: Room 2.8.5 (`room-runtime`, `room-compiler` through KSP, `room-testing` in tests);
`room.schemaLocation` = `app/schemas`, committed; the schema folder is an asset folder of the
debug variant so that JVM tests can read it.

**`core/data`**

- `local/ContactEntity`, `ContactDao`, `SafeRouteDatabase` (version 1, file `saferoute.db`).
- `ActiveSosContacts` + `RoomActiveSosContacts`: contacts with `optedOutAt == null`, at most 5,
  oldest first (newest last), read from Room only.
- `di/DataModule`: `DatabaseModule` (replaceable in tests) and the bindings.

**`core/network`**

- `apiCallNoContent`: `apiCall` treats a missing body as a failure, which is wrong for the two
  operations that answer 204.
- `ProblemCodes`: the four contacts codes. `ContactsApi` is provided by `NetworkModule`.

**`feature/contacts`**

- `ContactsRepository` / `ApiContactsRepository`: `contacts` (a Flow of the copy), `refresh`,
  `add`, `rename`, `remove`, `createInvite`, `confirmInvite`, `hasConsent`, `grantConsent`,
  `withdrawConsent`, `clearLocal`. Rules: a successful fetch replaces the table; a failed one
  changes nothing; a row the server answers with is stored at once; `contact_exists` triggers
  a fetch; a contact the server no longer has is dropped; a list that arrives after the copy
  was emptied is not written.
- `InviteLink`: `<API address>/c#<token>`, in memory only, hidden in `toString()`.
- `ContactPhone.kt`: `normaliseContactPhone` (India by default, explicit `+` for other
  countries, the server's E.164 shape) and `normaliseContactName`.
- `ContactsSessionSync`: fetch when the session becomes `Ready`; empty the copy on
  `SignedOut`, `NeedsAge` and `Blocked`. Started from `SafeRouteApplication`.

**Docs**: `android/README.md` "Emergency contacts (data layer)"; ADR 0024 addendum "the copy
on the phone"; `android/THIRD_PARTY.md`; one bullet in `CLAUDE.md` "Android rules"; diagram.

Not in this part: every screen, string, the consent notice text, the contact picker, the SMS
intent, the `<queries>` entry, the Home card, the Settings entry.

## 5. Diagram

[`docs/diagrams/013b1-contacts-local-copy.svg`](../diagrams/013b1-contacts-local-copy.svg):
who reads the copy, when it is fetched, changed and emptied. 11 nodes. (The diagram the prompt
names, `013b-contacts-flow`, shows screen states and comes with P013b2.)

## 6. Quality gate & test results

Run inside `android/` on 2026-10-09, JDK from Android Studio:

| Command | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` | BUILD SUCCESSFUL; **786 tests, 0 failures, 0 skipped**; lint 0 errors |
| `./gradlew assembleRelease` with the dummy API address, map key and Firebase flags | BUILD SUCCESSFUL |
| `tools/diagrams`: `pnpm generate` | 1 diagram, no warnings |
| markdownlint, gitleaks (Docker) | see the pull request |

One run in between failed and was fixed, not skipped: `SosColourUsageTest` reserves the word
`sos` in main sources for the emergency colour, and the notice version string contained it.
The value is now `2026-10-alerts-draft1`.

New tests (47): `ContactsDatabaseTest` and `SafeRouteDatabaseMigrationTest` (DAO, Flow,
`ActiveSosContacts`, the schema harness), `ApiContactsRepositoryTest` (the real HTTP client and
JSON against a local fake server, a real in-memory database), `ContactRulesTest`,
`ContactsSessionSyncTest`, `ContactsBoundaryTest`.

**Failure matrix (Plan v7 §7.5), rows this part touches, automated:**

| Row | Test |
| --- | --- |
| Contacts available with no network | `reading works with no server at all, and so does the sos list` |
| An opted-out contact is never alerted | `sos contacts leave out everyone who opted out`; `an opted-out contact cannot be invited, and the copy learns it` |
| SafeRoute API down / 5xx: device path unaffected | `a failed fetch never deletes or changes what is on the phone` |
| Duplicate request after a lost answer | `contact_exists refetches, so a retry after a lost answer finds the contact` |

Notes for the P014 failure matrix: **contacts empty at SOS time** (`current()` returns an empty
list: none added, all opted out, or withdrawn); **opted-out contact excluded** (but an opt-out
reaches the phone only with the next successful fetch); **contacts never synced** (signed in on
a new phone and never online since: the copy is empty although the server has contacts).

## 7. Decisions & ADRs

ADR 0024, addendum "the copy on the phone". Decisions:

- No queue of pending changes: a change needs the network. The phone never shows a contact
  the server does not have.
- The copy follows the session's STATE, not a "sign out" call, so a forced sign-out is covered
  and a missed clean-up is repeated at the next start.
- Consent is not cached on the phone: `hasConsent` asks the server. Adding needs the network
  anyway.
- A foreign number needs an explicit `+`; the app checks its shape only.
- The schema files are debug-variant assets. The alternative (assets of the `test` source set)
  is not visible to JVM tests.
- The migration test uses Room's driver-based helper: the name-based one compares paths by `/`
  and fails on Windows.

## 8. Security & privacy notes

- New personal data on the phone: names and phone numbers of people who are not users, in the
  app's private storage, never backed up, emptied with the session. **Not encrypted at rest**
  (follow-up).
- Nothing about a contact is logged. `ApiContactsRepositoryTest` runs the whole flow with the
  debug logger on and searches everything written to Android's log for the names, the numbers,
  the invite token and the ID token. It sees this app's log lines in a JVM test, not a phone's
  system log.
- The invite token is never stored and never part of a request URL; the types that hold a
  contact or a link hide it in `toString()`.
- No new permission. No address-book access, no SMS. `MainActivityTest` pins the full list;
  `ContactsBoundaryTest` checks the manifest and the sources.
- New dependency: AndroidX Room, Apache-2.0 (compatible).
- Test data is fake: "Test Contact N", `+91 90000 100NN`.

## 9. Known issues & risks

- Until P013b2 nothing in the app adds a contact; the fetch at `Ready` returns an empty list
  for everyone.
- An opt-out is known to the phone only after the next successful fetch (ADR 0024 addendum).
- Every signed-in app start now makes one more request (`GET /v1/contacts`).
- The debug APK contains the schema JSON (table layouts, no data).
- The staging service checks from P013a are still owed by Rahul.

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P013b1):

- Encrypt the local database at rest (SQLCipher or Keystore), before SOS data joins it.
- Evaluate libphonenumber for numbers outside India.
- P014: fetch the contacts before alerting when a network exists; handle an empty list.

For P013b2: `ContactsRepository` and its fake are ready; the consent notice text must be
written, mirrored in `docs/legal/`, and its version is `SOS_ALERTS_NOTICE_VERSION`; treat
`AlreadyExists` after a `NoConnection` for the same number as success; never show or log
`InviteLink.url` outside the SMS intent.

## 11. How Rahul can verify

1. CI green on the pull request (`android-ci`, `repo-checks`).
2. In Android Studio: sync, then run the app on the phone. It must start and behave exactly
   as before; there is nothing new to see.
3. Optional, in Android Studio: View → Tool Windows → App Inspection → Database Inspector,
   with the app running: a database `saferoute.db` with an empty table `contacts`.
4. Sign out and in again: the app still reaches Home.
5. Still owed from P013a: the checks on the staging service (`GET /c`, 401, 404, the page on
   a phone).

## 12. Learning notes

- **Room.** A library over SQLite, the database built into Android. You describe a table as a
  Kotlin class (`@Entity`), the queries as an interface (`@Dao`), and Room writes the code and
  checks the SQL at build time. See developer.android.com, "Save data in a local database
  using Room".
- **KSP.** The tool that runs code generators (Hilt's, and now Room's) while the app builds.
- **Schema export and migrations.** Each database version's layout is saved as a JSON file. A
  phone that has version 1 and installs an app with version 2 needs a `Migration` that says
  how to get from one to the other; the saved files let a test replay that.
- **Flow from a DAO.** A function that returns `Flow<List<...>>` emits the list again whenever
  the table changes, so a screen that collects it is always current.
- **`@Transaction`.** Several database steps that happen together or not at all.
- **`@TestInstallIn`.** Replaces a Hilt module in every test at once, here the database and
  the contacts repository, so no test touches a file or a server.
- **Source of truth.** One place decides what is correct (the server); every other copy is
  replaced from it and never the other way round.
