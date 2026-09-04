package me.xiaozhi.androidclient.audio

import java.util.Locale
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

internal object SherpaKeywordCompiler {
    private val pinyinFormat = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITH_TONE_MARK
        vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
    }
    private val initials = listOf(
        "zh", "ch", "sh",
        "b", "p", "m", "f", "d", "t", "n", "l",
        "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w",
    )

    fun compileList(wakeWords: String): String = wakeWords
        .split(',', '，', ';', '；', '\n')
        .map(String::trim)
        .filter(String::isNotBlank)
        .map(::compile)
        .distinctBy { it.lowercase(Locale.CHINA) }
        .joinToString("/")

    fun compile(wakeWord: String): String {
        val label = wakeWord.filterNot(Char::isWhitespace)
        val tokens = label.flatMap { character ->
            val syllable = PinyinHelper.toHanyuPinyinStringArray(character, pinyinFormat)
                ?.firstOrNull()
                ?.normalizeThirdToneMarks()
                ?: character.toString()
            splitSyllable(syllable)
        }
        return "${tokens.joinToString(" ")} @$label"
    }

    private fun splitSyllable(syllable: String): List<String> {
        val initial = initials.firstOrNull(syllable::startsWith) ?: return listOf(syllable)
        val final = syllable.removePrefix(initial)
        return if (final.isBlank()) listOf(initial) else listOf(initial, final)
    }

    // pinyin4j uses breve glyphs for third tone, while this Sherpa model's
    // vocabulary uses the standard caron glyphs.
    private fun String.normalizeThirdToneMarks(): String = this
        .replace('ă', 'ǎ')
        .replace('ĕ', 'ě')
        .replace('ĭ', 'ǐ')
        .replace('ŏ', 'ǒ')
        .replace('ŭ', 'ǔ')
}
