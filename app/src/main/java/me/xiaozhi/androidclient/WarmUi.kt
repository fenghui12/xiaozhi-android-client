package me.xiaozhi.androidclient

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay
import me.xiaozhi.androidclient.model.ScheduledTaskUi
import me.xiaozhi.androidclient.model.UiState

/**
 * 2026-11 前端改版的暖色组件：主屏待机、对话头部、语音胶囊、数字人画面上的浮层。
 *
 * 视觉稿在 `项目情况/前端改版效果图_2026-11-02/`，吉祥物素材（小狮子六种状态，透明底）
 * 在 `res/drawable-nodpi/mascot_*.webp`，原图与抠图脚本在 `项目情况/吉祥物小狮子_2026-11-02/`。
 *
 * 板子是 Android 11，没有 RenderEffect，做不了真正的背景模糊；「磨砂玻璃」一律用
 * 半透明白 + 细描边 + 柔和投影来近似。
 */
internal object Warm {
    val Paper = Color(0xFFFFF6EE)
    val Coral = Color(0xFFFF7F50)
    val CoralLight = Color(0xFFFFA27A)
    val CoralSoft = Color(0xFFFFE6D9)
    val Ink = Color(0xFF4A3426)
    val InkSoft = Color(0xFF8C7466)
    val Line = Color(0xFFF1E2D6)
    val Glass = Color.White.copy(alpha = 0.78f)
    val Mist = Color(0xFF8FB3D9)
    val Mint = Color(0xFF4FB38A)
    val Alert = Color(0xFFD9442B)
    val CoralGradient = Brush.horizontalGradient(listOf(CoralLight, Coral))
}

internal enum class MascotMood(@DrawableRes val drawable: Int, val label: String) {
    IDLE(R.drawable.mascot_idle, "待机中"),
    LISTENING(R.drawable.mascot_listening, "正在听…"),
    THINKING(R.drawable.mascot_thinking, "想一想…"),
    SPEAKING(R.drawable.mascot_speaking, "正在说话…"),
    GREETING(R.drawable.mascot_greeting, "你好呀"),
    SLEEPING(R.drawable.mascot_sleeping, "打盹中"),
}

/** 深夜（23 点到 6 点）待机时小狮子打瞌睡，其余时候睁眼待机。 */
private fun isNight(hour: Int) = hour >= 23 || hour < 6

internal fun mascotMood(state: UiState, hour: Int = LocalDateTime.now().hour): MascotMood = when {
    state.isAssistantSpeaking -> MascotMood.SPEAKING
    // 主动招呼刚触发、还没开始听：挥手
    state.isTurnActive && !state.isRecording && state.wakeWordStatus == "检测到人靠近" -> MascotMood.GREETING
    state.isRecording -> MascotMood.LISTENING
    // 话已说完、回答还没来：思考
    state.isTurnActive -> MascotMood.THINKING
    isNight(hour) -> MascotMood.SLEEPING
    else -> MascotMood.IDLE
}

/** 正在进行一轮对话（听 / 想 / 说）。 */
internal fun UiState.inConversation(): Boolean = isRecording || isTurnActive || isAssistantSpeaking

/**
 * 小狮子主形象：呼吸缩放 + 说话时轻微上下颠动，身后一圈柔光。
 * 换状态时交叉淡入，六张图画布一致，所以切换时位置不跳。
 */
@Composable
internal fun MascotHero(mood: MascotMood, size: Dp, modifier: Modifier = Modifier, showHalo: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "mascot")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val bounce by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse),
        label = "bounce",
    )
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (showHalo) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = this.size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.95f), Warm.CoralSoft.copy(alpha = 0.55f), Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                )
                val ring = radius * (0.80f + 0.03f * breath)
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = ring,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                )
                drawCircle(
                    color = Warm.Coral.copy(alpha = 0.12f),
                    radius = ring + 10.dp.toPx(),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                )
            }
        }
        Crossfade(targetState = mood, animationSpec = tween(320), label = "mood") { shown ->
            val lift = if (shown == MascotMood.SPEAKING) bounce * 6f else 0f
            Image(
                painter = painterResource(shown.drawable),
                contentDescription = shown.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(size * 0.06f)
                    .graphicsLayer {
                        val scale = 1f + 0.025f * breath
                        scaleX = scale
                        scaleY = scale
                        translationY = -lift.dp.toPx()
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.92f)
                    },
            )
        }
    }
}

