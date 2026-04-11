package me.xiaozhi.androidclient.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class PcmWavWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataLength: Long = 0

    init {
        raf.setLength(0)
        writeHeader(dataSize = 0)
    }

    fun write(samples: ShortArray, count: Int = samples.size) {
        if (count <= 0) {
            return
        }
        val byteBuffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until count) {
            byteBuffer.putShort(samples[index])
        }
        raf.seek(44 + dataLength)
        raf.write(byteBuffer.array())
        dataLength += byteBuffer.position()
    }

    fun close() {
        writeHeader(dataSize = dataLength)
        raf.close()
    }

    private fun writeHeader(dataSize: Long) {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt((36 + dataSize).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize.toInt())
        }
        raf.seek(0)
        raf.write(header.array())
    }
}
