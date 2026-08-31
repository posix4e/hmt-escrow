# bitcoind and a relay for a live job

The other half of the CVAT development deployment: a regtest bitcoind and a
NIP-01 relay, so a real escrow can settle while a human annotates in CVAT's UI.
CVAT itself is [deploy/cvat](../cvat/README.md).

## Running instance

On 2026-08-31 this was brought up on `tdx2`, alongside CVAT:

- bitcoind regtest RPC — `100.112.65.68:18443`
- nostr relay — `ws://100.112.65.68:6969`
- Deployment directory: `/home/tdx2/hpb-stack`; compose project `hpb`,
  containers `hpb_bitcoind`, `hpb_miner`, `hpb_relay`.

Both ports bind to the Tailscale address only, never all interfaces. This is a
development instance on a private network with no TLS and no auth on the relay.

## Start it

```sh
HPB_BIND_ADDRESS=100.112.65.68 docker compose -f deploy/hpb/compose.yml up -d
```

`HPB_BIND_ADDRESS` defaults to loopback. `HPB_UID`/`HPB_GID` (default 1000) must
match the user that will run the launcher, `HPB_BLOCK_SECONDS` (default 10) sets
the block interval, and `HPB_WALLET` (default `hpb`) names the wallet the
launcher funds escrows from — it must match `HPB_WALLET` in the launcher's own
environment.

Point the launcher at it with:

```sh
HPB_NETWORK=REGTEST \
HPB_RPC_URL=http://100.112.65.68:18443 \
HPB_RPC_COOKIE=/home/tdx2/hpb-stack/data/bitcoin/regtest/.cookie \
HPB_RELAYS=ws://100.112.65.68:6969
```

## Why a miner container exists

Nothing in `RoleStack` mines. `awaitVaultConfirmed` and `waitConfirmed` poll for
confirmations that would never arrive on regtest, so every escrow would hang at
setup. `miner.sh` also creates the wallets and funds the launcher's once.

Two things it has to get right, both learned the hard way:

- Coinbase outputs need **100 confirmations**. Mining exactly 101 blocks matures
  a single 50 BTC coinbase, which then cannot send 50 BTC *plus fees* — hence the
  block buffer and the smaller funding amount.
- The miner overrides the image entrypoint, so nothing sets the data directory
  for it and `bitcoin-cli` would look in root's home rather than the shared
  volume where the RPC cookie lives. It passes `-datadir` explicitly.

## Why bitcoind runs as root in the container

Its entrypoint remaps its own `bitcoin` user to `UID`/`GID` with `usermod` and
then drops privileges. Setting compose's `user:` breaks that — `usermod` needs
root, and the container crash-loops on `cannot lock /etc/passwd`. Passing
`UID`/`GID` as environment variables is the supported path, and it is what keeps
the RPC cookie owned by the host user so the launcher can read it.

## Why the relay is a container

`RelayFixture` in the test harness uses the Python `nostr-relay` package, whose
`coincurve` dependency does not build on Python 3.14 — the version on both this
host and the development Mac. Any NIP-01 relay satisfies the protocol, so this
uses `nostr-rs-relay` instead. It stores events in `data/relay` and accepts the
protocol's kinds without configuration.

## Stop without deleting data

```sh
docker compose -f deploy/hpb/compose.yml down
```

`data/bitcoin` and `data/relay` are bind mounts and survive. Deleting them
resets the chain and drops every published event.
