package me.xiaozhi.androidclient.util

/**
 * 位图采样率计算。
 *
 * **这是防止「导入一张大图把整机拖垮」的核心算术，所以它必须是纯函数、必须可测。**
 *
 * 背景（2026-09-19 真机事故）：校验「这是不是一张图片」时用了全尺寸
 * `BitmapFactory.decodeFile`，位图字节数 = 宽 × 高 × 4。用户导入一张
 * 18540×23437 的 JPEG 需要一次性分配 **1.74 GB**，而这块 RK3568 板子
 * `MemTotal` 只有 1.92 GB —— 整机被打进 zram 换页，主线程冻 5.1 秒触发 ANR。
 *
 * 所以凡是"要看一眼这张图"的地方，都必须先过这里算出采样率，绝不按原始尺寸解码。
 *
 * ## 两种用途，语义**不同**，不要互相复用
 *
 * - [portraitSampleSize]：立绘。要按 Crop 铺满屏幕，所以**两个方向都得盖住目标框**，
 *   只在两方向都还很大时才折半；再由像素预算兜底。
 * - [probeSampleSize]：校验探针。只要求"看清这是张能解码的图"，
 *   所以**任一方向超限就折半**。
 *
 * ⚠️ 2026-09-19 的教训：最初探针图省事复用了立绘那套"两方向都大才折半"的逻辑，
 * 而它要求两个方向都 ≥ 2×上限 —— 于是**任一方向小于 512 像素时 sample 恒为 1，
 * 等于按原图整张解码**，这次想修的 ANR 换张长条图就能原样复现
 * （`65535×511` 的合法 JPEG 会分配 127 MB）。**两处语义不同，就必须写两个循环。**
 */
object ImageSampling {

    /**
     * 立绘解码后的像素上限（约 4 MP，ARGB_8888 下约 16 MB）。
     * 用来兜住极端长宽比的图片——超过这个量级就有触发
     * `Canvas: trying to draw too large bitmap` 的风险。
     */
    const val MAX_PORTRAIT_PIXELS = 4_000_000L

    /** 校验"这是不是能解码的图片"时，探针位图的最大边长（像素）。 */
    const val MAX_IMAGE_PROBE_PX = 256

    /** 向上取整的除法，与 `BitmapFactory` 实际的采样语义一致。 */
    fun ceilDiv(value: Int, divisor: Int): Long = ((value + divisor - 1) / divisor).toLong()

    /**
     * 采样后**真实的**解码像素数。
     *
     * 注意不能用 `(w / s) * (h / s)`：`BitmapFactory` 对每个方向是**向上取整**到采样粒度的
     * 倍数（最小 1），整除会低估，两个方向合计最多低估约 4 倍。
     * 用整除判预算会让 [MAX_PORTRAIT_PIXELS] 不再是真上界
     * （实测 `100000×161` 会被判成正好 4 MP，实际解码 4.05 MP）。
     */
    fun decodedPixels(width: Int, height: Int, sample: Int): Long =
        ceilDiv(width, sample) * ceilDiv(height, sample)

    /**
     * 求一个 2 的幂采样率，使解码结果**在两个方向上都盖得住目标框**、
     * 且真实像素数不超过 [maxPixels]。
     *
     * 两步走：
     *  1. 只要两个方向都还比目标大一倍以上就继续折半 —— 保证结果仍能盖住目标
     *     （立绘是按 Crop 铺满显示的，采样过头会糊）；
     *  2. 再按真实解码像素兜一层 —— 极端长宽比下第一步可能停在很大的尺寸。
     *
     * 返回的 sample 一定 ≥ 1。传入非正的宽高时退回 1（调用方应先过滤）。
     */
    fun sampleSizeFor(
        width: Int,
        height: Int,
        maxWidthPx: Int,
        maxHeightPx: Int,
        maxPixels: Long = MAX_PORTRAIT_PIXELS,
    ): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= maxWidthPx && height / (sample * 2) >= maxHeightPx) {
            sample *= 2
        }
        while (decodedPixels(width, height, sample) > maxPixels) {
            sample *= 2
        }
        return sample
    }

    /** 全屏立绘用的采样率：目标是屏幕量级，不是原图。 */
    fun portraitSampleSize(width: Int, height: Int, maxWidthPx: Int, maxHeightPx: Int): Int =
        sampleSizeFor(width, height, maxWidthPx, maxHeightPx, MAX_PORTRAIT_PIXELS)

    /**
     * 校验用探针的采样率：**任一方向**超过 [maxPx] 就折半，直到两个方向都不超。
     *
     * 与 [portraitSampleSize] **不能互相复用**：这里没有"要盖住目标框"的诉求，
     * 只要求把位图压小。复用立绘逻辑会让长条图完全不被采样（见类注释里的教训）。
     */
    fun probeSampleSize(width: Int, height: Int, maxPx: Int = MAX_IMAGE_PROBE_PX): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (ceilDiv(width, sample) > maxPx || ceilDiv(height, sample) > maxPx) {
            sample *= 2
        }
        return sample
    }

    /**
     * 把图缩到**长边**不超过 [maxLongSide] 所需的粗采样率（2 的幂）。
     *
     * 用于导入时压缩落盘（见 `MainViewModel.shrinkImageIfNeeded`）：先按这个采样率粗解一次，
     * 再用精确比例缩到目标——避免"为了存一张缩略图而按原尺寸解码整张图"。
     *
     * **判据是长边**，所以同样是"任一方向超限就折半"，不能复用立绘那套
     * 「两个方向都要盖住目标框」的逻辑（那会在长条图上退化成完全不采样，
     * 正是 2026-09-19 那次 ANR 的形态）。
     */
    fun fitSampleSize(width: Int, height: Int, maxLongSide: Int): Int {
        if (width <= 0 || height <= 0 || maxLongSide <= 0) return 1
        val long = maxOf(width, height)
        var sample = 1
        while (ceilDiv(long, sample) > maxLongSide) {
            sample *= 2
        }
        return sample
    }
}
