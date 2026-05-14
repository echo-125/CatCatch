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
- material3-window-size-class（响应式布局）
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
│   ├── remote/              # M3U8Parser（多编码检测、相对路径解析、递归嵌套）
│   └── repository/          # DownloadRepository（封装 DAO + Parser）
├── di/AppModule.kt          # Hilt 全局单例（OkHttp, DB, DAO, Parser, Downloader, Repository）
├── domain/model/            # DownloadTask, TaskStatus (6态枚举), M3U8Data, Segment
├── service/
│   ├── DownloadService.kt   # 前台服务，协程并发下载 → 合并 → 转码，任务队列管理
│   ├── SegmentDownloader.kt # 单分片下载，断点续传，指数退避重试
│   └── FFmpegConverter.kt   # Android MediaExtractor + MediaMuxer TS→MP4 转码
├── ui/
│   ├── MainActivity.kt      # 单 Activity，enableEdgeToEdge()，计算 WindowSizeClass
│   ├── components/          # 共享组件
│   │   ├── CatCatchTopAppBar.kt   # 透明毛玻璃标题栏
│   │   ├── ExpandableSection.kt   # 可折叠区域
│   │   └── StatusChip.kt         # 彩色状态标签
│   ├── download/DownloadScreen.kt # 下载任务列表，标题栏统计，双面板布局
│   ├── home/
│   │   ├── HomeScreen.kt         # 添加任务页，剪贴板粘贴，批量添加，小窗极简模式
│   │   └── HomeViewModel.kt      # SavedStateHandle 持久化输入状态
│   ├── navigation/
│   │   ├── AdaptiveNavigation.kt  # 响应式导航外壳（竖屏底部栏/横屏侧边栏）
│   │   ├── BottomNavBar.kt        # 底部导航栏
│   │   ├── MainScreen.kt         # 主界面容器
│   │   ├── NavigationRailContent.kt # 侧边导航栏
│   │   └── Screen.kt            # 导航路由定义
│   ├── settings/
│   │   ├── SettingsScreen.kt     # 设置页（LazyColumn 分组）
│   │   └── SettingsSection.kt    # 设置分组标题
│   ├── task/TaskItem.kt         # 任务卡片组件，状态特定信息展示
│   └── theme/
│       ├── Color.kt             # 自定义颜色系统（Teal 主题 + 状态色）
│       └── Theme.kt             # Material 3 主题配置
└── util/
    ├── NotificationUtil.kt      # 通知工具
    └── PermissionUtil.kt        # 权限工具
```

### 关键设计点

- **ViewModel 状态管理**: `HomeViewModel` 持有 `HomeUiState` data class，通过 `MutableStateFlow` 驱动 UI
- **任务状态**: `TaskStatus` 枚举 — PENDING / DOWNLOADING / MERGING / COMPLETED / FAILED / CANCELLED
- **数据转换**: `TaskEntity.toDomain()` 将数据库实体转为领域模型
- **前台服务**: `DownloadService` 使用 `@AndroidEntryPoint`，`CoroutineScope(SupervisorJob() + Dispatchers.IO)`
- **M3U8 解析**: 编码探测顺序 utf-8 → big5 → gbk → gb2312 → apparent_encoding，使用 `URI.resolve()` 处理相对路径
- **权限适配**: Android 13+ POST_NOTIFICATIONS 运行时权限，存储权限 maxSdkVersion 限制
- **响应式布局**: 竖屏底部 NavigationBar，横屏左侧 NavigationRail
- **状态持久化**: SavedStateHandle + configChanges 防止旋转丢失输入

## 核心下载流程（5 步）

1. **下载播放列表** - HTTP GET 获取 M3U8 文本，多编码检测，验证 `#EXTM3U` 标记
2. **解析分片 URL** - 递归解析（最大 5 层），主播放列表按带宽降序选最高码率，相对路径转绝对路径，循环引用检测
3. **并发下载分片** - 协程 + Dispatchers.IO 并发（Semaphore 控制，默认 8 并发），断点续传，单分片重试 3 次（指数退避），批量重试 3 轮
4. **合并分片** - 按顺序二进制合并所有 .ts 为 merged.ts
5. **转换 MP4** - Android MediaExtractor + MediaMuxer 无损转码，注入 H.264 SPS/PPS，失败时保留 .ts

## 任务并发模型

两层并发控制：

- **任务级**：最多 3 个下载任务同时运行（可配置 1-10），超出排队，任务完成后自动启动下一个
- **分片级**：每个任务内协程并发下载 TS 分片（默认 8 并发，Semaphore 控制）

