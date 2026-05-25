# Plugin-Bilibili-Player：程序思路全解

> 一个完整的 B站视频嵌入 Halo 博客方案 —— 从扫码登录到 DASH 音画同步播放，踩过每一个坑，做出了每一个取舍。

---

## 一、解决什么问题

在 Halo 博客中嵌入 B站视频看似简单 —— 贴个 `<iframe src="//player.bilibili.com/...">` 就行了。但 B站官方播放器有三重墙：

1. **画质墙**：未登录只能 480P，登录后才有 720P+。如果服务端持有登录态，读者无需登录 B站就能看到高清视频。

2. **防盗链墙**：B站 CDN 校验 `Referer` 和 `Origin` 头，非 bilibili.com 域名的请求直接 403。直链拿不到，iframe 里播不了。

3. **音画分离墙**：720P 及以上 DASH 格式，视频和音频是两根独立 m4s 流。如果只用 `<video src="视频流">`，用户看了个默片。

本插件把这三重墙全拆了：服务端伪造身份登录并持有 SESSDATA，充当反向代理中转 CDN 流量，嵌入页面里用双元素 RAF 同步方案让视频和音频流精准对齐。

---

## 二、架构全景

```
┌─────────────────────────────────────────────────────────────────┐
│                         Halo 博客系统                            │
│                                                                  │
│  ┌────────────────────┐          ┌────────────────────────────┐ │
│  │   管理后台 (Vue 3)  │  ◄───►  │   后端 (Spring WebFlux)     │ │
│  │                    │  REST   │                             │ │
│  │  · 扫码登录         │         │  · BilibiliApiService       │ │
│  │  · 嵌入代码生成      │         │  · VideoController          │ │
│  │  · SSE 实时日志      │         │  · LoginController         │ │
│  └────────────────────┘         │  · LogService               │ │
│                                  │  · CdnMirrorUtil            │ │
│  ┌────────────────────┐         │                             │ │
│  │  嵌入播放器 (HTML)   │  ◄───►  │                             │ │
│  │                    │  代理    └──────────┬─────────────────┘ │
│  │  · 双元素 RAF 同步   │                   │                    │
│  │  · 清晰度切换        │          ┌────────▼─────────────────┐ │
│  │  · 扩展直连模式      │          │   B站 API / CDN           │ │
│  └────────────────────┘          │                           │ │
│                                   │  · passport.bilibili.com   │ │
│  ┌────────────────────┐          │  · api.bilibili.com        │ │
│  │ 浏览器扩展 (Chrome) │──post──►  │  · upos-*.bilivideo.com   │ │
│  │                    │ Message  │                           │ │
│  │  · Referer 伪造    │          └───────────────────────────┘ │
│  │  · 安装检测        │                                         │
│  └────────────────────┘                                         │
└─────────────────────────────────────────────────────────────────┘
```

系统由四个独立运行的部分组成，通过 HTTP 和 `postMessage` 通信：

| 层面 | 运行时 | 职责 |
|------|--------|------|
| **管理后台** | 浏览器 / Halo Admin | 博主操作界面：扫码登录、生成嵌入代码、实时日志 |
| **后端服务** | JVM / Spring WebFlux | B站 API 代理、CDN 反向代理、嵌入页面生成、日志收集 |
| **嵌入播放器** | 读者浏览器 iframe | 视频播放、音画同步、清晰度切换、扩展检测 |
| **浏览器扩展** | Chrome Extension | Referer 头注入，让播放器绕过代理直连 CDN |

---

## 二点五、UI 设计指导（DESIGN.md）

本项目参考 `DESIGN.md` 中的 Cal.com 设计系统分析作为 UI 设计指导。该文件定义了一套经过实战验证的 SaaS 级设计令牌体系，分为五个维度：

**颜色系统（27 个令牌）**：`primary: #111111`（黑色主 CTA）、`canvas: #ffffff`（白色基底）、`surface-soft/card/strong/dark`（四层表面层级递进）、功能色（success `#10b981` / warning `#f59e0b` / error `#ef4444`）、徽章色（orange/pink/violet/emerald 四色增强视觉区分度）。品牌电压来自 `--bp-pink: #fb7299`（B站粉），与 Cal.com 的中性色体系互补。

**排版系统（12 个层级）**：从 `display-xl`（64px Cal Sans 600wt）到 `caption`（13px Inter 500wt），覆盖展示、标题、正文、代码、按钮、导航全部场景。关键约束：display 层级用 Cal Sans 展示品牌个性，功能文本（title/body/button/nav）统一用 Inter。

