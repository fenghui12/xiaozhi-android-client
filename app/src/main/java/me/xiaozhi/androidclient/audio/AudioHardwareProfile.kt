package me.xiaozhi.androidclient.audio

enum class AudioHardwareProfile(val label: String, val supportsLocalBargeIn: Boolean) {
    LEGACY("标准语音", false),
    YUNDEA_INTEGRATED("实时打断", true),
}

data class AudioEndpoint(
    val name: String,
    val address: String,
    val usb: Boolean,
    val identity: UsbAudioIdentity? = null,
)

data class UsbAudioIdentity(val vendorId: Int, val productId: Int) {
    val supported: Boolean
        get() = vendorId == 0x4759 && productId == 0x3931
}

object AudioHardwareProfilePolicy {
    fun resolve(input: AudioEndpoint?, output: AudioEndpoint?): AudioHardwareProfile {
        if (input == null || output == null || !input.usb || !output.usb ||
            input.identity?.supported != true || input.identity != output.identity
        ) return AudioHardwareProfile.LEGACY
        return if (UsbEchoCancellationPolicy.canInterrupt(input.name, input.address, output.name, output.address)) {
            AudioHardwareProfile.YUNDEA_INTEGRATED
        } else {
            AudioHardwareProfile.LEGACY
        }
    }

    fun pairedOutput(input: AudioEndpoint?, outputs: List<AudioEndpoint>): AudioEndpoint? =
        outputs.firstOrNull { resolve(input, it) == AudioHardwareProfile.YUNDEA_INTEGRATED }
}
