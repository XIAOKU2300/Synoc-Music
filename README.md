# Synoc Music

[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![GitHub](https://img.shields.io/badge/GitHub-XIAOKU2300-181717?logo=github)](https://github.com/XIAOKU2300)
[![QQ群](https://img.shields.io/badge/QQ%E7%BE%A4-1124680973-12B7F5?logo=tencentqq&logoColor=white)](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=9PFsj_0lDY35JcGVKDF-Znb9o7asAEfy&authKey=bqRpzOa1%2F%2Ba6RU%2FnMGWV1LcBFzEYglGtreTuj4SfUF257lneWWeaDUrPocrAQirE&noverify=0&group_code=1124680973)
[![Upstream](https://img.shields.io/badge/fork%20of-MeloX--Android-ff2d55)](https://github.com/lladlam/MeloX-Android)

<p align="center">
  <img src="android/app/src/main/res/drawable-nodpi/ic_launcher_foreground.png" width="128" alt="Synoc Music icon" />
</p>

<p align="center">
  基于 MeloX Android 的非官方社区分支 —— 用手机控制你的山灵（Shanling）播放器
</p>

> [!IMPORTANT]
> **Synoc Music 是非官方开源项目**，与山灵（Shanling）、网易云音乐、QQ 音乐、小米、Apple 等公司及其关联方均不存在隶属、合作或授权关系。SyncLink 相关功能**仅供控制你本人拥有的设备**。

## 当前版本：0.7.0

`0.7.0` 是 Synoc Music 的首个版本：在 MeloX Android 0.6.0 基础上完整接入山灵 SyncLink（Wi-Fi / 蓝牙），并修复后台灵动岛不切歌、文件夹无法显示存储等问题。详见 [CHANGELOG.md](CHANGELOG.md)。

- 维护者：GitHub [XIAOKU2300](https://github.com/XIAOKU2300)
- 交流与反馈：QQ 群「山灵音乐逆向反馈群」**1124680973**（[点击链接加入群聊](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=9PFsj_0lDY35JcGVKDF-Znb9o7asAEfy&authKey=bqRpzOa1%2F%2Ba6RU%2FnMGWV1LcBFzEYglGtreTuj4SfUF257lneWWeaDUrPocrAQirE&noverify=0&group_code=1124680973)）
- 上游项目：[lladlam/MeloX-Android](https://github.com/lladlam/MeloX-Android)（GPLv3）。**MeloX 本体的问题请优先反馈到上游**；SyncLink 相关问题请在本项目或 QQ 群反馈，不要打扰上游作者。

## 山灵 SyncLink 功能

入口：设置 → 账号卡片下方「山灵 SyncLink」。连接成功后，App 首页会切换为**设备首页**，显示设备里的歌单和随机歌曲。

| 功能 | 说明 |
| --- | --- |
| 连接 | 局域网（Wi-Fi）自动发现 / 手动 IP；经典蓝牙 RFCOMM（M0 等 Linux 机型），未配对设备点击即配对 |
| 控制 | 播放 / 暂停、上一首 / 下一首、进度拖动、播放模式、音量（LO 输出时自动禁用）、收藏 |
| 曲库 | 全部歌曲、Hi-Res、收藏、最近播放、专辑、艺术家、流派、歌单、文件夹（显示存储已用 / 剩余空间）、搜索、播放队列 |
| 系统集成 | 通知栏 / 锁屏 / 耳机按键 / 蓝牙车机直接控制播放器；HyperOS 超级岛、vivo 原子岛与歌词通知同步显示设备正在播放的歌曲和歌词 |
| 歌词与封面 | 按标题 / 艺术家 / 专辑 / 时长在线严格匹配，支持逐字歌词与翻译；本地缓存与批量预下载 |
| 音质显示 | `FLAC 24/96`、`DSD128`、`MP3 320k` 等 |
| 稳定性 | 后台保活、断线自动退避重连，重连期间保持媒体通知不丢失；被其他手机抢占时不互踢 |

为安全起见，**以下高风险指令在代码中完全没有实现**：关机、恢复出厂、固件升级（OTA）、删除配对、设置 / 读取设备 Wi-Fi。

## 以下为上游 MeloX Android 说明

> 以下内容保留自上游 README，描述的是 Synoc Music 所继承的 MeloX 本体功能。

## 项目说明

MeloX Android 基于 [lladlam/MeloX](https://github.com/lladlam/MeloX) 的设计、交互与业务逻辑进行原生 Android 迁移。

项目目标不是使用 WebView 套壳，而是尽可能使用 Android 原生能力重新实现 MeloX：

- 使用 **Kotlin + Jetpack Compose** 重建界面与交互；
- 使用 **AndroidX Media3 / ExoPlayer** 实现播放、后台音频与系统媒体会话；
- 将 iOS 独有能力映射到 Android 平台能力；
- 在小米 HyperOS 上提供可选的焦点通知 / 超级岛适配，同时保持普通 Android 设备可用；
- 尽可能复刻 MeloX / Apple Music 风格的播放器、歌词、音乐库与过渡动画；
- Apple Watch 相关功能不在 Android 版本的兼容范围内。

Root 权限不是应用正常运行的必要条件；平台增强功能应尽量通过标准 Android 或 OEM 能力实现，并在不支持时降级。

## 当前已实现

### 账号与网易云音乐

- 网易云音乐网页登录；
- `MUSIC_U` Cookie 登录态持久化；
- 登录态搜索、歌曲详情、歌词、播放 URL 与音乐库请求；
- 账号权限变化后刷新播放资源缓存。

### 搜索与音乐库

- 歌曲、歌单、专辑、艺人、用户与播客搜索；
- 我喜欢的音乐；
- 用户歌单；
- 创建公开/私密歌单、向歌单添加歌曲及从自己的歌单移除歌曲；
- 最近播放；
- 歌单、专辑、艺人、歌曲百科与评论详情；
- 排行榜、首页推荐、每日推荐、私人 FM 与心动模式；
- 播客首页、分类、订阅与节目详情；
- 网易云音乐云盘读取、搜索、上传、播放与删除；
- 单曲/集合多选下载、逐次音质选择、离线歌词、自动缓存与存储修复；
- 保留 app-private 离线副本，并可导出到 MediaStore；本地库可按歌曲、艺术家、专辑和文件夹浏览；
- 从音乐库 / 搜索结果直接播放。

### 播放器

- MiniPlayer；
- Apple Music / Classic 两套全屏播放器外壳；
- 播放队列；
- 下一首播放、手动队列与队列排序；
- 播放 / 暂停、上一首 / 下一首、进度控制；
- 后台播放；
- MediaSession 与系统媒体控制；
- 锁屏播放信息；
- 封面与动态取色背景；
- AutoMix 双播放器预载、音频分析、过渡规划、节拍/速度匹配、淡化与 EQ 包络；
- 31 Hz–16 kHz 十段均衡器、完整预设、系统/播放器音量模式与睡眠定时；
- 播放器共享元素、展开 / 收回动画与横屏播放器。

### 音质

当前按照 MeloX 的音质模型接入网易云音乐播放接口：

- 标准：`standard`
- 高品质：`exhigh`
- 无损：`lossless`
- Hi-Res：`hires`
- 高清环绕声：`jyeffect`
- 沉浸环绕声：`sky`
- 超清母带：`jymaster`

实际可播放音质由**歌曲资源、账号权益、地区以及网易云音乐服务端返回结果**共同决定。请求的目标音质不可用时会按可用资源进行降级，并尽量显示服务端实际返回的音质等级。

### 歌词

- LRC 逐行歌词；
- YRC 官方逐字歌词；
- 当前行跟随与滚动；
- 逐字进度渲染；
- 当前行焦点、缩放与颜色过渡；
- Ruby 注音布局、翻译、间奏倒计时、点击跳转与长按分享；
- Apple Music 拖尾滚动、回弹、模糊、高光、长音与刷新率参数；
- EVA 动态构图、18 套具有独立构图/背景/排版/运动参数的 TextPV 模板；
- Skyline 横屏歌词与完整环境文字参数；
- 系统媒体歌词、独立歌词通知和可拖动悬浮歌词。

### HyperOS

- 标准 Android 媒体通知与 MediaSession；
- 可选 HyperOS 焦点通知 / 超级岛桥接；
- OEM 特性通过独立适配层实现，不作为普通 Android 设备的硬依赖。

### 社交、识曲与应用设置

- 一起听房间创建、加入、成员、进度和队列同步；
- 联系人、会话、文字私信与站内分享；
- 麦克风录音、网易云音频指纹、分段与持续听歌识曲；
- 首次启动引导、剪贴板链接识别、页面/首页排序与内容功能开关；
- GitHub 版本检查、更新提示、项目许可与设置重置。

### 第三方音乐源

- 可在“音乐服务”中单独开启第三方音乐源设置并阅读专用协议；该功能完全由本地开关控制，不属于云控范围。
- YouTube Music 接入基于 [lladlam/Square](https://github.com/lladlam/Square) 的双后端架构；InnerTube 模块已 vendored 到 `android/innertube/`，其源自 [Metrolist](https://github.com/mostafaalagamy/Metrolist)，并保留 `com.metrolist.innertube` 包名。
- Spotify 接入参考 [lladlam/Square](https://github.com/lladlam/Square) 的 Spotify backend 设计，播放引擎使用 `librespot-java`，目录和 OAuth 使用 Spotify Web API；Client ID 由用户在应用内自行填写。
- Spotify、YouTube Music 与网易云等源共用同一套 provider-neutral 下载与离线播放链路（`MeloXProviderDownloadStore`）。
- vivo 设备自动尝试接入 OriginOS 原子岛；不提供单独开关，不支持该能力的设备自动保留标准 Android 媒体通知。该适配使用阿里云文档所述的 `notification.superx.*` 本地通知扩展，不依赖 EMAS 远程推送。
- 支持导入 LX Music 兼容的 JavaScript 音乐源，并在受限 QuickJS 运行时中解析播放地址。
- 可配置 CHKSZ 个人 API Key，优先解析网易云、QQ音乐和酷狗歌曲；配置前需要前往 `api.chksz.com` 注册，目前仅支持 LinuxDo 用户注册。
- 第三方解析失败时才回退到对应平台的原生播放；歌词仍由 MeloX 自己的歌词路线处理。

## 平台范围

iOS Live Activity / Dynamic Island 已映射为 Android 媒体通知、歌词通知和可选 HyperOS 焦点通知；iOS 画中画歌词已映射为 Android 悬浮窗歌词。Apple Watch、watchOS 和 macOS 专属目标不属于 Android APK 的迁移范围。

Liquid Glass 在 Android 上使用 Kyant0 `AndroidLiquidGlass` 的原生 Backdrop 实现；具体效果仍受 Android 版本、GPU 与 OEM 合成器能力影响。

## 技术栈

| 用途 | Android 实现 |
| --- | --- |
| UI | Kotlin + Jetpack Compose |
| 导航 / 生命周期 | AndroidX Navigation / Lifecycle |
| 音频播放 | AndroidX Media3 / ExoPlayer |
| 系统媒体控制 | MediaSession / MediaSessionService |
| 网络请求 | OkHttp |
| 图片 / 封面加载 | Coil 3 |
| 异步任务 | Kotlin Coroutines |
| 玻璃 / Backdrop | Kyant0 `AndroidLiquidGlass` / `backdrop` |
| 小米平台增强 | HyperOS Focus Notification 适配层 |

## iOS → Android 平台映射

| MeloX / iOS | MeloX Android |
| --- | --- |
| SwiftUI | Jetpack Compose |
| NavigationStack | Navigation Compose |
| AVPlayer / AVFoundation | Media3 ExoPlayer |
| MPNowPlayingInfoCenter / Remote Commands | MediaSession |
| Live Activity / Dynamic Island | 标准媒体通知 + 可选 HyperOS 焦点通知 / 超级岛 |
| SwiftUI Mesh / Flowing Light | Compose Canvas / 动态取色背景 |
| Apple Liquid Glass | AndroidLiquidGlass Backdrop 折射、模糊与降级实现 |

## 运行环境

- Android 8.0（API 26）或更高版本；
- Android SDK 37；
- JDK 17；
- Gradle 9.5.0；
- 推荐使用当前版本 Android Studio 打开 `android/` 目录。

部分 RuntimeShader / Backdrop 视觉能力仅在较新的 Android 版本上可用；旧版本会使用降级样式。

## 本地构建

1. 克隆仓库：

   ```bash
   git clone https://github.com/lladlam/MeloX-Android.git
   cd MeloX-Android
   ```

2. 使用 Android Studio 打开：

   ```text
   MeloX-Android/android
   ```

3. 安装 Android SDK 37，并确保使用 JDK 17。

4. 也可以直接使用 Gradle 构建 Debug APK：

   ```bash
   cd android
   ./gradlew :app:assembleDebug --no-daemon --max-workers=2 --stacktrace
   ```

5. 构建产物位于：

   ```text
   android/app/build/outputs/apk/debug/app-debug.apk
   ```

GitHub Actions 会在 `main` 分支代码更新时自动构建 Debug APK。正式提供给用户的开发版 APK 会使用项目发布密钥签名，并随对应版本号的 GitHub Release 发布。

## Release 签名验证

GitHub Releases 中的正式 APK 使用 MeloX 发布证书签名。证书 SHA-256 指纹为：

```text
DF:CC:A9:86:5B:87:A4:02:D3:41:98:5A:48:EB:13:2B:D8:67:9D:FA:6D:9D:50:2F:36:5D:D1:62:10:A5:EB:E9
```

可使用 Android SDK Build Tools 验证：

```bash
apksigner verify --verbose --print-certs MeloX-Android-0.5.3.apk
```

## 项目结构

```text
.
├── android/
│   ├── app/
│   │   └── src/main/
│   │       ├── kotlin/com/lladlam/melox/
│   │       │   ├── core/          # 账号、网络、音质、歌词、音乐库模型
│   │       │   ├── playback/      # Media3 播放服务与播放资源解析
│   │       │   ├── platform/      # HyperOS 等平台适配
│   │       │   └── ui/            # Compose 页面、播放器、音乐库、玻璃效果
│   │       └── res/
│   ├── build.gradle.kts
│   ├── innertube/                  # Square/Metrolist InnerTube vendored module
│   └── settings.gradle.kts
├── .github/workflows/             # Android CI / Release
├── LICENSE
└── README.md
```

## 开源项目与特别鸣谢

MeloX Android 的主体代码来自 MeloX 的 Android 迁移工作，同时直接使用或参考了以下开源项目。**各项目仍分别遵循其自己的许可证；MeloX Android 的 GPLv3 不会替代第三方项目原有许可证。**

### 上游项目

- [lladlam/MeloX](https://github.com/lladlam/MeloX) — 本项目的上游 MeloX，实现、界面、交互与网易云音乐业务逻辑的主要迁移来源；主体采用 GPLv3。

### Android 直接依赖

- [AndroidX / Jetpack Compose](https://github.com/androidx/androidx) — Compose UI、Activity、Lifecycle、Navigation 等 Android 基础能力；主要使用 Apache License 2.0。
- [AndroidX Media3](https://github.com/androidx/media) — ExoPlayer、MediaSession 与后台播放；Apache License 2.0。
- [Coil](https://github.com/coil-kt/coil) — Compose 图片与封面加载；Apache License 2.0。
- [OkHttp](https://github.com/square/okhttp) — HTTP 网络客户端；Apache License 2.0。
- [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) — Kotlin 协程与异步任务；Apache License 2.0。
- `librespot-java` — Spotify 账号会话、播放和离线缓存；Apache License 2.0。
- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) — YouTube Music 匿名搜索和流地址解析；GPL-3.0。
- [Metrolist InnerTube](https://github.com/mostafaalagamy/Metrolist) — YouTube Music 登录态目录、账号库和歌单操作；GPL-3.0，模块已随本项目 vendored 于 `android/innertube/`。
- [Miuix](https://github.com/compose-miuix-ui/miuix) — 当前 Android Backdrop / Blur 实验实现使用 `miuix-blur`；Apache License 2.0。

### Liquid Glass / Backdrop 实现来源

Android 版直接依赖 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 的 `backdrop` 组件；`MeloXBackdropComponents.kt` 基于其官方 `LiquidButton` 与 `LiquidBottomTabs` 示例适配，在保留 MeloX iOS 尺寸和布局的前提下提供折射、模糊、按压高光与底栏选中透镜。

当前玻璃效果仍属于实验性实现，视觉与兼容性会继续调整。

### API 与 UI 参考项目

- [NEORUAA/MeiloX](https://github.com/NEORUAA/MeiloX) — 一个基于 Mei 的仿 Apple Music 网易云音乐客户端，为 MeloX Android 提供 UI 参考。
- [thlucas1/SpotifyWebApiPython](https://github.com/thlucas1/SpotifyWebApiPython) — Spotify Web API Python 客户端，为 Spotify API 接入提供参考。
- [bromothymolb/bilibili-api-zoku](https://github.com/bromothymolb/bilibili-api-zoku) — Bilibili API 调用与常用功能整合项目，为 Bilibili API 接入提供参考。

### 上游 MeloX 的参考来源

上游 MeloX 还特别鸣谢了以下项目；Android 迁移版在歌词 / 网易云接口等部分通过上游实现间接继承了这些设计与研究成果：

- [jayfunc/BetterLyrics](https://github.com/jayfunc/BetterLyrics) — 逐字歌词渲染、光效与动效参考；
- [WXRIW/Lyricify-Lyrics-Helper](https://github.com/WXRIW/Lyricify-Lyrics-Helper) — 网易云 YRC 逐字歌词解析参考；
- [qier222/YesPlayMusic](https://github.com/qier222/YesPlayMusic) — 网易云接口与播放器实现参考。

如后续 Android 版本迁移 PV Tool、BeatNet 或其他 MeloX 功能，将继续按照上游项目要求保留对应的独立许可证与署名。

## 免责声明

本项目出于学习、研究与开源交流目的开发，免费提供，不接受任何形式的售卖。

- Synoc Music / MeloX Android 不以绕过付费、版权、地区限制或任何音乐平台服务限制为目标；
- **山灵 SyncLink 兼容功能**基于对通信协议的自行分析，仅以实现互操作为目的，不包含、不分发山灵的任何代码、固件或素材，也不修改设备固件；请仅用于控制你本人合法拥有的播放器。“山灵”“Shanling”“SyncLink”为其权利人的商标，仅用于说明兼容对象；
- 在线匹配得到的歌词、封面版权归原作者与权利人所有，仅供个人欣赏，匹配结果可能不准确；
- 使用者应自行遵守所在地法律法规、各平台服务条款以及音乐内容的版权要求；
- 项目调用的第三方服务接口与设备协议可能发生变化，开发者不保证持续可用；
- 本项目按许可证所述不提供任何担保，使用本项目（包括因控制设备导致的异常、数据丢失或保修争议）产生的风险由使用者自行承担；
- 权利人如认为本项目侵犯其合法权益，请通过 GitHub 或上述 QQ 群联系，核实后会及时处理。

应用内完整条款见 `android/app/src/main/assets/legal/disclaimer-zh-CN.md`，首次启动时会弹窗确认。

## 许可证

Synoc Music 是 MeloX Android 的衍生作品，主体代码按照与上游相同的 **GNU General Public License version 3（GPLv3）** 发布，完整条款见 [LICENSE](LICENSE)。MeloX 原有代码版权归其原作者所有；SyncLink 相关新增代码（`core/synclink/`、`ui/synclink/`、`playback/SyncLinkPlayer.kt` 等）版权归 Synoc Music 贡献者所有，同样以 GPLv3 发布。

复制、修改或分发本项目时，请遵守 GPLv3 关于源代码提供、版权声明、修改说明以及同许可证分发等要求。

## 友情链接

- [LINUX DO](https://linux.do/) — 新的理想型社区。

第三方代码、库、资源与模型继续适用各自的许可证。各贡献者保留其对应贡献的版权。
