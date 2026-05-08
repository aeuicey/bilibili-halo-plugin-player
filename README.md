# BiliBili Halo Plugin Player

为 [Halo](https://github.com/halo-dev/halo) 博客系统提供 B站视频播放器嵌入插件，支持扫码登录获取高清晰度、DASH 音画分离播放、多清晰度动态切换。

## 功能特性

- **扫码登录** — 在插件管理后台生成 B站 登录二维码，扫码授权后自动持久化登录状态
- **多清晰度支持** — 获取 480P / 720P / 1080P 多档清晰度，登录后解锁更高画质
- **DASH 播放** — 基于 Media Source Extensions 在浏览器端合成音画分离的视频流，无需转码
- **后台管理** — 输入 BV 号即可生成嵌入代码，一键复制，粘贴到文章 HTML 编辑器即可使用
- **实时日志** — 内置调试日志面板，SSE 实时推送后端请求日志，方便排查问题

## 安装

1. 在 [Releases](https://github.com/aeuicey/bilibili-halo-plugin-player/releases) 页面下载最新 JAR 包
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
- 点击"复制代码"，将 HTML 代码粘贴到文章编辑器的 HTML 视图中
- 页面读者即可看到内嵌的 B站播放器

### 3. 读者端播放

- 播放器自动加载最高可用清晰度
- 点击右下角画质按钮可切换清晰度
- 支持自适应宽高比，响应式布局

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
| 前端 | Vue 3 + TypeScript / Vite |
| 构建 | Gradle / pnpm |

### DASH 播放原理

1. 调用 B站 `playurl` API 获取 DASH 格式的音视频流地址
2. 通过服务端代理转发视频片段（绕过跨域限制）
3. 浏览器端使用 `MediaSource` API 分别加载音视频 `SourceBuffer`
4. 音视频流在客户端自动同步合成播放

## 开源协议

本项目基于 [GNU General Public License v3.0](./LICENSE) 开源。

感谢以下项目：
- [Halo](https://github.com/halo-dev/halo) — 优秀的开源博客系统
- [create-halo-plugin](https://github.com/halo-dev/create-halo-plugin) — Halo 插件模板
- [EasyPlayer.js](https://github.com/EasyDarwin/EasyPlayer.js) — 播放器设计参考
- [Bilibili API](https://api.bilibili.com/) — 视频信息与播放地址接口
