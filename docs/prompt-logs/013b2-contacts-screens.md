# P013b2: Emergency contacts screens, consent and invite by SMS

| Field | Value |
| --- | --- |
| Prompt | P013 · part b, second half (P013a backend; P013b1 Android data layer; **P013b2 Android screens**) |
| Milestone | M5 (depends on P013b1, merged as `1103fdf`) |
| Branch | `feat/013b2-contacts-screens` |
| PR title | `feat(android): emergency contacts screens with consent and invite by SMS [P013b2]` |
| Notion | [P013b2 row in the Prompt Log](https://app.notion.com/p/3f307370772081a2b061fd48f250127d) |
| Date | 2026-10-09 |
| Plan refs | Plan v7 §3.2 F-07, §3.5, §5.1, §7.5, §12.1, §12.2; ADRs 0008, 0010, 0024 |

> **Nothing was checked on a phone.** Claude Code has no phone or emulator. Every test runs on
> the JVM (Robolectric). The contact picker, the SMS app, the opt-out page, TalkBack, dark mode
> and Bengali on a real screen are steps for Rahul (section 11).
>
> **The contact picker without a permission is the documented behaviour, not a tested one.**
> Android's guide says the picker's answer grants temporary access to the one entry; the same
> page also says that "in many cases" `READ_CONTACTS` is needed. The app handles a refusal
> calmly. Step 2 of the phone checks settles it.
>
> **Wording is a draft**: the `sos_alerts` notice, the invite SMS and every Bengali string, for
> a lawyer and a native speaker.
>
> **Size:** about 4,000 added lines (source 1,480, strings 190, tests 1,480, documents and
> diagram 850). Far over the ~800-line guide. Not split again: the screens depend on each
> other (list, add, invite, contact) and a half would leave a contact that cannot be invited
> or removed.

## 1. Objective

Let the user see, add, invite, rename and remove emergency contacts in the app, with the
`sos_alerts` consent asked just in time, the invite sent by the user from their own SMS app,
and no new permission.

## 2. Context & prerequisites

- Preflight: PR #49 (P013b1) merged as `1103fdf`; no open pull requests. The first attempt at
  this preflight stopped because #49 was still open.
- P013b1 provides `ContactsRepository`, `ActiveSosContacts`, the phone rules and the fakes.
- Still not reported: Rahul's checks of the staging service from P013a.

## 3. Workflow executed

1. `/start-prompt`: sync, PR #49 merged, Notion (P013b1 set to Merged), branch.
2. Read navigation, Settings, Home and the consent-notice pattern; read Android's guide on
   picking contact data.
3. Strings (English and Bengali), ViewModels, intents, screens, navigation, Home card,
   Settings row, manifest `<queries>`.
4. Tests; fixed what they found (section 6); docs, diagram, `/ship-prompt`.

## 4. Changes

**Screens** (`feature/contacts`)

- `ContactsScreen`: the list from the phone's copy, status in words, empty state, "Add
  contact" disabled with the reason at 5, an offline line, a refresh button, "Stop SOS alerts
  and remove all contacts" with a confirmation.
- `AddContactScreen`: checks the consent on the server; the `sos_alerts` notice ("I agree" /
  "Not now") before the first contact; then "From your contacts" or a typed name and number.
- `InviteScreen`: "Write the invite SMS" asks the server for a link and opens the SMS app;
  back in the app "Did you send the invite?" with "Yes, I sent it" and "Later".
- `ContactDetailScreen`: rename (in the screen), send the invite (again), remove. For an
  opted-out contact the reason replaces the invite button.
- `ContactsCard` in the Home sheet while the phone has no contact; "Not now" snoozes 3 days.
- Settings: an "Emergency contacts" row.

Logic:

- `ContactsViewModels.kt`: five ViewModels. A retry that gets "already exists" after a lost
  answer for the same number continues as a success. The invite link is held only between
  the server's answer and opening the SMS app.
- `ContactIntents.kt`: `pickPhoneNumberIntent`, `readPickedContact`, `inviteSmsIntent`,
  `openInviteSms`.
- `ContactsPreferences`: the card's snooze, a point in time in the app's settings file.
- Navigation: four destinations; an argument carries the contact's id only.
- Manifest: `<queries>` for `SENDTO` + `smsto`. **No new permission.**

**Text**: 80 strings in English and Bengali; `docs/legal/sos-alerts-notice-v1.md`.

**Docs**: `android/README.md` (screens and phone checks), ADR 0024 addendum, `CLAUDE.md`,
diagram.

Different from the prompt's text, on purpose:

- **A refresh button, not pull-to-refresh.** Material's pull-to-refresh is an experimental
  API, which production code here does not use (CLAUDE.md "Android rules").
- **The texts say "in a later version".** SOS alerts are not built; no screen says that a
  contact is or will be messaged.
- **Rename is in the screen, not a dialog** (a text field in a dialog never settled in the
  JVM test, and has to fit above the keyboard on a phone).
- **`smsto:` rather than `sms:`** in the intent and the `<queries>` entry: it is the scheme
  Android's guide uses for "compose an SMS to this number". Both reach SMS apps.
- **`ACTION_PICK` with the phone-number TYPE**, as in Android's guide, rather than with the
  content URI as data. Same picker.
- **The Home card is hidden by default in other Hilt tests** (`FakeContactsPreferences`), or
  two "Not now" buttons would confuse tests of other features.

## 5. Diagram

[`docs/diagrams/013b-contacts-flow.svg`](../diagrams/013b-contacts-flow.svg): the screens, the
two other apps involved, and the offline rules. 13 nodes.

## 6. Quality gate & test results

Run inside `android/` on 2026-10-09, JDK from Android Studio:

| Command | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` | BUILD SUCCESSFUL; **863 tests, 0 failures**; lint 0 errors |
| `tools/diagrams`: `pnpm generate` | 1 diagram, no warnings |
| markdownlint, gitleaks (Docker) | see the pull request |

`assembleRelease` was not run locally (no build file, `src/release` or `src/debug` change);
`android-ci` runs it.

77 new tests: `ContactsViewModelsTest` (consent not asked before the first add, agree, decline
sends nothing, 409 answers, the limit, retry after a lost answer, invite, confirm, rename,
remove, stop, the card's snooze), `ContactsScreensTest` (every screen and state, 200% font,
touch targets, wording), `ContactIntentsTest` (picker intent and result with a fake provider,
SMS intent, no SMS app, permissions, `<queries>`, the snooze store), `ContactsNoticeDocumentTest`,
`ContactsFlowTest` (the real `MainActivity` from the Home card to an invited contact), and a
new content case in `SosPlacementTest` (the card never lies under the SOS control, at every
sheet height, at double font size, in Bengali and in the dark theme).

Found by the tests and fixed, not skipped:

- `Uri.fromParts` wrote the `+` of the number as `%2B` in the SMS address; now `Uri.parse`.
- Lint: a string read through `LocalContext` in a composable; now `stringResource`.
- The rename dialog never became idle; rename moved into the screen.
- Two of P013b1's own guard tests were too strict (a comment naming `SEND_SMS`; the settings
  file); they now check declared permissions and the one stored key.
- Two tests of other features found two "Not now" buttons; see section 4.

**Failure matrix (Plan v7 §7.5), rows touched here:**

| Row | How |
| --- | --- |
| An opted-out contact is never alerted | Automated: no invite button and no link for an opted-out contact (`ContactsScreensTest`, `ContactsViewModelsTest`, `ContactsFlowTest`). Manual: step 5 |
| Contacts available with no network | Automated: the list opens read-only (`ContactsFlowTest`). Manual: step 6 |
| SEND_SMS denied or not approved | The app never asks for it: the SMS composer opens pre-filled and the user sends (automated: `ContactIntentsTest`; manual: step 3) |

For the P014 matrix (unchanged from P013b1): contacts empty at SOS time; an opt-out known to
the phone only after the next fetch; contacts never synced on a new phone.

## 7. Decisions & ADRs

ADR 0024, addendum "the screens, the picker and the invite": no permission for picking; the
invite is the user's SMS; the link is built on the phone and dropped after use; consent asked
just in time and never cached; no pull-to-refresh; rename in the screen; the Home card.

## 8. Security & privacy notes

- **No new permission.** `MainActivityTest` pins the list; `ContactIntentsTest` and
  `ContactsBoundaryTest` check that no contacts or SMS permission is held or declared.
- The contact picker returns one entry; the app asks it for the name and the number only
  (asserted with a fake provider).
- The phone number and the invite text go to the user's SMS app and nowhere else.
- What a person types or picks, a contact and the invite link are in memory only: not in
  `rememberSaveable`, a `SavedStateHandle` or a navigation argument. State classes hide them
  in `toString()`.
- `ContactsFlowTest` runs the whole flow in the real activity and searches everything written
  to Android's log for the name, the number, the token and the link. It sees this app's log
  lines in a JVM test, not a phone's system log, and not what the SMS app does with the text.
- The settings file gets one new value: a point in time for the card's snooze.
- Test data is fake.

## 9. Known issues & risks

- Unverified on a phone (see the top).
- A user who taps "Later", or never comes back from the SMS app, keeps "Invite not sent" even
  if they sent it; and "Yes, I sent it" cannot be checked. Both are by design.
- Each "Write the invite SMS" uses one of the contact's 10 links, even if the SMS is not sent.
- The consent check needs the network, so the notice cannot be read offline.
- The card can appear for a moment for a user who has contacts on the server but an empty
  copy (a new phone), until the first fetch.
- 80 Bengali strings are drafts.

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P013b2):

- Lawyer review: the `sos_alerts` notice and the invite SMS text.
- Bengali review of the contacts strings and the invite SMS by a native speaker.
- Phone checks of P013b2 (section 11), recording the phone and Android version.
- A per-contact "confirm you are my contact" handshake (not built; the invite is one-way).
- Pull-to-refresh on the list once Material's API is stable.

Recorded earlier and still open: tombstone retention and the opt-out page (P013a); link host
stability (P013a); abuse monitoring (P013a); matching contacts to registered users (P013a);
database encryption, libphonenumber, P014 notes (P013b1).

**Inputs for P014:** `ActiveSosContacts.current()` (phone only, opted-out excluded, at most 5,
may be empty); fetch with `ContactsRepository.refresh()` before alerting when a network
exists; the user has granted `sos_alerts` if they have any contact; the texts that say "in a
later version" (`contacts_intro`, `contacts_card_body`, `contacts_notice_p5`, the notice
document) must change when alerts are real, with a new `SOS_ALERTS_NOTICE_VERSION`.

## 11. How Rahul can verify

Record the phone model and Android version. Use your OWN second phone as the contact; never
send an invite to a person who did not agree. Staging must be running.

1. Settings → Emergency contacts → Add contact: the notice appears; "Not now" goes back and
   nothing was added. Again, "I agree": the form appears.
2. "From your contacts": the system picker opens with **no permission dialog**; pick an entry;
   name and number are filled in. (If it says "Couldn't read that contact", tell me the phone
   and Android version: that is the open point above.) Then try typing a number.
3. Save → "Write the invite SMS": your SMS app opens with the text and a link ending in `/c#`
   and 22 characters. Send it to your second phone.
4. Back in SafeRoute: "Did you send the invite?" → "Yes, I sent it": the list says "Invited".
5. On the second phone open the link and tap "Opt out". In SafeRoute tap refresh: "Opted out —
   won't be alerted"; the contact's screen has no invite button.
6. Airplane mode: the list opens and says changes need the internet; "Add contact" explains
   that there is no connection.
7. Add 5 contacts: "Add contact" is disabled and says why.
8. "Stop SOS alerts and remove all contacts": after the confirmation the list is empty, and
   adding again shows the notice again.
9. Bengali, dark mode, largest font size, TalkBack: every button is reachable and named.
10. Home with no contacts: pull the sheet up; the card is there and the SOS control is in the
    sheet's header. "Not now": the card is gone.
11. Add screenshots (light, dark, Bengali) to the pull request.

## 12. Learning notes

- **Intents to other apps.** An `Intent` describes something to do ("pick a phone number",
  "write an SMS to this number"); Android finds an app that can do it. The app that asks
  needs no permission for what the OTHER app does with the user watching. See
  developer.android.com, "Common intents".
- **Activity Result API** (`rememberLauncherForActivityResult`). Starts another screen and
  gets its answer back in a callback, here the picked contact.
- **Package visibility (`<queries>`).** Since Android 11 an app says in its manifest which
  kinds of other apps it wants to find. It is a declaration, not a permission.
- **Type-safe navigation arguments.** `ContactDetailDestination(contactId)` is a data class;
  Navigation saves it with the screen, which is why it carries an id and never a name.
- **`SavedStateHandle`.** How a ViewModel reads the arguments of its screen.
- **`stateIn` and `combine`.** Turn the database's Flow plus the screen's own flags into one
  StateFlow the screen draws.
- **`remember` against `rememberSaveable`.** The second survives the app being stopped by
  writing to saved state; values that must not be written anywhere use the first.
- **Live region.** A text marked this way is read out by TalkBack when it appears, which is
  how an error reaches someone who cannot see it.
