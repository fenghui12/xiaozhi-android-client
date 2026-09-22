package me.xiaozhi.androidclient.digitalhuman

import me.xiaozhi.androidclient.model.DigitalHumanSlot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫码上传页的回归网。
 *
 * 这里守的是一个**真实发生过的产品级漏洞**：这套扫码通道原本只服务「角色形象视频」，
 * 立绘和头像只有设备上的「本机」选文件入口 —— 而客户没有 ADB、也不方便用设备上的
 * 文件选择器，等于立绘这条路对客户来说根本走不通。
 *
 * 泛化之后最容易出的错是「页面文案/接受类型没跟着 target 走」：
 * 立绘的上传页还写着「选择 MP4 视频」。所以下面除了正向断言，还有一条**反向断言**。
 */
class LanUploadServerTest {

    private fun htmlFor(target: UploadTarget) =
        LanUploadServer.buildUploadHtml("奥特曼", target, "fake-token-123")

    @Test
    fun htmlContainsNecessaryUiElements() {
        val html = htmlFor(UploadTarget.Video(DigitalHumanSlot.IDLE))

        assertTrue("Should contain role name", html.contains("奥特曼"))
        assertTrue("Should contain slot label", html.contains("待机视频"))
        assertTrue("Should contain file input", html.contains("type=\"file\""))
        assertTrue("Should contain upload button", html.contains("uploadBtn"))
        assertTrue("Should contain progress bar", html.contains("progressBar"))
        assertTrue("Should contain token in POST URL", html.contains("/upload?token=fake-token-123"))
        assertTrue("Should handle XHR upload progress", html.contains("xhr.upload.onprogress"))
    }

    @Test
    fun videoTargetAcceptsOnlyVideo() {
        val html = htmlFor(UploadTarget.Video(DigitalHumanSlot.SPEAKING))

        assertTrue("视频页接受视频类型", html.contains("""accept="video/mp4,video/*""""))
        assertTrue("视频页用 video/mp4 作为 Content-Type", html.contains("'video/mp4'"))
        assertTrue("视频页显示槽位名", html.contains("讲话视频"))
    }

    @Test
    fun portraitTargetAcceptsImagesAndSaysSo() {
        val html = htmlFor(UploadTarget.Portrait)

        assertTrue("立绘页接受图片类型", html.contains("""accept="image/*""""))
        assertTrue("立绘页显示目标名", html.contains("角色立绘"))
        assertTrue("立绘页标题正确", html.contains("上传角色立绘"))
        assertTrue("立绘页提到会自动压缩", html.contains("2048"))
    }

    @Test
    fun avatarTargetAcceptsImagesAndSaysSo() {
        val html = htmlFor(UploadTarget.Avatar)

        assertTrue("头像页接受图片类型", html.contains("""accept="image/*""""))
        assertTrue("头像页显示目标名", html.contains("角色头像"))
        assertTrue("头像页标题正确", html.contains("上传角色头像"))
        assertTrue("头像页提到会自动压缩", html.contains("512"))
    }

    /**
     * 反向断言：图片类目标的页面**不该**出现视频字样。
     *
     * 这正是「页面文案没跟着 target 走」那个 bug 的指纹 ——
     * 泛化时如果只改了 accept 而忘了文案，用户会看到"请选择一段 MP4 视频"
     * 却只能选图片，第一反应是"坏了"。
     */
    /**
     * 反向断言：图片类目标的页面**不该**出现视频字样。
     *
     * 这正是「页面文案没跟着 target 走」那个 bug 的指纹 ——
     * 泛化时如果只改了 accept 而忘了文案，用户会看到"请选择一段 MP4 视频"
     * 却只能选图片，第一反应是"坏了"。
     */
    @Test
    fun imageTargetsDoNotMentionVideo() {
        for (target in listOf(UploadTarget.Portrait, UploadTarget.Avatar)) {
            val html = htmlFor(target)
            assertFalse(
                "${target.label} 的上传页不该出现 MP4 字样",
                html.contains("MP4"),
            )
            assertFalse(
                "${target.label} 的上传页不该出现 video/ 类型",
                html.contains("video/"),
            )
        }
    }

    /**
     * 手机端失败反馈必须真的看得见、且能重选文件。
     *
     * 守的是代码评审查出来的两个真实缺陷（原实现里都在）：
     *
     * 1. 隐藏提示时写的是 `statusMsg.style.display = 'none'`（**内联样式**），
     *    而显示时只改 `className = 'msg error'` —— 内联优先级高于 CSS 类，
     *    于是**错误提示永远不显示**。手机那头表现为"点了上传，什么都没发生"。
     * 2. 开始时 `fileInput.disabled = true`，失败分支没恢复，
     *    选错一次就只能刷新网页才能重来。
     *
     * 客户没有 ADB，扫码是他唯一的素材导入通道 —— 这条路上"失败无声"等于不可用。
     */
    @Test
    fun uploadPageErrorIsVisibleAndRetryable() {
        val html = htmlFor(UploadTarget.Portrait)

        assertTrue(
            "提示必须用显式内联 display 显示（否则被内联的 none 压住）",
            html.contains("statusMsg.style.display = 'block'"),
        )
        assertTrue(
            "失败后必须恢复 fileInput，否则无法重选文件",
            html.contains("fileInput.disabled = false"),
        )
        assertTrue(
            "失败后必须恢复上传按钮",
            html.contains("uploadBtn.disabled = false"),
        )
    }
}
