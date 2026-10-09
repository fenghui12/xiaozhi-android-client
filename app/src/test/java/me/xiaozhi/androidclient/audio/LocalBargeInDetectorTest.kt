package me.xiaozhi.androidclient.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBargeInDetectorTest {
    private val speech = ShortArray(960) { if (it % 2 == 0) 8_000 else -8_000 }
    private val quiet = ShortArray(960) { 200 }

    @Test
    fun `actual quiet TTS capture does not produce an interruption`() {
        val detector = LocalBargeInDetector()
        val energy = requireNotNull(javaClass.getResourceAsStream("/yundea_quiet_rms_20261009.txt"))
            .bufferedReader().use { it.readLines() }
        assertTrue(energy.size > 500)
        warmup(detector)
        energy.forEach { rms ->
            assertFalse(detector.process(ShortArray(960) { rms.toInt().toShort() }, 1, true))
        }
    }

    private fun warmup(detector: LocalBargeInDetector, epoch: Int = 1) {
        repeat(10) { assertFalse(detector.process(quiet, epoch, true)) }
    }

    @Test
    fun `only the measured USB device with matching nonempty addresses is eligible`() {
        val name = "USB-Audio - Yundea 1076"
        assertTrue(UsbEchoCancellationPolicy.canInterrupt(name, "card=2;device=0;", name, "card=2;device=0;"))
        assertFalse(UsbEchoCancellationPolicy.canInterrupt(name, "card=2;device=0;", name, "card=3;device=0;"))
        assertFalse(UsbEchoCancellationPolicy.canInterrupt(name, "", name, ""))
        assertFalse(UsbEchoCancellationPolicy.canInterrupt("USB-Audio - DECXIN", "card=2;", name, "card=2;"))
        assertFalse(UsbEchoCancellationPolicy.canInterrupt(name, "card=2;", "SSRK3568", "card=2;"))
    }

    @Test
    fun `playback startup is ignored and sustained speech triggers once`() {
        val detector = LocalBargeInDetector()
        repeat(10) { assertFalse(detector.process(speech, 1, true)) }
        repeat(4) { assertFalse(detector.process(speech, 1, true)) }
        assertTrue(detector.process(speech, 1, true))
        repeat(10) { assertFalse(detector.process(speech, 1, true)) }
    }

    @Test
    fun `a single loud impulse and quiet residual echo do not interrupt`() {
        val detector = LocalBargeInDetector()
        warmup(detector)
        repeat(20) {
            assertFalse(detector.process(speech, 1, true))
            assertFalse(detector.process(quiet, 1, true))
        }
    }

    @Test
    fun `speech must be consecutive`() {
        val detector = LocalBargeInDetector()
        warmup(detector)
        repeat(2) { assertFalse(detector.process(speech, 1, true)) }
        assertFalse(detector.process(quiet, 1, true))
        repeat(4) { assertFalse(detector.process(speech, 1, true)) }
        assertTrue(detector.process(speech, 1, true))
    }

    @Test
    fun `a new playback cannot inherit partial speech from the previous turn`() {
        val detector = LocalBargeInDetector()
        warmup(detector)
        repeat(2) { assertFalse(detector.process(speech, 1, true)) }
        repeat(14) { assertFalse(detector.process(speech, 2, true)) }
        assertTrue(detector.process(speech, 2, true))
    }

    @Test
    fun `route loss disables interruption and requires warmup after recovery`() {
        val detector = LocalBargeInDetector()
        warmup(detector)
        repeat(2) { assertFalse(detector.process(speech, 1, true)) }
        repeat(10) { assertFalse(detector.process(speech, 1, false)) }
        repeat(14) { assertFalse(detector.process(speech, 1, true)) }
        assertTrue(detector.process(speech, 1, true))
    }

    @Test
    fun `loud playback raises the required energy above residual echo`() {
        val detector = LocalBargeInDetector()
        warmup(detector)
        repeat(20) { assertFalse(detector.process(ShortArray(960) { 4_000 }, 1, true, 10_000.0)) }
        repeat(4) { assertFalse(detector.process(speech, 1, true, 10_000.0)) }
        assertTrue(detector.process(speech, 1, true, 10_000.0))
    }
}
