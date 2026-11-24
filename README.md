# Zai

精简、轻量的 Android 手机 AI 操控客户端，专为单机高效操控手机设计。

## 核心特点

- **无 Linux / 无 PRoot**：基于原版 Pi（`@earendil-works/pi-ai` + `@earendil-works/pi-agent-core`）打包为单个浏览器兼容脚本（~1.1 MB），直接运行在 Android 隐藏 WebView 的 V8 引擎中，单进程启动快、不因内存回收轻易断联。
- **双渠道接入模型**：
  - **ChatGPT 订阅账号登录**：通过 Pi 原生 OAuth PKCE 流与本地回调服务直接登录，凭据经 Android Keystore AES-256-GCM 加密存储。
  - **自定义接口自动找模型**：输入任意 OpenAI 兼容 / Responses / Anthropic 接口地址与 API Key，一键请求 `/models` 自动发现可用模型并匹配上下文与看图能力。
- **防掉权双通道控屏**：
  - **Shizuku 优先（UID 2000）**：通过 Shizuku `UserService` 直接执行点击、滑动、按键、中文粘贴输入、截屏与 `uiautomator dump`，绕开国产 ROM 安全中心对无障碍服务的风控拦截。
  - **无障碍快速读屏 + 自动恢复**：使用无障碍极速读取控件树与输入中文；若无障碍被系统关闭或脱绑，支持通过 Shizuku 一键或任务中自动重新拉起。
- **混合屏幕感知**：默认传精简编号控件树 `[1] [2] [3]...` 省 Token 且 100% 精准点击；在网页/小程序/控件稀疏或模型调用 `screenshot` 时，自动附带 **Set-of-Mark 标号框截图**，并自动裁剪历史旧截图防上下文膨胀。
- **应用内在线更新 + Pi 内核热更新**：
  - 启动时静默检查 GitHub Releases（默认 `haaharden/Zai`），支持在 **设置 → 应用更新与内核热更** 中直接下载固定证书签名的 Release APK 覆盖升级（支持系统安装器与 Shizuku 快捷安装）。
  - 支持 **免重装热更新 Pi 内核**（直接从 Release 拉取 `zai-pi-host.js` 并在 1 秒内重载隐藏 WebView 生效），无需重装 APK 即可更新模型适配、ChatGPT OAuth 与 Agent 逻辑。
- **适配小米 / HyperOS**：内置纯黑 OLED 主题、悬浮停止胶囊（截屏时自动避让）、省电白名单/自启动/后台弹出界面直达入口。

## 下载与更新

1. 前往 [GitHub Releases](https://github.com/haaharden/Zai/releases/latest) 下载 `Zai-v*.apk` 直接安装。
2. 后续升级打开 **Zai → 设置 → 应用更新与内核热更 → 检查更新**：
   - 有新版本 APK 时点 **「下载并更新」**（或 **「Shizuku 装」**）直接覆盖安装，保留全部设置、密钥与对话记录。
   - 若仅更新了 Pi 内核脚本，点 **「免重装热更新 Pi 内核」** 即可秒级生效。

## 本地构建

### 1. 构建 Pi 运行时（修改 `pi-host/` 后需要）

```powershell
cd pi-host
npm install --ignore-scripts
npm run build
```

产物自动输出至 `app/src/main/assets/pi/host.js` 与 `index.html`。

### 2. 编译签名 Release APK

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease
```

Release APK 位于：`app\build\outputs\apk\release\app-release.apk`。
