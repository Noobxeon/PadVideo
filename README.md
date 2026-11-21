# PadVideo — 安卓平板商业级智能双语视频播放器

**PadVideo** 是一款专为 **Android 平板与大屏设备** 打造的现代商业级本地与串流视频播放器，全面遵循 **Material Design 3 (Material You)** 大屏自适应规范构建，深度集成 **多层级文件浏览器、全手势专业播放控制、实时双语字幕翻译/语音同传** 以及 **多源在线字幕聚合搜索**。

---

## ✨ 核心特性

### 1. 专为平板与大屏设计的 Material Design 3 体验
- **大屏自适应布局**：在平板横屏/宽屏（`≥600dp`）下自动采用左侧 `NavigationRail` 导航栏，在超大屏（`≥840dp`）媒体库下启用 **双栏主从详情预览面板（List-Detail Canonical Layout）**，分屏或竖屏窄窗口下平滑切换底部导航栏。
- **Material You 动态色彩与 OLED 纯黑主题**：支持 Android 12+ 系统壁纸动态取色、深色影院主题、浅色模式以及专为 OLED 平板优化的 **纯黑省电模式**。
- **画中画小窗（PiP）与外接键盘快捷键**：支持返回桌面自动进入画中画悬浮窗；完整适配平板蓝牙键盘与磁吸妙控键盘快捷键（`Space`/`K` 播放暂停、`←`/`→` 快进退、`↑`/`↓` 音量、`[`/`]` 调倍速、`C` 字幕开关、`T` 切换实时双语翻译、`Esc` 退出）。

### 2. 全功能平板文件浏览器与视频媒体库
- **双模浏览**：既提供自动聚合分辨率标签（`4K UHD` / `1080P`）、缩略图缓存与观看进度条的 **智能媒体库**，也提供完整的 **层级目录文件浏览器**。
- **本地浏览**：支持内部存储快捷跳转、交互式路径面包屑导航、目录收藏夹、自然数字剧集排序（`Episode 2` 排在 `Episode 10` 之前）、搜索过滤，以及通过 **SAF 目录树挂载 USB-C U盘 / 移动硬盘** 与系统级「打开方式」直连播放。
- **网络浏览**：在「文件浏览 → 网络」输入 `smb://服务器/共享目录`，可选填账号、密码和域。支持 SMB 2/3 共享的目录浏览、过滤、直接播放、拖动进度与同目录选集。账号和密码保存在当前应用进程中。网络页也能打开 HTTP、HTTPS 和 RTSP 视频地址。
- **连接 Windows 共享**：可以直接粘贴资源管理器里的 `\\PC\Movies`，也可以用 `smb://192.168.1.20/Movies`。本地账号可填 `PC\用户名`，使用该账号的密码。Windows Hello PIN 用于本机登录；网络共享使用账号密码。填写了账号前缀时，域可留空。
- **外挂字幕自动关联**：打开视频时自动扫描并挂载同目录下同名 `.srt` / `.ass` / `.ssa` / `.vtt` 外挂字幕文件。

### 3. 商业级专业播放控制（Media3 ExoPlayer 内核）
- **全手势直觉交互（带居中毛玻璃 HUD 指示器）**：
  - **左半屏上下滑动**：无级调节屏幕亮度（`2%–100%`）
  - **右半屏上下滑动**：调节音量并支持 **最高 200% 音频响度增强（LoudnessEnhancer DSP）**，轻松听清平板扬声器下的低音量电影对白
  - **水平滑动**：毫秒级精准进度预览与跳转
  - **左 / 中 / 右区域双击**：快退 `-10s`（支持连击累加）/ 播放暂停 / 快进 `+10s`
  - **单指长按**：立即触发 `2.0x`（可调至 `4.0x`）极速倍速播放，松手即恢复原速
  - **双指捏合与拖拽**：支持 `0.5x–4.0x` 自由画面缩放与平移浏览
- **专业影院工具箱**：
  - **A-B 区间循环复读**：一键标记 A 点与 B 点循环精听台词，外语学习利器
  - **平板分栏交互式双语台词本**：右侧呼出完整字幕时间轴抽屉，实时高亮当前对白，**点击任意一句台词即可瞬间跳转播放**，并支持一键导出双语 `.srt`
  - **画面比例与几何变换**：自适应 / 铺满裁剪 / 拉伸满屏 / `16:9` / `4:3` / `21:9`，支持 **90° 逐级旋转** 与 **水平镜像翻转**（舞蹈/教程跟练）
  - **防误触屏幕锁、视频帧相册截图、时间戳书签、同目录剧集自动连播选集、多音轨切换、字幕同步微调（`±30s`）与睡眠定时器**

### 4. 实时双语字幕翻译 & 无字幕实时语音同传
- **播放指针前瞻预翻译（Lookahead Sliding Window）**：提前在后台批量预翻译播放位置后方对白，结合 **内存 + 磁盘二级 LRU 缓存**，快退重听与 A-B 复读零延迟、零重复请求。
- **五种翻译引擎自由切换与自动容灾**：
  1. **智能云端双引擎（默认免配置）**：自动调度 `Google GTX` 与 `MyMemory` 全球翻译接口，无需任何 API Key，国内海外网络自动无缝容灾。
  2. **Google ML Kit 端侧离线神经翻译**：在播放器的翻译面板选择 **ML Kit 端侧离线翻译**，选好源语言和目标语言后点击 **下载离线模型**。模型就绪后，打开 **双语对照** 或 **仅显示目标译文** 即可断网翻译外挂或内嵌文本字幕。
  3. **自定义 AI 大模型精翻（OpenAI / DeepSeek / 通义千问 / Ollama）**：结合前后句语境批量地道意译影视俚语与台词。