/** 圆形头像里只露小狮子的头：放大后贴顶对齐。 */
@Composable
internal fun MascotAvatar(size: Dp, modifier: Modifier = Modifier, mood: MascotMood = MascotMood.IDLE) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Warm.CoralSoft)
            .border(1.dp, Color.White, CircleShape),
        contentAlignment = Alignment.TopCenter,
    ) {
        Image(
            painter = painterResource(mood.drawable),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .requiredSize(size * 1.75f)
                .offset(y = size * 0.05f),
        )
    }
}

/** 声波条：说话时珊瑚橙→雾蓝渐变跳动，听的时候幅度小一些，待机时是一排静止的小圆点。 */
@Composable
internal fun VoiceWave(active: Boolean, intense: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing)),
        label = "phase",
    )
    Canvas(modifier = modifier) {
        val bars = 29
        val gap = size.width / bars
        val barWidth = gap * 0.42f
        val mid = size.height / 2f
        for (i in 0 until bars) {
            val t = i / (bars - 1f)
            val envelope = sin(t * PI).toFloat()
            val wobble = abs(sin(phase + i * 0.55f) * 0.6f + sin(phase * 1.7f + i * 1.3f) * 0.4f)
            val amplitude = when {
                !active -> 0.08f
                intense -> 0.25f + 0.75f * wobble * envelope
                else -> 0.12f + 0.35f * wobble * envelope
            }
            val h = (size.height * amplitude).coerceAtLeast(barWidth)
            val color = androidx.compose.ui.graphics.lerp(Warm.Coral, Warm.Mist, t)
            drawRoundRect(
                color = color.copy(alpha = if (active) 0.95f else 0.45f),
                topLeft = Offset(i * gap + (gap - barWidth) / 2f, mid - h / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

/** 半透明白卡片（磨砂玻璃的近似）。 */
@Composable
internal fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(28.dp),
    color: Color = Warm.Glass,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.shadow(10.dp, shape, ambientColor = Warm.Coral.copy(alpha = 0.12f), spotColor = Warm.Coral.copy(alpha = 0.16f)),
        color = color,
        shape = shape,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.9f)),
        content = content,
    )
}

@Composable
internal fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (dark) Color.Black.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.72f))
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.25f else 0.9f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = if (dark) Color.White else Warm.Ink)
    }
}

/** 每秒刷新的当前时间。 */
@Composable
internal fun rememberNow(): LocalDateTime {
    var millis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L - System.currentTimeMillis() % 1_000L)
            millis = System.currentTimeMillis()
        }
    }
    return remember(millis / 1_000L) { LocalDateTime.now() }
}

private fun greetingFor(hour: Int): String = when (hour) {
    in 5..10 -> "早上好，今天也要元气满满哦"
    in 11..12 -> "中午好，记得好好吃饭"
    in 13..17 -> "下午好，累了就歇一会儿吧"
    in 18..22 -> "晚上好，今天过得怎么样？"
    else -> "夜深了，早点休息吧"
}

/**
 * 主屏待机：大时钟、小狮子、问候语、几张「可以这样对我说」的卡片、底部语音胶囊。
 * 一轮对话开始后由 [ConversationHeader] + 聊天流接管，结束一段时间后回到这里。
 */