**间距系统（8 级）**：4/8/12/16/24/32/48/96px —— 基于 4px 网格的严格递增，从微间距到 section 级留白。

**圆角系统（7 级）**：4/6/8/12/16px + pill/full —— 卡片主容器用 `lg`（12px），按钮用 `md`（8px），导航胶囊用 `pill`（9999px）。

**组件规范**：`button-primary`（黑底白字 14px/600wt，40px 高度，12px×20px 内边距）、`top-nav`（白底 64px 高）、`hero-band`（96px 内边距）、`feature-card`（`surface-card` 底 + 32px 内部间距）、`nav-pill-group`（`surface-soft` 底胶囊容器）等，每个组件都是颜色+排版+圆角+间距 Token 的组合引用。

**与现有 UI 的关系**：

- 当前 `HomeView.vue` 的 `--bp-*` CSS 变量体系设计思路上与 DESIGN.md 同构 —— 都是 Token 驱动的约束系统
- 品牌色（B站粉 `#fb7299`）保留，但中性色层级（text/text-secondary/text-muted/border/bg）应逐步向 DESIGN.md 的 ink/body/muted/hairline/surface 体系对齐，以获得更成熟的信息层级
- 间距体系已部分对齐（`bp-wrap` 的 `gap: 16px` 对应 `md`，`padding: 24px` 对应 `lg`），可进一步统一至 4px 网格
- 按钮系统可参考 DESIGN.md 的 primary/secondary/disabled 三态规范，统一高度和内边距

---

## 三、后端正交层

后端是插件的中枢神经，跑在 Halo 的 Spring WebFlux 容器内。所有对外交互 —— 无论是管理员的 API 调用还是读者浏览器的视频请求 —— 都经过这一层。

### 3.1 插件入口：最小的胶水

```java
// BilibiliPlayerPlugin.java
@Component
public class BilibiliPlayerPlugin extends BasePlugin {
    public BilibiliPlayerPlugin(PluginContext pluginContext) { super(pluginContext); }
    @Override public void start() {}
    @Override public void stop() {}
}
```

插件入口几乎什么也不做 —— Halo 的 `BasePlugin` 已经处理好了生命周期、类路径隔离和路由注册。真正的逻辑在 `@RestController` 和 `@Service` 中，由 Spring 自动装配。

### 3.2 登录流程（v1.7 迁移至浏览器扩展）

> v1.7 之前，扫码登录由后端 `BilibiliApiService` 完成，SESSDATA 持久化到服务器文件系统。v1.7 将登录体系完全迁移至浏览器扩展，服务端不再持有任何用户凭证。

**扩展端登录流程：**

1. 用户点击浏览器工具栏扩展图标 → popup 页面调用 `chrome.runtime.sendMessage({type: 'generateQr'})`
2. 扩展 background.js 调 B站 `passport.bilibili.com/x/passport-login/web/qrcode/generate`
3. 前端轮询 `pollQr` → background.js 调 `qrcode/poll` → 状态码 `0` 时从回调 URL 提取 `SESSDATA`
4. SESSDATA 存入 `chrome.storage.local`，同时更新 DNR 动态规则（注入 Cookie + Referer）
5. 调用 `nav` API 验证登录态，获取用户昵称/头像/等级

**为什么迁移到扩展？**

- **安全隔离** — SESSDATA 是 B站登录凭证，服务端持有意味着凭证泄露风险。扩展的 `storage.local` 仅在用户本地，且受 Chrome 沙箱保护
- **权限最小化** — 后端不再需要调用需登录态的高清 API，职责简化为 ≤720P 的公开资源代理
- **多租户安全** — Halo 博客可能有多个管理员，服务端凭证无法区分"谁扫码"，扩展端每个用户独立凭证

### 3.3 视频数据链路：从 BV 号到播放地址

当管理员在前端输入 `BV1xx411c7mD` 并点击"获取视频"，发生两次 API 调用：

**调用一：获取视频信息**

```
GET https://api.bilibili.com/x/web-interface/view?bvid=BV1xx411c7mD
→ {title, pic, owner, pages[{cid, page, part}], stat, dimension}
```

后端解析为结构化的视频信息，包括封面图、UP 主、分 P 列表、互动数据（播放/弹幕/点赞数）和原始尺寸。

**调用二：获取播放地址（带 WBI 签名）**

```
GET https://api.bilibili.com/x/player/wbi/playurl?bvid=...&cid=...&qn=80&fnval=16&fnver=0&fourk=1&wts=...&w_rid=...
```

