# M3U8 视频下载器 - 安卓版功能需求文档

> 基于现有 Windows 桌面版（Python + ttkbootstrap）移植到安卓平台  
> 更新：新增浏览器插件联动功能，支持外部唤起并一键添加下载任务

## 一、应用概述

**应用名称**：M3U8 视频下载器（猫抓助手）  
**目标平台**：Android 8.0+（API 26+）  
**核心功能**：M3U8 视频流解析、多任务并发下载、断点续传、自动重试、合并转码  
**扩展功能**：与浏览器插件联动，嗅探视频资源后一键唤起 App 并添加下载任务

## 二、核心下载流程（5 步）
┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
│ 1.下载播放列表 │───>│ 2.解析分片URL │───>│ 3.并发下载分片 │───>│ 4.合并分片文件 │───>│ 5.转换MP4格式 │
└─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘
### 2.1 下载播放列表
- 输入 M3U8 URL
- HTTP GET 请求获取 M3U8 文本内容
- 编码检测：依次尝试 utf-8 / big5 / gbk / gb2312 / apparent_encoding
- 验证内容包含 `#EXTM3U` 标记

### 2.2 解析分片 URL
- 递归解析 M3U8 播放列表（最大深度 5 层）
- 处理主播放列表（Master Playlist）：按带宽降序选择最高码率子流
- 处理媒体播放列表（Media Playlist）：提取所有 .ts 分片 URL
- 相对路径自动转绝对路径
- 循环引用检测（visited 集合）

### 2.3 并发下载分片
- 线程池并发下载（默认 16 线程）
- 断点续传：跳过已存在且非空的文件
- 自动重试：单分片最多 3 次，间隔 2 秒
- 批量重试：最多 3 轮，轮间等待 2 秒
- 实时进度回调

### 2.4 合并分片
- 按顺序将所有 .ts 文件二进制合并为 merged.ts

### 2.5 转换 MP4
- 调用 ffmpeg 进行无损转码：`ffmpeg -i input -c copy -y output`
- ffmpeg 不存在时跳过，保留 .ts 文件，提供手动转换命令

## 三、功能模块清单

### 3.1 任务管理模块

#### 任务数据模型
| 字段 | 类型 | 说明 |
|------|------|------|
| task_id | int | 任务 ID（自增） |
| url | string | M3U8 链接 |
| output_name | string | 输出文件名 |
| output_dir | string | 输出目录 |
| max_workers | int | 下载线程数 |
| request_headers | map | 自定义请求头 |
| status | enum | 任务状态 |
| progress | float | 进度百分比 |
| downloaded | int | 已下载分片数 |
| total | int | 总分片数 |
| message | string | 状态消息 |

#### 任务状态枚举
等待中 ──[开始]──> 下载中
下载中 ──[取消]──> 取消中 ──> 已取消
下载中 ──[完成]──> 已完成
下载中 ──[异常]──> 已失败
已失败 ──[重试]──> 等待中
已取消 ──[重试]──> 等待中

#### 任务操作
- **添加任务**：单个添加 / 批量添加 / 粘贴添加 / 外部唤起添加
- **开始下载**：单个开始 / 全部开始
- **取消下载**：协作式停止
- **重试下载**：失败/取消的任务可重试
- **删除任务**：从列表移除
- **清除任务**：清除已完成/失败/取消的任务

#### 并发控制
- 最大同时下载任务数：1-10（默认 3）
- 任务排队机制：达到上限时新任务进入等待状态
- 自动排队：任务完成后自动启动等待中的任务

### 3.2 输入模块

#### 单个任务输入
| 输入项 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| 链接 | text | 是 | M3U8 播放列表 URL |
| 文件名 | text | 否 | 输出文件名（不含扩展名），为空则自动生成 |
| 保存路径 | path | 是 | 下载保存目录 |
| 线程数 | int | 否 | 并发下载线程数 1-64（默认 16） |
| 请求头 | JSON | 否 | 自定义 HTTP 请求头 |

#### 批量添加格式
每行一个任务，格式：`链接|文件名|请求头JSON`  
示例：`https://example.com/video.m3u8|我的视频|{"Referer":"https://example.com"}`

#### 粘贴添加
- 支持从剪贴板粘贴
- 支持多行（每行一个任务）
- 自动解析格式

