package org.hpb.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DescriptorsTest {
    private val k1 = ByteArray(32).also { it[31] = 1 }
    private val k2 = ByteArray(32).also { it[31] = 2 }
    private val k3 = ByteArray(32).also { it[31] = 3 }

    // bitcoin-cli -regtest deriveaddresses "tr(NUMS,{multi_a(2,K1,K2,K3),
    //   {and_v(v:pk(K1),older(20)),and_v(v:pk(K2),after(200))}})#ldt8qgtl"  (Core 29.0)
    private val goldenVaultAddress =
        "bcrt1pant5y4mjkym8mrzaxmef2flne7yf38fs55dfgczkk0sxg8wrvsns28ux5j"

    private fun vault() = Descriptors.vault(
        Secp.xonlyHex(k1), Secp.xonlyHex(k2), Secp.xonlyHex(k3), 20, 200, Network.REGTEST,
    )

    @Test
    fun vaultAddressMatchesCoreGolden() {
        val vault = vault()
        assertEquals(goldenVaultAddress, vault.address)
        assertContentEquals(byteArrayOf(0x51, 0x20), vault.scriptPubKey.copyOfRange(0, 2))
    }

    @Test
    fun leafClassificationRoundTrips() {
        val vault = vault()
        for (leaf in VaultLeaf.entries) {
            assertEquals(leaf, vault.leafOf(vault.leafHashes.getValue(leaf)))
        }
        assertNull(vault.leafOf(ByteArray(32)))
        vault.controlBlocks.values.forEach { assertTrue(it.size == 65 || it.size == 97) }
    }

    @Test
    fun scriptNumEncodings() {
        assertContentEquals(byteArrayOf(0x51), Script.scriptNum(1))
        assertContentEquals(byteArrayOf(0x60), Script.scriptNum(16))
        assertContentEquals(byteArrayOf(0x01, 0x11), Script.scriptNum(17))
        assertContentEquals(byteArrayOf(0x01, 0x14), Script.scriptNum(20))
        assertContentEquals(byteArrayOf(0x01, 0x7f), Script.scriptNum(127))
        assertContentEquals(byteArrayOf(0x02, 0x80.toByte(), 0x00), Script.scriptNum(128))
        assertContentEquals(byteArrayOf(0x02, 0xc8.toByte(), 0x00), Script.scriptNum(200))
        assertContentEquals(byteArrayOf(0x03, 0x00, 0x00, 0x01), Script.scriptNum(65536))
    }

    @Test
    fun duplicateKeysRejected() {
        assertFailsWith<IllegalArgumentException> {
            Descriptors.vault(
                Secp.xonlyHex(k1), Secp.xonlyHex(k1), Secp.xonlyHex(k3), 20, 200, Network.REGTEST,
            )
        }
    }

    @Test
    fun genesisIsDeterministicJobAndNetworkScoped() {
        val a1 = Keys.genesisPrivateKey(k1, "job-1", 0, Network.REGTEST)
        val a2 = Keys.genesisPrivateKey(k1, "job-1", 0, Network.REGTEST)
        val b = Keys.genesisPrivateKey(k1, "job-1", 1, Network.REGTEST)
        val c = Keys.genesisPrivateKey(k1, "job-2", 0, Network.REGTEST)
        val d = Keys.genesisPrivateKey(k1, "job-1", 0, Network.SIGNET)
        val e = Keys.genesisPrivateKey(k1, "job-1", 0, Network.MAINNET)
        assertContentEquals(a1, a2)
        // same job on another network derives a different escrow entirely
        assertEquals(5, setOf(a1.hex(), b.hex(), c.hex(), d.hex(), e.hex()).size)

        val genesis = Descriptors.genesis(Secp.xonlyHex(a1), Network.REGTEST)
        assertEquals("tr(${Secp.xonlyHex(a1)})", genesis.descriptor)
        assertTrue(genesis.address.startsWith("bcrt1p"))
        assertEquals(32, Keys.escrowId(genesis.scriptPubKey).size)
    }

    @Test
    fun bondDescriptorShape() {
        val bond = Descriptors.bond(Secp.xonlyHex(k1), 500, Network.REGTEST)
        assertTrue(bond.descriptor.contains("after(500)"))
        assertTrue(bond.address.startsWith("bcrt1p"))
        assertEquals(33, bond.controlBlock.size)
    }
}
