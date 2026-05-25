# BiliBili Halo Plugin Player

[![Build](https://github.com/aeuicey/bilibili-halo-plugin-player/actions/workflows/build.yml/badge.svg)](https://github.com/aeuicey/bilibili-halo-plugin-player/actions/workflows/build.yml)

为 [Halo](https://github.com/halo-dev/halo) 博客系统提供 B站视频播放器嵌入方案，由 **Halo Plugin（服务端）** 与 **Chrome 扩展（客户端）** 两个独立组件协同工作：

- **Halo Plugin** —— 生成自包含嵌入播放器、管理后台、≤720P 视频代理、CDN 镜像升级
- **浏览器扩展** —— 注入 Referer 实现 CDN 直连、独立扫码登录、DASH 高清地址解析与回传

两者通过 `window.postMessage` 双向通信，扩展未安装时自动降级为服务端代理模式。

> 📖 完整技术文档见 [how.md](./how.md) —— 从架构全景到每一行设计的取舍。
> 🎨 UI 设计指导见 [DESIGN.md](./DESIGN.md) —— Cal.com 设计系统令牌体系。

<img width="1357" height="1692" alt="image" src="https://github.com/user-attachments/assets/8ec6109b-2c2b-43c3-ae55-6bee598196aa" />

---

## 目录

- [总体路线图](#总体路线图)
- [Halo Plugin](#halo-plugin)
  - [版本迭代](#halo-plugin-版本迭代)
  - [架构与实现](#halo-plugin-架构与实现)
- [浏览器扩展](#浏览器扩展)
  - [版本迭代](#浏览器扩展-版本迭代)
  - [架构与实现](#浏览器扩展-架构与实现)
- [两端通信协议](#两端通信协议)
- [安装](#安装)
- [使用指南](#使用指南)
- [技术栈](#技术栈)
- [开源协议](#开源协议)

---

## 总体路线图

```
2026-05      2026-05      2026-05      2026-06      2026-07
   │            │            │            │            │
   ▼            ▼            ▼            ▼            ▼
┌──────┐    ┌──────┐    ┌──────┐    ┌──────┐    ┌──────┐
│v1.5  │───►│v1.6  │───►│v1.7  │───►│v1.8  │───►│v1.9  │
│后端   │    │后端+ │    │后端+ │    │后端  │    │后端  │
│      │    │扩展v1│    │扩展v2│    │      │    │      │
└──────┘    └──────┘    └──────┘    └──────┘    └──────┘
```

| 阶段 | 版本 | 目标 | 状态 |
|------|------|------|------|
| **基础播放** | v1.0–v1.4 | 多清晰度嵌入、DASH 音画同步、扫码登录、CDN 代理 | ✅ 已完成 |
| **代理优化** | v1.5 | CDN 镜像智能升级（13 节点）、WBI 签名、扩展 Referer 注入 | ✅ 已完成 |
| **扩展奠基** | v1.6 | Chrome 扩展 v1.0：Referer 注入 + 安装检测 + 遥测日志 | ✅ 已完成 |
| **高清直连** | v1.7 | 扩展 v2.x：扫码登录迁移至扩展、DASH 高清地址回传、WBI 签名内联、流式连接步骤 UI | ✅ 已完成 |
| **生态扩展** | v1.8 | 弹幕加载、播放列表/合集支持、分 P 连续播放 | 🚧 计划中 |
| **体验打磨** | v1.9 | 暗色模式、自定义播放器主题、移动端响应式优化 | 🚧 计划中 |
| **国际化** | v1.10 | i18n、播放统计、快捷键支持 | 🚧 计划中 |

---

## Halo Plugin

### Halo Plugin 版本迭代

| 版本 | 日期 | 核心变更 |
|------|------|----------|
| **v1.0** | 2026-04 | 基础视频嵌入、360P/480P/720P 多清晰度、Video.js 播放器控件 |
| **v1.1** | 2026-04 | 扫码登录、SESSDATA 持久化恢复、高清晰度解锁（后端持有凭证） |
| **v1.2** | 2026-04 | DASH 音视频分离播放、双元素 RAF 毫秒级同步、清晰度动态切换 |
| **v1.3** | 2026-05 | 分辨率自适应画幅比例、SSE 实时日志面板、嵌入代码生成器、PlayerState 无缝切换 |
| **v1.4** | 2026-05 | CDN 代理连接修复、封面图优化、cid 复用修复、管理后台品牌化、移除 Tiptap 编辑器扩展 |
| **v1.5** | 2026-05 | CDN 镜像智能升级（`CdnMirrorUtil`，13 节点轮询）、WBI 签名算法（`WbiSignUtil`）、`nocache` 参数、播放地址缓存 key 修复 |
| **v1.6** | 2026-05 | 浏览器扩展 v1.0 配套：扩展双向握手检测、CDN 容灾/备用 URL 降级、播放器遥测上报、日志 SSE 推送、遥测 GET 绕过 CSRF |
| **v1.7** | 2026-05 | 流式连接步骤 UI（5 节点毛玻璃步骤条）、扩展 DASH 高清直连架构、后端 WBI 模块移除、登录体系完全迁移至扩展、`plugin.yaml` 版本字段修复 |

### Halo Plugin 架构与实现

后端跑在 Halo 的 Spring WebFlux 容器内，职责随版本演进逐步收窄：

**当前职责边界（v1.7）**

| 职责 | 说明 | 对应文件 |
|------|------|----------|
| 嵌入页面生成 | `StringBuilder` 拼接自包含 HTML（内联 CSS + JS + Video.js CDN 异步加载） | `VideoController.java` |
| ≤720P 视频代理 | `Flux<DataBuffer>` 流式反向代理 B站 CDN，注入 Referer/Origin | `VideoController.proxyVideo()` |
| CDN 镜像升级 | 将劣质 CDN（海外/MCDN/P2P）替换为国内优质镜像（13 节点轮询） | `CdnMirrorUtil.java` |
| 视频信息解析 | 从 BV 号解析标题/封面/分 P/尺寸等元数据 | `BilibiliApiService.java` |
| 日志收集 | 环形缓冲（300 条）+ SSE 实时推送 + SLF4J 双写 | `LogService.java` |
| 管理后台 | Vue 3 SPA：扫码登录面板、嵌入代码生成器、日志抽屉 | `HomeView.vue` |

**已移除的职责（迁移至扩展）**

| 原职责 | 移除版本 | 迁移原因 |
|--------|----------|----------|
| SESSDATA 持有与扫码登录 | v1.7 | 安全隔离：凭证不应留在服务端 |
| WBI 签名（高清 playurl） | v1.7 | 高清请求下放至扩展，后端仅处理公开资源 |
| DASH 地址解析（qn≥80） | v1.7 | 扩展持有 SESSDATA 后直接请求 B站 API |

**嵌入播放器引擎**

后端生成的 HTML 页面内联约 200 行 JS，核心模块：

- **双元素 RAF 同步** — `<video>` 播放视频轨 + 隐藏 `<audio>` 播放音频轨，`requestAnimationFrame` 每 16ms 校正一次，±150ms 容忍度
- **清晰度策略** — `qn < 80` 走单元素 FLV/MP4；`qn >= 80` 进入 DASH 双元素模式
- **扩展检测** — `postMessage` 双向握手（5 次递增信号 + 主动 ping），3 秒超时降级代理
- **PlayerState** — 切换清晰度前保存 `currentTime` + `isPlaying`，新实例加载后恢复
- **CDN 容灾** — 5 分钟内 3 次 waiting 事件自动刷新播放地址

---

## 浏览器扩展

### 浏览器扩展版本迭代

扩展版本独立于 Plugin 版本，遵循 `major.minor` 语义：

| 版本 | 日期 | 配套 Plugin | 核心变更 |
|------|------|-------------|----------|
| **v1.0** | 2026-05 | v1.5/v1.6 | `declarativeNetRequest` Referer 注入、content-script 信号广播、安装检测、popup 状态展示 |
| **v2.0** | 2026-05 | v1.7 | 扫码登录迁移至扩展 popup、SESSDATA 存入 `chrome.storage.local`、动态 DNR 规则注入 Cookie、高清 DASH 地址获取与回传 |
| **v2.1** | 2026-05 | v1.7 | 内联 WBI 签名算法（MD5 + mixin_key + w_rid）、修复 buvid3 格式（加 `BUV3` 前缀）、修复 popup.js 空指针、统一 `status` 消息消除时序竞争 |

### 浏览器扩展架构与实现

Chrome MV3 Service Worker + content script + popup，共 5 个文件：

| 文件 | 职责 |
|------|------|
| `manifest.json` | 声明权限（`declarativeNetRequest` + `storage`）、content script 注入规则（`document_start`，`all_frames`） |
| `background.js` | Service Worker：登录流程（二维码生成/轮询/SESSDATA 提取）、WBI 签名、DASH URL 获取、动态 DNR 规则更新 |
| `content-script.js` | 页面注入：统一 `status` 消息广播（先查 background 再发送）、DASH 请求转发、连接记录 |
| `popup.html` / `popup.js` | 工具栏弹窗：展示登录状态/用户信息/最后连接域名、触发扫码登录/登出 |
| `rules.json` | 静态 DNR 规则：`Referer: https://www.bilibili.com` 注入所有 `*.bilivideo.com/*` 请求 |

**扩展登录流程（v2.x）**

```
用户点击扩展图标
  → popup 调用 chrome.runtime.sendMessage({type:'generateQr'})
    → background.js 调 B站 /qrcode/generate
      ← {url, qrcode_key}
    → popup 渲染二维码（前端 Canvas，B站粉配色）
    → 轮询 /qrcode/poll（前端 setInterval 2000ms）
      ← 状态码 0 时从回调 URL 提取 SESSDATA
    → SESSDATA 存入 chrome.storage.local
    → updateDnrRules() 注入 Cookie + Referer 动态规则
    → 调 /nav 验证登录态，获取昵称/头像/等级
```

**DASH 高清回传流程（v2.x）**

```
播放器 iframe（qn≥80）
  → postMessage {type:'requestDash', bvid, cid, qn, fnval}
    → content-script 转发 chrome.runtime.sendMessage({type:'getDashUrl', ...})
      → background.js：
        1. 生成 buvid3（BUV3 + UUID + infoc 格式）
        2. fetch WBI 密钥（imgKey + subKey）
        3. 签名参数（mixin_key + MD5 → w_rid）
        4. fetch B站 /x/player/wbi/playurl（Cookie: SESSDATA + buvid3）
        5. 选取 AVC 视频轨 + 最高码率音频轨
        6. CDN 镜像升级（与后端 CdnMirrorUtil 镜像池保持一致）
      ← {videoUrl, audioUrl, codecs, width, height, quality}
    ← content-script postMessage {type:'dashUrl', ok:true, ...}
  → 播放器直接加载 CDN 直链（扩展已注入 Referer，无需代理）
```

**WBI 签名内联实现（v2.1）**

B站 2023 年起对 playurl API 强制要求 WBI 签名。扩展 background.js 内联完整算法：

1. 从 `nav` API 获取 `img_url` + `sub_url` → 提取 `imgKey` + `subKey`
2. 混音密钥表（`MIXIN_KEY_ENC_TAB`）从 `(imgKey + subKey)` 中抽取 32 字符 = `mixinKey`
3. 参数排序 → URL 编码 → 拼接 `mixinKey` → MD5 → `w_rid`
4. 密钥每小时自动刷新（首次请求时检查过期）

**通信时序修复（v2.1）**

v2.0 的信号策略：先广播 `installed` 消息，再异步查询 background 发 `status`。播放器在 6 秒重试窗口内可能只收到第一条（无 login 信息），误判为未登录而降级到 720P。

v2.1 改为**统一 `status` 消息**：`signal()` 先调用 `chrome.runtime.sendMessage({type:'getStatus'})` 获取完整状态，然后一次性发送 `{type:'status', installed:true, login, userInfo}`。播放器侧优先处理 `status`，兼容 legacy `installed`。

---

## 两端通信协议

扩展与播放器通过 `window.postMessage` 通信，消息格式统一为：

```javascript
{
  source: 'bilibili-player-extension',  // 扩展发出
  // 或
  source: 'bilibili-player',            // 播放器发出
  type: '...',
  ...
}
```

### 消息类型对照表

| 方向 | type | 触发时机 | 负载 |
|------|------|----------|------|
| 扩展 → 播放器 | `status` | content-script 启动 / 响应 ping | `{installed:true, login, userInfo}` |
| 扩展 → 播放器 | `installed` | v2.0 及之前（v2.1 兼容） | `{version:'2.1'}` |
| 扩展 → 播放器 | `dashUrl` | background 返回 DASH 地址后 | `{ok, videoUrl, audioUrl, codecs, width, height, quality, error}` |
| 播放器 → 扩展 | `ping` | 播放器初始化时主动探测 | `{}` |
| 播放器 → 扩展 | `requestDash` | 选择 qn≥80 清晰度时 | `{reqId, bvid, cid, qn, fnval}` |

### 网络架构对比

**扩展未安装（降级模式）**

```
读者浏览器
  → GET /plugins/bilibili-player/api/video/proxy?url=<CDN>
    → Halo 服务器（Spring WebFlux Flux<DataBuffer>）
      → B站 CDN（Referer/Origin 伪装）
```

**扩展已安装（直连模式）**

```
读者浏览器
  → GET <B站 CDN 直链>
    → 扩展 declarativeNetRequest 注入 Referer: bilibili.com
      → B站 CDN（验证通过）
```

---

## 安装

### 1. 安装 Halo Plugin

1. 在 [Releases](https://github.com/aeuicey/bilibili-halo-plugin-player/releases) 或 [Actions](https://github.com/aeuicey/bilibili-halo-plugin-player/actions/workflows/build.yml) 下载最新 JAR 包
2. Halo 后台 → 插件管理 → 上传插件 → 选择 JAR
3. 启用 "BiliBili播放器"

### 2. 安装浏览器扩展（推荐，解锁高清）

1. 下载 `bilibili-player-extension-v2.1.zip`
2. Chrome 地址栏输入 `chrome://extensions/`，开启"开发者模式"
3. 点击"加载已解压的扩展程序"，选择解压后的 `browser-extension/` 文件夹
4. 扩展图标显示在工具栏，点击图标扫码登录 B站

> 扩展未安装时播放器仍可正常工作，但最高仅支持 720P（走服务端代理）。

---

## 使用指南

### 扫码登录（扩展端，推荐）

1. 点击 Chrome 工具栏的扩展图标
2. 点击"生成登录二维码"，用 B站 App 扫码
3. 手机确认授权后，扩展自动保存 SESSDATA
4. 播放器页面自动识别登录态并加载 1080P+/4K

### 生成嵌入代码

1. Halo 后台 → 左侧菜单"B站播放器"
2. 切换到"嵌入代码"标签页
3. 输入 BV 号或 B站链接，点击"解析"
4. 多 P 视频可选择分 P
5. 点击"复制代码"，粘贴到文章编辑器的 HTML 视图中

### 读者端播放

- 播放器自动加载最高可用清晰度
- 右下角画质按钮可切换清晰度
- 横屏/竖屏视频自动匹配正确画幅比例
- 底部步骤条实时展示连接链路状态

---

## 技术栈

| 组件 | 技术 |
|------|------|
| Halo Plugin 后端 | Java 21 / Spring WebFlux / Halo Plugin API |
| Halo Plugin 前端 | Vue 3 + TypeScript / Vite / Halo UI Components |
| 嵌入播放器 | Video.js 8 / 内联 Vanilla JS / 双元素 RAF 同步 |
| 浏览器扩展 | Chrome MV3 / declarativeNetRequest / Service Worker |
| 构建 | Gradle / pnpm |
| CI/CD | GitHub Actions (JDK 21 + Node 20 + pnpm 10) |

---

## 开源协议

本项目基于 [GNU General Public License v3.0](./LICENSE) 开源。

感谢以下项目：
- [Halo](https://github.com/halo-dev/halo) — 优秀的开源博客系统
- [create-halo-plugin](https://github.com/halo-dev/create-halo-plugin) — Halo 插件模板
- [Video.js](https://videojs.com) — Web 播放器框架
- [Bilibili API](https://api.bilibili.com/) — 视频信息与播放地址接口