@Composable
internal fun HomeIdleView(
    state: UiState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = rememberNow()
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = now.format(DateTimeFormatter.ofPattern("HH:mm")),
                    color = Warm.Ink,
                    fontSize = 72.sp,
                    lineHeight = 76.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${now.monthValue}月${now.dayOfMonth}日  " +
                        now.dayOfWeek.getDisplayName(JavaTextStyle.FULL, Locale.CHINA),
                    color = Warm.InkSoft,
                    fontSize = 18.sp,
                )
            }
            RoundIconButton(icon = Icons.Default.Settings, contentDescription = "设置", onClick = onOpenSettings)
        }

        Spacer(modifier = Modifier.weight(1f))
        MascotHero(
            mood = mascotMood(state, now.hour),
            size = 400.dp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = greetingFor(now.hour),
            color = Warm.Ink,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.weight(1f))
        SuggestionCards(state = state)
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun SuggestionCards(state: UiState) {
    val wakeWord = state.roleProfiles.firstOrNull { it.id == state.activeRoleId }
        ?.wakeWords?.firstOrNull() ?: "小智小智"
    val nextTask: ScheduledTaskUi? = state.scheduledTasks.firstOrNull()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SuggestionCard(
            icon = Icons.Default.NotificationsActive,
            tint = Warm.Coral,
            title = "今日提醒",
            body = nextTask?.message ?: "还没有提醒",
            hint = if (nextTask != null) "${nextTask.kind} · ${nextTask.status}" else "试试「10分钟后叫我喝水」",
            background = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.85f), Warm.CoralSoft.copy(alpha = 0.7f))),
        )
        SuggestionCard(
            icon = Icons.Default.AutoStories,
            tint = Warm.Mint,
            title = "讲个故事",
            body = "睡前故事、冷知识",
            hint = "说「$wakeWord，讲个故事」",
            background = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.85f), Color(0xFFDDF3E8).copy(alpha = 0.8f))),
        )
        SuggestionCard(
            icon = Icons.Default.PhotoCamera,
            tint = Warm.Mist,
            title = "看看我",
            body = "让它用摄像头看一眼",
            hint = "说「看看我在干嘛」",
            background = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.85f), Color(0xFFE3EDF8).copy(alpha = 0.85f))),
        )
    }
}

