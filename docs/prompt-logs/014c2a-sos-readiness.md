# P014c2a: the SOS setup readiness screen

| Field | Value |
| --- | --- |
| Prompt | P014c, part 2 (readiness), first half. Part 2 was split: P014c2a (this log) and P014c2b (the settings that do not exist yet, and the two offers of the screen) |
| Milestone | M5 (follows P014c1b, merged as `a16c443`) |
| Branch | `feat/014c2a-sos-readiness` |
| PR title | `feat(sos): SOS setup readiness screen with phone-maker battery guidance [P014c2a]` |
| Notion | [P014c2a row in the Prompt Log](https://app.notion.com/p/3f707370772081b49933dc2736278adc) |
| Date | 2026-10-13 |
| Plan refs | Plan v7 §3.2 F-08, §5.3, §7.1–7.5, §12.3; ADR 0027 (note of 2026-10-13, P014c2a), ADR 0024, ADR 0015 |

> **Nothing was run on a phone.** Every test runs on the JVM (Robolectric).
>
> **The battery hints for Samsung, Xiaomi and Redmi, Vivo, Oppo and Realme are drafts.**
> They are written from general knowledge and were checked on no phone. The screen says so.
>
> **This pull request is larger than the ~800 lines the prompt allows**: about 1,510 added
> lines in `android/`, of which about 735 are tests and 112 are strings in two languages.
> Section 9 says why it was not cut again.

## 1. Objective

Let the user see, before an emergency, whether an SOS from this phone would reach anyone:
a checklist with a status and a fix per row, and a summary "Ready" or "Not ready: N things
to fix" that never uses the word "safe". No background work; nothing logged or uploaded.

## 2. Context & prerequisites

- Preflight: `main` synced at `a16c443`; PR #64 (P014c1b) merged; no open pull requests;
  hook path `.githooks`. A first start of this prompt was stopped because PR #64 was still
  open then. Notion: P014c1b set to Merged, a new row for this prompt.
- `android-ci` on `main` for `a16c443`: success (run 38301695368). That lets row A1 count
  the widget's tests.
- **Reported by Rahul on 2026-10-13** (Samsung Galaxy S21 FE, Android 16, a build with the
  widget): the widget is in the list, takes 4 cells, cannot be resized, and the lock screen
  does not offer it. Recorded in the device table. Taps on the widget were not reported.

**What was found (discovery):**

| Asked for (2.1) | Found |
| --- | --- |
| (a) contacts on this phone | `ActiveSosContacts.current()` (not opted out, from the phone only). **No last-synced time anywhere**: added |
| (b) a name for alerts | **None exists.** `SosMessageSettings.name()` returns null; the SMS says "Someone who listed you as an emergency contact needs help." No name field in the app or in the account |
| (c) SMS mode | `SmsModeSource` (automatic or the SMS app), `BuildConfig.SEND_SMS_DECLARED`, the card `SmsAlertModeCard` in `SmsPermissionCard.kt` |
| (d) the location permission flow | `LocationEnvironment` (what is granted, whether location is on); the request lives on Home behind "my location" and its disclosure (ADR 0015) |
| (e) notifications | `NotificationGate.block()` |
| (f) battery | Nothing |
| (g) silent countdown, SOS language | **No setting exists.** The countdown vibrates and has no sound (ADR 0027); the alert language is the app's language |
| (h) practice | `SosOpenMode.PRACTICE` |
| Settings | Settings → Emergency contacts (`ContactsScreen`) |

## 3. Workflow executed

1. `/start-prompt` (stopped once: PR #64 open; started again after the merge).
2. Discovery; the split decided and posted with the plan.
3. The last-synced time; the rules (`SosReadiness.kt`); the ViewModel, the screen, the
   intents to the phone's settings; navigation; 53 strings in two languages.
4. Tests. On the way:
   - my test asserted that a count inserted into a Bengali text is shown in Latin digits.
     It is not: Android formats numbers for the language, so "3" becomes a Bengali digit.
     The assertion was mine and wrong; the emergency number 112 is written out in the text
     and stays Latin. Noted in section 9;
   - the existing guard `no SOS text calls a message delivered or read, or the user safe`
     failed on my sentence "It does not promise that an alert arrives or is read". The
     guard was right to be strict: the sentence now reads "... that an alert reaches
     anyone".
5. The matrix, the manual scripts, ADR 0027, CLAUDE.md.
6. The Android gate three times in a row; `/ship-prompt`.

## 4. Changes

**`core/emergency/SosReadiness.kt`** (new): `ReadinessFacts`, `sosReadiness(facts, now)`,
`contactsFreshness`. Six rows (alerts, contacts, SMS, location, notifications, battery),
four statuses (OK, to fix, cannot know, for your information), and the number to fix.

**`feature/emergency/SosSetup.kt`** (new): `SosSetupViewModel` (reads the phone at
`onResume`, `onRefresh`), and three intents to the phone's own settings (the app's page,
its notifications, the Location page).

**`feature/emergency/SosSetupScreen.kt`** (new): the summary, the six rows, three facts
about how an SOS behaves today (no sound, the app's language, no name), "Practice SOS".

**`feature/contacts`**: `ContactsPreferences.contactsSyncedAt` (key `contacts_synced_at`);
`ApiContactsRepository` takes a `Clock`, notes the time when a fetched list was written
and resets it when the copy is emptied; a button "SOS setup: check that an SOS would
work" on the contacts screen.

**`navigation`**: `SosSetupDestination`.

**Strings**: 53 new, English and Bengali (`contacts_open_setup`, `sos_setup_*`).

**Docs**: ADR 0027 (note of P014c2a), the failure matrix, script M30 (new) and M21,
CLAUDE.md.

**No change**: the manifest, permissions, the database schema, build files, the API
contract, the backend, the consent notice, the SMS texts, an SOS itself.

## 5. Diagram

No diagram needed: one screen and one pure function, whose rules are a table in the ADR
note and in section 7. (The prompt makes the diagram optional for this part.)

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug --rerun-tasks`, run 1 | Pass: 1195 tests, 0 failures, 0 skipped (4 min 39 s) |
| The same, run 2 | Pass: 1195 tests, 0 failures, 0 skipped (5 min 10 s) |
| The same, run 3 | Pass: 1195 tests, 0 failures, 0 skipped (5 min 8 s) |
| Every test named in the failure matrix exists | Pass: 307 names |
| markdownlint, link check, gitleaks | Pass (139 Markdown files; links and anchors in the changed documents, 0 broken; no leaks) |

Before these three, one run of the whole gate failed on one existing test (the wording
guard, section 3); the sentence was changed and the three runs followed.

`assembleRelease` was not run: no build file, manifest, `src/release` or `src/debug`
changed.

33 new tests (1162 before, 1195 now).

| Requirement (2.3) | Tests |
| --- | --- |
| Every row's states (ok, needs action, cannot know) | `SosReadinessTest` (9); `SosSetupScreenTest` "with everything in order ...", "not ready counts what is to fix ...", "the contacts row says none, cannot tell, or how many ...", "location switched off leads to ...", "the battery row names the phone makers ..." |
| Summary logic | `SosReadinessTest` "the sum counts every row to fix, and never the battery", "with everything in order it is ready ..."; `SosSetupScreenTest` "one thing to fix is said in the singular" |
| Last-synced wording at several ages | `SosReadinessTest` "how old the list is, in words, at several ages", "an old list is said in words but is not by itself a thing to fix"; `ApiContactsRepositoryTest` "a fetched list notes the time, and a failed fetch leaves the time as it was", "emptying the copy forgets when it was fetched" |
| Fix intents | `SosSetupRulesTest` "the three ways out open the phone's own settings and carry nothing but the app's name"; the screen tests click each fix |
| State recomputed on resume | `SosSetupViewModelTest` "every return to the screen reads the phone again", "it shows nothing until the screen is in front ..." |
| Offline refresh message | `SosSetupViewModelTest` "a refresh without a connection says so and changes nothing", "refresh fetches the list once and reads the phone again"; `SosSetupScreenTest` "refresh is a button, and a refresh that failed says that the copy is unchanged" |
| Dual-state SMS mode | `SosReadinessTest`, `SosSetupViewModelTest` and `SosSetupScreenTest`, one test each |
| Bengali, font scale 2.0 | `SosSetupScreenTest` "in Bengali at double font size every fix and the practice button can still be reached" |
| String parity | `StringResourceParityTest` (unchanged, passes); `SosSetupRulesTest` (the same keys in both files) |
| Never "safe", no promise | `SosSetupRulesTest` "no text of the screen calls the user or anything safe, or promises that an alert arrives"; the existing guard in `SosScreensTest` covers the new strings too |
| Logging, no permission, no background work | `SosSetupRulesTest` "the screen asks for no permission, asks for no battery exemption, runs nothing in the background and logs nothing"; `MainActivityTest` (the permission list, unchanged) |
| Reached from Emergency contacts | `SosFlowTest` "emergency contacts leads to the SOS setup checklist, which says that alerts are not set up" |

**Failure matrix** (`docs/sos/failure-matrix.md`)

| Row | What changed | Status now |
| --- | --- | --- |
| A6, contacts empty at SOS time | The checklist says beforehand that no contact would be texted; tests added | Automated pass · Manual pending (unchanged; the new tests count after the merge) |
| A7, contacts never synced | The checklist says "Never updated" and offers Refresh; the last-fetched time; tests added | **Automated pass · Manual pending** ("Waiting on P014c" removed) |
| 4, app killed by the OEM | Guidance about battery limits beforehand; tests added | Automated pass · Manual pending (unchanged) |
| 7, SEND_SMS denied | The checklist says which way of sending this phone uses; tests added | Automated pass · Manual pending (unchanged) |
| A1, the widget | Its tests (P014c1b) are on `main` and ran green; a device row for what was reported | Automated pass · Manual pending |
| Known gaps | "A warning before an emergency" is built; what remains is that the user must open it (P014c2b) | n/a |

Script M30 is new; M21 was updated. Device results gained one row.

## 7. Decisions & ADRs

ADR 0027, note of 2026-10-13 (P014c2a).

| Row | OK | To fix | Otherwise |
| --- | --- | --- | --- |
| SOS alerts | Agreed to the notice shown today | Not agreed | |
| Contacts on this phone | At least one who has not opted out | None | "Cannot check" when the list was never fetched and none is on the phone; it counts |
| How alerts are sent | Automatic | The SMS app, in a build that could send by itself | "For your information" in a build that cannot |
| Location | Precise | Approximate, not allowed, switched off | |
| Notifications | Allowed for the app | Not allowed | One category switched off does not count |
| Battery | | | Always "cannot check"; never counted |

- **The checklist gates nothing.** An SOS never asks it.
- **An old list is not a fault.** The plan names no age; the screen says the age in words.
- **(d) "the existing permission flow" was not reused.** ADR 0015 allows Android's location
  dialog only after "my location" on the map and its disclosure. The row opens the app's
  page in the phone's settings instead. This departs from the prompt's wording on purpose.
- **(e)** opens the notification settings; no second place that requests the permission.
- **(f)** an intent to the app's settings page needs no permission. No
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. **No community link**: no official address could
  be verified, so it is omitted, as the prompt says.
- **(b) and (g) are shown as facts, not settings**, because the settings do not exist.
- **The split.** P014c2b: the name for alerts (it changes what an SOS sends and what the
  phone stores, so the notice must be looked at again, and the app's settings file holds
  flags only), the alert language and the countdown sound as settings, the offer after the
  first contact, and the entry on Home while not ready.

## 8. Security & privacy notes

- **No new permission.** The screen asks for none.
- The state holds counts and yes-or-no answers: no contact's name or number, no position.
- One new stored value: when the contact list was last fetched. A point in time, in the
  app's settings file, cleared at sign-out.
- Nothing is logged or uploaded; no analytics. The only request the screen can cause is
  the fetch of the contact list on "Refresh", which existed before.
- The intents to the phone's settings carry the app's package name and nothing else.
- No link to an outside site.

## 9. Known issues & risks

- **Unverified on a phone.**
- **The battery hints are unchecked** and can be wrong for a given phone or version.
- **"Ready" can be wrong** about what the app cannot see: a SIM without balance, no signal,
  a battery manager, a contact whose phone is off. The sentence under the summary says so.
- **The user must open the screen.** It is reached from Emergency contacts only, until
  P014c2b offers it.
- **A count inside a Bengali text is shown in Bengali digits** by Android ("Not ready: 3
  things to fix"). That holds for every count in the app, not only here; 112 is written
  out and stays Latin. Whether counts should be forced to Latin digits is a follow-up.
- A debug build's list of test numbers (P014b6) is not a row: the banner on the SOS
  screens already says who would be texted.
- 53 Bengali strings are new drafts and **need human review before release**.
- **Size: about 1,510 added lines in `android/`** (about 660 of app code, 112 of strings,
  about 735 of tests). Part 2 was already halved by scope. Cutting this half again would
  have meant a pull request with the rules and no screen, and a second one, still above
  the limit, with the screen, its strings and its tests; the checklist and the tests that
  pin its words belong together. If you want it cut that way, say so before merging.

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P014c2a):

- Check the battery hints on a Samsung, a Xiaomi or Redmi, a Vivo and an Oppo or Realme
  phone (script M30, part 4), and correct the texts.
- Phone check of the checklist (script M30) and Bengali review of 53 strings.
- Matrix rows A6, A7, 4 and 7: count the new tests after `android-ci` is green on `main`.
- Counts in Bengali texts: Latin or Bengali digits?
- Rahul: the rest of M17, part 7 (taps on the widget).

Updated: the P014c1b follow-up about row A1 (done here).

**For P014c2b:** a name for alerts (where it is stored, and whether notice version 3 covers
it: the notice says what the SMS says, and a name is new), the alert language and the
countdown sound as settings, the one-time offer of "SOS setup" after the first contact,
the entry on Home while not ready. The three "facts" on the screen become those settings.

**Then** P014c3 (the notice after a restart, sign-out safety), on Rahul's word.

## 11. How Rahul can verify

1. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug`.
2. Read `core/emergency/SosReadiness.kt` and the table in section 7.
3. On the S21 FE: Settings → Emergency contacts → "SOS setup: check that an SOS would
   work". Run script M30. Nothing in it sends an SMS.
4. In particular, part 4: open the app settings from the battery row, find SafeRoute's
   battery setting, and tell me what it is called on your phone and whether the Samsung
   line on the screen was right.
5. Report the summary line, the status of each row, and whether a change made in the
   phone's settings showed after coming back without reopening the screen.

## 12. Learning notes

- **Pure function.** The checklist's rules take facts and return statuses; they touch no
  phone, so every case can be tested in milliseconds.
- **Lifecycle and "resume".** A screen is resumed each time it comes to the front, also
  when you return from the phone's settings. `LifecycleResumeEffect` runs code then.
  [Docs](https://developer.android.com/topic/libraries/architecture/compose#lifecycle-effects)
- **Opening the phone's settings.** An intent such as
  `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` opens a settings page. It needs no
  permission and changes nothing by itself.
  [Docs](https://developer.android.com/reference/android/provider/Settings)
- **Battery optimisation and phone makers.** Android limits apps in the background (Doze,
  App Standby); several phone makers add their own, stricter managers that an app cannot
  see. [Docs](https://developer.android.com/training/monitoring-device-state/doze-standby)
- **Foreground services and visibility.** The SOS keeps running through a foreground
  service; a battery manager that kills it is what the battery row warns about.
- **Number formatting follows the language.** `getString(id, 3)` in Bengali prints a
  Bengali digit.
- **DataStore.** A small file of named values; here it gained one timestamp.
