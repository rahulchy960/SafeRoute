# docs/prompt-logs/

One markdown log per prompt, named `NNN-<core-work>.md` (e.g. `014-sos-device-flow.md`). Each
log is the source-controlled copy of the prompt's Notion Prompt Log page and follows the same
12-section template (Plan v7 §17.8):

1. Objective
2. Context & prerequisites
3. Workflow executed (step-by-step, including commands)
4. Changes (files, API contract diff, migrations)
5. Diagram
6. Quality gate & test results (commands, pass/fail counts, failure-matrix rows)
7. Decisions & ADRs
8. Security & privacy notes
9. Known issues & risks
10. Follow-ups & prerequisites for the next prompt
11. How Rahul can verify
12. Learning notes (Android/Kotlin concepts explained)

`/ship-prompt` writes the log. If a PR gets change requests, append a **Revision** section.
