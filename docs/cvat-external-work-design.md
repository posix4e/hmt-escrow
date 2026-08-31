# Design: CVAT as an external work source

A proposal and a plan. Nothing here is implemented. Evidence for every claim
about production and about CVAT's behaviour is in
[the architecture comparison](cvat-oracle-comparison.md); everything asserted
about CVAT was run against the real deployment, not read from documentation.

Scope per the owner's decision: **tags/classification first**, shapes later.
No HTTP compatibility with the existing HUMAN oracle REST contracts.

## The inversion being undone

Today `CvatBridge` pulls frames out of CVAT and ships them as data URIs, so the
labeler is *the* labeling interface. The redesign makes the labeler *a* worker
client: annotation happens in CVAT's real editor, and the protocol supplies
workers, escrow, validation and payout around it.

## Decisions taken

- **Completion detection: webhook.** Not polling.
- **Recording role: cross-checked from the start.** Not a single trusted role.

Two remain open, and are argued in detail below: **access provisioning** and
**staging**.

---

# Question 1: how a worker gets into CVAT

## What the three options actually are

**(a) Worker brings their own CVAT account; the launcher leases it org
membership.** The launcher, as org owner, invites the worker's email as
`role=worker`; the worker accepts with their own credentials; revocation
destroys the membership, never the account. This is what production does.

**(b) The launcher provisions a CVAT user per worker.** The launcher creates the
account, holds its credentials, and hands them to the worker.

**(c) A short-lived scoped per-assignment credential.** The launcher mints a
narrow, expiring token that grants exactly one assignment.

## What CVAT can and cannot do

Option (c) is **not buildable on CVAT**, and the reason is more specific than
"it doesn't exist". CVAT 2.74 *does* have expiring tokens —
`POST /api/auth/access_tokens` takes `name`, a nullable `expiry_date`
("once the token expires, clients cannot use it anymore") and a `read_only`
flag. But the request body has **no user field**: a token is minted by the
authenticated user *for themselves*. A launcher cannot mint one for a worker,
and `read_only` is the only scoping available — there is nothing
assignment-scoped. The unit of access in CVAT is org membership, full stop.

So the real choice is between (a) and (b).

## Option (a) verified end to end, without a mail server

1. Launcher `POST /api/invitations?org=<slug>` with the worker's email. Returns
   **HTTP 500 "Email backend is not configured"** on an instance with no SMTP —
   but the invitation, user and membership rows are all written first, and the
   64-character `key` is readable from `GET /api/invitations`.
2. Worker `POST /api/auth/register`. Open on this deployment: no admin
   involvement, `email_verification_required: false`, auth token returned
   immediately.
3. Worker `POST /api/invitations/{key}/accept` **authenticated as themselves**.
   Returns 200; membership flips to `is_active=true`, `role=worker`.

Accepting anonymously is refused with 401.

Step 2 depends on the instance allowing open self-registration, which is
deployment configuration rather than a CVAT invariant. Since the launcher
operates the CVAT instance in this design, that is a requirement to state, not
an obstacle.

## Why (a), stated as the argument that actually decides it

The decisive point is not convenience, it is **what a dishonest launcher can
forge**.

Under (b) the launcher holds the worker's CVAT credentials. It can therefore
*author annotations as the worker*, then point at that work as evidence the
worker performed badly and withhold payout — and no observer can distinguish
the worker's work from the launcher's forgery, because both are authenticated
as the same CVAT account. That silently breaks the property the whole protocol
exists to provide: mechanical validation any observer can recompute.

Under (a) the worker's CVAT account is theirs. The launcher can assign and
unassign jobs and read annotations, but it cannot produce annotations
authenticated as the worker.

Secondary but real: (a) gives the worker a CVAT identity that persists across
escrows and launchers, which is the substrate any future reputation depends on.
(b) makes worker identity a per-launcher artifact.

## The two things (a) costs, both of which must be designed for

### The grant has to carry a secret

The invitation key is the credential that must reach the worker, so it travels
NIP-44-encrypted to the claiming worker's pubkey. Today grants are public.

