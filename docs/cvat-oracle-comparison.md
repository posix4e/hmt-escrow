# CVAT: what production HUMAN does, and what this repo does

Written after reading `humanprotocol/human-protocol` at `114980f1`
(`packages/examples/cvat/{exchange,recording}-oracle`) and after exercising
this repo's `CvatClient` against a real CVAT 2.74.0 deployment. It answers one
question — how does a worker get into CVAT — and records what the current
bridge gets wrong.

## Production: the worker annotates inside CVAT

The oracles never move pixels to the worker. They move the *worker* to CVAT.

### Access: the worker brings their own CVAT account, the oracle invites it

There is no per-worker credential minting and no shared account. The exchange
oracle owns a CVAT **organization**, and admits workers to it by email:

```python
# exchange-oracle/src/cvat/api_calls.py:833
(invitation, _) = api_client.invitations_api.create(
    models.InvitationWriteRequest(role="worker", email=user_email),
    org=Config.cvat_config.org_slug,
)
return invitation.user.id
```

`POST /register` ("Binds a CVAT user to a HUMAN App user",
`endpoints/exchange.py:208`) is the entry point. It takes the authenticated
worker's `email` and `wallet_address`, calls the invitation above, and persists
the triple `wallet_address ↔ cvat_email ↔ cvat_id`. Registration is
one-shot — a second attempt is rejected, and the "user is a member of the
organization already" CVAT error is explicitly caught and surfaced.

Access is revoked symmetrically by destroying the org membership, not the
account (`remove_user_from_org`, `api_calls.py:848`). The worker keeps their
CVAT identity across escrows; only org membership is leased.

So the identity CVAT authenticates is the worker's own, and the oracle's
authority over it is exactly "membership in my org, role=worker".

### Assignment: an oracle-held CVAT job, assigned to that user

`POST /assignment` (`endpoints/exchange.py:407`) picks an available CVAT job
for the worker's wallet, checks qualifications, refuses if the worker already
holds an unfinished assignment, and sets the CVAT job's assignee to the
worker's `cvat_id` (`update_job_assignee`, `api_calls.py:813`). The response
carries a `url` — `{CVAT_URL}/tasks/{task_id}/jobs/{job_id}` plus workspace
query params per job type (`utils/assignments.py:15`) — and that url is `None`
once the assignment is finished. Resignation exists as a first-class endpoint
(`/assignment/resign`), and re-issuing a job clears its annotations
(`clear_job_annotations`, `restart_job`).

The worker then opens that url and annotates in CVAT's real editor.

### Completion: webhooks, not polling

The oracle registers a **project-scoped CVAT webhook** at project creation
(`create_cvat_webhook`, `api_calls.py:324`) with a shared secret, subscribed to
exactly one event:

```python
events=[models.EventsEnum(WebhookEventType.update_job.value)]
```

`POST /cvat-webhook` (`endpoints/cvat.py:13`) receives it, and the receipt path
**persists it rather than acting on it**: the `process_incoming_cvat_webhooks`
cron (`crons/cvat/state_trackers.py:24`) later drains pending webhooks in
chunks with `skip_locked`, per-webhook savepoints, and explicit
success/failure accounting so a failed one is retried.

The actual handler, `handle_update_job_event` (`handlers/cvat_events.py:20`),
ignores anything without a `state` change in `before_update`, then matches the
webhook's `assignee.id` and `updated_date` against the *latest* assignment and
rejects stale or mismatched ones.

So completion is **webhook-triggered but cron-processed** — CVAT is never
polled for job state, yet no work happens in the HTTP request. The sibling
crons (`track_completed_tasks`, `track_completed_projects`,
`track_assignments`) roll individual job completions up to task and project
completion and expire stale assignments.

That staleness guard is the interesting part: the webhook is untrusted input,
and the oracle re-derives which assignment it belongs to rather than trusting
it.

### Results: exported to storage, validated against ground truth