@Composable
private fun SuggestionCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    body: String,
    hint: String,
    background: Brush,
) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier = Modifier
            .width(232.dp)
            .height(158.dp)
            .shadow(8.dp, shape, ambientColor = Warm.Coral.copy(alpha = 0.10f), spotColor = Warm.Coral.copy(alpha = 0.14f))
            .clip(shape)
            .background(background)
            .border(1.dp, Color.White.copy(alpha = 0.9f), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(title, color = Warm.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(body, color = Warm.Ink, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(hint, color = Warm.InkSoft, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 对话中的头部：左上角色名，右上设置，中间小狮子 + 声波 + 状态。 */
@Composable
internal fun ConversationHeader(
    state: UiState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mood = mascotMood(state)
    Box(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.align(Alignment.TopStart)) {
            Text(state.activeRoleName.ifBlank { "小智" }, color = Warm.Coral, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("你的桌面 AI 陪伴", color = Warm.InkSoft, fontSize = 14.sp)
        }
        RoundIconButton(
            icon = Icons.Default.Settings,
            contentDescription = "设置",
            onClick = onOpenSettings,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        Column(
            modifier = Modifier.align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MascotHero(mood = mood, size = 168.dp, showHalo = false)
            VoiceWave(
                active = state.isAssistantSpeaking || state.isRecording,
                intense = state.isAssistantSpeaking,
                modifier = Modifier
                    .width(300.dp)
                    .height(42.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(mood.label, color = Warm.InkSoft, fontSize = 15.sp)
        }
    }
}

/**
 * 底部语音胶囊：左边麦克风状态，中间字幕，右边「打断」。
 * 字幕规则沿用旧 ComposerCard：麦克风掉线 > 播报 > 聆听 > 最后一句 > 唤醒提示。
 */
@Composable
internal fun VoicePill(
    state: UiState,
    onAbortSpeaking: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    statusOnly: Boolean = false,
    homeIdle: Boolean = false,
) {
    val wakeWord = state.roleProfiles.firstOrNull { it.id == state.activeRoleId }?.wakeWords?.firstOrNull()
    // 字幕优先级（每一条都是踩过的坑，别随手调顺序）：
    //   * 麦克风掉线盖过一切：此时设备已经聋了，别的文案都是假的（实测 USB 摄像头 error -71）。
    //   * 播报排在聆听前面：播报期间麦克风仍开着（为了语音打断），isRecording 也为 true，
    //     顺序反了会出现「顶上写讲话中、底下写正在聆听」（2026-09-21 用户报过）。
    //   * 待机且没说过话时必须写出唤醒词：屏幕上没有手动入口，唤醒词是唯一的开始方式。
    val subtitle = when {
        !state.microphoneReady -> "⚠ ${state.microphoneMessage.ifBlank { "没有检测到摄像头麦克风，请检查 USB 连接" }}"
        // 数字人画面上方已有大字幕卡，这里只报状态，不把同一句话再念一遍
        statusOnly -> mascotMood(state).label
        state.isAssistantSpeaking -> state.lastTtsText.ifBlank { "正在播报…" }
        state.isRecording -> state.lastSttText.ifBlank { "正在聆听…" }
        // 主屏待机时不挂上一轮的旧回答：时钟屏上留着一句过时的话很怪
        state.lastTtsText.isNotBlank() && !homeIdle -> state.lastTtsText
        wakeWord != null -> "说「$wakeWord」叫我"
        else -> ""
    }
    val listening = state.isRecording && !state.isAssistantSpeaking
    val transition = rememberInfiniteTransition(label = "pill")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val shape = RoundedCornerShape(40.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(14.dp, shape, ambientColor = Warm.Coral.copy(alpha = 0.14f), spotColor = Warm.Coral.copy(alpha = 0.2f))
            .clip(shape)
            .background(if (dark) Color.Black.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.86f))
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.2f else 0.95f), shape)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .graphicsLayer {
                    val s = if (listening) pulse else 1f
                    scaleX = s
                    scaleY = s
                }
                .clip(CircleShape)
                .background(if (state.microphoneReady) Warm.CoralGradient else Brush.linearGradient(listOf(Color.Gray, Color.Gray))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state.microphoneReady) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            text = subtitle,
            color = when {
                !state.microphoneReady -> Warm.Alert
                dark -> Color.White
                state.isAssistantSpeaking -> Warm.Ink
                else -> Warm.InkSoft
            },
            fontSize = 17.sp,
            fontWeight = if (!state.microphoneReady) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 打断：最右边一颗红圆键，常驻、只在播报时亮起。
        // 位置和尺寸是两次反馈的折中（角落小按钮「不好按」→ 整行大按钮「太占地方」），
        // 待机时保留淡红底，用户平时就知道它在这儿，播报时一亮起来肌肉记忆立刻接上。
        val speaking = state.isAssistantSpeaking
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (speaking) Warm.Alert else Warm.Alert.copy(alpha = if (dark) 0.28f else 0.12f))
                .clickable(enabled = speaking, onClick = onAbortSpeaking),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "打断",
                tint = if (speaking) Color.White else Warm.Alert,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** 数字人画面左上角：角色名 + 在线小圆点。 */
@Composable
internal fun DigitalHumanNameChip(name: String, online: Boolean, status: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color.Black.copy(alpha = 0.30f))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(28.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (online) Warm.Mint else Color(0xFFBDBDBD)),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(status, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
    }
}

/** 数字人画面下方的字幕卡：上面一行小字是用户刚说的，下面大字是角色正在说的。 */
@Composable
internal fun DigitalHumanSubtitleCard(state: UiState, modifier: Modifier = Modifier) {
    val spoken = state.lastTtsText
    val heard = state.lastSttText
    if (spoken.isBlank() && heard.isBlank()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(Color.Black.copy(alpha = 0.40f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(30.dp))
            .padding(horizontal = 26.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VoiceWave(
            active = state.isAssistantSpeaking || state.isRecording,
            intense = state.isAssistantSpeaking,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
        )
        if (heard.isNotBlank()) {
            Text(heard, color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (spoken.isNotBlank()) {
            Text(
                spoken,
                color = Color.White,
                fontSize = 26.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