This costs witnesses **nothing**, which is worth being precise about:
`Validators.scoped` is re-checked from the grant's *public* fields
(`claimEventId`, `granted`, `taskKeys`, and the worker linkage). The encrypted
payload is opaque to everyone except the worker, so no witness decrypts anything
to re-verify an assignment. The reducer's causal-phase ordering is untouched.

### One CVAT identity can back two npubs — a Sybil hole

Nothing stops two different Nostr keys from declaring the *same* CVAT email.
Both would resolve to one CVAT account, letting one person hold two assignments
on the same task and agree with themselves — which defeats inter-worker
agreement validation and cross-checking alike.

The launcher must therefore reject a claim whose CVAT identity already backs
another granted worker in the same escrow, and — because the launcher is not
trusted — **every witness must be able to re-check that**. That means the
binding npub ↔ cvat_id has to be observable, so the grant carries the worker's
resolved `cvat_id` in the clear (it is not a secret; the invitation key is).

## Whoever operates CVAT is trusted for annotation integrity

This should be said plainly rather than discovered later. Annotations live in a
system the launcher administers. A launcher who is also the CVAT org owner can
edit them. Cross-checking recording roles does not fix this: if the launcher
tampers before every recorder pulls, they all pull the same tampered data and
agree.

**The mitigation is a worker-side commitment.** At submission time the worker
hashes their own canonical annotations — they have CVAT access, so they can read
back exactly what they drew — and the submission carries that hash. The
recording roles later publish the annotations they pulled. Any observer compares
the published canonicalisation against the worker's signed commitment:

- they match → the annotations are what the worker actually submitted
- they differ → either the worker lied about their own work or someone tampered
  after submission, and in both cases it is detectable and attributable

This turns the submission from a bare "I'm done" into the thing that makes the
external work source verifiable at all. It is the single most important element
of this design.

---

# Question 4: staging, and exactly what the schema will and will not absorb

## What is actually free-form

This is the load-bearing fact, and it is narrower than it first appears:

| Field | Shape | Free-form? |
| --- | --- | --- |
| `Task.question` | `Pj.str(...)` of an arbitrary string | **yes** |
| `Answer.answer` | `Pj.str(...)` inside the NIP-44 submission | **yes** |
| `Claim` content | closed object: `payout_address`, `attestations` | **no** |
| `Grant` content | closed object: `status`, `task_keys`, `expires_at`, `reason` | **no** |

`parseClaim` and `parseGrant` read a fixed key set, and claim and grant events
are in the byte-locked vector corpus. So **the CVAT access handshake cannot ride
on the claim or the grant without paying the invariant-2 bill**, while the work
reference and the completion assertion can ride on `Task.question` and
`Answer.answer` for free.

That asymmetry is what shapes the staging.

## Phase 0 — no schema change at all

- **Work reference** goes inside `Task.question` as JSON: tool, base URL, org,
  CVAT task id, CVAT job id, label schema. The on-chain manifest already commits
  `key` and `question`, so the work reference is committed on-chain for free.
- **Completion assertion + annotation commitment** go inside `Answer.answer` as
  JSON: CVAT job id, the worker's cvat_id, and the hash of their canonical
  annotations.
- **Access handshake** runs as a NIP-44 side-channel keyed to the claim and
  grant event ids, *not* as new claim/grant fields: worker → launcher with the
  CVAT email, launcher → worker with the invitation key and resolved cvat_id.

Cost: **zero vector regeneration, zero Swift core work.** The Swift core sees
the same types with different string contents. The web labeler gains an "open in
CVAT" flow; the phone apps need no core change to keep working on inline jobs.

Risk: free-form JSON is unvalidated and two implementations can drift. Mitigated
by specifying the inner JSON as normative in `docs/protocol.md` even while it
lives inside a string, and parsing it in exactly one place.

## Phase 1 — promote to first-class fields

Do this when shapes (boxes/polygons) arrive, because that milestone forces a
typed canonical serialisation for IoU agreement and the results hash anyway.
Promoting earlier pays the same bill twice.

What comes due:
- `JobOffer` gains a work-source variant → `manifestJson()` changes → the
  on-chain manifest hash changes
- the access handshake becomes claim/grant fields
- `docs/vectors/protocol.vectors.json` regenerates
- the Swift core gains the same fields with byte-identical canonical JSON
  emission — key order, UTF-16 string sorting, number formatting — because both
  implementations regenerate the corpus and compare byte for byte