这里有两个关键参数：

- `qn=80`：请求画质等级（80 = 1080P）。实际返回的清晰度取决于登录状态和视频本身支持。
- `fnval=16`：请求 DASH 格式（`0x10` = 音视频分离）。如果视频只有 480P 以下，B站返回 `durl`（单根 MP4 直链）。

**WBI 签名为什么要存在？**

B站从 2023 年开始对 playurl API 强制要求 WBI 签名，否则直接拒绝请求。签名流程：

```
1. 从 nav API 获取 img_url 和 sub_url（两个图片链接）
2. 提取文件名（去掉路径和扩展名）作为 imgKey 和 subKey
3. 用混音密钥表（MIXIN_KEY_ENC_TAB）从 (imgKey + subKey) 中抽取 32 个字符 = mixinKey
4. 参数排序 → URL 编码 → 拼接 mixinKey → MD5 → 得到 w_rid
```

两个密钥每 1 小时自动刷新一次，避免了手动维护的麻烦。这是 B站反爬的基础防线，本插件的实现完全遵循官方算法。

**播放地址缓存**

同一个 `bvid+cid+qn+fnval` 组合的请求结果缓存 10 分钟（`CACHE_TTL_MS`），避免重复调用 B站 API 触发频率限制。前端传 `nocache=1` 可强制刷新。

### 3.4 CDN 代理：为什么需要它，以及如何升级

B站视频/音频的真实 CDN 地址形如：

```
https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/...
```

浏览器直接向这个地址发 GET 请求时，B站 CDN 校验 HTTP 头：
- `Referer` 必须是 `https://www.bilibili.com`
- `Origin` 必须是 `https://www.bilibili.com`

博客页面部署在 `alicetec.cn`，这两个头都不对，CDN 直接返回 403。

**方案：服务端反向代理**

```java
// VideoController.proxyVideo()
HttpRequest.newBuilder()
    .uri(URI.create(url))
    .header("Referer", "https://www.bilibili.com")
    .header("Origin", "https://www.bilibili.com")
    .GET()
```

后端以 B站 的身份去拉 CDN 内容 → 拿到 `InputStream` → 用 `Flux.generate()` 切成 64KB 的 `DataBuffer` 块 → 以 `application/octet-stream` 流式返回给浏览器。

浏览器端看到的是：

```
https://alicetec.cn/plugins/bilibili-player/api/video/proxy?url=<编码后的CDN地址>
```

Referer 校验在服务端完成了，浏览器不需要操心。同时支持 `Range` 请求（拖拽进度条时的分段加载），代理层透传 `Content-Range` 和 `Accept-Ranges` 头。

**CORS 放宽**

代理响应强制加了 `Access-Control-Allow-Origin: *`，因为嵌入播放器和博客页面可能不同源（iframe 场景）。

**CDN 升级（CdnMirrorUtil）**

B站给不同用户分配的 CDN 节点质量差异很大：

- `upos-sz-mirrorcos`：标准深圳镜像，国内速度 OK
- `upos-sz-mirrorhw`：华为云镜像，部分地区更快
- `mcdn.bilivideo.com`：P2P CDN（MCDN），依赖其他用户上传，质量不稳定
- `upos-sz-mirrorcf.bilivideo.com`：Cloudflare 海外节点，国内访问慢

`CdnMirrorUtil.upgradeCdnHostname()` 在返回播放地址之前对每条 CDN URL 做一次升级判断：

```
if (免流CDN 或 已是优质镜像CDN) → 原样返回
if (海外CDN 或 MCDN P2P)       → 替换为国内镜像（轮询选取）
if (普通 UPOS CDN)             → 替换为国内镜像（轮询选取）
```

国内镜像池包含 13 个节点：阿里云、腾讯云、华为云、百度云、金山云等。轮询选取（`AtomicInteger` 自增取模）实现简单负载均衡。

### 3.5 嵌入播放器生成

这是最有趣的部分 —— 后端不返回 JSON，而是直接生成一个自包含的 HTML 页面。

```java
// VideoController.embedPlayer()
@GetMapping(value = "/plugins/bilibili-player/embed", produces = "text/html")
public Mono<ResponseEntity<String>> embedPlayer(@RequestParam String bvid, @RequestParam String cid)
```

生成策略：

1. **CSS 双保险**：`<link>` 加载 Video.js CDN 样式，同时内联一套 `video-js` 关键布局 CSS。如果 CDN 挂了，控件不会错位。备用 CDN 也写了 `onerror` 回退到 jsdelivr。

