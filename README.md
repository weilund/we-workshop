# WE 工坊（WE Workshop）

一个 Android 客户端，用来在手机上浏览并下载 **Wallpaper Engine**（壁纸引擎）的 Steam 创意工坊内容。

Wallpaper Engine 没有手机端，创意工坊也只能在 PC 客户端里看。这个 App 把工坊搬到了 Android：列表、筛选、搜索、详情、GIF 预览，以及**源文件下载**（保存到手机 `Download/Wallpaper WE/`）。

## 功能

- 浏览创意工坊列表：缩略图、标题、作者、订阅数
- 排序：趋势 / 最新 / 最高评分
- 筛选：类型（场景/视频/应用/网页）、分类（壁纸/预设/资源）、年龄分级（所有人/问题内容/成人）
- 搜索（支持中文）
- 分页浏览，支持直接输入页码跳转
- 详情页：GIF 大图、标签、描述、订阅/收藏/浏览数、文件大小、跳转 Steam
- 记住上一次的筛选状态（排序/类型/分类/分级/搜索词）
- **下载源文件**：登录 Steam 后把壁纸源文件（视频等）直接下载到手机 `Download/Wallpaper WE/`
- 下载列表：进度、取消、失败重试、打开所在文件夹
- 断点续传 + 失败自动重试（最多 3 次，间隔 3s / 6s / 12s）

## 数据源与原理

- **列表**：登录后通过 Steam 统一消息服务 `PublishedFileService.QueryFiles` 查询（数据源与 WE 客户端一致）；未登录时回退到 `steamcommunity.com/workshop/browse` 网页解析（匿名可见条目会更少）
- **详情**：`ISteamRemoteStorage/GetPublishedFileDetails` 接口 + Steam 社区页面
- **下载**：`javaSteam` 建立 Steam 连接 → 获取 depot 密钥 / CDN 节点 / manifest 授权码 → 从 Steam CDN 下载 manifest 与分块 → AES 解密 + zstd/xz 解压 → 写入 `Download/Wallpaper WE/`
- **网络**：需要能访问 Steam（国内需代理或 VPN，手机开全局代理 / TUN 模式即可）

## 构建

需要 JDK 17 与 Android SDK（compileSdk 35、build-tools 35.0.0）。在根目录创建 `local.properties`：

```properties
sdk.dir=/path/to/android-sdk
```

然后：

```bash
./gradlew assembleRelease
```

产物位于 `app/build/outputs/apk/release/app-release.apk`。

> 仓库里的 Gradle Wrapper 指向腾讯云镜像（`mirrors.cloud.tencent.com`），国内网络可直接拉取 Gradle 发行版；如需官方地址，改 `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 即可。

主要依赖：`in.dragonbra:javasteam`（Steam 协议）、OkHttp、protobuf-java、BouncyCastle、zstd-jni、xz、lz4。

## 技术栈

- Java + Kotlin（仅 Steam 相关用 Kotlin）
- 单 Activity + WebView，前端为 `assets/` 下的 HTML/CSS/JS
- `javaSteam` 负责 Steam 登录（含 Steam Guard）与 CDN 下载

## 说明

- 仅供个人学习与自用；创意工坊内容版权归原作者所有，请勿二次分发
- 下载受保护的源文件需要用自己的 Steam 账号登录
- 本项目与 Valve / Wallpaper Engine 官方无关