**Recommendation: phase 0 now, phase 1 at the shapes milestone.**

---

# The decided pieces, designed

## Webhook completion detection

Mirroring production, with its hard-won details:

- register one **project-scoped** webhook at project creation:
  `target_url`, `type=project`, `project_id`, `secret` (≤64 chars),
  `events=["update:job"]`
- verify the **`X-Signature-256`** header (HMAC-SHA256 of the body with the
  secret) before anything else; production does, and the payload is untrusted
  input
- **persist the delivery and return** — do no work in the HTTP handler. A
  drainer processes pending deliveries with retries and per-delivery failure
  accounting
- ignore deliveries without a `state` change in `before_update`, and re-derive
  which assignment a delivery belongs to rather than trusting it: match the
  payload's `assignee.id` and `updated_date` against the latest assignment, and
  drop stale or mismatched ones

Consequence to state in the runbook: **a launcher now needs a reachable inbound
HTTP endpoint.** That is a new operational requirement, and it is why polling
was the tempting alternative.

## Cross-checked recording roles

Multiple recording roles independently pull annotations and publish reveals;
acceptance requires agreement among them, reusing the existing agreement
machinery rather than inventing a second one.

Two failure modes have to be designed out:

- **False disagreement from pulling at different times.** Recorders must pull
  only after the CVAT job reaches a terminal state, and must canonicalise
  identically. The worker's committed hash is the anchor: a recorder whose pull
  does not match the commitment reports a mismatch rather than publishing a
  competing truth.
- **Cross-checking does not defend against a tampering CVAT operator** (see
  above). The worker commitment does. Recorder agreement defends against a
  dishonest or broken *recorder*.

---

# Plan

Phase 0 throughout. Each step is independently reviewable; detekt caps
cyclomatic complexity at 7, so these are deliberately small units.

1. **`CvatOrg`** (`kotlin/cvat`) — launcher-side admin operations against real
   CVAT: create org, create project and task, register the webhook, create an
   invitation and read its key, resolve a user id, assign and unassign a job,
   list job states, remove a membership. Every method covered by
   `:harness:realCvatTest` against the live instance, in the style already
   established there.
2. **`CvatAccess`** — the NIP-44 access handshake as a side-channel: worker
   sends its CVAT email against a claim; launcher invites, resolves `cvat_id`,
   and returns the invitation key. Includes the Sybil guard (reject a CVAT
   identity already backing another granted worker in this escrow).
3. **Work-source encoding** — the normative JSON for `Task.question` and
   `Answer.answer`, specified in `docs/protocol.md` and parsed in one place.
   Vector corpus untouched; assert that in CI by regenerating and diffing.
4. **Worker commitment** — canonical annotation serialisation for tags, plus
   the worker-side read-back-and-hash at submission. This is the piece the
   verifiability argument rests on, so it lands before anything depends on it.
5. **Webhook listener** — signature verification, persist-and-return, drainer
   with retries, staleness and assignee re-derivation.
6. **Recording role** — pull on terminal job state, canonicalise, compare
   against the worker commitment, publish the reveal. Cross-check across
   multiple recorders; disagreement is reported, not silently resolved.
7. **Validation over pulled annotations** — reuse agreement and groundtruth
   hashing unchanged, over the canonicalised strings.
8. **End-to-end harness test** — real CVAT, real relay, real bitcoind on
   regtest, a worker annotating through CVAT's API as itself, two recording
   roles agreeing, payout confirmed on-chain.
9. **Retire the inline-frame path** — remove the data-URI export and `MockCvat`
   once the real-CVAT harness covers the same ground, rather than maintaining a
   mock that has now twice concealed a real defect.

Carry forward one lesson the current bridge learned the hard way: **read back
what you wrote.** Import must verify persisted annotations rather than trust an
HTTP 200 — the transport bug in `CvatClient` was invisible precisely because
nothing checked.

## Still to answer

1. **Provisioning** — take option (a) as argued, accepting an encrypted grant?
2. **Staging** — phase 0 now and phase 1 at the shapes milestone, as
   recommended?
