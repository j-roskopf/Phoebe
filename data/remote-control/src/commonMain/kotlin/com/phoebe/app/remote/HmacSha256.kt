package com.phoebe.app.remote

import kotlin.random.Random

object HmacSha256 {

    private val K = intArrayOf(
        0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
        0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
        0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
        0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
        0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
        0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
        0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
        0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt()
    )

    private fun Int.rotr(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))

    fun sha256(data: ByteArray): ByteArray {
        var h0 = 0x6a09e667.toInt()
        var h1 = 0xbb67ae85.toInt()
        var h2 = 0x3c6ef372.toInt()
        var h3 = 0xa54ff53a.toInt()
        var h4 = 0x510e527f.toInt()
        var h5 = 0x9b05688c.toInt()
        var h6 = 0x1f83d9ab.toInt()
        var h7 = 0x5be0cd19.toInt()

        val length = data.size
        val bitLength = length.toLong() * 8L
        val padLength = ((56 - (length + 1) % 64 + 64) % 64)
        val totalLength = length + 1 + padLength + 8
        val padded = ByteArray(totalLength)
        data.copyInto(padded, 0, 0, length)
        padded[length] = 0x80.toByte()

        for (i in 0 until 8) {
            padded[totalLength - 1 - i] = ((bitLength ushr (i * 8)) and 0xFF).toByte()
        }

        val w = IntArray(64)
        for (chunk in 0 until totalLength step 64) {
            for (i in 0 until 16) {
                val idx = chunk + i * 4
                w[i] = ((padded[idx].toInt() and 0xFF) shl 24) or
                    ((padded[idx + 1].toInt() and 0xFF) shl 16) or
                    ((padded[idx + 2].toInt() and 0xFF) shl 8) or
                    (padded[idx + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotr(7) xor w[i - 15].rotr(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotr(17) xor w[i - 2].rotr(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            var f = h5
            var g = h6
            var h = h7

            for (i in 0 until 64) {
                val s1 = e.rotr(6) xor e.rotr(11) xor e.rotr(25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + ch + K[i] + w[i]
                val s0 = a.rotr(2) xor a.rotr(13) xor a.rotr(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj

                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }

            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            h5 += f
            h6 += g
            h7 += h
        }

        val out = ByteArray(32)
        writeInt(out, 0, h0)
        writeInt(out, 4, h1)
        writeInt(out, 8, h2)
        writeInt(out, 12, h3)
        writeInt(out, 16, h4)
        writeInt(out, 20, h5)
        writeInt(out, 24, h6)
        writeInt(out, 28, h7)
        return out
    }

    fun sha256(data: String): ByteArray = sha256(data.encodeToByteArray())

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val blockSize = 64
        val preparedKey = when {
            key.size > blockSize -> sha256(key).copyOf(blockSize)
            key.size < blockSize -> key.copyOf(blockSize)
            else -> key.copyOf()
        }

        val oKeyPad = ByteArray(blockSize)
        val iKeyPad = ByteArray(blockSize)
        for (i in 0 until blockSize) {
            oKeyPad[i] = (preparedKey[i].toInt() xor 0x5C).toByte()
            iKeyPad[i] = (preparedKey[i].toInt() xor 0x36).toByte()
        }

        val inner = sha256(iKeyPad + data)
        return sha256(oKeyPad + inner)
    }

    fun hmacHex(key: ByteArray, data: ByteArray): String =
        hmac(key, data).toHex()

    fun hmacHex(keyHex: String, data: String): String =
        hmac(keyHex.decodeHex(), data.encodeToByteArray()).toHex()

    fun generateSecretHex(byteLength: Int = 32): String {
        val bytes = Random.nextBytes(byteLength)
        return bytes.toHex()
    }

    fun generateNonce(byteLength: Int = 16): String {
        val bytes = Random.nextBytes(byteLength)
        return bytes.toHex()
    }

    private fun writeInt(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value ushr 24).toByte()
        out[offset + 1] = (value ushr 16).toByte()
        out[offset + 2] = (value ushr 8).toByte()
        out[offset + 3] = value.toByte()
    }
}

fun ByteArray.toHex(): String = buildString(size * 2) {
    for (b in this@toHex) {
        val v = b.toInt() and 0xFF
        append(HEX_CHARS[v ushr 4])
        append(HEX_CHARS[v and 0x0F])
    }
}

fun String.decodeHex(): ByteArray {
    val clean = this.trim()
    check(clean.length % 2 == 0) { "Invalid hex string length: ${clean.length}" }
    val out = ByteArray(clean.length / 2)
    for (i in out.indices) {
        val h = clean[i * 2].digitToInt(16)
        val l = clean[i * 2 + 1].digitToInt(16)
        out[i] = ((h shl 4) or l).toByte()
    }
    return out
}

private const val HEX_CHARS = "0123456789abcdef"
