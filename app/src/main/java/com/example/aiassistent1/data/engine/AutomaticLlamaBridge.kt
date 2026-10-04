package com.example.aiassistent1.data.engine

/** Text uses UTF-8 bytes so emoji never cross JNI's Modified UTF-8 interface. */
internal object AutomaticLlamaBridge {
    init { System.loadLibrary("assistant_llama") }
    interface BytesCallback { fun onBytes(bytes: ByteArray) }
    external fun begin()
    external fun prepare(path: ByteArray)
    external fun count(prompt: ByteArray): Int
    external fun load(path: ByteArray, context: Int, batch: Int, threads: Int, gpu: Int): Int
    external fun resize(context: Int, batch: Int, threads: Int): Int
    external fun generate(prompt: ByteArray, continuation: Boolean, temperature: Float,
        topP: Float, topK: Int, penalty: Float, callback: BytesCallback): LongArray
    external fun cancel()
    external fun shutdown()
}
