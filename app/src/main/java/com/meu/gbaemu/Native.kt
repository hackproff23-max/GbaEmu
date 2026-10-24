package com.meu.gbaemu

import java.nio.ByteBuffer

object Native {
    init { System.loadLibrary("gbabridge") }
    external fun load(core: String, rom: ByteArray, name: String, sysDir: String): Boolean
    external fun run()
    external fun reset()
    external fun setInput(mask: Int)
    external fun frameBuffer(): ByteBuffer
    external fun frameSize(): IntArray
    external fun audio(out: ShortArray): Int
    external fun clearAudio()
    external fun sampleRate(): Double
    external fun fps(): Double
    external fun readSram(): ByteArray?
    external fun writeSram(d: ByteArray)
    external fun saveState(): ByteArray?
    external fun loadState(d: ByteArray): Boolean
    external fun cheatReset()
    external fun cheatSet(index: Int, enabled: Boolean, code: String)
    external fun unload()
}