- **无字幕生肉视频实时语音识别同传**：针对完全没有内嵌或外挂字幕的视频，可通过麦克风和 Android 系统语音识别服务识别现场声音，再实时翻译并导出 `.srt`。离线语音同传需要支持端侧语音识别的 Android 12 或更新版本，以及系统已安装的语音模型。

### 5. 多源聚合在线字幕搜索与智能转码
- **智能片名解析与文件哈希匹配**：自动剥离 `2160p.BluRay.x265` 等发布组标签提取纯净片名、年份与 `SxxExx` 季集号；支持中文搜索词自动译为英文检索全球库，并计算本地视频 **射手 4 段 MD5 哈希** 与 **OpenSubtitles 64-bit MovieHash**。
- **开箱即用多源并行检索**：默认内置免密钥 **射手影音哈希直连** 与 **OpenSubtitles 全球开放索引**，同时支持在设置中扩展配置 **伪射手网（Assrt）**、**OpenSubtitles REST API v1** 与 **SubDL**。
- **压缩包直解与零乱码保证**：自动在内存中解压 `.zip` / `.gz` 字幕包，精准探测 `UTF-8` / `UTF-16` / `GB18030 (GBK)` / `Big5` 编码并统一转换为 UTF-8；外语字幕还支持在搜索面板一键 **「生成双语」** 挂载！

---

## 🏗 工程架构

```text
app/src/main/java/com/padvideo/
├── MainActivity.kt                          # Edge-to-Edge、系统视频打开 Intent、PiP 画中画生命周期管理
├── PadVideoApplication.kt                   # 轻量级依赖注入容器 AppContainer
├── data/
│   ├── model/                               # 视频、字幕、翻译引擎、商业播放控制强类型模型
│   ├── preferences/AppSettingsRepository.kt # 设置、断点续播历史、收藏目录、SAF 挂载与视频书签持久化
│   └── repository/
│       ├── FileExplorerRepository.kt        # MediaStore 扫描 + 本地目录/SAF 浏览 + 自然排序 + 缩略图缓存
│       ├── OnlineSubtitleRepository.kt      # 多源并发字幕检索、射手/OS 文件哈希、ZIP/GZ 解压落盘
│       └── RealtimeTranslationRepository.kt # 多引擎翻译容灾、AI 大模型批量翻译与二级持久化缓存
├── player/
│   ├── PadPlayerController.kt               # Media3 ExoPlayer + MediaSession + 200% 响度增益 + 视频静帧抓拍
│   └── LiveSpeechCaptionEngine.kt           # 无字幕视频连续语音识别同传引擎
├── subtitle/
│   ├── EncodingDetector.kt                  # BOM + UTF-8 严格校验 + GB18030/Big5 编码自动识别
│   ├── SubtitleParser.kt                    # SRT / WebVTT / ASS 全格式解析、二分时间轴检索与双语导出
│   └── VideoFilenameParser.kt               # 影视文件名清洗与 64-bit MovieHash / 射手 MD5 哈希算法
└── ui/
    ├── PadVideoApp.kt                       # 平板 NavigationRail / NavigationBar 自适应根容器
    ├── MainViewModel.kt / PlayerViewModel.kt# 响应式单向数据流状态管理
    ├── screens/                             # 媒体库、文件浏览器、历史与网络串流、设置中心、商业级播放器主屏
    ├── dialogs/                             # 在线字幕搜索面板、实时翻译中心、倍速/音轨/字幕偏移/选集书签面板
    └── theme/                               # Material Design 3 影院配色与动态主题
```

---

## 🧪 构建与自动化测试

环境要求：**JDK 17**、**Android SDK 35**。

```bash
# 运行全部单元测试、MockWebServer 网络集成测试与 Robolectric 平板 Compose UI 测试
bash gradlew testDebugUnitTest

# 运行 Android Lint 静态检查
bash gradlew lintDebug

# 构建 Debug 与混淆签名版 Release APK
bash gradlew assembleDebug assembleRelease
```

输出安装包位于以下目录。
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release.apk`

从 v1.0.0 更新到 v1.1.x 时需先卸载旧版，卸载会清除旧版的应用内设置和观看记录。v1.1.1 与 v1.1.0 使用同一张发布证书，可覆盖安装。

在 GitHub Actions 发布时，通过仓库 Secrets 配置 `PADVIDEO_KEYSTORE_BASE64`、`PADVIDEO_STORE_PASSWORD`、`PADVIDEO_KEY_ALIAS` 和 `PADVIDEO_KEY_PASSWORD`。在 `main` 运行 Android CI 并勾选 `publish` 后，工作流会先测试，再验证 APK 签名并发布。

---

## 📄 开源协议

本项目基于 [MIT License](LICENSE) 开源发布。
