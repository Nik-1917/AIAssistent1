package com.example.aiassistent1.data.provider

import com.example.aiassistent1.domain.model.MetricKwsConfig
import com.example.aiassistent1.domain.model.MetricKwsProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Bounded binary format. SHA-256 detects damage; it is not an authentication mechanism. */
object MetricKwsProfileCodec {
    const val MAX_BYTES = 20_000
    private const val MAGIC = 0x4D4B5731
    private const val VERSION = 1

    fun encode(profile: MetricKwsProfile): ByteArray {
        profile.validate()
        val payload = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                val c = profile.config
                out.writeUTF(c.modelVersion)
                out.writeUTF(c.modelSha256)
                out.writeUTF(c.featureVersion)
                out.writeInt(c.sampleRate)
                out.writeInt(c.embeddingSize)
                out.writeInt(c.minSamples)
                out.writeInt(c.maxSamples)
                out.writeFloat(c.keywordThreshold)
                out.writeLong(profile.voiceRevision)
                out.writeLong(profile.createdAt)
                profile.embedding.forEach(out::writeFloat)
            }
        }.toByteArray()
        return (payload + MessageDigest.getInstance("SHA-256").digest(payload)).also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): MetricKwsProfile? {
        if (bytes.size !in 64..MAX_BYTES) return null
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), bytes.takeLast(32).toByteArray())) return null
        return try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                val model = input.readUTF()
                val hash = input.readUTF()
                val feature = input.readUTF()
                val rate = input.readInt()
                val size = input.readInt()
                val min = input.readInt()
                val max = input.readInt()
                val threshold = input.readFloat()
                val config = MetricKwsConfig(model, hash, feature, size, threshold, rate, min, max)
                val profile = MetricKwsProfile(config, input.readLong(), input.readLong(), FloatArray(size) { input.readFloat() })
                if (input.read() != -1) return null
                profile.apply { validate() }
            }
        } catch (_: java.io.IOException) { null }
        catch (_: IllegalArgumentException) { null }
    }
}
