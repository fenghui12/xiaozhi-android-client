package me.xiaozhi.androidclient.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.xiaozhi.androidclient.camera.CameraVisionTool
import me.xiaozhi.androidclient.network.XiaozhiRealtimeClient
import me.xiaozhi.androidclient.scheduling.ReminderScheduler
import org.json.JSONArray
import org.json.JSONObject

private const val CAMERA_TOOL_NAME = "self.camera.take_photo"

class McpCameraServer(
    private val cameraVisionTool: CameraVisionTool,
    private val realtimeClient: XiaozhiRealtimeClient,
    private val scope: CoroutineScope,
    private val reminderScheduler: ReminderScheduler,
    private val isCurrentSession: (String) -> Boolean,
    private val isScheduledDeliveryInProgress: () -> Boolean,
    private val isInitialSupervisionReminderInProgress: () -> Boolean,
    private val log: (String) -> Unit,
) {
    fun handleIncomingMcp(rawText: String): Boolean {
        val transportEnvelope = runCatching { JSONObject(rawText) }.getOrElse {
            log("忽略无效 MCP JSON：${it.message.orEmpty()}")
            return true
        }
        // The WebSocket transport wraps JSON-RPC in { type: "mcp", payload: {...} }.
        // ESP32 removes this envelope before dispatching; Android receives it directly.
        val request = transportEnvelope.optJSONObject("payload") ?: transportEnvelope
        val requestSessionId = transportEnvelope.optString("session_id").takeIf { it.isNotBlank() }
        if (request.optString("jsonrpc") != "2.0") {
            return false
        }
        val method = request.optString("method")
        if (method.isBlank()) {
            // This is a response to a client-initiated MCP debug request, not a server call.
            return false
        }
        val id = request.opt("id")
        if (id == null || id == JSONObject.NULL) {
            return true
        }

        when (method) {
            "initialize" -> handleInitialize(id, request.optJSONObject("params"))
            "tools/list" -> replyToolsList(id)
            "tools/call" -> handleToolCall(id, request.optJSONObject("params"), requestSessionId)
            else -> replyError(id, "Method not implemented: $method")
        }
        return true
    }

    private fun handleInitialize(id: Any, params: JSONObject?) {
        val vision = params?.optJSONObject("capabilities")?.optJSONObject("vision")
        cameraVisionTool.updateVisionEndpoint(
            url = vision?.optString("url")?.takeIf { it.isNotBlank() },
            token = vision?.optString("token"),
        )
        if (vision?.optString("url").isNullOrBlank()) {
            log("MCP 初始化完成，服务端暂未下发视觉上传地址")
        } else {
            log("MCP 初始化完成，已收到视觉上传地址")
        }
        replyResult(
            id,
            JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put(
                    "serverInfo",
                    JSONObject()
                        .put("name", "xiaozhi-android-client")
                        .put("version", "0.3.0"),
                ),
        )
    }

    private fun replyToolsList(id: Any) {
        replyResult(
            id,
            JSONObject().put(
                "tools",
                JSONArray()
                    .put(cameraToolSchema())
                    .put(timerSchema())
                    .put(timerCurrentSchema())
                    .put(supervisionStartSchema())
                    .put(supervisionCurrentSchema())
                    .put(supervisionCancelSchema()),
            ),
        )
        log("已向服务端声明摄像头、定时提醒、监督开始和取消工具")
    }

    private fun handleToolCall(id: Any, params: JSONObject?, requestSessionId: String?) {
        val name = params?.optString("name").orEmpty()
        val args = params?.optJSONObject("arguments") ?: JSONObject()
        when (name) {
            CAMERA_TOOL_NAME -> {
                // Camera decisions belong to the model. Android only delivers
                // the reminder and never performs local supervision vision.
                handleCameraCall(id, requestSessionId, args)
            }
            "self.timer.set" -> {
                if (isScheduledDeliveryInProgress()) {
                    log("提醒投递期间拦截了重复的 self.timer.set")
                    replyResult(
                        id,
                        toolTextResult("原定时器已经到期，请直接向用户播报当前提醒，不要创建新的定时器。"),
                    )
                    return
                }
                val seconds = args.optInt("seconds", 0)
                val time = args.optString("time").takeIf { it.isNotBlank() }
                val message = args.optString("message", "时间到了")
                val reminder = reminderScheduler.setTimer(seconds, time, message)
                if (reminder == null) replyError(id, "请指定有效的 seconds 或 HH:MM time")
                else replyResult(id, toolTextResult("已设置提醒：${reminder.message}"))
            }
            "self.timer.current" -> {
                val reminder = reminderScheduler.activeTimer()
                if (reminder == null) replyResult(id, toolTextResult("当前没有到期的定时提醒"))
                else replyResult(id, toolTextResult("当前定时提醒：${reminder.message}"))
            }
            "self.supervision.start" -> {
                if (isScheduledDeliveryInProgress()) {
                    log("任务投递期间拦截了 self.supervision.start")
                    replyResult(id, toolTextResult("当前到期任务正在播报，请直接播报，不要创建监督任务。"))
                    return
                }
                val seconds = args.optInt("seconds", 0)
                val message = args.optString("message", "任务")
                val reminder = if (seconds in 1..86400 && message.isNotBlank()) {
                    reminderScheduler.startSupervision(seconds, message)
                } else null
                if (reminder == null) replyError(id, "已有监督任务，或参数无效")
                else replyResult(id, toolTextResult("监督任务已开始：${reminder.message}"))
            }
            "self.supervision.current" -> {
                val reminder = reminderScheduler.activeSupervision()
                if (reminder == null) replyResult(id, toolTextResult("当前没有监督任务"))
                else replyResult(id, toolTextResult("当前监督任务：${reminder.message}"))
            }
            "self.supervision.check" -> {
                log("已拦截模型发起的 self.supervision.check，核验由设备协调器管理")
                replyResult(id, toolTextResult("监督核验由设备自动安排，请勿重复设置。"))
            }
            "self.supervision.complete" -> {
                log("已拦截模型发起的 self.supervision.complete，完成状态必须来自摄像头核验")
                replyResult(id, toolTextResult("不能根据对话直接完成监督任务；设备会以摄像头核验结果为准。"))
            }
            "self.supervision.cancel" -> {
                if (isScheduledDeliveryInProgress()) {
                    log("任务投递期间拦截了 self.supervision.cancel")
                    replyResult(id, toolTextResult("当前到期任务正在播报，未取消任何监督任务。"))
                    return
                }
                reminderScheduler.cancelSupervision()
                replyResult(id, toolTextResult("监督任务已取消"))
            }
            else -> replyError(id, "Unknown tool: $name")
        }
    }

    private fun handleCameraCall(id: Any, requestSessionId: String?, args: JSONObject) {
        val question = args.optString("question").orEmpty()
        if (question.isBlank()) {
            replyError(id, "Missing valid argument: question")
            return
        }
        log("收到拍照识别请求")
        scope.launch(Dispatchers.IO) {
            if (requestSessionId == null || !isCurrentSession(requestSessionId)) {
                log("已忽略旧会话的拍照识别请求")
                return@launch
            }
            runCatching { cameraVisionTool.takePhotoAndExplain(question) }
                .onSuccess { explanation ->
                    if (isCurrentSession(requestSessionId)) replyResult(id, toolTextResult(explanation))
                    log("拍照识别完成")
                }
                .onFailure { error ->
                    if (isCurrentSession(requestSessionId)) replyError(id, error.message ?: "Camera vision failed")
                    log("拍照识别失败：${error.message.orEmpty()}")
                }
        }
    }

    private fun replyResult(id: Any, result: JSONObject) {
        realtimeClient.sendMcp(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", result)
                .toString(),
        )
    }

    private fun replyError(id: Any, message: String) {
        realtimeClient.sendMcp(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("error", JSONObject().put("message", message))
                .toString(),
        )
    }

    private fun cameraToolSchema(): JSONObject = JSONObject()
        .put("name", CAMERA_TOOL_NAME)
        .put(
            "description",
            "Always remember you have a camera. If the user asks you to see something, use this tool to take a photo and explain it. When a reminder message contains 【监督】, you must call this camera tool before judging whether the task was completed.",
        )
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put("question", JSONObject().put("type", "string")),
                )
                .put("required", JSONArray().put("question")),
        )

    private fun timerSchema() = simpleToolSchema(
        "self.timer.set",
        "Set a new local countdown timer or HH:MM alarm. Never call this when receiving 【定时提醒】; that marker means an existing timer is due.",
        JSONObject()
            .put("seconds", JSONObject().put("type", "integer"))
            .put("time", JSONObject().put("type", "string"))
            .put("message", JSONObject().put("type", "string")),
        JSONArray(),
    )

    private fun timerCurrentSchema() = simpleToolSchema(
        "self.timer.current",
        "When you receive the exact marker 【定时提醒】, call this tool to get the due reminder text, then speak that reminder to the user. Do not call self.timer.set.",
        JSONObject(),
        JSONArray(),
    )

    private fun supervisionStartSchema() = simpleToolSchema(
        "self.supervision.start",
        "Start one local supervision task. At the due time the device sends a reminder containing 【监督】; then you must call self.camera.take_photo before judging completion.",
        JSONObject()
            .put("seconds", JSONObject().put("type", "integer"))
            .put("message", JSONObject().put("type", "string")),
        JSONArray().put("seconds").put("message"),
    )

    private fun supervisionCancelSchema() = simpleToolSchema("self.supervision.cancel", "Cancel the active supervision task.", JSONObject(), JSONArray())

    private fun supervisionCurrentSchema() = simpleToolSchema(
        "self.supervision.current",
        "When you receive 【监督】, call this tool first to get the task. Then remind the user and call self.camera.take_photo before judging completion.",
        JSONObject(),
        JSONArray(),
    )

    private fun simpleToolSchema(name: String, description: String, properties: JSONObject, required: JSONArray) = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("inputSchema", JSONObject().put("type", "object").put("properties", properties).put("required", required))

    private fun toolTextResult(text: String): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("isError", false)
}