## 浏览器插件联动

通过 URL Scheme `catcatch://add` 唤起 App，参数：
- `url`（必填）：M3U8 播放列表 URL
- `title`（可选）：视频标题，自动填充文件名
- `headers`（可选）：自定义请求头 JSON
- `referer`（可选）：快捷设置 Referer 头

安全限制：仅允许 HTTP/HTTPS 协议，headers 过滤常用头，添加前展示完整信息。

## 功能完成状态

### P0 - MVP（已完成）
- [x] M3U8 解析（单层播放列表）
- [x] 单任务下载（顺序分片）
- [x] 基础 UI（输入 URL、显示进度）
- [x] 分片合并
- [x] 下载完成通知

### P1 - 重要功能（已完成）
- [x] 多线程并发下载（协程并发，Semaphore 控制）
- [x] 断点续传（跳过已存在且非空的文件）
- [x] 自动重试机制（单分片 3 次指数退避 + 批量 3 轮重试）
- [x] 多任务管理与并发控制（队列管理，最多 3 并发）
- [x] 任务取消入口（DownloadService.cancel）
- [x] 任务重试入口（retryTask）

### P2 - 增强功能（大部分完成）
- [x] 主播放列表递归解析（自动选择最高码率，最大 5 层）
- [x] 批量添加任务（BatchAddDialog，格式：链接|文件名）
- [x] 自定义请求头（输入框 + Repository 传递）
- [x] FFmpeg 转码集成（Android 原生 MediaExtractor + MediaMuxer）
- [x] 后台下载服务（Foreground Service + 通知）
- [ ] 浏览器插件联动（Deep Link + 添加任务对话框）— URL Scheme 已定义，Activity 端未实现

### P3 - 体验优化（部分完成）
- [x] 剪贴板粘贴添加（ContentPaste 按钮）
- [ ] 浏览器分享集成
- [ ] 仅 WiFi 下载选项
- [x] 设置页面（基础框架，功能项为占位）
- [x] 深色模式（Theme.kt 已支持，跟随系统）
- [ ] 静默添加模式

### UI 重构（已完成）
- [x] 自定义 Teal 主题（#0D9488 主色，#F8FAFC 浅色背景，#0F172A 深色背景）
- [x] 响应式布局（竖屏底部 NavigationBar，横屏左侧 NavigationRail）
- [x] 组件样式（ElevatedCard 20dp 圆角，按钮 48dp 高，StatusChip 彩色标签）
- [x] 下载页增强（标题栏统计圆点，状态特定信息，双面板布局）
- [x] 设置页重写（LazyColumn 分组：下载设置、浏览器插件、外观、关于）
- [x] 可折叠区域（ExpandableSection，用于错误详情、高级选项）
- [x] 透明毛玻璃标题栏（CatCatchTopAppBar）
- [x] 小窗极简模式（高度 <400dp 时只显示 URL + 按钮）
- [x] SavedStateHandle 状态持久化
- [x] imePadding 键盘适配

## 待实现功能

### 高优先级
- [ ] 浏览器 Deep Link 处理（MainActivity intent-filter + 解析逻辑）
- [ ] 设置页功能实现（下载目录选择、并发数配置、深色模式切换）
- [ ] DataStore 配置持久化（替代硬编码的下载目录和并发数）

### 中优先级
- [ ] 浏览器分享集成（接收 ACTION_SEND Intent）
- [ ] 仅 WiFi 下载选项
- [ ] 静默添加模式（URL Scheme 触发时跳过确认）
- [ ] 下载速度和剩余时间实时显示（DownloadTask 扩展字段已预留）

### 低优先级
- [ ] WorkManager 替代自定义 Foreground Service（更好的系统集成）
- [ ] 任务排序和筛选
- [ ] 下载历史搜索
- [ ] 导出/导入任务列表

## 注意事项

- `domain/usecase/` 目录不存在，当前 ViewModel 直接调用 Repository（个人项目无需过度抽象）
- WorkManager 已引入依赖但未使用，后台下载通过自定义 Foreground Service 实现
- Navigation Compose 已引入，目前三个页面：Home、Downloads、Settings
- 发布版启用 ProGuard 混淆（`isMinifyEnabled = true`）和资源缩减
- 存储适配：Android 10+ 使用 MediaStore / SAF，默认保存到 `/storage/emulated/0/Download/CatCatch`
- FFmpeg 转码使用 Android 原生 MediaExtractor + MediaMuxer，非 ffmpeg-kit 库
- 配置变更（旋转）时 Activity 重建，通过 SavedStateHandle 保持输入状态