#### 外部唤起添加（浏览器插件联动）
- 通过自定义 URL Scheme 或 HTTPS Deep Link 唤起 App
- 自动解析传入参数，打开「添加任务」对话框并预填信息
- 用户确认后一键添加任务到列表
- 支持从浏览器插件直接嗅探到的 M3U8 链接、视频标题、请求头等
- 详情见「5.6 浏览器插件联动」

### 3.3 并发模型（安卓适配）

#### 原 Windows 版并发架构（两层）

第一层 - 任务级并发（GUI层）
└─ 最多 3 个下载任务同时运行
└─ 每个任务独立线程

第二层 - 分片级并发（下载层）
└─ 每个任务内 16 线程并发下载 TS 分片

#### 安卓适配建议
- 使用 Kotlin Coroutines 替代 threading
- 任务级并发：使用 WorkManager 或自定义调度器
- 分片级并发：使用协程 + Dispatchers.IO
- 进度通知：使用 LiveData / StateFlow / Channel

### 3.4 配置模块

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| download_path | string | 外部存储下载目录 | 默认下载路径 |
| max_workers | int | 16 | 每任务下载线程数 |
| max_concurrent_downloads | int | 3 | 最大同时下载任务数 |
| auto_cleanup | bool | true | 下载完成后自动清理临时文件 |
| auto_generate_name | bool | true | 未指定文件名时自动生成 |
| external_add_confirm | bool | true | 外部唤起添加任务时是否弹出确认对话框 |

### 3.5 错误处理

| 场景 | 处理方式 |
|------|----------|
| M3U8 编码问题 | 多编码自动检测 |
| M3U8 格式无效 | 提示错误，任务标记失败 |
| M3U8 嵌套过深（>5层） | 提示错误 |
| M3U8 循环引用 | 检测并提示错误 |
| 单分片下载失败 | 最多重试 3 次，间隔 2 秒 |
| 批量分片失败 | 最多 3 轮批量重试 |
| 网络中断 | 断点续传支持 |
| ffmpeg 不可用 | 跳过转码，保留 .ts 文件 |
| 存储空间不足 | 提示用户 |
| 权限被拒绝 | 引导用户授权 |
| 外部唤起参数无效 | 提示错误，不添加任务 |

## 四、UI 界面设计

### 4.1 页面结构
┌─────────────────────────────────────────────┐
│ 标题栏：M3U8 视频下载器 │
├─────────────────────────────────────────────┤
│ 新建任务区域 │
│ ┌─────────────────────────────────────────┐ │
│ │ [单个任务] [批量添加] (Tab切换) │ │
│ │ │ │
│ │ 链接：[] [粘贴] │ │
│ │ 路径：[] [浏览] │ │
│ │ 文件名：[__________] 线程：[16] │ │
│ │ 请求头：[____________________] │ │
│ │ [粘贴添加] [添加任务] │ │
│ └─────────────────────────────────────────┘ │
├─────────────────────────────────────────────┤
│ 下载任务列表 │
│ ┌─────────────────────────────────────────┐ │
│ │ 工具栏：[开始][全部开始][取消][重试][删除] │ │
│ │─────────────────────────────────────────│ │
│ │ # │ 文件名 │ 状态 │ 进度 │ 速度/信息 │ │
│ │ 1 │ 视频1 │ 下载中 │ 45% │ 2.5MB/s │ │
│ │ 2 │ 视频2 │ 等待中 │ 0% │ - │ │
│ └─────────────────────────────────────────┘ │
├─────────────────────────────────────────────┤
│ 运行日志 │
│ ┌─────────────────────────────────────────┐ │
│ │ [2024-01-01 12:00:00] 开始下载... │ │
│ │ [2024-01-01 12:00:01] 分片 1/100 已下载 │ │
│ └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘

### 4.2 交互设计

#### 任务列表操作
- **单击**：选中任务
- **长按**：弹出上下文菜单（开始/取消/重试/删除/复制链接/打开目录）
- **双击**：等待中/失败/取消状态则开始，下载中则取消

#### 快捷操作
- 从浏览器分享 M3U8 链接到本应用
- 从剪贴板粘贴 URL
- 下载完成后通知提醒
- 从外部链接（浏览器插件）唤醒时自动弹出添加任务对话框

#### 外部唤起处理流程
1. 应用接收到 Intent (URL Scheme 或 Deep Link)
2. 解析参数（url, title, headers 等）
3. 检查 App 是否在前台：若未启动则先启动主界面
4. 弹出「添加下载任务」对话框，自动填充链接、文件名、请求头
5. 用户可修改参数，点击「确认」添加任务
6. 若设置 `external_add_confirm = false`，则静默直接添加到任务列表（可选）

