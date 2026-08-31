#!/usr/bin/env bash
# Regtest block producer. Waits for bitcoind, makes the wallets once, matures
# coins, funds the launcher wallet, then keeps producing blocks so escrow
# confirmations actually land.
set -euo pipefail

# The entrypoint is overridden here, so nothing sets the datadir for us and
# bitcoin-cli would look in root's home instead of the shared volume — where
# the RPC cookie actually is.
cli() { bitcoin-cli -regtest -datadir=/home/bitcoin/.bitcoin -rpcconnect=bitcoind "$@"; }

block_seconds="${HPB_BLOCK_SECONDS:-10}"
launcher_wallet="${HPB_WALLET:-hpb}"

until cli getblockchaininfo >/dev/null 2>&1; do
    echo "waiting for bitcoind…"
    sleep 2
done

# createwallet fails if it already exists; loadwallet fails if already loaded.
# Neither is an error here, so both are allowed to fail.
for wallet in miner "$launcher_wallet"; do
    cli createwallet "$wallet" >/dev/null 2>&1 || cli loadwallet "$wallet" >/dev/null 2>&1 || true
done

miner_address="$(cli -rpcwallet=miner getnewaddress)"

# Coinbase outputs need 100 confirmations. Mining exactly 101 blocks matures a
# single 50 BTC coinbase, which cannot then send 50 BTC plus fees — so mine a
# buffer and fund well under one coinbase.
height="$(cli getblockcount)"
if [[ "$height" -lt 130 ]]; then
    echo "mining $((130 - height)) blocks to maturity"
    cli generatetoaddress "$((130 - height))" "$miner_address" >/dev/null
fi

# Keep the launcher wallet able to post its stake bond and fund escrows.
balance="$(cli -rpcwallet="$launcher_wallet" getbalance)"
if awk -v b="$balance" 'BEGIN { exit !(b < 10) }'; then
    launcher_address="$(cli -rpcwallet="$launcher_wallet" getnewaddress)"
    echo "funding $launcher_wallet at $launcher_address"
    # Funding must not be fatal: the block producer below is what everything
    # else waits on, and a transient failure here is recoverable next restart.
    if cli -rpcwallet=miner sendtoaddress "$launcher_address" 25 >/dev/null; then
        cli generatetoaddress 1 "$miner_address" >/dev/null
    else
        echo "WARNING: could not fund $launcher_wallet; mining anyway"
    fi
fi

echo "producing a block every ${block_seconds}s"
while true; do
    cli generatetoaddress 1 "$miner_address" >/dev/null 2>&1 || echo "mine failed; retrying"
    sleep "$block_seconds"
done
