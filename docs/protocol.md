# Human Protocol on Bitcoin — event protocol (v1)

The Nostr event protocol IS the API. There are no servers in the reference
flow: launchers run the oracle roles from their own clients, witnesses
co-sign from their own devices, and every party derives identical state from
public events plus their own Bitcoin view.

## Networks

MAINNET is production; SIGNET is the test network (there is no
testnet3/testnet4 compatibility); REGTEST exists for local development and
CI. The protocol is identical on all three — only the per-network
parameters differ (refund CSV delay, escrow CLTV expiry, minimum stake,
bond lock; see `org.hpb.engine.Network`). Events carry no network field:
the network's HRP is mixed into the genesis key tweak, so the same job
derives a different escrow (and escrow id) on every network, and addresses
only parse under their network's HRP — cross-network replay fails closed.

## Identity

A participant is a secp256k1 x-only public key (64 hex chars) — the same key
signs Nostr events and appears in Taproot escrow descriptors. Payout
destinations are ordinary Bitcoin addresses chosen by workers.

## Transport

Multi-relay always: publish to every configured relay, read the verified
union. Relays are commodity infrastructure — any NIP-01 relay works, and any
participant may serve their own events from an embedded relay. Clients MUST
verify event signatures on read; relays are transport, never truth.

## Kind registry

| Kind | Class | Author | Purpose |
|---|---|---|---|
| 30078 | addressable | any | KVStore (NIP-78): operator metadata, d=`org.humanprotocol.kv:<key>`; well-known keys: `fee`, `role`, `name`, `url`, `payout_btc_address` |
| 9559 | regular | escrow parties | escrow coordination records (reservations, cancel requests) |
| 33400 | addressable | launcher | job offer, d=`<escrow_id>`; revocation = republish `status:"revoked"` |
| 33401/2 | addressable | worker/oracle | reserved: registration flows for hosted-oracle deployments |
| 33405 | addressable | attester | attestation, d=`<schema>:<worker>`; republish-to-revoke |
| 33406 | addressable | any scorer | reputation snapshot, d=`<subject>` (one view among many) |
| 9560 | regular | worker | assignment claim |
| 9561 | regular | launcher | grant / reject — THE allocation authority |
| 9562 | regular | worker | resign |
| 9563 | regular | worker | submission (NIP-44 to the validator) |
| 9564 | regular | launcher | escrow results reveal (inline, hash-committed) |
| 9565 | regular | launcher | payout receipt (auditable vs the on-chain PAYOUT) |
| 9566 | regular | ANY | assessment — open, bidirectional web-of-trust reputation |
| 9567 | regular | any | abuse report |
| 9568 | regular | any | NIP-44 envelope: `psbt_sign_request` / `psbt_sign_response` |
| 9569 | regular | reserved | ZK validation/payout proofs (roadmap; offers carry `require_proof`) |

Every escrow-scoped event carries `["x", <escrow_id>]` (relay-indexed).
Contents are JSON with `"v": 1`.

## Job offer (33400)

Content: `escrow_address` (the genesis P2TR — the escrow's stable id source),
`reward_per_task_sats`, `manifest` (INLINE JSON string — Nostr-first
artifacts; its sha256 is the on-chain SETUP commitment), `kyc`
(`{required, attesters[]}` — **KYC is optional**; default none), `expires_at`,
`status` (`open|paused|closed|revoked`). The manifest carries the task list
and the validation policy.

## Mechanical validation (manifest-committed)

Both strategies are pure functions over the revealed submission set — any
observer recomputes acceptance AND the payout list:

- `groundtruth`: an answer is accepted iff
  `sha256("<task_key>:<normalized answer>")` (normalize = trim + lowercase)
  is in the committed hash set. Groundtruth itself never needs revealing.
- `agreement`: per task, the modal normalized answer wins when it reaches
  `ceil(n × agreement_threshold)` of the task's submissions (lexicographic
  tie-break); agreeing workers are accepted. Submissions are NIP-44
  encrypted to the validator during collection — encryption doubles as the
  commitment against copy attacks; the 9564 reveal publishes the full set
  inline, and its sha256 is the results hash committed in the on-chain
  PAYOUT record. Non-reveal is a provable reputational failure: workers hold
  their own signed submissions.

