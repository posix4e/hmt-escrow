# Runbook

## Networks

Two real networks plus the harness one — no testnet3/testnet4 compat:

| network | role | Core flags | coins |
|---|---|---|---|
| `MAINNET` | production | `-txindex` | real |
| `SIGNET` | THE testnet | `-signet -txindex` | free (faucet) |
| `REGTEST` | local dev + CI | spawned by the harness | mined locally |

Per-network protocol parameters (refund CSV delay, escrow CLTV expiry,
minimum stake, bond lock) live in `org.hpb.engine.Network`; everything else
is identical across networks by construction.

## Demo on signet (testnet)

The full serverless round-trip — stake, vault, offer, claims, submissions,
reveal, witness co-sign, on-chain payout — as one program against the real
test network:

```sh
# 1. a synced signet node (first sync is minutes, the chain is small)
bitcoind -signet -txindex -daemon

# 2. a wallet with faucet coins (https://signetfaucet.com)
bitcoin-cli -signet createwallet hpb
bitcoin-cli -signet -rpcwallet=hpb getnewaddress   # fund this, wait 1 conf

# 3. run the demo (defaults: HPB_NETWORK=SIGNET, Core's default RPC
#    port/cookie, wallet "hpb"; only the relays are required)
cd kotlin
HPB_RELAYS=wss://your-relay.example gradle :headless:demo
```

It prints each step with mempool.space/signet links and waits for real
confirmations. Identity keys and role indexes persist under
`~/.hpb-demo/signet/`, so the stake bond is paid once and reused across
runs. Any NIP-01 relay works; for a throwaway one:
`pip install --user nostr-relay && nostr-relay serve` (then
`HPB_RELAYS=ws://127.0.0.1:6969`). Public relays also work but may purge
unknown kinds — run your own for anything you want to keep.

`HPB_NETWORK=MAINNET` runs the identical program in production (real sats:
mind `minStakeSats` and fund only what the job pays out). The regtest
harness smoke-tests this exact runner in CI (`DemoRunTest`).

## Labeling with CVAT

The bridge outsources a CVAT task's annotation to the network: each frame
becomes a job task carrying the image inline plus CVAT's label schema as
choices, workers label from any client (the `labeler` web app, the Android
app), and the validated consensus labels come back as CVAT tag annotations
— CVAT stays the dataset/QA tool, the protocol supplies workers, escrow,
and payout.

```sh
cd kotlin
# a worker's labeling page at http://127.0.0.1:7677
HPB_RELAYS=wss://your-relay.example gradle :labeler:run

# the launcher side: export CVAT task 42, collect labels, pay, import
CVAT_URL=https://cvat.example CVAT_TOKEN=... CVAT_TASK_ID=42 \
HPB_RELAYS=wss://your-relay.example gradle :cvat:run
```

`HPB_CVAT_WORKERS` sets how many independent labels each frame needs
(agreement consensus decides the answer). No CVAT deployment handy?
`gradle :cvat:mock` serves a tiny built-in lookalike (task 1, "animals",
cat/dog) on :7688 — the default `CVAT_URL`.

## Serverless (the reference flow)

No servers. Each participant needs: their key, relays, and — for parties
that watch the chain (launcher, witness) — a bitcoind they trust
(`-txindex`; v26+). Workers need only keys and relays.

- **Launcher** (runs the whole job from its client): stake once →
  `LauncherRole.createEscrow` → fund the genesis address → `setupAndOffer`
  (sweeps to the vault, announces, publishes the offer) → `grantClaims` →
  `collectSubmissions` → `revealAndReserve` → `requestCosign` →
  `finishPayout` (broadcasts, publishes the receipt).
- **Witness** (any third party): run `WitnessRole.serveOnce()` whenever the
  client wakes. It learns escrows from announces, replays reservations into
  its own index, recomputes validation + payouts from the reveal, and
  refuses to sign anything that doesn't verify.
- **Worker**: `WorkerActor.claim` → await grant → `submit`. Payouts arrive
  at the address the worker chose; receipts name the txid for wallet-side
  verification.

## Headless witness (optional, for 24/7 liveness)

The same `WitnessRole` in a poll loop — no extra authority, just
responsiveness:

```sh
cd kotlin && gradle :headless:installDist
# HPB_NETWORK defaults to SIGNET, and the RPC url/cookie default to Core's
# conventions for that network — set them only to override.
HPB_KEY_FILE=witness.key \
HPB_RELAYS=wss://your-relay.example \
HPB_DB_PATH=witness.sqlite \
headless/build/install/headless/bin/headless
```

## Tests

```sh
cd kotlin
gradle detekt test        # everything: gates, units, integration, both e2e variants
```

Requires `bitcoind` (v26+) on PATH and the `nostr-relay` Python package
(`pip install --user nostr-relay`).
