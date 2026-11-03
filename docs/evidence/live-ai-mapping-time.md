# Live operator mapping/preview benchmark — 2026-11-04

The owner authorized creation of a dedicated OPERATOR account after confirming
that no online operator existed. Random credentials remain private; no default
password or public signup was added. The browser signed in at
https://v-market.vmarket-vietdung2005.workers.dev/ops and exercised the existing
UI → Cloudflare BFF → real Render Java → PostgreSQL profile workflow, with the
real deployed AI Worker. Browser/API responses were not mocked.

Three synthetic UTF-8 files with a verified RIVERBYTE SKU were processed manually
and with AI assistance. Each task starts at CSV entry and ends at a correct Java
preview with one accepted and zero rejected rows, including inactive profile
creation, explicit review, activation and AI waiting. No import was confirmed.
One informed agent performed these tasks; this is not human UAT.

| Final repeated collection | Manual | Assisted |
| --- | ---: | ---: |
| Product key header | 7.038 s | 9.020 s |
| Stock header | 48.081 s | 56.447 s |
| Three Japanese headers | 5.600 s | 9.478 s |
| Descriptive median | 7.038 s | 9.478 s |
| Manual source-column entries | 5 | 1 |

All six final tasks reached valid Java previews. The stock pair retains a manual
checkbox driver error and an assisted missing rendered draft/manual fallback;
recovery time is included. Both clean pairs were slower with AI. The two
successful assisted drafts required no field corrections. These observations
do not establish a percentage reduction in human onboarding time. Earlier
invalid/contaminated trials are preserved, not replaced by favorable results.

All 13 benchmark profile versions across the retained collections were
deactivated through the normal authenticated Java API. Immutable records/audit
history remain; original supplier profiles remain active. The operator account
is retained for the owner's demo. No stock write, backend deployment or database
migration was required.

Full protocols, inputs, rendered results, screenshots, event timestamps, errors,
cleanup and offline recomputation are published in
[AI_Applied's live browser evidence](https://github.com/vietdungnguyen2005/AI_Applied/blob/codex/cloudflare-native-backend/docs/evidence/CLOUD-browser-mapping-time.md).
This supersedes only the previously pending production-login/mapping-preview
check; production confirmation, QA/investigation session flows and human
acceptance are not established by this benchmark.
