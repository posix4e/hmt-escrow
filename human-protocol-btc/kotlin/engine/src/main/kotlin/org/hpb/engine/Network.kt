package org.hpb.engine

/**
 * The protocol's networks: MAINNET is production, SIGNET is THE testnet
 * (no testnet3/testnet4 compatibility — free coins, reliable blocks), and
 * REGTEST exists for local development and the CI harness.
 *
 * Timelocks: cancelDelayBlocks is the launcher's relative CSV refund delay;
 * expiryBlocks is the absolute CLTV escrow deadline added at setup time.
 */
enum class Network(
    val hrp: String,
    val wifPrefix: Byte,
    val minConfirmations: Int,
    val cancelDelayBlocks: Int,
    val expiryBlocks: Int,
    val minStakeSats: Long,
    val stakeLockBlocks: Int,
) {
    MAINNET("bc", 0x80.toByte(), 3, 1008, 14400, 1_000_000, 4320),
    SIGNET("tb", 0xEF.toByte(), 1, 144, 1440, 100_000, 288),
    REGTEST("bcrt", 0xEF.toByte(), 1, 20, 200, 100_000, 100),
    ;

    /** Bitcoin Core's default RPC port on this network. */
    val defaultRpcPort: Int
        get() = when (this) {
            MAINNET -> 8332
            SIGNET -> 38332
            REGTEST -> 18443
        }

    /** Subdirectory of the Core datadir holding this network's state (and .cookie). */
    val datadirSubdir: String
        get() = when (this) {
            MAINNET -> ""
            SIGNET -> "signet"
            REGTEST -> "regtest"
        }
}

const val DUST_LIMIT_SATS = 330L
const val MAX_PAYOUT_RECIPIENTS = 100

/** Escrow lifecycle. Semantics follow the Human Protocol lifecycle design. */
enum class EscrowStatus { LAUNCHED, PENDING, PARTIAL, PAID, COMPLETE, CANCELLED, TO_CANCEL }