### 4.3 设置页面

- 最大并发下载数（1-10）
- 默认下载线程数（1-64）
- 自动清理临时文件（开关）
- 默认保存路径
- 外部唤起添加时确认（开关）
- 关于/版本信息

## 五、安卓平台特有需求

### 5.1 权限需求
- `INTERNET` - 网络访问
- `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` - 存储读写（Android 10 以下）
- `MANAGE_EXTERNAL_STORAGE` - 所有文件访问（Android 11+，可选）
- `FOREGROUND_SERVICE` - 前台服务（后台下载）
- `POST_NOTIFICATIONS` - 通知权限（Android 13+）

### 5.2 后台下载
- 使用前台服务（Foreground Service）保持下载任务在后台运行
- 显示常驻通知，包含下载进度
- 支持通知栏操作（暂停/取消）

### 5.3 存储适配
- Android 10+ 使用 MediaStore API 或 SAF（Storage Access Framework）
- 默认保存到应用专属目录或公共下载目录
- 支持用户选择自定义保存路径

### 5.4 网络适配
- 支持 WiFi / 移动网络切换检测
- 可选：仅 WiFi 下载选项
- 网络断开时自动暂停，恢复后继续

### 5.5 ffmpeg 集成
- 方案 A：集成 ffmpeg-kit（推荐）
- 方案 B：跳过转码，仅合并为 .ts 文件
- 方案 C：使用 Android MediaCodec 硬件解码（复杂）

### 5.6 浏览器插件联动

#### 功能目标
配合浏览器插件（如「猫抓」或其他 M3U8 嗅探扩展），当用户在网页上嗅探到视频资源时，点击插件按钮可以一键唤起本 App 并自动添加下载任务，无需手动复制粘贴 URL。

#### 技术实现

##### 5.6.1 URL Scheme 定义
- **Scheme**：`m3u8downloader` 或 `m3u8catcher`（可配置）
- **路径**：`/add`
- **参数**（URL 编码）：
  - `url`（必填）：M3U8 播放列表 URL
  - `title`（可选）：视频标题，用于自动填充文件名
  - `headers`（可选）：自定义请求头，JSON 格式字符串
  - `referer`（可选）：快捷设置 Referer 头
- **示例**：  
  `m3u8downloader://add?url=https%3A%2F%2Fexample.com%2Fvideo.m3u8&title=MyVideo&referer=https%3A%2F%2Fexample.com`

##### 5.6.2 Android Deep Link 备选
- 支持 HTTPS Deep Link，例如：`https://download.example.com/add?url=...`
- 需在网站根目录放置 `assetlinks.json` 验证域名

##### 5.6.3 AndroidManifest 配置
```xml
<activity android:name=".ui.MainActivity">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <!-- 自定义 URL Scheme -->
        <data android:scheme="m3u8downloader" android:host="add" />
        <!-- HTTPS Deep Link -->
        <data android:scheme="https" android:host="download.example.com" android:pathPrefix="/add" />
    </intent-filter>
</activity>
```

##### 5.6.4 参数解析规则

- url 参数必须进行 URL 解码，且需验证是否为合法 HTTP/HTTPS URL

- title 自动去除非法文件名字符（\/:*?"<>|），长度限制 200 字符

- headers 为 JSON 对象，解析后合并到任务的请求头中；若同时传递 referer，则优先使用 headers 中的 Referer 或覆盖

- 若未提供 title，则按原自动生成规则（从 URL 提取或使用默认名）

#####  5.6.5 交互体验

- 首次唤起：若应用未安装，可引导用户到应用商店下载（通过 HTTPS Deep Link 配合 fallback URL）

- 应用在后台：唤起后应前置到前台，并自动弹出添加任务对话框

- 应用在前台：直接弹出对话框，不重复启动 Activity

- 静默添加模式：用户可在设置中开启「直接添加无需确认」，此时接到唤起后直接添加任务并在状态栏发送简要通知

- 错误处理：当 URL 缺失或格式错误时，提示「无效的下载链接」并放弃添加

#####  5.6.6 浏览器插件适配说明

提供插件开发者文档：

- 插件需要嗅探页面中的 M3U8 地址，以及可选的标题和 Referer

