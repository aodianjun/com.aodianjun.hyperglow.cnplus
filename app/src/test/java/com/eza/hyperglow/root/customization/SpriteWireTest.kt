package com.eza.hyperglow.root.customization

import android.os.Bundle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 贴图跨进程通道:Bundle 挂 fd → 接收侧限长读取 + sha256 校验 + 关闭 fd。
 * 用真实临时文件与 Robolectric 的 Bundle/ParcelFileDescriptor 走完整往返。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpriteWireTest {

    private fun tempFile(bytes: ByteArray, name: String = "sprite.png"): File {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "sprite-wire-test")
        assertTrue(dir.isDirectory || dir.mkdirs())
        return File(dir, name).apply { writeBytes(bytes) }
    }

    @Test
    fun signatureFiltersInvalidAttachments() {
        val good = SpriteWire.Attachment(64, "a".repeat(64), tempFile(byteArrayOf(1)))
        val badId = SpriteWire.Attachment(63, "a".repeat(64), tempFile(byteArrayOf(2), "a.png"))
        val badHash = SpriteWire.Attachment(65, "short", tempFile(byteArrayOf(3), "b.png"))
        val missing = SpriteWire.Attachment(66, "a".repeat(64), File("Z:\\definitely\\missing.png"))
        assertEquals("64:${"a".repeat(64)}", SpriteWire.signature(listOf(good, badId, badHash, missing)))
        assertEquals("", SpriteWire.signature(emptyList()))
    }

    @Test
    fun attachThenReadRoundTripsBytes() {
        val first = byteArrayOf(1, 2, 3, 4)
        val second = byteArrayOf(5, 6, 7)
        val bundle = Bundle()
        val attachments = listOf(
            SpriteWire.Attachment(64, SpriteWire.sha256(first), tempFile(first, "one.png")),
            SpriteWire.Attachment(80, SpriteWire.sha256(second), tempFile(second, "two.png"))
        )
        val descriptors = SpriteWire.attach(bundle, attachments)
        assertEquals(2, descriptors.size)
        assertEquals(2, bundle.getIntArray(SpriteWire.KEY_IDS)?.size)
        try {
            val received = SpriteWire.read(bundle)
            assertEquals(listOf(64, 80), received.map { it.id })
            assertArrayEquals(first, received[0].bytes)
            assertArrayEquals(second, received[1].bytes)
        } finally {
            descriptors.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun readDropsHashMismatch() {
        val bytes = byteArrayOf(9, 9, 9)
        val bundle = Bundle()
        val descriptors = SpriteWire.attach(
            bundle,
            listOf(SpriteWire.Attachment(70, "0".repeat(64), tempFile(bytes)))
        )
        try {
            assertTrue(SpriteWire.read(bundle).isEmpty())
        } finally {
            descriptors.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun readWithoutKeysReturnsEmpty() {
        assertTrue(SpriteWire.read(Bundle()).isEmpty())
    }

    @Test
    fun attachWithNoValidAttachmentsWritesNothing() {
        val bundle = Bundle()
        val descriptors = SpriteWire.attach(
            bundle,
            listOf(SpriteWire.Attachment(0, "a".repeat(64), tempFile(byteArrayOf(1))))
        )
        assertTrue(descriptors.isEmpty())
        assertTrue(!bundle.containsKey(SpriteWire.KEY_IDS))
    }

    @Test
    fun readSkipsOversizedAndKeepsValid() {
        val big = ByteArray(SpriteWire.MAX_BYTES_PER_SPRITE + 1) { 7 }
        val small = byteArrayOf(4, 2)
        val bundle = Bundle()
        val descriptors = SpriteWire.attach(
            bundle,
            listOf(
                SpriteWire.Attachment(90, SpriteWire.sha256(big), tempFile(big, "big.png")),
                SpriteWire.Attachment(91, SpriteWire.sha256(small), tempFile(small, "small.png"))
            )
        )
        try {
            val received = SpriteWire.read(bundle)
            assertEquals(listOf(91), received.map { it.id })
            assertArrayEquals(small, received.single().bytes)
        } finally {
            descriptors.forEach { runCatching { it.close() } }
        }
    }
}
