package me.xiaozhi.androidclient.integration

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

private const val TERMUX_PACKAGE = "com.termux"
private const val TERMUX_API_PACKAGE = "com.termux.api"
private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

class TermuxRunner(private val context: Context) {
    private val requestCodeCounter = AtomicInteger(1000)

    fun isAvailable(): Boolean {
        return isPackageInstalled(TERMUX_PACKAGE)
    }

    fun isTermuxApiAvailable(): Boolean {
        return isPackageInstalled(TERMUX_API_PACKAGE)
    }

    fun statusLabel(enabled: Boolean): String {
        return when {
            !enabled -> "未启用"
            !isAvailable() -> "未安装 Termux"
            else -> "已安装，需要在 Termux 打开 allow-external-apps"
        }
    }

    fun termuxApiStatusLabel(enabled: Boolean): String {
        return when {
            !enabled -> "未启用"
            !isAvailable() -> "未安装 Termux"
            !isTermuxApiAvailable() -> "缺少 Termux:API 应用"
            else -> "可调用 termux-api 命令"
        }
    }

    fun runPythonScript(
        pythonPath: String,
        scriptPath: String,
        workdir: String,
    ): Result<String> {
        val normalizedScript = scriptPath.trim()
        if (normalizedScript.isBlank()) {
            return Result.failure(IllegalArgumentException("Python 脚本路径不能为空"))
        }
        return runCommand(
            commandPath = pythonPath.trim(),
            arguments = listOf(normalizedScript),
            workdir = workdir,
            label = "Python MCP",
        )
    }

    fun runTermuxApiCommand(
        commandPath: String,
        arguments: String,
        workdir: String,
    ): Result<String> {
        val normalizedCommand = commandPath.trim()
        if (normalizedCommand.isBlank()) {
            return Result.failure(IllegalArgumentException("termux-api 命令路径不能为空"))
        }
        return runCommand(
            commandPath = normalizedCommand,
            arguments = tokenizeArguments(arguments),
            workdir = workdir,
            label = "Termux API",
        )
    }

    private fun runCommand(
        commandPath: String,
        arguments: List<String>,
        workdir: String,
        label: String,
    ): Result<String> = runCatching {
        if (!isAvailable()) {
            error("未安装 Termux")
        }
        if (commandPath.isBlank()) {
            error("命令路径不能为空")
        }

        val resultIntent = Intent(context, TermuxResultService::class.java)
        val pendingIntentFlags = PendingIntent.FLAG_ONE_SHOT or
            PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getService(
            context,
            requestCodeCounter.incrementAndGet(),
            resultIntent,
            pendingIntentFlags,
        )

        val intent = Intent(ACTION_RUN_COMMAND).apply {
            component = ComponentName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, commandPath)
            putExtra(EXTRA_ARGUMENTS, arguments.toTypedArray())
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_SESSION_ACTION, 0)
            putExtra(EXTRA_COMMAND_LABEL, label)
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            if (workdir.isNotBlank()) {
                putExtra(EXTRA_WORKDIR, workdir.trim())
            }
        }

        ContextCompat.startForegroundService(context, intent)
        "已请求 Termux 执行：$label"
    }

    private fun tokenizeArguments(raw: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var quoteChar = '\u0000'
        var escaping = false

        for (char in raw.trim()) {
            when {
                escaping -> {
                    current.append(char)
                    escaping = false
                }

                char == '\\' -> escaping = true

                inQuotes && char == quoteChar -> {
                    inQuotes = false
                    quoteChar = '\u0000'
                }

                !inQuotes && (char == '"' || char == '\'') -> {
                    inQuotes = true
                    quoteChar = char
                }

                !inQuotes && char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }

                else -> current.append(char)
            }
        }

        if (current.isNotEmpty()) {
            tokens += current.toString()
        }
        return tokens
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
        }.isSuccess
    }
}