- 构造上述 URL Scheme 并调用 window.location.href 或通过 Intent（Android）唤起

- 建议同时检测是否安装 App（通过尝试唤醒后监听页面隐藏/显示），未安装则引导下载

#####  安全性考虑

- 仅允许 HTTP/HTTPS 协议 URL，禁止 file:// 或内部协议

- 对 headers JSON 进行过滤，仅保留常用头（Referer, User-Agent, Origin, Cookie 等），禁止注入恶意头

- 在添加任务前向用户展示完整 URL 和请求头，防止恶意网页自动添加钓鱼任务

##  六、技术栈建议
### 推荐方案：Kotlin + Jetpack Compose

- UI 框架：Jetpack Compose + Material 3

- 异步处理：Kotlin Coroutines + Flow

- 后台任务：WorkManager + Foreground Service

- 数据持久化：Room / DataStore

- 网络请求：OkHttp / Ktor

- 依赖注入：Hilt

- 架构模式：MVVM + Repository

- 深度链接处理：Navigation Compose + NavDeepLinkBuilder

### 备选方案：Flutter

- 跨平台框架，可同时支持 iOS

- 使用 Dart 语言

- HTTP 请求：dio 包

- 状态管理：Riverpod / Bloc

- 深度链接：uni_links 包

## 七、数据流架构
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   UI Layer   │<───>│  ViewModel  │<───>│  Repository  │
│  (Compose)   │     │             │     │              │
└─────────────┘     └─────────────┘     └──────┬──────┘
                                               │
                    ┌──────────────────────────┼──────────────────────────┐
                    │                          │                          │
              ┌─────┴─────┐            ┌──────┴──────┐            ┌──────┴──────┐
              │  M3U8 解析  │            │  分片下载器   │            │  FFmpeg 转码  │
              │  解析器     │            │             │            │              │
              └───────────┘            └─────────────┘            └─────────────┘

## 八、文件/模块划分建议

app/
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt          # Room 数据库
│   │   ├── TaskDao.kt              # 任务数据访问
│   │   └── SettingsDataStore.kt    # 配置存储
│   ├── remote/
│   │   └── M3U8Parser.kt           # M3U8 解析
│   └── repository/
│       └── DownloadRepository.kt   # 下载仓库
├── domain/
│   ├── model/
│   │   ├── DownloadTask.kt         # 任务模型
│   │   └── TaskStatus.kt           # 状态枚举
│   └── usecase/
│       ├── AddTaskUseCase.kt       # 添加任务
│       ├── StartDownloadUseCase.kt # 开始下载
│       └── ManageTaskUseCase.kt    # 任务管理
├── service/
│   ├── DownloadService.kt          # 前台下载服务
│   ├── SegmentDownloader.kt        # 分片下载器
│   └── FFmpegConverter.kt          # 转码服务
├── ui/
│   ├── home/
│   │   ├── HomeScreen.kt           # 主页面
│   │   └── HomeViewModel.kt        # 主页 ViewModel
│   ├── task/
│   │   ├── TaskListScreen.kt       # 任务列表
│   │   └── TaskItem.kt             # 任务项组件
│   ├── settings/
│   │   └── SettingsScreen.kt       # 设置页面
│   ├── components/
│   │   ├── AddTaskDialog.kt        # 添加任务对话框
│   │   └── BatchAddDialog.kt       # 批量添加对话框
│   └── deeplink/
│       └── DeepLinkHandler.kt      # 深度链接解析与分发
└── util/
    ├── NetworkUtil.kt              # 网络工具
    ├── FileUtil.kt                 # 文件工具
    └── NotificationUtil.kt         # 通知工具

## 九、开发优先级

### P0 - 核心功能（MVP）

- M3U8 解析（单层播放列表）

- 单任务下载（单线程）

- 基础 UI（输入 URL、显示进度）

- 分片合并

- 下载完成通知

### P1 - 重要功能

- 多线程并发下载

- 断点续传

- 自动重试机制

- 多任务管理

- 任务状态流转

### P2 - 增强功能

- 主播放列表解析（自动选择最高码率）

- 批量添加任务

- 自定义请求头

- ffmpeg 转码集成

- 后台下载服务

- 浏览器插件联动（Deep Link + 自动添加任务对话框）

### P3 - 优化体验

- 剪贴板粘贴添加

- 浏览器分享集成

- 仅 WiFi 下载选项

- 下载速度限制

- 深色模式支持

- 静默添加模式（设置项）