# P012i: Daily-use loop, monetization hypotheses and rejected ideas (docs only)

| Field | Value |
| --- | --- |
| Prompt | P012i (docs only; follows P012h) |
| Milestone | M4 planning (depends on P012h, merged as `2439dcf`) |
| Branch | `docs/012i-daily-use-and-monetization` |
| PR title | `docs(plan): record daily-use loop, SafeCircle Plus hypothesis, Safe Date mode and rejected ideas [P012i]` |
| Notion | [P012i row in the Prompt Log](https://app.notion.com/p/3f407370772081b89ad9cb45c45d4bc4) |
| Date | 2026-10-09 |
| Plan refs | Plan v7 §1, §3.3, §7, §8; addenda v7.3 (I), v7.4 (B, C, D, F, G), v7.6; ADRs 0010, 0011, 0015, 0021, 0022 |

> **Docs only.** No code, contract, migration, workflow or dependency changes. Nothing is
> built, measured, priced or scheduled.
>
> **No price, revenue figure or benchmark number is in the repository.** The prompt gave
> benchmark figures for a large family-safety app; they are in the private Notion backlog
> only, marked "hypothesis, not for public use". **They were not verified:** the investor
> releases they come from were not opened. The prompt asked for indicative price test ranges
> to be kept in Notion but gave none; **none was invented**, and the Notion item says "not
> recorded".
>
> **The table of what dating apps offer comes from a web search on 2026-10-09.** Search
> results and their summaries were read; the company pages were not all opened. The addendum
> says so next to the table, and marks the one thing that could not be confirmed.
>
> **No legal, tax or payments conclusion is drawn.** Every such point is marked "to be
> verified by a lawyer".

## 1. Objective

Record how SafeRoute could be used often and could earn money, which ideas were rejected and
why, what is kept from them, and how to test the rest without writing code.

## 2. Context & prerequisites

- Preflight: `main` synced at `2439dcf`; PR #51 (P012h) merged; no open pull requests.
- The next free addendum number is v7.7; the next free ADR number is 0026.
- The Plan PDF cannot be read on this machine; Plan v7 §3.3 is cited as the prompt cites it.
- The decisions recorded were given in the prompt as already taken.
- Addendum v7.4, section G already had three lines on monetization ("Plus tier", "Teams",
  promoted listings) and the rule that revenue claims stay out of public material. This
  prompt adds detail to the first two and leaves the section as written.

## 3. Workflow executed

1. `/start-prompt`: sync, PR #51 merged, Notion (P012h set to Merged), branch.
2. Read ADR 0011, ADR 0021 and addendum v7.4 (sections A, B, C, G).
3. Web search for what Tinder, Bumble and Hinge publish about their own safety features.
4. Wrote addendum v7.7 and ADR 0026; dated notes on ADR 0011 and ADR 0021; reading order;
   ADR index; rules in `CLAUDE.md`.
5. Notion: ADR row, nine ideas (figures in one of them only), four follow-ups.
6. Checks; `/ship-prompt`.

## 4. Changes

- `docs/plan/addendum-v7.7.md` (new): A status; B the daily loop; C monetization hypotheses
  (principles, SafeCircle Plus, Teams, order of testing, a note on benchmarks without
  numbers); D rejected ideas, with what large dating apps already offer and its sources;
  E Safe Date mode; F validation plan and the ten survey questions; G risks; H gates; I plan
  edits (a short closing section, as in the earlier addenda).
- `docs/adr/0026-daily-use-loop-and-plus-hypothesis.md` (new, **Proposed**); ADR index row.
- `docs/adr/0011-trusted-circle-principles.md` and
  `docs/adr/0021-post-mvp-product-direction.md`: a note of 2026-10-09 at the top of each,
  pointing to ADR 0026. Their texts are unchanged.
- `docs/plan/README.md` and `CLAUDE.md`: reading order PDF → v7.1 → … → v7.7.
- `CLAUDE.md`, "Product guardrails": never paywall SOS, 112, basic live sharing or basic
  check-ins, and never sell a way around a Trusted Circle principle; never charge to read
  other people's disclosures or safety reports; no confession or anonymous-story feed and no
  dating features without an ADR, the gates and a lawyer's review; "safer", never "safe";
  subscriptions, prices, revenue figures and benchmark numbers stay out of the repository and
  out of public material.

## 5. Diagram

No diagram needed. No flow, state machine, schema, API sequence, infrastructure or CI
pipeline changes.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| markdownlint (Docker, same config as CI) | 0 errors in 118 files |
| Relative links in every Markdown file | 654 checked; 0 broken in the files of this prompt; the 1 older broken link in ADR 0004 is still there (follow-up of P012h) |
| gitleaks on the commit | no leaks found |
| Search of the new files for currency signs and amounts | none found |
| `git diff --stat main` | Markdown only: `CLAUDE.md` and files under `docs/` |

No failure-matrix rows apply: no SOS, live-location or contacts code is touched.

## 7. Decisions & ADRs

ADR 0026, **Proposed**: a passive daily loop; the traveller controls it and no Trusted Circle
principle can be bought; the circle as the paying unit, Plus and Teams as hypotheses; four
principles that are not hypotheses; Safe Date as a journey type; a paid confession tab and a
dating app rejected; validation before building; figures kept out of the repository; seven
gates.

Where the text goes beyond the prompt's wording, as a clarification and not a new decision:

- **No Trusted Circle principle can be bought, in any tier, by a person or an institution.**
  The prompt's Plus list (more members, automatic notices, SMS fallback, priority support)
  contains none; the sentence makes sure a later tier does not either.
- **Teams is bound by the same rules as a person**, and whether an institution may make the
  tool a condition of work or residence is marked for the lawyer.
- **Live sharing stays time-limited in the paid tier too**: the limit is a privacy rule.
- **Safe Date**: what the traveller writes about the other person is that person's personal
  data, marked for the lawyer.
- **Automatic arrival notices break promises the app makes today** (foreground only, nothing
  stored). The ADR records that each needs its own ADR and consent.

## 8. Security & privacy notes

- No personal data in the change.
- The addendum names coercive monitoring as the central risk of a feature built for "the
  person who worries", and ties the answer to ADR 0011.
- It states that the app has no analytics and that the interest check cannot count taps
  without a separate decision.
- External links in the addendum go to public help-centre, company and news pages.

## 9. Known issues & risks

- **ADR 0026 is Proposed** and could be read as a commitment. Three documents say it is not.
- **The benchmark figures in Notion are unverified**, and the price ranges are missing.
- **The dating-app table is second-hand** (see the top) and will age quickly.
- **Notion stages:** the Ideas backlog has no "Rejected", "Now", "Closed test" or "After
  SafeCircle" option. The rejected ideas are under "Blocked" with "REJECTED" in the blocker;
  the pilot and the interviews are under "Research"; Safe Date is under "Later"; the interest
  check is under "Blocked". Each note says what the prompt asked for.
- "1 to 2 circle links" for the free tier is in the repository. It is a limit, not a price,
  and it is part of a hypothesis.
- The `CLAUDE.md` rules apply from this merge, although the concept is only Proposed.

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P012i):

