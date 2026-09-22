package com.example.aiassistent1.data.provider

internal object NativeAudio {
    init { System.loadLibrary("assistant_audio") }
    external fun createEncoder(rate: Int, bitrate: Int): Long
    external fun encode(handle: Long, pcm: ShortArray?, count: Int, flush: Boolean): ByteArray
    external fun closeEncoder(handle: Long)
    external fun createProcessor(echo: Boolean): Long
    external fun processFrame(handle: Long, data: FloatArray, reverse: Boolean, delay: Int)
    external fun closeProcessor(handle: Long)
}
