# 技术路线

本文档描述 BiliBili Halo Plugin Player 的完整技术实现：视频解析、流选择、音画同步、流分发与容错。面向想了解实现细节或参与开发的读者；使用与部署说明见 [README](../README.md)。

## 总览

```
管理端 (Vue3, Halo Console)
  ├─ 扫码登录 ──────► passport.bilibili.com（WBI / Set-Cookie）
  ├─ 视频解析 ──────► api.bilibili.com（view / wbi/view/detail / wbi/playurl）
  └─ 生成嵌入代码 ◄── <iframe src="/plugins/bilibili-player/embed?bvid=...&cid=...">

读者端嵌入页（服务端生成的自包含 HTML + Video.js）
  ├─ playurl 一次拉取全量 DASH 列表（后端四级降级链）
  ├─ 清晰度切换 = 前端本地换轨，不重调 API
  └─ 流地址三级分发：浏览器直连 → CF Worker 代理 → 服务器代理兜底
```

## 一、视频解析机制

### playurl 请求策略

`GET /x/player/wbi/playurl`，WBI 签名（mixin key + MD5，密钥缓存 1 小时），携带 SESSDATA / Referer / UA：

- `fnval=3344`（16 DASH | 128 4K | 1024 8K | 2048 AV1），`qn=127` 取全量清晰度，`fourk=1`
- 刻意不含 64(HDR) / 256(杜比音频) / 512(杜比视界)：这些流为 HEVC/Dolby-only，浏览器原生 `<video>` 基本无法解码

### 四级降级链（后端 `BilibiliApiService.getVideoPlayUrlWithFallback`）

任一步 code≠0 / 超时 / 无可用流即进入下一级，全程经 LogService 记录：

| 级别 | 参数 | 产物 |
|------|------|------|
| ① | fnval=3344, qn=127 | 全量 DASH（4K/8K/AV1） |
| ② | fnval=16, qn=80 | 基础 DASH |
| ③ | fnval=1, qn=64 | durl MP4 单文件（音画同体） |
| ④ | platform=html5, high_quality=1, fnval=1 | 无 Referer 鉴权的 1080P MP4 兜底 |

响应附加字段：`fetchedAt`（流 URL 120 分钟过期）、`strategy`、`supportFormats`、`dash.dolby`、`dash.flac`。

### 视频信息双接口（风控回退）

B 站对数据中心 IP 按接口路径风控：无签名的 `x/web-interface/view` 可能返回 HTML 拦截页（HTTP 412）。策略：

1. 首选 `view`（网络异常自动重试一次，超时 15s）
2. 返回非 JSON / 网络失败 → 回退 WBI 签名的 `x/web-interface/wbi/view/detail`（B 站 Web 端现行接口），取 `data.View` 字段
3. 非 JSON 响应记录状态码与内容摘要，便于诊断

### 扫码登录的 SESSDATA 提取

新版扫码接口确认登录后，SESSDATA 不再出现在回调 URL 参数中（URL 变为 ticket 形式的 crossDomain 地址）。按优先级三级提取：

1. 轮询响应的 `Set-Cookie` 响应头（新版主路径）
2. 回调 URL 参数（旧版兼容）
3. 请求 crossDomain 地址，从其响应 `Set-Cookie` 取（兜底）

SESSDATA 持久化到 `~/.halo-bilibili-player/sessdata`，重启自动恢复。

## 二、流选择算法（嵌入页前端）

一次 playurl 拉取全量 DASH 轨列表后，所有切换均为本地换轨：

1. **按目标 qn 过滤** `dash.video[]`；缺流时向 `accept_quality` 相邻更低档降级
2. **编码优先级**：`avc1`（保底，始终可解）> `av01` / `hev1`（仅 `canPlayType` 返回 `probably` 才考虑）；同编码多条码流取 bandwidth 中位者（更稳定）
3. **音频轨**：取最高 bandwidth；大会员且浏览器支持时可扩展 `dash.dolby` / `dash.flac`
4. **清晰度切换**：菜单点击 → 保存 `PlayerState`（进度/播放态）→ 换轨 → 恢复进度；仅当流 403 或距 `fetchedAt` 超 110 分钟时才重新请求 playurl

## 三、DASH 音画分离播放（Dual Element RAF Sync）

B 站 1080P+ 为 DASH 协议，音视频分离为独立 m4s。方案与 B 站官方播放器思路一致（音画分离 + 客户端同步），但用原生 `<video>` / `<audio>` 标签替代 MSE 管线：

```
dash.video[] (m4s) → <video>          （浏览器内置解码器，AVC/HEVC/AV1 自适应）
dash.audio[] (m4s) → <audio hidden>   （隐藏 DOM，相同候选地址链）
                          ↑
               requestAnimationFrame 每帧校正（±150ms 容忍度）
```

