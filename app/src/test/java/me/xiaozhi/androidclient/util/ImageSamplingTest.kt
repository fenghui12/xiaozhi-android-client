package me.xiaozhi.androidclient.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ImageSampling] 的单元测试。
 *
 * 这些用例守的是一次真机事故（2026-09-19）：导入立绘时用全尺寸
 * `BitmapFactory.decodeFile` 判"是不是图片"，一张 18540×23437 的 JPEG 要分配
 * **1.74 GB**，而板子 `MemTotal` 只有 1.92 GB —— 整机换页、主线程冻 5.1 秒触发 ANR。
 *
 * ## 这份测试自己踩过的两个坑（改它之前先读）
 *
 * 1. **不能用被测实现自己的公式去验被测实现。** 第一版的 `decodedPixels` 写的是
 *    `(w / s) * (h / s)`（整除），而实现内部的预算估算**也是整除** —— 于是断言退化成
 *    「算法算出来的数 ≤ 算法允许的数」，永远成立。现在这里的 [ceilPixels] 用**向上取整**，
 *    它模拟的是 `BitmapFactory` 的真实行为（外部真值），不是实现内部那套估算。
 * 2. **用例形状必须覆盖失效分支。** 第一版探针用例只喂了 `4000×6000`、`18540×23437`、
 *    `60000×60000` —— **全是近似方形**，恰好绕开了唯一会暴露缺陷的分支
 *    （任一方向小于 2×上限时完全不采样）。现在必须同时有长条图。
 *
 * 证伪方式（复核这些断言还有没有效）：
 *   · 把 [ImageSampling.probeSampleSize] 换成复用 `sampleSizeFor(..., Long.MAX_VALUE)`
 *     → `probe never exceeds the cap on either dimension` 必须立刻变红（长条图那几行）；
 *   · 删掉 [ImageSampling.sampleSizeFor] 的第一个 while 循环
 *     → `huge image is downsampled far below the memory-danger line` 必须变红。
 */
class ImageSamplingTest {

    /**
     * 模拟 `BitmapFactory` 在给定 `inSampleSize` 下**真实**解码出的像素数。
     *
     * 关键在**向上取整**：Android 是把每个方向向上取整到采样粒度的倍数（最小 1），
     * 不是整除。用整除会低估，两个方向合计最多低估约 4 倍。
     */
    private fun ceilPixels(width: Int, height: Int, sample: Int): Long =
        (((width + sample - 1) / sample).toLong()) * (((height + sample - 1) / sample).toLong())

    /** 采样后每个方向的真实边长。 */
    private fun dims(width: Int, height: Int, sample: Int): Pair<Int, Int> =
        ((width + sample - 1) / sample) to ((height + sample - 1) / sample)

    /** 覆盖"近似方形 + 长条"两类形状——长条是探针缺陷唯一会暴露的地方。 */
    private val shapes = listOf(
        863 to 1536,
        4000 to 6000,
        5000 to 5000,
        8000 to 6000,
        18540 to 23437,
        60000 to 60000,
        // ↓ 长条：任一方向小于 2×256 时，"两方向都大才折半"的逻辑会完全失效
        20000 to 100,
        100 to 20000,
        65535 to 511,
        4000 to 100000,
        1 to 1_000_000,
        100000 to 100,
    )

    // ───────────────── 探针（校验用） ─────────────────

    @Test
    fun `probe never exceeds the cap on either dimension`() {
        // 这是探针的**规格本身**，独立于任何像素计算公式，无法被"换个公式"绕过。
        for ((w, h) in shapes) {
            val s = ImageSampling.probeSampleSize(w, h)
            val (dw, dh) = dims(w, h, s)
            assertTrue(
                "${w}x$h 的探针采样率 $s 让宽度变成 $dw，超过上限 ${ImageSampling.MAX_IMAGE_PROBE_PX}",
                dw <= ImageSampling.MAX_IMAGE_PROBE_PX,
            )
            assertTrue(
                "${w}x$h 的探针采样率 $s 让高度变成 $dh，超过上限 ${ImageSampling.MAX_IMAGE_PROBE_PX}",
                dh <= ImageSampling.MAX_IMAGE_PROBE_PX,
            )
        }
    }

    @Test
    fun `probe keeps the bitmap tiny no matter how big or how lopsided the source is`() {
        for ((w, h) in shapes) {
            val s = ImageSampling.probeSampleSize(w, h)
            val pixels = ceilPixels(w, h, s)
            assertTrue(
                "${w}x$h 的探针位图是 $pixels 像素（应 < 1 MP）——校验自己就会吃内存",
                pixels < 1_000_000,
            )
        }
    }

    @Test
    fun `probe does not shrink an image that is already small enough`() {
        assertEquals(1, ImageSampling.probeSampleSize(100, 200))
        assertEquals(1, ImageSampling.probeSampleSize(256, 256))
        assertEquals(1, ImageSampling.probeSampleSize(1, 1))
    }

    // ───────────────── 立绘（显示用） ─────────────────

    @Test
    fun `huge image is downsampled far below the memory-danger line`() {
        // 真机实测过的那张图：18540 x 23437（约 4.3 亿像素），全尺寸 ARGB_8888 要 1.74 GB
        val w = 18540
        val h = 23437
        assertTrue("这张图全尺寸解码确实超过 1 GB，用例前提成立", w.toLong() * h * 4 > 1_000_000_000L)

        val s = ImageSampling.portraitSampleSize(w, h, maxWidthPx = 1080, maxHeightPx = 1920)
        val bytes = ceilPixels(w, h, s) * 4
        assertTrue("采样后位图必须远小于 64 MB，实际 ${bytes / 1048576} MB", bytes < 64L * 1024 * 1024)
    }

