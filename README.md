# CatCatch 猫抓

M3U8 视频流下载器，支持多线程并发下载、断点续传、自动解密和转码。

## 功能特性

- **M3U8 解析** — 递归嵌套播放列表解析，自动选择最高码率，支持多编码检测（UTF-8 / GBK / Big5）
- **多线程下载** — 协程并发分片下载，Semaphore 控制并发数（默认 8），支持断点续传
- **自动重试** — 单分片 3 次指数退避重试 + 批量 3 轮重试
- **加密支持** — AES-128-CBC 加密 M3U8 自动解密，密钥缓存
- **自动转码** — TS → MP4，支持 FFmpeg-kit / 系统原生 MediaExtractor 双后端
- **多任务管理** — 队列管理，最多 3 个任务并发，非阻塞转码
- **浏览器联动** — URL Scheme `catcatch://add` 唤起，支持浏览器分享和静默模式
- **Material 3** — 自定义 Teal 主题，响应式布局（竖屏底部栏 / 横屏侧边栏）

## 截图

<!-- TODO: 添加截图 -->

## 技术栈

| 组件 | 版本 |
|------|------|
| Kotlin | 2.0.0 |
| Jetpack Compose (BOM) | 2024.06.00 |
| Material 3 | — |
| Hilt | 2.51.1 |
| Room | 2.6.1 |
| OkHttp | 4.12.0 |
| FFmpeg-kit | 6.0-2.LTS |
| Compile/Target SDK | 34 |
| Min SDK | 26 |

## 构建

```bash
# 调试版
./gradlew assembleDebug

# 发布版（混淆 + 资源缩减）
./gradlew assembleRelease

# 安装到设备
./gradlew installDebug
```

## 浏览器插件联动

通过 URL Scheme 唤起 App 添加下载任务：

```
catcatch://add?url=https://example.com/playlist.m3u8&title=视频标题&headers={"origin":"https://example.com"}
```

| 参数 | 必填 | 说明 |
|------|------|------|
| `url` | 是 | M3U8 播放列表 URL |
| `title` | 否 | 视频标题，自动填充文件名 |
| `headers` | 否 | JSON 格式请求头 |

支持的触发方式：
- URL Scheme（`catcatch://add?url=...`）
- 浏览器分享（ACTION_SEND）
- Deep Link（HTTP/HTTPS 链接）

## 项目结构

```
app/src/main/java/com/catcatch/
├── data/           # 数据层（Room DB, M3U8 解析, Repository）
├── di/             # Hilt 依赖注入
├── domain/model/   # 领域模型
├── service/        # 下载服务、分片下载器、FFmpeg 转码
├── ui/             # Compose UI（首页、下载页、设置页）
└── util/           # 工具类（缓存、通知、权限）
```

## 系统要求

- Android 8.0+（API 26）
- 存储权限（Android 10 以下）/ SAF（Android 10+）
- 通知权限（Android 13+）

## 许可证

<!-- TODO: 选择许可证 -->
