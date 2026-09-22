package com.example.aiassistent1.data.provider

/** One encoder per recording; caller owns flush/close, including exceptional paths. */
class Mp3Encoder(sampleRate: Int = 16_000, bitrate: Int = 64, channels: Int = 1) : AutoCloseable {
    private var handle: Long
    private var finished = false
    init {
        require(channels == 1 && sampleRate == 16_000 && bitrate in 8..160)
        handle = NativeAudio.createEncoder(sampleRate, bitrate)
        check(handle != 0L)
    }
    fun encode(pcm: ShortArray, count: Int = pcm.size): ByteArray {
        check(handle != 0L && !finished)
        require(count in 1..minOf(pcm.size, 65536))
        return NativeAudio.encode(handle, pcm, count, false)
    }
    fun flush(): ByteArray {
        check(handle != 0L)
        if (finished) return byteArrayOf()
        finished = true
        return NativeAudio.encode(handle, null, 0, true)
    }
    override fun close() {
        if (handle != 0L) NativeAudio.closeEncoder(handle)
        handle = 0
    }
}
