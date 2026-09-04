package me.xiaozhi.androidclient.digitalhuman

import me.xiaozhi.androidclient.model.DigitalHumanSlot
import org.junit.Assert.assertTrue
import org.junit.Test

class LanVideoUploadServerTest {

    @Test
    fun htmlContainsNecessaryUiElements() {
        val html = LanVideoUploadServer.buildUploadHtml("奥特曼", DigitalHumanSlot.IDLE.label, "fake-token-123")

        assertTrue("Should contain role name", html.contains("奥特曼"))
        assertTrue("Should contain slot label", html.contains("待机视频"))
        assertTrue("Should contain file input", html.contains("type=\"file\""))
        assertTrue("Should contain upload button", html.contains("uploadBtn"))
        assertTrue("Should contain progress bar", html.contains("progressBar"))
        assertTrue("Should contain token in POST URL", html.contains("/upload?token=fake-token-123"))
        assertTrue("Should handle XHR upload progress", html.contains("xhr.upload.onprogress"))
    }
}
