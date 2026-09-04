package me.xiaozhi.androidclient.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class SherpaKeywordCompilerTest {
    @Test
    fun `compiles arbitrary Chinese wake words to Sherpa tokens`() {
        assertEquals("w ǒ sh ì x iǎo zh ì @我是小智", SherpaKeywordCompiler.compile("我是小智"))
        assertEquals("n ǐ h ǎo p èi q í @你好佩奇", SherpaKeywordCompiler.compile("你好佩奇"))
    }

    @Test
    fun `compiles and separates multiple configured wake words`() {
        assertEquals(
            "w ǒ sh ì x iǎo zh ì @我是小智/n ǐ h ǎo p èi q í @你好佩奇",
            SherpaKeywordCompiler.compileList("我是小智，你好佩奇"),
        )
    }
}
