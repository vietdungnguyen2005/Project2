# Supplier preview and confirmation recovery

The operator obtains a Java preview, reviews it, and confirms it explicitly. The
browser retains a copy of the exact original file bytes for confirmation, including
CP932. Editing the source invalidates approval.

The receipt's epoch expiry is displayed as UTC and counted down. An expired or
malformed receipt disables confirmation. This is a display guard: only Java checks
the signature, actor, exact bytes, current active profile and catalog versions.

Preview, confirm and recovery reads disable React Query's inherited mutation
retry. A confirm error locks source editing, new previews and confirmation until
the operator explicitly refreshes current Import runs, reads the returned list and
acknowledges inspection. A failed inspection keeps the lock. Absence from the latest
20 runs does not establish that an import did not happen. After acknowledgement,
the operator must request another preview, review it and approve it again. No
request is resubmitted automatically. This recovery guard is browser-local;
Java's receipt verification and idempotency remain the server authority across
reloads and tabs.

A successful STAGED/RUNNING acknowledgement is shown as pending. It does not claim
stock was applied. Failure to refresh the list after acknowledgement is a read
error, not a failed confirmation to resend.

Run `npm run verify`, then start the production server and execute
`npm run test:e2e:handoff` (default http://127.0.0.1:3000; override BASE_URL).
The browser test mocks HTTP and verifies expiry, one request on lost response,
failed/successful recovery inspection, explicit renewed review, UTF-8/CP932 byte
identity and pending acknowledgement. It also checks mobile overflow. Its results
do not prove real Java/database integration or AI mapping quality. Existing Java
integration tests remain separate.

This is the first P10 increment. Signed mapping drafts, editable new inactive
profile creation, separate activation and real draft-to-import database-invariant
acceptance remain pending. Existing AI feature defaults remain off.