    @Test
    fun `portrait result never exceeds the pixel budget`() {
        // 用**向上取整**的真值判，而不是实现内部的整除估算 ——
        // 旧写法用整除时，`100000×161` 会被判成正好 4 MP（实际 4.05 MP）而漏过。
        for ((w, h) in shapes) {
            val s = ImageSampling.portraitSampleSize(w, h, 1080, 1920)
            val pixels = ceilPixels(w, h, s)
            assertTrue(
                "${w}x$h 采样后真实解码 $pixels 像素，超过预算 ${ImageSampling.MAX_PORTRAIT_PIXELS}",
                pixels <= ImageSampling.MAX_PORTRAIT_PIXELS,
            )
        }
    }

    @Test
    fun `sampled result is never smaller than the screen itself`() {
        // 判据钉在【设计意图】上：立绘要按 Crop 铺满 800x1280 的屏幕，
        // 采样过头会让它被放大糊掉。
        //
        // ⚠️ 这里**不能**断言"必须盖住 1080x1920 这个目标框"——那个框是上限，
        //    而"按总像素折半"的兜底循环本来就会在某些图上把它压下去
        //    （4000x6000 会被采到 1000x1500）。第一版就是这么写错的，
        //    结果代码正确、测试假红。真正该守的是"别小于屏幕"。
        val screenPixels = 800L * 1280
        for ((w, h) in listOf(863 to 1536, 4000 to 6000, 5000 to 5000, 8000 to 6000, 18540 to 23437)) {
            val s = ImageSampling.portraitSampleSize(w, h, 1080, 1920)
            val pixels = ceilPixels(w, h, s)
            assertTrue(
                "${w}x$h 采样后只剩 $pixels 像素，已小于屏幕的 $screenPixels —— 全屏显示会被放大糊掉",
                pixels >= screenPixels,
            )
        }
    }

    @Test
    fun `image already smaller than the target is not downsampled`() {
        assertEquals(1, ImageSampling.portraitSampleSize(863, 1536, 1080, 1920))
        assertEquals(1, ImageSampling.portraitSampleSize(800, 1280, 1080, 1920))
    }

    @Test
    fun `sample size is always a power of two and at least one`() {
        for ((w, h) in shapes) {
            for (s in listOf(
                ImageSampling.portraitSampleSize(w, h, 1080, 1920),
                ImageSampling.probeSampleSize(w, h),
            )) {
                assertTrue("sample 必须 >= 1，实际 $s", s >= 1)
                assertEquals("sample 必须是 2 的幂，实际 $s", 0, s and (s - 1))
            }
        }
    }

    @Test
    fun `non-positive dimensions fall back to one instead of looping forever`() {
        assertEquals(1, ImageSampling.sampleSizeFor(0, 100, 1080, 1920))
        assertEquals(1, ImageSampling.sampleSizeFor(100, -5, 1080, 1920))
        assertEquals(1, ImageSampling.sampleSizeFor(-1, -1, 1080, 1920))
        assertEquals(1, ImageSampling.probeSampleSize(0, 100))
        assertEquals(1, ImageSampling.probeSampleSize(-1, -1))
    }

    @Test
    fun `decodedPixels mirrors the decoder's round-up semantics`() {
        // 直接锁住这个语义本身：整除实现会在这里给出 3_686_400，而真值是 3_690_241。
        assertEquals(1921L * 1921L, ImageSampling.decodedPixels(3841, 3841, 2))
        assertEquals(18540L * 23437L, ImageSampling.decodedPixels(18540, 23437, 1))
    }

    // ───────────────── 导入压缩（缩到长边上限） ─────────────────

    @Test
    fun `fit sample brings the long side within the limit for every shape`() {
        // 导入落盘时用它先粗采样。判据是**长边**，所以长条图也必须被采样——
        // 这正是"复用立绘那套两方向都要大才折半的逻辑会完全不采样"的那个坑。
        val limit = 2048
        for ((w, h) in shapes) {
            val s = ImageSampling.fitSampleSize(w, h, limit)
            val (dw, dh) = dims(w, h, s)
            assertTrue(
                "${w}x$h 采样后长边是 ${maxOf(dw, dh)}，超过上限 $limit",
                maxOf(dw, dh) <= limit,
            )
        }
    }

    @Test
    fun `fit sample never shrinks an image that already fits`() {
        assertEquals(1, ImageSampling.fitSampleSize(800, 1280, 2048))
        assertEquals(1, ImageSampling.fitSampleSize(2048, 2048, 2048))
        assertEquals(1, ImageSampling.fitSampleSize(100, 50, 512))
    }

    @Test
    fun `fit sample of a real phone photo lands in the expected range`() {
        // 4000x6000 的手机照片：粗采样到长边 <= 2048。
        // 2 的幂采样会有余量，所以允许落在 (1024, 2048] ——
        // 关键是**不能停在 1**（那等于完全不压缩）。
        val s = ImageSampling.fitSampleSize(4000, 6000, 2048)
        val (dw, dh) = dims(4000, 6000, s)
        assertTrue("长边 ${maxOf(dw, dh)} 必须 <= 2048，实际 sample=$s", maxOf(dw, dh) <= 2048)
        assertTrue("长边 ${maxOf(dw, dh)} 不该被压到 1024 以下（那是过度压缩）", maxOf(dw, dh) > 1024)
    }

    @Test
    fun `fit sample handles degenerate inputs`() {
        assertEquals(1, ImageSampling.fitSampleSize(0, 100, 2048))
        assertEquals(1, ImageSampling.fitSampleSize(100, 0, 2048))
        assertEquals(1, ImageSampling.fitSampleSize(100, 100, 0))
        assertEquals(1, ImageSampling.fitSampleSize(-5, -5, 2048))
    }
}
