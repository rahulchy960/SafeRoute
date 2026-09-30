# moderation/

Minimal internal web app for moderators (Plan v7 §3.2, §9, §15.4). Moderators use it to
approve, reject or redact community incident reports; every decision goes to an audit log.
Approved queries can be exported as CSV/XLSX. It runs on Cloud Run behind IAP or with
moderator-role auth.

**Status:** empty. It is filled in **P018** (`feat/018-moderation-web-export`).
