package me.xiaozhi.androidclient.digitalhuman

import android.content.Context
import android.util.AttributeSet
import android.widget.VideoView

/**
 * 按 cover 方式铺满父容器的 [VideoView]：画面放大到盖住整个容器，超出部分由父容器裁掉。
 *
 * ## 为什么不能"在 onPrepared 里算一次缩放、把绝对像素写进 layoutParams"
 *
 * 之前的写法是：`onPrepared` 回调里读一次容器尺寸，算出一个缩放比，
 * 然后把 `layoutParams.width/height` 设成算好的绝对像素。这个做法有两个致命前提：
 *
 * 1. 那一次回调必须刚好赶上「容器已经量好、且量到的是最终尺寸」——
 *    而 `onPrepared` 是解码器异步回调，它和 Compose 的测量/布局没有先后保证；
 * 2. 算完之后容器再也不会变——可只要发生任何一次重新布局（切段、旋转、
 *    系统栏变化、窗口尺寸变化），写死的绝对像素都不会跟着更新。
 *
 * 两个前提任意一个不成立，画面就会**永久卡在错误尺寸上**，而且因为缩放比是按
 * 「容器的长边」算的，失真表现为整幅画面缩到容器的一小块（客户 2026-09-16 反馈的
 * "人物图像变的很小、缩小三分之二"就是这个现象）。
 *
 * ## 改成自定义 onMeasure 之后
 *
 * 尺寸变成**父容器尺寸的纯函数**：父容器每次重新测量都会带着当前尺寸再算一遍，
 * 不存在"算一次就定死"的窗口期，也不可能因为回调时机而算错。
 * 拿到视频尺寸之前先老实占满，`setSourceSize` 会触发重新测量。
 */
class CoverVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : VideoView(context, attrs) {

    private var sourceWidth = 0
    private var sourceHeight = 0

    /** 由 `onPrepared` 回调把解码器报出的视频尺寸传进来，随后自动触发一次重新测量。 */
    fun setSourceSize(width: Int, height: Int) {
        if (width == sourceWidth && height == sourceHeight) return
        sourceWidth = width
        sourceHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (sourceWidth <= 0 || sourceHeight <= 0 || availableWidth <= 0 || availableHeight <= 0) {
            // 视频尺寸还没拿到（或容器还没量出尺寸）：先占满，等 setSourceSize / 重新测量。
            setMeasuredDimension(availableWidth, availableHeight)
            return
        }
        // cover：取「撑满宽」和「撑满高」里更大的那个缩放比，保证两个方向都不留边。
        val scale = maxOf(
            availableWidth.toFloat() / sourceWidth,
            availableHeight.toFloat() / sourceHeight,
        )
        setMeasuredDimension(
            (sourceWidth * scale).toInt().coerceAtLeast(1),
            (sourceHeight * scale).toInt().coerceAtLeast(1),
        )
    }
}
