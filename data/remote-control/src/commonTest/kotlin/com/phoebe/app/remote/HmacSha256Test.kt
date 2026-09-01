package com.phoebe.app.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HmacSha256Test {

    @Test
    fun testRfc4231TestCase1() {
        val key = ByteArray(20) { 0x0b }
        val data = "Hi There".encodeToByteArray()
        val expected = "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
        val actual = HmacSha256.hmacHex(key, data)
        assertEquals(expected, actual)
    }

    @Test
    fun testRfc4231TestCase2() {
        val key = "Jefe".encodeToByteArray()
        val data = "what do ya want for nothing?".encodeToByteArray()
        val expected = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        val actual = HmacSha256.hmacHex(key, data)
        assertEquals(expected, actual)
    }

    @Test
    fun testRfc4231TestCase3() {
        val key = ByteArray(20) { 0xaa.toByte() }
        val data = ByteArray(50) { 0xdd.toByte() }
        val expected = "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe"
        val actual = HmacSha256.hmacHex(key, data)
        assertEquals(expected, actual)
    }

    @Test
    fun testRfc4231TestCase4() {
        val key = "0102030405060708090a0b0c0d0e0f10111213141516171819".decodeHex()
        val data = ByteArray(50) { 0xcd.toByte() }
        val expected = "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b"
        val actual = HmacSha256.hmacHex(key, data)
        assertEquals(expected, actual)
    }

    @Test
    fun testRfc4231TestCase5() {
        val key = ByteArray(20) { 0x0c }
        val data = "Test With Truncation".encodeToByteArray()
        val expectedFull = "a3b6167473100ee06e0c796c2955552bfa6f7c0a6a8aef8b93f860aab0cd20c5"
        val actual = HmacSha256.hmacHex(key, data)
        assertEquals(expectedFull, actual)
        // Truncated 128-bit prefix as in RFC 4231 Test 5
        val expectedTruncated = "a3b6167473100ee06e0c796c2955552b"
        assertTrue(actual.startsWith(expectedTruncated))
    }

    @Test
    fun testRfc4231TestCase6() {
        val key = ByteArray(131) { 0xaa.toByte() }
        val data = "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray()
        val expected = "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"
        val actual = HmacSha256.hmacHex(key, data)
        assertEquals(expected, actual)
    }

    @Test
    fun testRfc4231TestCase7() {
        val key = ByteArray(131) { 0xaa.toByte() }
        val data = "This is a test using a larger than block-size key and a larger than block-size data. The key needs to be hashed before being used by the HMAC algorithm.".encodeToByteArray()
        val expected = "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2"
        val actual = HmacSha256.hmacHex(key, data)
        assertEquals(expected, actual)
    }
}