- **纠偏**：`|video.currentTime - audio.currentTime| > 0.15s` 且音频播放中时，对齐音频时间
- **断粮暂停**：仅当音频真正断粮（缓冲末端落后于播放点，或 stalled 且前瞻缓冲不足 0.5s）才暂停 video，等 audio `canplay` 恢复——纠偏产生的瞬时 `seeking` 不触发暂停，避免 play/pause 抖动
- **事件钩子**：`seeked` / `ratechange` / `volumechange` 同步到 audio 元素

### 为何不用 MSE / WebAV

初期尝试了 `MediaSource` + `SourceBuffer` 手工推流与 `@webav/av-cliper` 混流合并，均因 Spring WebFlux 代理返回的 `ReadableStream` 不兼容浏览器原生 `pipeThrough` 接口而失败。双元素方案零外部依赖、利用浏览器原生解码，代价是无码率自适应（只能整流切换）。

## 四、三级流分发（省流架构）

服务器带宽有限时，观众播放不应消耗服务器带宽。嵌入页 `<head>` 带 `<meta name="referrer" content="no-referrer">`，流地址按「流分发模式」生成**有序候选数组**，媒体加载失败时递进下一候选并保持播放进度：

| 模式 | 候选顺序 | 适用场景 |
|------|---------|---------|
| `smart`（默认） | 浏览器直连 → Worker 代理 → 服务器代理 | CDN 放行空 Referer，最大化省流 |
| `worker` | Worker 代理 → 服务器代理 | 直连被拦但 Worker 可用 |
| `server` | 仅服务器代理 | 最保守，全部流量经服务器中转 |

```
smart 模式:
浏览器 (no-referrer) ──直连──> B站 CDN        ← 空 Referer 放行
        │ 失败降级
        ├─────────> Cloudflare Worker (?url=&token=，伪装 Referer/Origin，透传 Range)
        └─────────> /api/video/proxy?url=<Bilibili CDN>   ← 始终兜底
                      → HttpClient InputStream → Flux<DataBuffer> 64KB 分块
                        → B站 CDN (Referer/Origin 伪装)
```

- 每个 tier 内 baseUrl → backupUrl 依次尝试；video/audio 元素独立递进候选索引
- Worker 地址与令牌在插件设置页配置；留空时 `smart` 自动跳过 Worker 层
- Worker 脚本见 [`workers/bili-proxy.js`](../workers/bili-proxy.js)，含域名白名单与可选令牌校验

### Worker 分块边缘缓存

`workers/bili-proxy.js` 内置 2MB 块对齐缓存，缓解 CF 链路（观众 → CF 边缘 → B 站 CDN）的逐次回源延迟：

- Range 请求归一化到 2MB 块边界，块缓存在 CF 边缘（`caches.default`，key = 流 URL 哈希 + 块序号）
- 命中即免回源：单块 Range 直接切片返回；少量跨块内存拼装；大范围（如 `bytes=0-` 渐进加载）逐块流式输出，客户端断开即停
- 无 Range 的整体请求流式透传，同时 `tee()` 后台按块写入缓存
- B 站流 URL 120 分钟过期，缓存块带 `x-cached-at` 时间戳，超 100 分钟自动失效重取；超 500MB 不缓存（CF 免费版单文件上限 512MB）
- 收益：同一观众的 seek/重看/断线重连全部边缘命中；多观众在 URL 有效期内命中同一份缓存

## 五、播放容错链

```
媒体 error → 递进候选 URL（保持 currentTime）
  全部耗尽 → 重新拉取 playurl（应对 403/过期），仅一次防死循环
5s 解码看门狗（无 loadeddata/progress）
  → 同 qn 换次优编码轨 → 整档降 qn → MP4 单文件模式
MP4 模式：音画同体直出，关闭 RAF 同步，移除隐藏 audio 元素
```

## 六、分辨率自适应

- 嵌入代码：`aspect-ratio` 使用 DASH 轨道实际 `W/H`，不硬编码 `16/9`
- 播放器：换轨后将轨道 `width/height` 注入 CSS 容器
- 管理后台：解析后显示 `1920×1080 · 横屏` 或 `1080×1920 · 竖屏` 标记

## 七、可观测性

- 后端 `LogService`：内存环形缓冲（300 条）+ `Sinks.Many` SSE 实时推送
- 播放器遥测 `/api/player/log`：ready / fetch / dash（选中 codec 与带宽）/ proxyTier（分发层级）/ decodeFail / fallback / stall / syncWait / 播放操作等
- 管理端日志面板：SSE 实时接收（断线降级 2s 轮询），按 `time+level+msg` 去重，级别筛选 + 自动滚动

## 八、管理端 UI 规范

遵循 Halo Console 设计约定：`VPageHeader` + `VTabbar`（账号登录 / 视频嵌入 / 运行日志）+ `VCard` 骨架；组件取自 `@halo-dev/components`（VStatusDot / VLoading / VDescription / VAlert 等）；HTTP 请求统一走 `@halo-dev/api-client` 的 `axiosInstance`；嵌入站点地址取 `globalInfo.externalUrl`（反代部署下正确）；样式全部 scoped，不污染全局。
