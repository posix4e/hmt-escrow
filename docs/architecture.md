# Human Protocol on Bitcoin

A from-scratch, serverless-first rewrite of the
[Human Protocol](https://github.com/humanprotocol/human-protocol) running
natively on Bitcoin. No API compatibility with previous versions; the Nostr
event protocol **is** the API.

## Architecture

- **Escrows are Taproot vaults**, denominated in sats. Each job gets a
  *genesis* P2TR address (launcher-only, the escrow's stable identifier);
  `setup` sweeps funds into the *vault*:

  ```
  tr(NUMS,{
    multi_a(2,<launcher>,<cosigner1>,<cosigner2>),   # cooperative payout (2-of-3)
    {and_v(v:pk(<launcher>),older(CANCEL_DELAY)),    # launcher CSV refund
     and_v(v:pk(<cosigner1>),after(EXPIRY_HEIGHT))}  # co-signer CLTV sweep
  })
  ```

  Co-signer slots are *witnesses* — any third parties the job picks, signing
  from their own devices — or hosted oracle keys; that is a per-job key
  assignment, not a different protocol.
- **Serverless-first**: oracle roles are library logic run from the
  launcher's own client. Validation is mechanical and manifest-committed
  (groundtruth matching, or inter-worker agreement consensus) — a pure
  function over the revealed submission set that anyone can recompute.
- **Everything coordinates over Nostr**: job offers, claims, grants,
  encrypted submissions, validations, payout receipts, open web-of-trust
  reputation, operator metadata, and PSBT co-signing envelopes. Multi-relay
  by default; participants can embed their own relay — zero shared
  infrastructure. Manifests/results ride inline in events, hash-committed
  on-chain via `HMTB` OP_RETURN records (<= 80 bytes).
- **The chain reader is an embedded library, not a server**: SQLite inside
  the process, filled from the participant's own bitcoind; escrow status is
  derived deterministically from transaction patterns, tapleaf
  identification, and signed records.
- **Staking** is CLTV time-locked bond UTXOs (provable, non-slashable).
- **KYC is optional** and attestation-based: jobs declare accepted attesters;
  the default is none.

## Trust model (read this)

Funds sit in a real on-chain 2-of-3. **Any two colluding key-holders can move
funds.** Payout correctness (reserved-funds accounting, fee splits, dust
rules) is enforced by every signer's local policy checks and attested by
hash-committed, signed records — not by consensus (no covenants on Bitcoin).
The launcher can always recover funds unilaterally after the cancel timelock;
a co-signer can sweep funds back to the launcher after escrow expiry.

## Layout

- `kotlin/` — the reference implementation (Gradle multi-project):
  - `engine` — descriptors + BIP341 taptree math, `HMTB` OP_RETURN codec,
    bitcoind RPC, multi-party PSBT pipeline, embedded chain reader (SQLite,
    a library — never a server), escrow/staking/stats APIs, Nostr client
    (NIP-01 multi-relay, NIP-44, NIP-78 KVStore)
  - `protocol` — event codecs, the deterministic assignment reducer,
    mechanical validators (groundtruth + agreement), attestations
  - `roles` — launcher / witness / worker as pure library logic (a phone
    app and a headless runner hold exactly the same thing)
  - `headless` — the optional witness daemon (a poll loop, nothing more)
  - `harness` — regtest + relay integration suites and the serverless
    end-to-end round-trips

  Complexity is budgeted in CI: detekt caps every function at cyclomatic
  complexity 7.
- `docs/` — `protocol.md` (the normative spec), `runbook.md`, `vectors/`
  (the cross-language test corpora).

## Development

```sh
cd kotlin
gradle detekt test          # lint gates + engine unit tests
gradle :harness:test        # spawns bitcoind -regtest (Bitcoin Core >= 26 on PATH)
```

## Roadmap

iOS/Winnow integration (Swift twin via the shared vector corpus), ZK proofs
of validator/payout correctness (kind 9569 + `require_proof` reserved),
anonymous-KYC credentials, k-of-n key assignments, MuSig2 key-path,
Lightning fan-out for sub-dust payouts.
