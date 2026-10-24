package com.andrexthedev.autovybz.audit

import com.andrexthedev.autovybz.ui.about.AutoVybzInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Donation addresses are money: verify them cryptographically (Bitcoin bech32 checksum, Solana base58 ->
 * 32-byte ed25519 key, Ethereum EIP-55 mixed-case checksum via Keccak-256) and check that the app, README
 * and website all show exactly the same values.
 */
class ProjectInfoAuditTest {

    @Test
    fun valuesAreExactlyTheRequestedOnes() {
        assertEquals("79KsqtJJdhKFJ9woxnYgtf3nq7HxQveafWBCtC3mxWi8", AutoVybzInfo.DONATE_SOL)
        assertEquals("bc1qeqzrlfg3edrydk4s0hecakc82gp26n5p7hkc7f", AutoVybzInfo.DONATE_BTC)
        assertEquals("0xBC3fab34f69bc9f6661608C3FB36dDdC313C42F7", AutoVybzInfo.DONATE_ETH)
        assertEquals("hippie.highho@gmail.com", AutoVybzInfo.CONTACT_EMAIL)
        assertEquals("AndrexTheDev", AutoVybzInfo.DEVELOPER)
        assertTrue(AutoVybzInfo.SOURCE_URL.startsWith("https://github.com/AndrexTheDev/"))
        assertTrue(Regex("""^[\w.+-]+@[\w-]+\.[\w.]+$""").matches(AutoVybzInfo.CONTACT_EMAIL))
    }