2. **不依赖外部 JS CDN 加载播放器核心**：Video.js 自身通过内联脚本异步加载（`loadVideoJS` 函数），先检查 `window.videojs` 是否存在，不存在才动态创建 `<script>` 标签。如果两个 CDN 都失败，进入错误状态。

3. **预连接优质 CDN**：`<link rel="preconnect">` 和 `<link rel="dns-prefetch">` 提前建立到镜像 CDN 的连接，减少视频首帧延迟。

4. **内联所有业务逻辑**：约 200 行的 JS 内联在页面中，包括：
   - 双元素音视频同步引擎
   - 清晰度切换逻辑
   - 浏览器扩展检测
   - CDN 代理模式切换
   - 播放器遥测上报
   - 错误处理与提示

整个过程不依赖任何外部 JS 文件（Video.js 本身除外），避免了 npm 构建链路。

### 3.6 日志系统：环形缓冲 + SSE 推送

```java
@Service
public class LogService {
    private final ConcurrentLinkedQueue<Map<String, Object>> history = new ConcurrentLinkedQueue<>();
    private final Sinks.Many<Map<String, Object>> sink = Sinks.many().multicast().onBackpressureBuffer(200, false);
```

设计思路：

- **环形缓冲**：`history` 队列容量上限 300 条，超出时自动淘汰最旧的记录。防止内存无限增长。
- **SSE 实时推送**：`sink.asFlux()` 通过 `MediaType.TEXT_EVENT_STREAM` 暴露给前端，前端 `EventSource` 接收实时日志流。
- **兜底轮询**：前端每 2 秒调 `/logs/history` 拉取全量历史，弥补 SSE 在某些代理环境下的不稳定性。
- **双写**：日志同时写入 SLF4J（输出到 Halo 日志文件）和内存队列。管理员在插件面板看到的是内存队列的内容（前端轮询），Halo 的日志文件保留完整历史。

日志级别过滤在前端完成，后端只提供 `INFO/WARN/ERROR/DEBUG` 标记。

---

## 四、管理后台（Vue 3 Admin 面板）

前端是 Halo 插件体系下的标准 Vue 3 SPA，使用 Halo 官方组件库（`@halo-dev/components`）。

### 4.1 路由注册

```typescript
// ui/src/index.ts
export default definePlugin({
  routes: [
    { parentName: 'Root', route: { path: '/bilibili-player', component: HomeView } },
    { parentName: 'PluginRoot', route: { path: '/plugins/bilibili-player', component: HomeView } }
  ]
})
```

两个路由指向同一个组件 —— Halo 侧边栏的"工具"组和插件配置页共用同一套界面。

### 4.2 登录面板

登录流程的状态机：

```
idle → loading → pending → scanned → success
                    ↓          ↓
                 expired     error
```

关键交互细节：

- 二维码由前端 `qrcode.toDataURL()` 在 Canvas 上渲染，配色为 B站粉（`#18191c` 暗色码块 + 白色背景）。不依赖任何后端图片生成。
- 轮询由前端 `setInterval(2000ms)` 驱动，不是后端推送。这样即使浏览器切到后台，定时器仍能工作（虽然浏览器会限速）。
- 连续 3 次轮询失败自动将二维码标为过期，防止网络异常时的无限等待。
- 登录成功后等 800ms 再调用 `checkLogin()` —— B站后台 SESSDATA 生效有短暂延迟，立即查可能返回未登录。

### 4.3 嵌入代码生成器

输入 BV 号或完整 B站链接 → 解析 → 调两个 API → 展示结果 → 生成 HTML 代码。

**输入解析：**

```typescript
const BVID_REGEX = /BV[a-zA-Z0-9]{10}/
const AVID_REGEX = /av(\d+)/i
```

支持 `BV1xx411c7mD`、`https://www.bilibili.com/video/BV1xx411c7mD`、`av170001` 三种格式。

**双模式嵌入代码：**

默认模式（简约）：
```html
<iframe src="..." style="width:100%;aspect-ratio:16/9;border:none;border-radius:8px"
  allowfullscreen allow="autoplay;encrypted-media" loading="lazy"></iframe>
```

展开"尺寸设置"后（受控）：
```html
<div data-bilibili-player="true" data-bvid="BV..." data-cid="..."
  style="position:relative;width:100%;max-width:800px;aspect-ratio:1920/1080;...">
  <iframe src="..." style="position:absolute;top:0;left:0;width:100%;height:100%;border:none" ...></iframe>
</div>
```

