# <img height="60px" src="./static/human.svg" alt="human" />

# Human Protocol on Bitcoin

A from-scratch rewrite of the Human Protocol to run natively on Bitcoin.
The old Ethereum/Python stack is gone — it lives in git history if you need
it.

The short version:

- **Escrows are Taproot vaults**, denominated in sats. Cooperative 2-of-3
  payouts via PSBT, a launcher CSV refund and a co-signer CLTV sweep as
  timelocked backstops, and `HMTB` OP_RETURN records anchoring state so
  escrow status derives deterministically from chain data.
- **No servers.** Oracle roles are library code run from the launcher's own
  client; co-signers are third-party witnesses who recompute validation and
  the exact payout list from public events before signing. The chain reader
  is embedded SQLite over your own bitcoind.
- **Everything coordinates over Nostr** — offers, claims, encrypted
  submissions, reveals, receipts, attestations, PSBT envelopes. The event
  protocol is the API: [`docs/protocol.md`](docs/protocol.md).
- **Validation is mechanical** (manifest-committed groundtruth or
  inter-worker agreement), KYC is optional per job via portable
  attestations, staking is CLTV-locked bond UTXOs.
- **Networks**: mainnet for production, signet for testing, regtest for
  dev/CI. No testnet3/testnet4.
- **Android app** ([`android/`](android/)): worker, witness co-sign
  approval, and the distributed dashboard — a thin Compose shell over the
  JVM-tested core.

## Layout

- [`kotlin/`](kotlin/) — the reference implementation (engine, protocol,
  roles, headless daemon, test harness, android core)
- [`kotlin/labeler/`](kotlin/labeler/) — a miniature labeling web app: the
  worker side of the protocol as one page (claim, label, submit, get paid).
  Installable on phones: run it with `HPB_LABELER_BIND=<your LAN IP>`, open
  that URL on the phone, and on iOS Share → Add to Home Screen gives a
  standalone full-screen app
- [`kotlin/cvat/`](kotlin/cvat/) — the CVAT bridge: export a CVAT task's
  frames to the network as a paid labeling job, import the validated labels
  back as CVAT annotations (see [`docs/runbook.md`](docs/runbook.md))
- [`android/`](android/) — the Compose app
- [`ios/`](ios/) — the native iOS worker app: SwiftUI over `HpbCore`, a
  Swift implementation of the worker protocol byte-locked to the same
  vector corpus as the Kotlin reference
- [`docs/`](docs/) — [`protocol.md`](docs/protocol.md) (the normative
  spec), [`runbook.md`](docs/runbook.md),
  [`architecture.md`](docs/architecture.md), and the cross-language test
  vectors

## Quickstart

```sh
cd kotlin
gradle detekt test        # full suite; needs Bitcoin Core >= 26 on PATH and pip install nostr-relay
```

Run the whole flow against signet — stake, vault, offer, workers, witness
co-sign, on-chain payout:

```sh
HPB_RELAYS=ws://... gradle :headless:demo
```

See [`docs/runbook.md`](docs/runbook.md) for the signet walkthrough and the
headless witness daemon.