- Lawyer review: subscriptions, consumer protection, payments and tax.
- Decide whether to add any analytics (privacy design, consent).
- Second survey round (adults only) with the questions of addendum v7.7, section F.
- Verify the family-safety benchmark figures before quoting, and add the price test ranges.

In the Ideas backlog: passive arrival and late notices; SafeCircle Plus (hypothesis); Teams
for employers and colleges; Safe Date journey type; confession/stories tab (rejected); dating
app (rejected); manual pilot of 10 to 20 adult pairs; buyer interviews (8 to 10); Plus
interest check.

Next prompt: P014 (device-first SOS). Nothing here changes its scope. Still owed by Rahul
from P013: the phone checks of P013b2 and the staging checks of P013a.

## 11. How Rahul can verify

1. Read `docs/plan/addendum-v7.7.md`. Check the free/paid table in section C, the reasons in
   section D, and that no number in the file is a price or a revenue figure.
2. Read `docs/adr/0026-daily-use-loop-and-plus-hypothesis.md`. It stays Proposed until you
   say otherwise.
3. Read the notes at the top of ADR 0011 and ADR 0021, and the new rules in `CLAUDE.md`,
   "Product guardrails".
4. Notion: the ADR row, nine ideas, four follow-ups. Open "SafeCircle Plus (hypothesis)":
   check the benchmark against your source and add the price ranges. Move the items filed
   under substitute stages where you want them.
5. CI: `repo-checks` green; "Files changed" shows Markdown only. Squash and merge.

## 12. Learning notes

No Android or code concepts in this prompt. Two notes on the subject:

- **Who uses and who pays can be different people.** The traveller does the tapping; the
  person at home gets the relief. A product like that is sold to a group (a "circle"), which
  is why the hypothesis prices the circle and not the user.
- **A hypothesis is written so that it can fail.** Each one here names what would be measured
  (pairs still active after a week, a stated price range, an interview that becomes a pilot).
  Until one of those exists, the tier table is a guess with good formatting.