细节：
- `aspect-ratio` 不使用硬编码 `16/9`，而是从 DASH 视频轨道提取真实 `width/height`。竖屏视频（如 B站竖屏模式）会是 `9/16`。
- `data-bilibili-player` 属性为未来可能的 CSS 选择器 / 脚本增强预留。
- `loading="lazy"` 减少首屏带宽占用。
- 封面图通过代理加载并添加 `@320w_200h_1e_1c` 后缀（B站图片处理参数），缩略图仅几十 KB。

**图片代理处理：**

管理后台的封面图和头像也走代理 + 缩略图优化：

```typescript
function proxyImage(url) {
  let secure = url.startsWith('http://') ? 'https://' + url.substring(7) : url
  if (!secure.includes('@')) secure += '@320w_200h_1e_1c'
  return `${API}/video/proxy?url=${encodeURIComponent(secure)}`
}
```

B站图片 CDN 常返回 HTTP 链接，在 HTTPS 页面上会被浏览器拦截（混合内容警告）。函数强制升级为 HTTPS，并附加缩略图后缀。

### 4.4 日志抽屉

右下角悬浮按钮 → 从右侧滑入的抽屉面板。

特色：
- 5 级过滤（ALL / INFO / WARN / ERROR / DEBUG），后端日志级别在上层已标记
- 自动滚动开关（`VSwitch` + `nextTick` + `scrollTop = scrollHeight`）
- 清空按钮只清前端展示，不影响后端 Halo 日志文件
- 过滤后的条目数实时显示在标题栏

---

## 五、嵌入播放器（核心引擎）

这是整个项目最精彩的部分 —— 一段约 200 行内联 JavaScript，在读者的浏览器里驱动整个播放体验。

### 5.1 双元素 RAF 同步（DASH Dual Element Sync）

B站 720P+ 视频采用 DASH 格式，视频轨（m4s）和音频轨（m4s）是两根独立的 HTTP 流。浏览器的 `<video>` 元素不能同时播放两个流。

**走过的弯路：**

最初尝试了 `MediaSource` + `SourceBuffer`（MSE）方案：JavaScript 代理层 fetch 两根流 → append 到 SourceBuffer → 播放器播放合并后的流。但在 Spring WebFlux 的 `Flux<DataBuffer>` 代理返回的 `ReadableStream` 上，浏览器的 `pipeThrough` 无法正常工作 —— MSE 要求精确的字节级控制，而代理层是 chunked transfer，两者的边界语义不兼容。

也试过 `@webav/av-cliper` 的 `mixinMP4AndAudio` 做服务端合并，但 m4s 不是标准 MP4，合并需要重新封装容器，性能开销太大。

**最终方案：双元素 + 帧同步**

思路和 B站官方播放器一样 —— 视频归视频，音频归音频，客户端做同步。

```
┌─────────────────────────────┐
│  <video> 元素（可见）         │
│  src = 视频轨 CDN URL        │
│  浏览器内置 H.264/HEVC 解码  │
└──────────────┬──────────────┘
               │
               │  requestAnimationFrame 每帧校正
               │  ±150ms 容忍度
               │
┌──────────────▼──────────────┐
│  <audio> 元素（隐藏）         │
│  src = 音频轨 CDN URL        │
│  自动播放，与视频同步        │
└─────────────────────────────┘
```

**同步引擎核心逻辑：**

```javascript
function syncAudio() {
  if (!audioReady || audio.paused) return;
  var diff = Math.abs(video.currentTime - audio.currentTime);
  if (diff > 0.15) {  // 超过 150ms 才校正
    audio.currentTime = video.currentTime;
  }
  if (video.paused && !audio.paused) audio.pause();
  if (!video.paused && audio.paused) audio.play();
}

setInterval(syncAudio, 16);  // ~60fps 校正
```

注意这里用 `setInterval` 而非 `requestAnimationFrame` —— 视频播放时 RAF 的频率取决于显示器刷新率，而音频同步需要稳定的时间间隔。16ms 对应约 60 次/秒的校正频率，远高于人耳能感知的 ±150ms 容差。

**事件级联：**

```
video.onplay    → audio.play()
video.onpause   → audio.pause()
video.onseeked  → audio.currentTime = video.currentTime; audio.play()
video.onratechange → audio.playbackRate = video.playbackRate
video.onvolumechange → audio.volume = video.volume
```

鼠标拖进度条时的 `seeked` 事件是关键 —— 必须在事件触发后立即同步音频位置，否则会先听到旧位置的音频，然后再跳转。

### 5.2 清晰度策略：MP4 与 DASH 的分界线