The exchange oracle exports the finished CVAT project's dataset and writes it
to cloud storage (`handlers/job_export/results.py:76`). The recording oracle
*downloads* that export plus an annotation metafile
(`recording-oracle/src/handlers/validation/handlers.py:_download_results`) and
scores it against **ground-truth frames** seeded into the tasks
(`core/gt_stats.py`, `handlers/validation/intermediate_results.py`), with
per-job-type quality checkers under `quality_checkers/{image,audio}.py`. On
success it writes a validation metafile and emits a `JobCompleted` webhook to
the exchange oracle; on failure it returns per-job rejection reasons.

Validation is GT-based, not inter-worker agreement. Agreement appears only as
CVAT's own optional consensus feature, which is separate.

## This repo today: the bridge inverts all of that

`kotlin/cvat/CvatBridge.kt` pulls frames *out* of CVAT, base64s each one into a
Nostr job offer, has workers answer in a mini web app, and PATCHes consensus
labels back as tags. Its doc comment states the inversion plainly: "workers
need only a relay connection, never CVAT access."

That makes the labeler *the* labeling interface rather than *a* worker client,
and it caps the system at whole-frame classification forever — boxes, polygons,
interpolation and every other real annotation primitive live in CVAT's editor
and cannot be reached through a data-URI image and a list of choices.

## CvatClient is no longer unverified

All five endpoints were exercised against real CVAT 2.74.0 (task 3 on the tdx2
instance), not against `MockCvat`. Four are correct as written:

| Call | Result |
| --- | --- |
| `GET /api/tasks/{id}` → `.name` | 200, correct |
| `GET /api/labels?task_id={id}&page_size=100` → `.results[]` | 200, correct |
| `GET /api/tasks/{id}/data/meta` → `.size` | 200, correct |
| `GET /api/tasks/{id}/data?quality=compressed&type=frame&number={n}` | 200, **but see below** |
| `PATCH /api/tasks/{id}/annotations?action=create` | 200 by `curl`; **failed from Kotlin** — see below |

`Token` auth, the `?action=create` payload shape and the label/meta parsing are
all correct as URLs and JSON. But the client itself was still broken, in a way
no amount of `curl` probing could show.

### The write path was broken by the HTTP client, not the API

`CvatClient` built its transport with `HttpClient.newHttpClient()`. That
defaults to **HTTP/2**, and against this deployment the h2c upgrade loses the
request body: CVAT answers every call that carries one with
`400 {"detail":"JSON parse error - Expecting value: line 1 column 1 (char 0)"}`
— the error you get from an empty body.

Only one `CvatClient` call carries a body, and it is the one that matters:
`appendTags`. So the entire *import* half of the bridge — writing consensus
labels back into CVAT — could never have worked against a real server. The
read path was unaffected, because GETs have no body to lose.

`curl` cannot find this: it speaks HTTP/1.1 by default, so hand-probing every
endpoint passes while the Kotlin client fails. `MockCvat` cannot find it
either, and for a more interesting reason — `com.sun.net.httpserver` is
HTTP/1.1-only, so the JDK client never attempts an upgrade against the mock and
the bug is invisible by construction.

The fix is to pin the client to HTTP/1.1 (`CvatClient.kt`). It is now covered
by `:harness:realCvatTest`, which runs against a real deployment and fails
without the pin.

The prior session's *API* guesses held up. Its transport did not.

### Both defects were found twice, independently

After reaching these conclusions, the rescued `deploy/cvat/README.md` turned out
to document both of them — the JPEG media type and the HTTP/2 body loss — from a
session that fixed them and then never committed the code. Two independent
investigations against the same deployment converging on the same two defects is
good corroboration that they are real and not artifacts of how either was
probed.

That README also described a five-test real-service suite, a regtest settlement
and persisted tags on task 2. None of that code is in the repository; the claims
are recorded there as unreproduced. What exists now is a three-test
`:harness:realCvatTest` covering `CvatClient` only, verified green against the
live instance.

**The one real bug (latent):** `quality=compressed` returns `image/jpeg`
(magic `ff d8 ff e0`), while `CvatBridge.exportTasks` hardcodes the data URI
prefix `data:image/png;base64,`.

