package me.xiaozhi.androidclient.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一条**路标**测试：它记录的是"一个看起来很像 bug、但其实不是"的地方。
 *
 * ## 背景
 *
 * v1.2.6 修了 OTA 检查的取消竞态：`finally` 里用 `appUpdateCheckJob === self`
 * 判断"我还是当前那次检查吗"。但状态写入（`when` 各分支里的 `_uiState.update`）
 * 没有同样的判断。
 *
 * 代码评审据此报了一条【严重】结论：旧任务挂在 `checkForUpdate` 的阻塞 OkHttp 上时
 * 取消打断不了，而 `checkForUpdate` 用 `runCatching{}.getOrElse{}` 把取消结果包成了
 * **正常返回值**，于是被取消的旧任务仍会继续走 `when`、用过时结果覆盖 UI
 * （`UpToDate` 分支还会把 `availableUpdate` 置 null，抹掉刚查到的更新）。
 *
 * ## 结论：那条结论是错的
 *
 * `checkForUpdate` 的结构是 `withContext(Dispatchers.IO) { runCatching { ... }.getOrElse { ... } }`。
 * 取消的检查发生在 **`withContext` 的边界上**（prompt cancellation guarantee），
 * 也就是**在 `runCatching` 之外** —— `runCatching` 根本吞不到它。
 * 协程一旦被取消，`withContext` 抛 `CancellationException` 而不是返回值，
 * 后面的 `when` 一行都不会执行。
 *
 * ## 为什么要把这件事写成测试
 *
 * 评审报告是会流传的。**一条错误的结论如果不留路标，后人会照着它再追一遍**，
 * 甚至去"修"一段本来正确的代码（假红比假绿更坏）。
 * 这个测试就是那条路标：谁再怀疑这里，先跑它。
 *
 * 证伪方式：把 [withContext] 换成不用它的写法（例如直接在 IO 上 `runBlocking`），
 * `cancelled withContext does not deliver its value` 应当变红。
 */
class CancellationSemanticsTest {

    @Test
    fun `cancelled withContext does not deliver its value`() = runBlocking {
        var reachedAfterWithContext = false

        val job = launch {
            // 与 AppUpdateManager.checkForUpdate 完全同构：
            // 外层 withContext(IO)，内层 runCatching{...}.getOrElse{...}
            val value = withContext(Dispatchers.IO) {
                runCatching {
                    // 模拟不可取消的阻塞式 OkHttp 调用
                    Thread.sleep(300)
                    "STALE_RESULT"
                }.getOrElse { "ERROR" }
            }
            // 这一行就是"旧任务继续用过时结果写 UI"的那一步
            reachedAfterWithContext = true
            println("!!! 取消后仍然拿到了值：$value")
        }

        delay(60)
        job.cancel()
        job.join()

        assertFalse(
            "取消后 withContext 仍把值交了出来 —— 如果这里红了，" +
                "说明「OTA 检查的旧任务会覆盖 UI」那条结论成立，需要给 when 各分支也加身份判断",
            reachedAfterWithContext,
        )
    }

    @Test
    fun `control - without cancellation the value does arrive`() = runBlocking {
        // 对照组：没有它，上面那条断言即使因为"代码根本没跑"而通过，也看不出来。
        var reached = false
        val job = launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    Thread.sleep(50)
                    "OK"
                }.getOrElse { "ERROR" }
            }
            reached = true
        }
        job.join()
        assertTrue("对照组失败说明实验本身无效，上面那条断言也就没有意义", reached)
    }
}