| 清晰度 | QN 值 | fnval | 返回格式 | 播放方式 |
|--------|-------|-------|---------|---------|
| 360P | 16 | 1 | durl（单根 MP4 直链） | 单一 `<video>` |
| 480P | 32 | 1 | durl | 单一 `<video>` |
| 720P | 64 | 1 | durl | 单一 `<video>` |
| 1080P | 80 | 16 | DASH 音视频分离 | 双元素 RAF 同步 |
| 1080P60 | 116 | 16 | DASH | 双元素 RAF 同步 |
| 4K | 120 | 16 | DASH | 双元素 RAF 同步 |

分界线在 `qn >= 80`。低于 80 时，B站 API 返回 `durl` 数组（每段一个直链 URL），播放器用一个 `<video>` 元素直接播放，简单省事。`qn >= 80` 时，进入 DASH 模式，播放器动态创建隐藏的 `<audio>` 元素并启动同步引擎。

**清晰度切换时的 PlayerState：**

切换清晰度相当于重新创建 Video.js player 实例并加载新的源。为了做到无缝体验，`PlayerState` 在销毁前保存了：

- `currentTime`：当前播放位置
- `isPlaying`：是否正在播放

新实例加载完成后恢复这两个状态，用户几乎感觉不到切换（除了短暂的缓冲）。

### 5.3 浏览器扩展检测与直连模式

CDN 代理是一个瓶颈 —— 所有视频流量从 B站 CDN → Halo 服务器 → 读者浏览器，服务端带宽压力大，延迟也高。有没有办法让读者浏览器直接连接 B站 CDN？

有，但需要伪造 `Referer` 头。浏览器出于安全原因不允许 JavaScript 修改 `Referer`，但 Chrome 扩展可以。

**detection → 三方握手：**

```
播放器页面 (iframe)           浏览器扩展 (content script)
       │                              │
       ├── window.postMessage ──────►│  "ping"（主动探测）
       │   {source:'bilibili-player',│
       │    type:'ping'}             │
       │                              │
       │◄── window.postMessage ──────┤  "pong"（响应）
       │   {source:'bilibili-player- │
       │    extension',              │
       │    type:'installed'}        │
       │                              │
       ├── 设置 proxyOk = true ────  │
       ├── 切换为直连模式             │
       └── 上报 'extOk' 遥测         │
```

扩展在 `content-script.js` 的 `document_start` 阶段注入，在页面 JS 执行前就开始发信号（t=0, 300ms, 800ms, 1500ms, 3000ms 共 5 次），确保播放器的监听器就绪后能收到。

播放器侧也主动 `pingExt()`（t=0, 500ms, 1500ms），双向探测避免竞争条件。3 秒后未检测到扩展则判定为未安装，进入代理模式。

**扩展做的事：**

通过 `declarativeNetRequest` API（Chrome MV3）在请求发出前注入 `Referer: https://www.bilibili.com` 头。`rules.json` 定义的规则匹配所有 `*.bilivideo.com/*` 和 `*.akamaized.net/*` 的请求。这样浏览器发出的每个视频/音频请求都带正确的 Referer，CDN 照常放行，不需要服务端代理中转。

### 5.4 错误处理与 CDN 容灾

CDN 节点可能抽风 —— 返回 403/404/超时。播放器有三级容灾：

1. **重试**：Video.js 的 `error` 事件触发后，自动重试一次（`player.src()` 重新设置）。
2. **CDN 故障计数**：如果在 5 分钟内累计 3 次 CDN 错误（`cdnStalls` 计数器），触发 `refreshCdn()` 刷新播放地址，重新从 B站 API 获取新的 CDN URL。
3. **备用 CDN**：如果 DASH 轨道的 `backupUrl` 存在，优先尝试备用 URL。

### 5.5 遥测（Player Telemetry）

嵌入播放器每次播放/暂停/seek/切换清晰度/错误时，向服务端发送一个轻量级日志：

```
GET /plugins/bilibili-player/api/player/log
  ?event=play
  &bvid=BV1xx411c7mD
  &cid=12345
  &detail=
  &page=https://alicetec.cn/archives/my-post
```

`page` 参数优先使用 `window.parent.location.href`（获取父页面地址），如果跨域无法访问则回退到 `document.referrer`。这让博主知道哪些文章里的播放器被使用了。

请求发出去就完事 —— `keepalive: true` + `mode: 'no-cors'`，不关心响应。

---

## 六、浏览器扩展

扩展只有三个文件，结构极简：

