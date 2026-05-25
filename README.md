# BiliBili Halo Plugin Player

[![Build](https://github.com/aeuicey/bilibili-halo-plugin-player/actions/workflows/build.yml/badge.svg)](https://github.com/aeuicey/bilibili-halo-plugin-player/actions/workflows/build.yml)

为 [Halo](https://github.com/halo-dev/halo) 博客系统提供 B站视频播放器嵌入插件，支持扫码登录获取高清晰度、DASH 音视频分离播放、多清晰度动态切换、分辨率自适应画幅比例、浏览器扩展直连 CDN 零代理延迟。

> 📖 完整技术文档见 [how.md](./how.md) —— 从架构全景到每一行设计的取舍。
> 🎨 UI 设计指导见 [DESIGN.md](./DESIGN.md) —— Cal.com 设计系统令牌体系。

<img width="1357" height="1692" alt="image" src="https://github.com/user-attachments/assets/8ec6109b-2c2b-43c3-ae55-6bee598196aa" />


## 功能特性

- **扫码登录** — 插件管理后台生成 B站 登录二维码，扫码授权后 SESSDATA 自动持久化，Halo 重启后自动恢复登录态
- **多清晰度支持** — 360P / 480P / 720P / 1080P / 1080P60 / 4K，登录后解锁更高画质（大会员可看 4K）
- **DASH 音画分离播放** — `<video>` + 隐藏 `<audio>` 双元素 `setInterval` 毫秒级同步，±150ms 容忍度
- **浏览器扩展直连** — Chrome 扩展通过 `declarativeNetRequest` 注入 Referer 头，让读者浏览器直连 B站 CDN，完全绕过服务端代理
- **CDN 智能升级** — 自动将海外 / P2P CDN 节点替换为国内优质镜像（13 节点轮询），降低延迟
- **分辨率自适应** — 自动识别横屏(16:9)、竖屏(9:16)、方形视频，嵌入代码 + 播放器同步对应画幅比例
- **WBI 签名** — 完整实现 B站 WBI 混音密钥签名算法，密钥每小时自动刷新
- **后台管理** — 输入 BV 号即可生成嵌入代码，一键复制，粘贴到文章 HTML 编辑器即可使用
- **实时日志** — SSE 实时推送 + 环形缓冲（300 条），按级别过滤，附带玩家遥测上报
- **GitHub Actions 自动构建** — 每次推送自动编译生成 JAR 包

## 安装

1. 在 [Releases](https://github.com/aeuicey/bilibili-halo-plugin-player/releases) 或 [Actions](https://github.com/aeuicey/bilibili-halo-plugin-player/actions/workflows/build.yml) 页面下载最新 JAR 包
2. 进入 Halo 后台 → 插件管理 → 上传插件，选择下载的 JAR 文件
3. 在已安装插件列表中找到 "BiliBili播放器"，确认已启用

## 使用指南

### 1. 扫码登录（可选）

> 登录后可获取 720P 及以上清晰度。未登录仅支持 480P。

- 进入 Halo 后台 → 左侧菜单"B站播放器" → 账号登录
- 点击"生成登录二维码"，使用 B站客户端扫码
- 手机确认授权后，页面自动显示登录成功及用户信息
- 登录状态持久化到服务器文件系统，Halo 重启后自动恢复

### 2. 生成嵌入代码

- 切换到"嵌入代码"标签页
- 输入 B站视频链接或 BV 号，点击"解析"
- 多 P 视频可选择对应分 P
- 系统自动识别视频分辨率并显示横/竖屏标记
- 点击"复制代码"，将 HTML 代码粘贴到文章编辑器的 HTML 视图中
- 页面读者即可看到内嵌的 B站播放器

### 3. 读者端播放

- 播放器自动加载最高可用清晰度（默认 1080P，登录后）
- 右下角画质按钮可切换清晰度
- 横屏/竖屏视频自动匹配正确画幅比例
- 支持完整 Video.js 控件（播放/暂停/进度/音量/画中画/全屏）

## 技术路线

### 音视频分离播放（DASH Dual Element Sync）

B站 720P+ 视频采用 DASH 协议，音视频分离为独立 m4s 文件。本插件实现了一套**零外部依赖的音视频同步方案**：

```
B站 playurl API (fnval=16)
  → dash.video[] (m4s)  → <video src="proxy">
  → dash.audio[] (m4s)  → <audio style="display:none" src="proxy">
                              ↑
                    requestAnimationFrame
                     每帧同步 播放/暂停/seek/音量/倍速
```

**为何不用 MSE / WebAV？**

初期尝试了 `MediaSource` + `SourceBuffer` 手工推流，以及 `@webav/av-cliper` 的 `mixinMP4AndAudio` 合并方案。两者均因 Spring WebFlux 代理返回的 `ReadableStream` 不兼容浏览器原生 `pipeThrough` 接口而失败。

**最终方案——Dual Element RAF Sync**，与 B站官方播放器思路一致（音画分离 + 客户端同步），但使用 `<video>` / `<audio>` 原生标签替代复杂的 MSE 管线：

- `video` 元素加载视频轨 — 浏览器内置解码器，HEVC/AVC/AV1 自适应
- `audio` 元素加载音频轨 — 隐藏 DOM，相同的代理 URL 路径
- `requestAnimationFrame` 循环 — 每 16ms 校正一次音频时间，±150ms 容忍度
- `seeked` / `ratechange` / `volumechange` 事件钩子 — 鼠标拖动进度条时间步响应

### 清晰度策略

| 清晰度 | qn | fnval | 返回格式 | 播放方式 |
|--------|-----|-------|---------|---------|
| 360P/480P/720P | 64 | 1 | durl MP4 直链 | 单一 `<video>` |
| 1080P/4K+ | ≥80 | 16 | DASH 音视频分离 | 双元素 RAF 同步 |

### 分辨率自适应

- 嵌入代码：`aspect-ratio` 使用实际 `W/H` 而非硬编码 `16/9`
- 播放器：`loadQuality` 后将轨道 `width/height` 注入 CSS 容器
- 管理后台：分析视频后显示 `1920×1080 · Landscape` 或 `1080×1920 · Portrait`

### 网络架构

```
浏览器 fetch
  → /api/video/proxy?url=<Bilibili CDN>
    → Spring WebFlux Flux<DataBuffer> streaming
      → B站 CDN (Referer/Origin 伪装)
        → 浏览器原生 <video>/<audio>
```

### CDN 智能升级

B站分配的 CDN 节点质量参差不齐。`CdnMirrorUtil` 在返回播放地址前对每条 CDN URL 做域名级别升级：

- 免流 CDN / 国内镜像 CDN → 原样保留
- 海外 CDN（Cloudflare / Akamai） → 替换为国内镜像
- MCDN P2P（依赖其他用户上传，不稳定） → 替换为直连 CDN
- 普通 UPOS CDN → 替换为国内镜像

国内镜像池 13 个节点（阿里云/腾讯云/华为云/百度云/金山云等），`AtomicInteger` 轮询选取。

### WBI 签名

B站部分 API（如高清晰度 playurl）要求 WBI 签名。v1.7 之前后端完整实现混音密钥算法（`WbiSignUtil`）用于服务端代理高清请求；v1.7 将高清解析迁移至浏览器扩展后，后端仅需处理 ≤720P 的 FLV/MP4 请求（`qn=64, fnval=0`），该参数组合通常不受 WBI 限制，因此后端 WBI 模块已移除。扩展端在请求 B站 playurl 时直接使用标准 Cookie 鉴权，无需额外签名。

### 浏览器扩展直连模式

CDN 代理是瓶颈 —— 所有视频流量都经过 Halo 服务器。Chrome 扩展通过 `declarativeNetRequest` API 在发出请求前注入 `Referer: https://www.bilibili.com` 头，让读者浏览器直连 B站 CDN。

扩展与播放器通过 `window.postMessage` 双向握手检测安装状态（5 次递增间隔信号 + 主动 ping 响应），3 秒内未检测到则回退到代理模式。

### 扩展 DASH 高清回传架构（v1.7）

高清播放（qn≥80）需要 B站登录态（SESSDATA）。为避免服务端持有用户凭证带来的安全风险，v1.7 将高清解析完全下放至浏览器扩展：

```
播放器 iframe (qn≥80)
  → postMessage "requestDash" ──────► 扩展 content-script
                                     │
                                     ├──► 扩展 background.js
                                     │     fetch B站 playurl (Cookie: SESSDATA)
                                     │     ← {dash.video[], dash.audio[]}
                                     │
  ◄── postMessage "dashUrl" ─────────┘
      {videoUrl, audioUrl, codecs, width, height}
```

- **扩展独立持有 SESSDATA** — 扫码登录在扩展 popup 中完成，凭证仅存于 `chrome.storage.local`，服务端无感知
- **地址回传** — content-script 将 DASH 音视频 URL 通过 `postMessage` 回传播放器，播放器直接加载 CDN 直链
- **降级容错** — 扩展未登录/凭证过期时，回传错误码，播放器自动降级到 720P FLV 并提示用户登录
- **CDN 升级双端** — 扩展 background.js 也内置了 CDN 镜像升级逻辑，与后端 `CdnMirrorUtil` 镜像池保持一致

### 流式连接步骤显示（v1.7）

嵌入播放器在初始化到可播放之间有一段"黑盒时间"（检测扩展 → 获取地址 → 缓冲）。v1.7 在视频区域底部叠加了一个毛玻璃步骤条，将链路可视化：

```
●───●───●───●───●
^   ^   ^   ^   ^
1   2   3   4   5

1: 检测插件状态          → "正在检测插件状态"
2: 确定策略              → "检测到插件" / "未检测到插件，使用默认播放策略"
3: 解析清晰度            → "正在解析清晰度"
4: 等待播放地址          → "正在等待插件回传播放地址" → "回传成功"
5: 等待缓冲              → "正在等待播放缓冲" → "播放就绪"（淡出）
```

- **状态机驱动** — 11 个挂载点（扩展检测、playurl 返回、DASH 回传、`canplay` 事件等）调用 `setConnectionStep()` 推进步骤
- **防降级设计** — `setConnectionStep(s,t)` 拒绝步骤回退，避免切换清晰度时文字闪烁
- **无干扰** — `pointer-events: none` + 播放就绪后 1.2s 自动淡出，不遮挡 Video.js 控件

## 路线图

### 已完成

- [x] **v1.0** — 基础视频嵌入、360P/480P/720P 多清晰度、Video.js 播放器控件
- [x] **v1.1** — 扫码登录、SESSDATA 持久化恢复、高清晰度解锁
- [x] **v1.2** — DASH 音视频分离播放、双元素 RAF 毫秒级同步
- [x] **v1.3** — 分辨率自适应画幅比例、SSE 实时日志面板、嵌入代码生成器
- [x] **v1.4** — CDN 代理连接修复、封面图优化、cid 复用修复、管理后台品牌化、移除 Tiptap 编辑器扩展
- [x] **v1.5** — CDN 镜像智能升级（13 节点轮询）、WBI 签名算法、浏览器扩展 Referer 注入
- [x] **v1.6** — 浏览器扩展双向握手检测、CDN 容灾/备用 URL 降级、播放器遥测、日志 SSE 推送

### 计划中

- [x] **v1.7** — 流式连接步骤显示、浏览器扩展双向握手 DASH 高清直连、插件配置页品牌化
- [ ] **v1.8** — 弹幕加载、播放列表/合集支持、分 P 连续播放
- [ ] **v1.9** — 暗色模式、自定义播放器主题、移动端响应式优化
- [ ] **v1.10** — 国际化 (i18n)、播放统计、快捷键支持

> 欢迎通过 [Issues](https://github.com/aeuicey/bilibili-halo-plugin-player/issues) 提交功能建议与反馈。

## 开发

```bash
# 克隆仓库
git clone https://github.com/aeuicey/bilibili-halo-plugin-player.git
cd bilibili-halo-plugin-player

# 构建
./gradlew build -x test

# JAR 包路径
# build/libs/plugin-bilibili-player-X.X.X.jar
```

### 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Java 21 / Spring WebFlux / Halo Plugin API |
| 前端 | Vue 3 + TypeScript / Vite / Halo UI Components |
| 构建 | Gradle / pnpm |
| CI/CD | GitHub Actions (JDK 21 + Node 20 + pnpm 10) |

## 更新日志

### v1.7.0 (2026-05-25)

- **新增** 嵌入播放器流式连接步骤显示 — 在视频区域底部以步骤条形式实时展示检测插件 → 确定策略 → 解析清晰度 → 等待播放地址 → 等待缓冲的完整链路，播放就绪后自动淡出
- **新增** 浏览器扩展 DASH 高清直连架构 — 扩展通过 `chrome.runtime.sendMessage` 获取 DASH 音视频地址并 `postMessage` 回传播放器，qn≥80 的高清请求完全由扩展承载，服务端仅提供 ≤720P 的 FLV/MP4 解析
- **重构** 登录体系迁移至浏览器扩展 — 后端不再持有 SESSDATA，扫码登录由扩展 popup 独立完成，避免服务端凭证泄露风险
- **修复** `updateModeStatus()` 引用的 `modeStatus` DOM 元素缺失，导致播放模式（FLV/DASH/直连/代理）状态标签不显示的问题
- **修复** `plugin.yaml` 补充 `version` 字段，满足 Halo 插件元数据规范

### v1.6.0 (2026-05-14)

- **新增** 浏览器扩展（`browser-extension/`），`declarativeNetRequest` Referer 注入直连 CDN
- **新增** 扩展双向握手检测（`postMessage` ping/pong），3 秒超时回退代理模式
- **新增** 播放器遥测上报（play/pause/seek/error），含父页面来源追踪
- **新增** CDN 容灾机制：累计 3 次错误自动刷新播放地址，备用 URL 降级
- **新增** `DESIGN.md` UI 设计指导（Cal.com 设计系统令牌体系）
- **新增** `how.md` 完整技术文档（660+ 行，架构全景 + 设计取舍）
- **优化** 日志系统：SSE 实时推送 + 环形缓冲（300 条），双写 SLF4J
- **优化** 播放地址缓存 TTL 从 5 分钟延长至 10 分钟，按 `bvid+cid+qn+fnval` 组合缓存
- **优化** 嵌入页面内联 Video.js 关键 CSS，CDN 故障时控件不崩

### v1.5.0 (2026-05-12)

- **新增** CDN 镜像智能升级（`CdnMirrorUtil`），13 节点轮询替代单点 CDN
- **新增** WBI 签名算法（`WbiSignUtil`），完整实现 B站混音密钥签名
- **新增** `nocache` 参数支持，前端可强制刷新播放地址缓存
- **修复** playurl 缓存 key 缺少 cid 维度导致不同分 P 返回相同地址

### v1.4.0 (2026-05-11)

- **修复** 连续解析不同视频时 cid 被复用导致播放失败的问题
- **修复** CDN 代理流传输中连接中断导致 EOFException 异常
- **修复** 视频封面图 HTTP→HTTPS 重定向导致的缩略图加载失败，并优化封面图片尺寸
- **优化** 管理后台顶栏设计，标题品牌化升级

## 开源协议

本项目基于 [GNU General Public License v3.0](./LICENSE) 开源。

感谢以下项目：
- [Halo](https://github.com/halo-dev/halo) — 优秀的开源博客系统
- [create-halo-plugin](https://github.com/halo-dev/create-halo-plugin) — Halo 插件模板
- [Video.js](https://videojs.com) — Web 播放器框架
- [Bilibili API](https://api.bilibili.com/) — 视频信息与播放地址接口
