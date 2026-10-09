package me.xiaozhi.androidclient.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioHardwareProfileTest {
    private val integrated = AudioEndpoint("USB-Audio - Yundea 1076", "card=2;device=0;", true, UsbAudioIdentity(0x4759, 0x3931))
    private val camera = AudioEndpoint("USB-Audio - DECXIN", "card=3;device=0;", true, UsbAudioIdentity(0x1234, 0x5678))
    private val speaker = AudioEndpoint("SSRK3568-HMI.6.1.75", "", false)

    @Test
    fun `verified integrated device selects local interruption`() {
        assertEquals(AudioHardwareProfile.YUNDEA_INTEGRATED, AudioHardwareProfilePolicy.resolve(integrated, integrated))
    }

    @Test
    fun `old camera microphone and board speaker retain legacy behavior`() {
        assertEquals(AudioHardwareProfile.LEGACY, AudioHardwareProfilePolicy.resolve(camera, speaker))
        assertNull(AudioHardwareProfilePolicy.pairedOutput(camera, listOf(speaker)))
    }

    @Test
    fun `unknown or partial identity is never enough to enable interruption`() {
        assertEquals(AudioHardwareProfile.LEGACY, AudioHardwareProfilePolicy.resolve(integrated.copy(identity = null), integrated))
        val other = integrated.copy(identity = UsbAudioIdentity(0x4759, 0x9999))
        assertEquals(AudioHardwareProfile.LEGACY, AudioHardwareProfilePolicy.resolve(other, other))
        assertEquals(AudioHardwareProfile.LEGACY, AudioHardwareProfilePolicy.resolve(integrated.copy(usb = false), integrated))
        assertEquals(AudioHardwareProfile.LEGACY, AudioHardwareProfilePolicy.resolve(integrated, integrated.copy(usb = false)))
    }

    @Test
    fun `two devices of the same model must not be treated as one card`() {
        assertEquals(AudioHardwareProfile.LEGACY, AudioHardwareProfilePolicy.resolve(integrated, integrated.copy(address = "card=3;device=0;")))
        assertEquals(AudioHardwareProfile.LEGACY, AudioHardwareProfilePolicy.resolve(integrated.copy(address = ""), integrated.copy(address = "")))
    }

    @Test
    fun `unplugging either endpoint returns to legacy and reconnecting restores integrated mode`() {
        assertEquals(AudioHardwareProfile.LEGACY, AudioHardwareProfilePolicy.resolve(integrated, null))
        assertEquals(AudioHardwareProfile.LEGACY, AudioHardwareProfilePolicy.resolve(null, integrated))
        assertEquals(AudioHardwareProfile.LEGACY, AudioHardwareProfilePolicy.resolve(null, null))
        assertEquals(AudioHardwareProfile.YUNDEA_INTEGRATED, AudioHardwareProfilePolicy.resolve(integrated, integrated))
    }

    @Test
    fun `camera and integrated microphone cannot borrow each others playback capability`() {
        assertEquals(AudioHardwareProfile.LEGACY, AudioHardwareProfilePolicy.resolve(camera, integrated))
        assertEquals(AudioHardwareProfile.LEGACY, AudioHardwareProfilePolicy.resolve(integrated, speaker))
        assertEquals(integrated, AudioHardwareProfilePolicy.pairedOutput(integrated, listOf(speaker, camera, integrated)))
    }

    @Test
    fun `card numbers are discovered rather than hard coded`() {
        val moved = integrated.copy(address = "card=5;device=0;")
        assertEquals(AudioHardwareProfile.YUNDEA_INTEGRATED, AudioHardwareProfilePolicy.resolve(moved, moved))
    }
}