Payout list derivation is deterministic: reward × accepted answers,
aggregated per worker, ordered by worker pubkey.

## Assignment reducer

Every party reduces the same state from events alone. Ordering: causal
phase first — claims (9560), then grants (9561), then resign/submission
(9562/9563), then validation (9564) — with `created_at` ascending +
event-id tie-break within a phase. Phase ordering is normative: causally
chained events routinely share a second, and a bare `(created_at, id)`
sort tie-breaks on effectively random ids, silently dropping a grant that
sorts before its claim or a reveal that sorts before its submission.
Invalid signatures dropped; wrong-party events IGNORED. Authority: only
the offer author's 9561 moves CLAIMED→ACTIVE/REJECTED and only their 9564
validates; only the worker's own 9562/9563 resign/submit. Grants expire at
`expires_at` (lazy, read-time).
States: CLAIMED, ACTIVE, REJECTED, RESIGNED, SUBMITTED, VALIDATED, EXPIRED.

## Escrow anchoring (Bitcoin)

Escrows are per-job Taproot vaults
(`tr(NUMS,{multi_a(2,L,C1,C2),{launcher CSV refund, C1 CLTV sweep}})`);
state transitions carry ≤80-byte `HMTB` OP_RETURN records (SETUP/PAYOUT/
CANCEL/COMPLETE/WITHDRAW/STAKE). Chain data is adversarial: a record
naming an escrow counts only when its transaction SPENDS that escrow's
UTXOs (a deposit corroborates nothing — anyone can send dust to a public
address), SETUP applies only on the unique genesis→vault spend and is
immutable on the active chain, and malformed bytes under the magic decode
as an unknown record, never an error — a forged or garbled OP_RETURN must
neither alter state nor wedge a scanner. Receipts (9565) name the payout txid; the
txid's PAYOUT record carries `sha256(payout_id)` and the transaction's
outputs must cover the receipt's `(address, sats)` lines — wallets verify
through their own chain view (full node or compact block filters).
Co-signers (witnesses — any third parties, discovered via NIP-51 curation
lists) re-run local policy checks before signing any payout PSBT (9568
envelopes carry the PSBT): the offer's manifest hash must equal the
SETUP-committed one (a re-published offer cannot change the terms signed
for); the reveal must contain only grant-scoped rows — at most one per
(worker, task), each covered by a granted assignment (`Validators.scoped`,
also applied by launchers when collecting); the PSBT must spend only the
escrow's vault UTXOs and carry exactly one PAYOUT record committing to the
request (payout-id hash, results hash, flags); and the COMPLETE output
multiset must be accounted for — the claimed recipients, the exact
SETUP-committed co-signer fees at finalize, and a single remainder sink
(vault change mid-job, launcher refund at finalize). Any surplus output,
wherever it points, is refused. Co-sign liveness is normative: a witness treats
a failed verification as possibly transient (relays are eventually
consistent — the reveal or reservation may arrive after the request),
publishing at most one diagnostic refusal per request while re-verifying on
later polls; a launcher accepts any successful `psbt_sign_response` for a
payout id, ignoring earlier refusals. Policy is not consensus: any 2-of-3 collusion can
move funds — identical trust shape to the original protocol's oracles, plus
the launcher's unilateral timelocked refund.

## Reputation

Kind 9566 assessments are open and bidirectional — anyone may assess anyone
(workers rate launchers and witnesses too). Clients aggregate reputation
from trust roots they choose (NIP-51 lists); 33406 snapshots are individual
scorers' views, never authoritative.

## Spam & abuse

Claims without required attestations are ignored by grant logic (and never
granted). Deployments may additionally require NIP-13 proof-of-work on 9560
(`min_pow_bits` in the launcher's KVStore metadata) and NIP-42 relay auth.
Economic bonds are a documented non-goal for v1: the 2-of-3 escrow already
caps damage to grant-slot exhaustion, which expiry reclaims.

## Vectors

`docs/vectors/protocol.vectors.json` (deterministic fixtures: codecs,
reducer, validators, payouts, attestation checks) and
`docs/vectors/nip44.vectors.json` (official NIP-44 suite) are the
cross-language source of truth — every implementation runs both.
