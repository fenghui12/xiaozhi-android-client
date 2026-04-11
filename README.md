# Xiaozhi Android Client

一个面向 `小智 AI` 官方服务的 Android 客户端，基于官方 OTA + WebSocket 协议接入，支持语音对话、文本输入、激活流程、Termux/Python 扩展以及 MCP 调试。

> 当前仓库聚焦 Android 客户端，不包含官方服务端。

## 功能

- 对接官方 OTA 配置接口并获取实时会话参数
- 支持设备激活码展示与官方控制台激活流程
- WebSocket 实时会话握手与 `hello / listen / abort / mcp` 消息流
- 麦克风采集、Opus 编码、服务端音频解码与播放
- 文本聊天、按住说话、实时监听、唤醒词监听
- 自定义助手头像
- 设置页统一管理官方接入、语音体验、本地扩展和开发者工具
- 通过 Termux 运行 Python 文件或 `termux-api` 命令，便于接入 MCP

## 环境要求

- Android 8.0+
- Android Studio Jellyfish 或更高
- JDK 17
- 已安装 Android SDK 34

## 构建

```powershell
cd xiaozhi-android-client
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

如果需要生成你自己的正式签名包，先复制一份：

```text
.release-signing.local.example -> .release-signing.local
```

然后把本地 keystore 路径和密码填进去，再执行 `assembleRelease`。

调试包输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release 包输出：

```text
app/build/outputs/apk/release/app-release-signed.apk
```

## 安装与激活

1. 安装 APK 并打开应用
2. 首次进入后获取官方配置
3. 如果界面显示激活码，到 `https://xiaozhi.me` 控制台完成激活
4. 激活完成后开始文本或语音对话

## Termux / Python

如果你要在手机上跑 Python 或 MCP：

1. 安装 `Termux`
2. 在 Termux 中允许外部应用调用
3. 在应用设置页填写 Python 路径、脚本路径或 `termux-api` 命令
4. 由应用发起执行，并在日志中查看结果

## 项目结构

```text
app/src/main/java/me/xiaozhi/androidclient/
  audio/         音频采集、Opus 编解码、播放链路
  data/          本地配置持久化
  integration/   Termux / Python / 本地扩展
  model/         UI 状态和数据模型
  network/       OTA、激活、WebSocket 实时协议
  ui/            Compose 页面和 ViewModel
```

## 发布

已构建的安装包会放在 GitHub Releases。

## 协议说明

本项目实现的是兼容官方接入流程的客户端逻辑，包括：

- OTA 获取 `websocket.url / token / version`
- 必要时的激活轮询
- WebSocket 握手头 `Authorization / Protocol-Version / Device-Id / Client-Id`
- 会话 `session_id` 管理

## License

MIT