This has never actually mis-served a frame, because the bridge has only ever
run against `MockCvat`, whose `/data` route returns a genuine PNG via
`framePng(number)` with `Content-Type: image/png` regardless of the `quality`
parameter. The mock agreed with the code, so the code looked right. The first
run against real CVAT would have emitted JPEG bytes under a PNG data URI — a
textbook mock-written-by-the-same-author failure, and the clearest single piece
of evidence for why the mock cannot serve as validation.

Fixed by deriving the data-URI type from the response `Content-Type` —
`CvatClient.frame` now returns a `CvatFrame(bytes, contentType)` and the bridge
builds the URI from it. Switching to `quality=original` would *not* have been a
fix: that returns whatever the source file was (PNG here only because the probe
uploaded PNGs; a JPEG-sourced task returns JPEG at both qualities). Other divergences found — the mock omits the
`count/next/previous` pagination envelope on `/api/labels`, ignores `task_id`
entirely, omits `frames[]` from `data/meta`, returns `{"version":0}` instead of
the full annotation object from the PATCH, and has no async data-upload phase
at all (real CVAT returns `202` with an `rq_id` that must be polled at
`/api/requests/{rq_id}` before any frame is fetchable).

None of those four divergences currently break `CvatClient`, because it reads
only the fields that happen to match. That is luck, not design, and it is worth
deciding explicitly whether the mock should be corrected to match reality or
retired in favour of the real deployment.

## Porting the access model: what was tested on tdx2

Production's mechanism was exercised directly against the real instance, because
"the oracle invites the worker" is not by itself enough to build on.

**Invitations need an organization.** The tdx2 instance had zero organizations;
production always passes `org=Config.cvat_config.org_slug`. An org
(`hpb-probe-org`) had to be created first.

**The documented call returns HTTP 500 here, and still works.**
`POST /api/invitations?org=hpb-probe-org` with `{"role":"worker","email":...}`
returns `500 "Email backend is not configured."` — tdx2 has no SMTP. But the
failure is *only* the delivery step, and it happens after the database writes:

- the invitation row is created, and its `key` is readable from
  `GET /api/invitations?org=...`
- the CVAT user is created (`username` = the email address)
- the membership row is created with `role=worker`

so the 500 is survivable. A launcher can create the invitation, tolerate the
error, read the key back over the API, and deliver it itself. That matters
because in a Nostr-native design there is no email channel to fall back on.

**The membership lands inactive, and the invited account cannot log in.** The
membership row is written with `is_active = false`, and the auto-created user's
password hash begins with `!` — Django's marker for an *unusable* password.

**But the whole flow completes without a mail server.** Running it end to end
against the real instance:

1. the launcher creates the invitation as org owner (the 500 above), and reads
   the 64-character `key` back from `GET /api/invitations?org=...`
2. the worker self-registers at `POST /api/auth/register` — which is **open** on
   this deployment: no admin involvement, `email_verification_required: false`,
   and an auth token is returned immediately
3. the worker `POST /api/invitations/{key}/accept` **authenticated as
   themselves** — returns 200, and the membership flips to `is_active = true`
   with `role = worker`

Accepting anonymously is refused with 401, which is the property worth having:
CVAT authenticates the worker's own identity, and the launcher's authority stops
at "membership in my org".

The consequence for the design: the **invitation key is the credential that must
travel**, and the launcher never holds worker credentials. Over Nostr that is a
NIP-44 payload from the launcher to the claiming worker's pubkey, which fits the
existing grant flow — but it means a granted assignment carries a secret, which
today's grants do not.

**The deeper mismatch:** production keys worker identity on **email address**.
This protocol keys it on **Nostr pubkey**. Workers here have no email, so
`invitations_api.create(email=...)` has no natural input. Any port has to
invent one (a derived `npub@…` placeholder, as the probe effectively did) or
abandon invitation-by-email for direct membership creation as the org owner.
That choice is upstream of the three options in the handoff and should be made
first.

## Deployment

Real CVAT 2.74.0 runs on `tdx2` at <http://100.112.65.68:8080>, bound to the
Tailscale IP only. Traefik routes on `Host(100.112.65.68)`, so `http://tdx2:8080`
returns a bare 404 — use the IP. Credentials for the `hpb-owner` dev admin live
in `.local/cvat/credentials.json` (gitignored, mode 0600). See
`deploy/cvat/README.md`.
