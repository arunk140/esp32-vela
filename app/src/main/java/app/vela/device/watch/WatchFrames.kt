package app.vela.device.watch

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The CMF Watch Pro 2's frames, as the patched firmware's app channel uses them (cmf-watch-sdk
 * `docs/abi-1.1.md` "Data channel" and `docs/abi-1.4.md` "Writing a host"; the Python host's
 * `wp2apps/link.py` is the reference implementation).
 *
 * A frame is `F5 ‖ len:u16be ‖ c1:u16be ‖ 00 01 ‖ 00 01 ‖ c2:u16be ‖ body`, with
 * `body = AES-128-CBC(K, IV, PKCS7(plain ‖ crc32le(plain)))`. K is the watch's pairing secret
 * (`AT GETSECRET`), so no bind and no "Paired" popup: Nothing X keeps its own session.
 */
object WatchFrames {
    const val SYSTEM = 0xFFFF
    const val MSG = 0x90A5
    const val PING = 0x90A7
    const val EVENT = 0xB0A6
    private const val HEAD = 0xF5
    const val HEADER = 11
    /** Watch → phone frames in the clear that declare a length with the CRC but omit it. */
    private val PLAIN_REPLIES = setOf(0x0048)
    private val IV = hex("5051525354555657606162636465665A")

    fun reply(c2: Int) = c2 or 0xA000

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun crc(b: ByteArray): ByteArray {
        val v = CRC32().apply { update(b) }.value
        return byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    }

    private fun cipher(mode: Int, key: ByteArray) =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(IV)) }

    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE, key).doFinal(plain + crc(plain))

    /** The plaintext, or null when [body] was not sealed with [key] (another host's frame on the shared link). */
    fun decrypt(key: ByteArray, body: ByteArray): ByteArray? {
        if (body.isEmpty() || body.size % 16 != 0) return null
        val p = runCatching { cipher(Cipher.DECRYPT_MODE, key).doFinal(body) }.getOrNull() ?: return null
        if (p.size < 4) return null
        val plain = p.copyOfRange(0, p.size - 4)
        return if (crc(plain).contentEquals(p.copyOfRange(p.size - 4, p.size))) plain else null
    }

    fun frame(c2: Int, plain: ByteArray, key: ByteArray): ByteArray {
        val body = encrypt(key, plain)
        val out = ByteArrayOutputStream(HEADER + body.size)
        fun u16(v: Int) {
            out.write(v shr 8 and 0xFF)
            out.write(v and 0xFF)
        }
        out.write(HEAD)
        u16(body.size)
        u16(SYSTEM)
        u16(1)
        u16(1)
        u16(c2)
        out.write(body)
        return out.toByteArray()
    }

    class Frame(val c1: Int, val c2: Int, val body: ByteArray)

    /** Reassembles frames that span notifications. */
    class Parser {
        private var buf = ByteArray(0)

        fun feed(data: ByteArray): List<Frame> {
            buf += data
            val out = ArrayList<Frame>()
            while (true) {
                val start = buf.indexOfFirst { it.toInt() and 0xFF == HEAD }
                if (start < 0) {
                    buf = ByteArray(0)
                    break
                }
                buf = buf.copyOfRange(start, buf.size)
                if (buf.size < HEADER) break
                fun u16(i: Int) = (buf[i].toInt() and 0xFF) shl 8 or (buf[i + 1].toInt() and 0xFF)
                val length = u16(1)
                val c1 = u16(3)
                val c2 = u16(9)
                val size = if (c1 == SYSTEM && c2 in PLAIN_REPLIES) length - 4 else length
                if (buf.size < HEADER + size) break
                out += Frame(c1, c2, buf.copyOfRange(HEADER, HEADER + size))
                buf = buf.copyOfRange(HEADER + size, buf.size)
            }
            return out
        }
    }
}
