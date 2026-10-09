package me.xiaozhi.androidclient.audio

import kotlin.math.sqrt
import kotlin.math.max

object UsbEchoCancellationPolicy {
    fun isSupportedDevice(name: String): Boolean =
        name.trim().equals("USB-Audio - Yundea 1076", ignoreCase = true)

    fun canInterrupt(inputName: String, inputAddress: String, outputName: String, outputAddress: String): Boolean =
        isSupportedDevice(inputName) && isSupportedDevice(outputName) &&
            inputAddress.isNotBlank() && inputAddress == outputAddress
}

class LocalBargeInDetector {
    private var epoch: Int? = null
    private var warmupFrames = 0
    private var speechFrames = 0
    private var triggered = false

    fun process(frame: ShortArray, playbackEpoch: Int, enabled: Boolean, referenceRms: Double = 0.0): Boolean {
        if (!enabled || epoch != playbackEpoch) {
            epoch = playbackEpoch
            warmupFrames = 0
            speechFrames = 0
            triggered = false
        }
        if (!enabled || triggered || frame.isEmpty()) return false
        if (warmupFrames < 10) {
            warmupFrames += 1
            return false
        }
        val rms = sqrt(frame.sumOf { it.toDouble() * it.toDouble() } / frame.size)
        speechFrames = if (rms >= max(2_500.0, referenceRms * 0.6)) speechFrames + 1 else 0
        if (speechFrames < 5) return false
        triggered = true
        return true
    }
}
