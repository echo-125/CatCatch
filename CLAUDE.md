# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

CatCatch（猫抓助手）- M3U8 视频流下载器，从 Windows 桌面版（Python + ttkbootstrap）移植到 Android。

**个人项目，以快速开发快速验证为主**，优先实现核心功能跑通流程，不过度设计。

核心能力：M3U8 播放列表解析（递归嵌套）、多线程并发分片下载、断点续传、自动重试、分片合并、FFmpeg 转码。

扩展能力：浏览器插件联动（URL Scheme / Deep Link），嗅探视频资源后一键唤起 App 添加下载任务。

## 构建命令

```bash
./gradlew assembleDebug          # 调试版构建
./gradlew assembleRelease        # 发布版构建（启用混淆 + 资源缩减）
./gradlew installDebug           # 安装调试版到设备
./gradlew test                   # 运行单元测试
./gradlew connectedAndroidTest   # 运行仪器测试
```

## 技术栈

- Kotlin 2.0.0 / JVM 17 / AGP 8.5.0 / KSP 2.0.0-1.0.22
- Jetpack Compose (BOM 2024.06.00) + Material 3
- Kotlin Coroutines 1.8.1 + Flow
- Hilt 2.51.1（依赖注入）/ Room 2.6.1（数据库）/ DataStore 1.1.1（配置）
- OkHttp 4.12.0（网络）/ WorkManager 2.9.0（已引入未使用）
- Compile/Target SDK 34, Min SDK 26

## 架构

MVVM + Repository，单模块项目（`:app`）。

```
UI (Compose) ←→ ViewModel (StateFlow) ←→ Repository
                                              │
                              ┌───────────────┼───────────────┐
                              │               │               │
                        M3U8Parser    SegmentDownloader  DownloadService
```

### 当前实际代码结构

```
app/src/main/java/com/catcatch/
├── CatCatchApp.kt           # Application 入口，Hilt 容器 + 通知渠道注册
├── data/
│   ├── local/               # AppDatabase (Room v1), TaskDao (Flow 响应式查询), TaskEntity
│   ├── remote/              # M3U8Parser（多编码检测、相对路径解析）
│   └── repository/          # DownloadRepository（封装 DAO + Parser）
├── di/AppModule.kt          # Hilt 全局单例（OkHttp, DB, DAO, Parser, Downloader, Repository）
├── domain/model/            # DownloadTask, TaskStatus (6态枚举), M3U8Data, Segment
├── service/
│   ├── DownloadService.kt   # 前台服务，协程顺序下载 → 合并 → 清理
│   └── SegmentDownloader.kt # 单分片下载，支持断点续传和协程取消
├── ui/
│   ├── MainActivity.kt      # 单 Activity，enableEdgeToEdge()
│   ├── home/                # HomeScreen (Compose) + HomeViewModel (@HiltViewModel, StateFlow)
│   ├── task/TaskItem.kt     # 任务列表项组件
│   └── theme/Theme.kt       # Material 3 主题，动态颜色 (Android 12+)
└── util/                    # NotificationUtil, PermissionUtil
```

### 关键设计点

- **ViewModel 状态管理**: `HomeViewModel` 持有 `HomeUiState` data class，通过 `MutableStateFlow` 驱动 UI
- **任务状态**: `TaskStatus` 枚举 — PENDING / DOWNLOADING / MERGING / COMPLETED / FAILED / CANCELLED
- **数据转换**: `TaskEntity.toDomain()` 将数据库实体转为领域模型
- **前台服务**: `DownloadService` 使用 `@AndroidEntryPoint`，`CoroutineScope(SupervisorJob() + Dispatchers.IO)`
- **M3U8 解析**: 编码探测顺序 utf-8 → big5 → gbk → gb2312 → apparent_encoding，使用 `URI.resolve()` 处理相对路径
- **权限适配**: Android 13+ POST_NOTIFICATIONS 运行时权限，存储权限 maxSdkVersion 限制

## 核心下载流程（5 步）

1. **下载播放列表** - HTTP GET 获取 M3U8 文本，多编码检测，验证 `#EXTM3U` 标记
2. **解析分片 URL** - 递归解析（最大 5 层），主播放列表按带宽降序选最高码率，相对路径转绝对路径，循环引用检测
3. **并发下载分片** - 协程 + Dispatchers.IO 并发（默认 16 并发），断点续传，单分片重试 3 次，批量重试 3 轮
4. **合并分片** - 按顺序二进制合并所有 .ts 为 merged.ts
5. **转换 MP4** - ffmpeg-kit 无损转码 `ffmpeg -i input -c copy -y output`，不可用时保留 .ts

## 任务并发模型

两层并发控制：

- **任务级**：最多 3 个下载任务同时运行（可配置 1-10），超出排队，任务完成后自动启动下一个
- **分片级**：每个任务内协程并发下载 TS 分片（默认 16 并发，可配置 1-64）

## 浏览器插件联动

通过 URL Scheme `m3u8downloader://add` 唤起 App，参数：
- `url`（必填）：M3U8 播放列表 URL
- `title`（可选）：视频标题，自动填充文件名
- `headers`（可选）：自定义请求头 JSON
- `referer`（可选）：快捷设置 Referer 头

安全限制：仅允许 HTTP/HTTPS 协议，headers 过滤常用头，添加前展示完整信息。

## 尚未实现（按优先级）

### P0 - MVP（先跑通核心流程）
- [x] M3U8 解析（单层播放列表）
- [x] 单任务下载（顺序分片）
- [x] 基础 UI（输入 URL、显示进度）
- [x] 分片合并
- [x] 下载完成通知

### P1 - 重要功能
- [ ] 多线程并发下载（协程并发替代顺序下载）
- [ ] 断点续传完善
- [ ] 自动重试机制（单分片 + 批量）
- [ ] 多任务管理与并发控制
- [ ] 任务取消入口

### P2 - 增强功能
- [ ] 主播放列表递归解析（自动选择最高码率）
- [ ] 批量添加任务
- [ ] 自定义请求头
- [ ] FFmpeg 转码集成（ffmpeg-kit）
- [ ] 后台下载服务完善
- [ ] 浏览器插件联动（Deep Link + 添加任务对话框）

### P3 - 体验优化
- [ ] 剪贴板粘贴添加
- [ ] 浏览器分享集成
- [ ] 仅 WiFi 下载选项
- [ ] 设置页面
- [ ] 深色模式
- [ ] 静默添加模式

## 注意事项

- `domain/usecase/` 目录不存在，当前 ViewModel 直接调用 Repository（个人项目无需过度抽象）
- `ui/components/`、`ui/settings/` 目录为空
- WorkManager 已引入依赖但未使用，后台下载通过自定义 Foreground Service 实现
- Navigation Compose 已引入但目前仅单页面
- 发布版启用 ProGuard 混淆（`isMinifyEnabled = true`）和资源缩减
- 存储适配：Android 10+ 使用 MediaStore / SAF，默认保存到公共下载目录