    @Test
    fun bitcoinAddressHasValidBech32Checksum() {
        assertTrue(Crypto.bech32Valid(AutoVybzInfo.DONATE_BTC))
        val program = Crypto.segwitProgram(AutoVybzInfo.DONATE_BTC)
        assertEquals("P2WPKH witness program", 20, program.size)
        // A single changed character must be detected.
        assertFalse(Crypto.bech32Valid(AutoVybzInfo.DONATE_BTC.replaceRange(10, 11, "x")))
        // BIP-173 reference vector.
        assertTrue(Crypto.bech32Valid("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))
    }

    @Test
    fun solanaAddressIs32ByteBase58Key() {
        assertEquals(32, Crypto.base58Decode(AutoVybzInfo.DONATE_SOL).size)
        assertTrue(AutoVybzInfo.DONATE_SOL.none { it in "0OIl" })
    }

    @Test
    fun keccakMatchesReferenceVectors() {
        assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", Crypto.hex(Crypto.keccak256(ByteArray(0))))
        assertEquals("4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45", Crypto.hex(Crypto.keccak256("abc".toByteArray())))
        // > 1 block (rate = 136 bytes)
        assertEquals(32, Crypto.keccak256(ByteArray(300) { it.toByte() }).size)
    }

    @Test
    fun ethereumAddressHasValidEip55Checksum() {
        assertTrue(Crypto.eip55Valid(AutoVybzInfo.DONATE_ETH))
        // EIP-55 reference vector.
        assertTrue(Crypto.eip55Valid("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"))
        val flipped = AutoVybzInfo.DONATE_ETH.replaceFirst("BC3f", "bC3f")
        assertFalse(Crypto.eip55Valid(flipped))
    }

    @Test
    fun readmeAndWebsiteShowTheSameAddresses() {
        val files = listOf("README.md", "site/build.py").map { ProjectFiles.root(it) }
        for (f in files) {
            val text = f.readText()
            for (a in listOf(AutoVybzInfo.DONATE_SOL, AutoVybzInfo.DONATE_BTC, AutoVybzInfo.DONATE_ETH, AutoVybzInfo.CONTACT_EMAIL))
                assertTrue("${f.name} lacks $a", a in text)
        }
        // No stale / truncated copies of an address anywhere in the app sources.
        val code = ProjectFiles.autoVybzSources().filter { it.name != "AutoVybzInfo.kt" }.joinToString { it.readText() }
        for (a in listOf(AutoVybzInfo.DONATE_SOL, AutoVybzInfo.DONATE_BTC, AutoVybzInfo.DONATE_ETH))
            assertFalse("address literal duplicated outside AutoVybzInfo", a.substring(0, 12) in code)
    }
}

/** Minimal, dependency-free reference implementations used only by the audit. */
internal object Crypto {
    private const val BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((b ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun split(addr: String): Pair<String, IntArray>? {
        if (addr.lowercase() != addr && addr.uppercase() != addr) return null
        val a = addr.lowercase(); val pos = a.lastIndexOf('1')
        if (pos < 1 || pos + 7 > a.length) return null
        val data = a.substring(pos + 1).map { BECH32.indexOf(it) }
        if (data.any { it < 0 }) return null
        return a.substring(0, pos) to data.toIntArray()
    }

    fun bech32Valid(addr: String): Boolean {
        val (hrp, data) = split(addr) ?: return false
        val exp = hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }
        return polymod((exp + data.toList()).toIntArray()) == 1 // bech32 (witness v0)
    }

    fun segwitProgram(addr: String): ByteArray {
        val data = split(addr)!!.second.let { it.copyOfRange(1, it.size - 6) } // drop version + checksum
        var acc = 0; var bits = 0; val out = ArrayList<Byte>()
        for (v in data) { acc = (acc shl 5) or v; bits += 5; while (bits >= 8) { bits -= 8; out += ((acc ushr bits) and 0xFF).toByte() } }
        return out.toByteArray()
    }

    private const val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun base58Decode(s: String): ByteArray {
        var n = BigInteger.ZERO
        for (c in s) { val d = B58.indexOf(c); require(d >= 0) { "invalid base58 '$c'" }; n = n * BigInteger.valueOf(58) + BigInteger.valueOf(d.toLong()) }
        val body = n.toByteArray().dropWhile { it == 0.toByte() }
        return ByteArray(s.takeWhile { it == '1' }.length) + body.toByteArray()
    }

    private val RC = listOf(
        "0000000000000001", "0000000000008082", "800000000000808A", "8000000080008000", "000000000000808B", "0000000080000001",
        "8000000080008081", "8000000000008009", "000000000000008A", "0000000000000088", "0000000080008009", "000000008000000A",
        "000000008000808B", "800000000000008B", "8000000000008089", "8000000000008003", "8000000000008002", "8000000000000080",
        "000000000000800A", "800000008000000A", "8000000080008081", "8000000000008080", "0000000080000001", "8000000080008008",
    ).map { it.toULong(16).toLong() }
    private val ROT = intArrayOf(1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44)
    private val PI = intArrayOf(10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1)

    private fun keccakF(st: LongArray) {
        val bc = LongArray(5)
        for (round in 0 until 24) {
            for (i in 0 until 5) bc[i] = st[i] xor st[i + 5] xor st[i + 10] xor st[i + 15] xor st[i + 20]
            for (i in 0 until 5) {
                val t = bc[(i + 4) % 5] xor java.lang.Long.rotateLeft(bc[(i + 1) % 5], 1)
                for (j in 0 until 25 step 5) st[j + i] = st[j + i] xor t
            }
            var t = st[1]
            for (i in 0 until 24) { val j = PI[i]; val tmp = st[j]; st[j] = java.lang.Long.rotateLeft(t, ROT[i]); t = tmp }
            for (j in 0 until 25 step 5) {
                for (i in 0 until 5) bc[i] = st[j + i]
                for (i in 0 until 5) st[j + i] = st[j + i] xor (bc[(i + 1) % 5].inv() and bc[(i + 2) % 5])
            }
            st[0] = st[0] xor RC[round]
        }
    }

    /** Original Keccak-256 (Ethereum), i.e. padding 0x01, not FIPS-202 SHA3-256. */
    fun keccak256(msg: ByteArray): ByteArray {
        val rate = 136
        val padded = ByteArray(((msg.size / rate) + 1) * rate)
        msg.copyInto(padded); padded[msg.size] = 0x01
        padded[padded.size - 1] = (padded[padded.size - 1].toInt() or 0x80).toByte()
        val st = LongArray(25)
        for (off in padded.indices step rate) {
            for (i in 0 until rate / 8) {
                var lane = 0L
                for (b in 0 until 8) lane = lane or ((padded[off + i * 8 + b].toLong() and 0xFF) shl (8 * b))
                st[i] = st[i] xor lane
            }
            keccakF(st)
        }
        return ByteArray(32) { ((st[it / 8] ushr (8 * (it % 8))) and 0xFF).toByte() }
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun eip55Valid(addr: String): Boolean {
        if (!Regex("^0x[0-9a-fA-F]{40}$").matches(addr)) return false
        val body = addr.substring(2)
        val hash = hex(keccak256(body.lowercase().toByteArray()))
        return body.indices.all { i ->
            val c = body[i]
            if (c.isDigit()) true else (hash[i].digitToInt(16) >= 8) == c.isUpperCase()
        }
    }
}
