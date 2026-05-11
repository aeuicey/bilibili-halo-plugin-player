# BiliBili Halo Plugin Player

[![Build](https://github.com/aeuicey/bilibili-halo-plugin-player/actions/workflows/build.yml/badge.svg)](https://github.com/aeuicey/bilibili-halo-plugin-player/actions/workflows/build.yml)

为 [Halo](https://github.com/halo-dev/halo) 博客系统提供 B站视频播放器嵌入插件，支持扫码登录获取高清晰度、DASH 音视频分离播放、多清晰度动态切换、分辨率自适应画幅比例。

<img width="1357" height="1692" alt="image" src="https://github.com/user-attachments/assets/8ec6109b-2c2b-43c3-ae55-6bee598196aa" />


## 功能特性

- **扫码登录** — 在插件管理后台生成 B站 登录二维码，扫码授权后自动持久化登录状态
- **多清晰度支持** — 360P / 480P / 720P / 1080P / 1080P60 / 4K，登录后解锁更高画质（需大会员）
- **DASH 音画分离播放** — 视频 `<video>` + 隐藏 `<audio>` 双元素 `requestAnimationFrame` 毫秒级同步
- **分辨率自适应** — 自动识别横屏(16:9)、竖屏(9:16)、方形视频，嵌入代码 + 播放器同步对应画幅比例
- **后台管理** — 输入 BV 号即可生成嵌入代码，一键复制，粘贴到文章 HTML 编辑器即可使用
- **实时日志** — 内置调试日志面板，实时推送后端请求日志，方便排查问题
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