| 文件 | 职责 |
|------|------|
| `manifest.json` | 声明权限（declarativeNetRequest + storage）、content script 注入规则 |
| `rules.json` | 定义 Referer 头注入规则 |
| `content-script.js` | 信号发送 + 连接记录 |
| `popup.html` / `popup.js` | 工具栏弹窗，显示连接状态 |

**content-script 的信号策略：**

扩展会在 5 个时间点（0ms / 300ms / 800ms / 1500ms / 3000ms）发送 `postMessage`，确保播放器的 `message` 事件监听器无论何时初始化都能收到信号。同时监听来自播放器的 `ping` 消息并立即响应 —— 这是一个双向握手协议，双方都可以主动发起探测。

**连接记录：**

检测到自己在播放器页面上运行时，把域名和时间戳写入 `chrome.storage.local`，popup 弹窗可以读取并展示"最后连接：alicetec.cn · 1分钟前"。

---

## 七、数据流全览

把整个过程串起来就是：

```
管理员                                    后端                                      B站
  │                                        │                                         │
  │── 点击"生成二维码" ────────────────────►│                                         │
  │                                        │── GET /qrcode/generate ───────────────►│
  │                                        │◄── {url, qrcode_key} ────────────────│
  │◄── 渲染二维码 ◄───────────────────────│                                         │
  │                                        │                                         │
  │   [每2秒轮询]                           │                                         │
  │── GET /qrcode/poll?key=xxx ───────────►│── GET /qrcode/poll?key=xxx ───────────►│
  │◄── {status: "scanned"} ◄──────────────│◄── {code: 86090} ─────────────────────│
  │                                        │                                         │
  │   [手机上点确认]                         │                                         │
  │── GET /qrcode/poll ───────────────────►│── GET /qrcode/poll ───────────────────►│
  │◄── {status: "success"} ◄──────────────│◄── {code: 0, url: "?SESSDATA=..."} ──│
  │                                        │── 提取 SESSDATA，解码，持久化到文件         │
  │── GET /login/status ──────────────────►│── GET /nav (Cookie: SESSDATA=...) ───►│
  │◄── {isLogin:true, uname:"...", lv:6} ◄│◄── {isLogin:true, uname:"..."} ──────│
  │                                        │                                         │
  │── 输入 BV 号，点击"获取视频" ───────────►│                                         │
  │                                        │── GET /view?bvid=... ─────────────────►│
  │                                        │◄── {title, pic, pages, stat} ────────│
  │                                        │── GET /playurl?...&w_rid=... ─────────►│
  │                                        │◄── {quality, dash:{video[],audio[]}} ─│
  │                                        │── CdnMirrorUtil.upgradeCdnHostname()     │
  │◄── 视频信息 + 嵌入代码 ◄────────────────│                                         │
  │                                        │                                         │
  │── 复制代码，粘贴到文章 HTML 编辑器中        │                                         │
  │                                        │                                         │
═══════════════════════════════════════════════════════════════════════════════════════
                                    读者访问                                       │
═══════════════════════════════════════════════════════════════════════════════════════
  │                                        │                                         │
读者浏览器                                  │                                         │
  │── 加载文章页面                           │                                         │
  │── 解析 <iframe src="/embed?bvid=...">   │                                         │
  │                                        │                                         │
  │── GET /embed?bvid=...&cid=... ────────►│                                         │
  │                                        │── StringBuilder 拼接 HTML + CSS + JS ──│
  │◄── 自包含 HTML 页面 ◄──────────────────│                                         │
  │                                        │                                         │
  │── 执行页内 JS：                           │                                         │
  │   · loadVideoJS() → 加载 CDN 播放器      │                                         │
  │   · pingExt() → 检测浏览器扩展            │                                         │
  │   · loadQuality(80) → 加载 1080P 源     │                                         │
  │                                        │                                         │
  │── ✗ 扩展未检测到 → 代理模式               │                                         │
  │                                        │                                         │
  │── GET /api/playurl?bvid=...&qn=80 ────►│                                         │
  │◄── {dash:{video:[{baseUrl:...}]}} ◄────│                                         │
  │                                        │                                         │
  │── GET /api/video/proxy?url=<视频CDN> ─►│── GET <视频CDN> (Referer: bilibili) ─►│
  │◄── Flux<DataBuffer> 流 ◄──────────────│◄── m4s 视频流 ◄───────────────────────│
  │                                        │                                         │
  │── GET /api/video/proxy?url=<音频CDN> ─►│── GET <音频CDN> (Referer: bilibili) ─►│
  │◄── Flux<DataBuffer> 流 ◄──────────────│◄── m4s 音频流 ◄───────────────────────│
  │                                        │                                         │
  │── <video> 播放视频轨                     │                                         │
  │── <audio> 播放音频轨                     │                                         │
  │── setInterval(syncAudio, 16) 每帧校正    │                                         │
  │                                        │                                         │
  │── ✗ 扩展已检测到 → 直连模式               │                                         │
  │── GET <视频CDN> ──────────────────────►│ (不经过代理)                               │
  │   (扩展注入 Referer: bilibili.com)       │── CDN 验证 Referer = bilibili.com ✓ ──►│
  │◄── m4s 视频流 ◄───────────────────────│◄── m4s 视频流 ◄────────────────────────│
  │                                        │                                         │
  │── POST /api/player/log?event=play ────►│── logService.info("[player] ...")        │
  │                                        │── 写入环形缓冲 + SLF4J                      │
```

