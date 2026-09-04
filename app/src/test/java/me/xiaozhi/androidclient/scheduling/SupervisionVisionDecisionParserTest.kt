package me.xiaozhi.androidclient.scheduling

import org.junit.Assert.assertEquals
import org.junit.Test

class SupervisionVisionDecisionParserTest {
    @Test
    fun `parses completed status from json`() {
        assertStatus(SupervisionVisionStatus.COMPLETED, "{\"status\":\"COMPLETED\"}")
    }

    @Test
    fun `does not confuse not completed with completed`() {
        assertStatus(SupervisionVisionStatus.NOT_COMPLETED, "STATUS: NOT_COMPLETED")
    }

    @Test
    fun `parses uncertain status`() {
        assertStatus(SupervisionVisionStatus.UNCERTAIN, "STATUS: UNCERTAIN")
    }

    @Test
    fun `natural language success claim is treated as uncertain`() {
        assertStatus(SupervisionVisionStatus.UNCERTAIN, "看起来已经喝水了")
    }

    @Test
    fun `empty response is uncertain`() {
        assertStatus(SupervisionVisionStatus.UNCERTAIN, "")
    }

    private fun assertStatus(expected: SupervisionVisionStatus, response: String) {
        assertEquals(expected, SupervisionVisionDecisionParser.parse(response).status)
    }
}
