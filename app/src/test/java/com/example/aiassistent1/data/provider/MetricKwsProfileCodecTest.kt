package com.example.aiassistent1.data.provider

import com.example.aiassistent1.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

class MetricKwsProfileCodecTest {
    private val profile = MetricKwsProfile(MetricKwsConfig("model1", "a".repeat(64), "features1", 2, .8f),
        7, 1000, floatArrayOf(.6f, .8f))

    private fun resign(bytes: ByteArray): ByteArray {
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    @Test fun roundTripAllMetadataAndEmbedding() {
        val read = MetricKwsProfileCodec.decode(MetricKwsProfileCodec.encode(profile))!!
        assertEquals(profile.config, read.config)
        assertEquals(profile.profileRevision, read.profileRevision)
        assertEquals(profile.createdAt, read.createdAt)
        assertArrayEquals(profile.embedding, read.embedding, 0f)
    }

    @Test fun everyTruncationAndSingleByteCorruptionRejected() {
        val bytes = MetricKwsProfileCodec.encode(profile)
        for (i in bytes.indices) {
            assertNull(MetricKwsProfileCodec.decode(bytes.copyOf(i)))
            val corrupt = bytes.copyOf().apply { this[i] = (this[i].toInt() xor 1).toByte() }
            assertNull(MetricKwsProfileCodec.decode(corrupt))
        }
    }

    @Test fun rejectsUnknownMagicAndVersionEvenWithValidChecksum() {
        for (offset in listOf(0, 4)) {
            val bytes = MetricKwsProfileCodec.encode(profile)
            ByteBuffer.wrap(bytes).putInt(offset, 42)
            assertNull(MetricKwsProfileCodec.decode(resign(bytes)))
        }
    }

    @Test fun rejectsTrailingGarbageAndOversizedFiles() {
        val bytes = MetricKwsProfileCodec.encode(profile)
        assertNull(MetricKwsProfileCodec.decode(bytes + byteArrayOf(1)))
        val payload = bytes.copyOfRange(0, bytes.size - 32) + byteArrayOf(1)
        assertNull(MetricKwsProfileCodec.decode(payload + MessageDigest.getInstance("SHA-256").digest(payload)))
        assertNull(MetricKwsProfileCodec.decode(ByteArray(MetricKwsProfileCodec.MAX_BYTES + 1)))
    }

    @Test fun refusesInvalidProfilesBeforeWriting() {
        for (value in listOf(floatArrayOf(0f, 0f), floatArrayOf(Float.NaN, 1f), floatArrayOf(1f), floatArrayOf(4f, 3f))) {
            assertTrue(runCatching { MetricKwsProfileCodec.encode(profile.copy(embedding = value)) }.isFailure)
        }
        assertTrue(runCatching { MetricKwsProfileCodec.encode(profile.copy(profileRevision = -1)) }.isFailure)
    }

    @Test fun legacySpeakerRevisionIsDiscardedWithoutChangingModelOrEmbedding() {
        val legacy = MetricKwsProfileCodec.encode(profile)
        ByteBuffer.wrap(legacy).putInt(4, 1)
        val migrated = MetricKwsProfileCodec.decode(resign(legacy))!!
        assertEquals(0L, migrated.profileRevision)
        assertEquals(profile.config, migrated.config)
        assertEquals(profile.createdAt, migrated.createdAt)
        assertArrayEquals(profile.embedding, migrated.embedding, 0f)
        val saved = MetricKwsProfileCodec.encode(migrated)
        assertEquals(2, ByteBuffer.wrap(saved).getInt(4))
        assertArrayEquals(profile.embedding, MetricKwsProfileCodec.decode(saved)!!.embedding, 0f)
    }

    @Test fun rejectsNonFinitePayloadEvenWithValidChecksum() {
        val bytes = MetricKwsProfileCodec.encode(profile)
        ByteBuffer.wrap(bytes).putFloat(bytes.size - 32 - 4, Float.NaN)
        assertNull(MetricKwsProfileCodec.decode(resign(bytes)))
    }
}