---

## 八、设计中的取舍

### 为什么不用 MSE？

MSE（Media Source Extensions）可以把两根流合并成一个 `MediaSource` 对象，从技术上看是最"优雅"的方案。但 Spring WebFlux 代理返回的是 HTTP chunked transfer，浏览器的 `fetch()` 拿到的是 `ReadableStream`，MSE 的 `SourceBuffer.appendBuffer()` 需要完整的媒体段（segment），而 chunked 流的边界不等于 segment 边界。要在不完整的 chunk 级别做 append 意味着自己实现 m4s 容器解析和缓冲区管理 —— 这本质上是在浏览器里写一个简易的 MP4 解复用器。

双元素方案放弃了"合并"的优雅，换来了浏览器内置解码器的稳定性和零额外计算开销。

### 为什么不是后端合并流？

服务端合并 m4s 音视频流需要重新封装 MP4 容器（写 `moov`/`moof`/`mdat` box），对每个请求都做一次，CPU 开销不小。而且合并后的单根 MP4 流失去了 DASH 自适应码率的优势 —— 用户无法在不重新请求的情况下切换清晰度。

双元素方案保留了每根流的独立性，清晰度切换只需替换 `<video>` 和 `<audio>` 的 `src`。

### 为什么是服务端生成 HTML 而不是前端 SPA？

嵌入播放器页面被 iframe 加载，加载的是完全不同的文档上下文。如果用 Vue/React 做 SPA，需要打包整个运行时 + Video.js + 业务逻辑，首屏体积至少几百 KB。服务端 `StringBuilder` 拼接的内联 HTML 只有约 15KB（gzip 后约 5KB），其中业务逻辑约 8KB，Video.js 从 CDN 异步加载。

这是"最简单的工具做最合适的事"：生成一个包含了所有逻辑的 HTML 字符串，不需要构建工具链。

### 为什么管理后台不做 SSR？

管理后台是 Halo 的插件面板，用户数 = 1（博主本人）。性能不是瓶颈，开发体验更重要。Vue 3 + Vite HMR 让前端迭代快速，`pnpm dev` 秒级热更新。

---

## 九、关键文件索引

| 文件 | 行数 | 职责 |
|------|------|------|
| `BilibiliPlayerPlugin.java` | 15 | 插件入口，几乎为空 |
| `BilibiliApiService.java` | 579 | 核心服务：登录、视频信息、播放地址、WBI 签名 |
| `VideoController.java` | 346 | REST API + CDN 代理 + 嵌入页面生成 |
| `LoginController.java` | 58 | 登录相关 API 端点 |
| `CdnMirrorUtil.java` | 127 | CDN 域名升级（镜像轮询） |
| `LogService.java` | 63 | 环形缓冲 + SSE 日志 |
| ~~`WbiSignUtil.java`~~ | ~~62~~ | ~~WBI 签名算法（v1.7 已移除，高清请求下放至扩展）~~ |
| `HomeView.vue` | 923 | 管理后台全界面（扩展状态 + 嵌入 + 日志） |
| `index.ts` | 42 | 前端路由注册 |
| `content-script.js` | 52 | 浏览器扩展信号发送 |
| `rules.json` | - | Referer 注入规则 |
| `plugin.yaml` | - | Halo 插件元数据 |

---

> 这个插件从 v1.0 的简单 iframe 嵌入演进到 v1.7 的全功能播放器（扩展 DASH 高清直连 + 流式连接步骤），每一步都是在踩坑中做出的务实选择。希望这份文档能让后续的开发者快速理解"为什么这么做"而不只是"做了什么"。
