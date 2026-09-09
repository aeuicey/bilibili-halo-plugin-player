# BiliBili Halo Plugin Player

[![Build](https://github.com/aeuicey/bilibili-halo-plugin-player/actions/workflows/build.yml/badge.svg)](https://github.com/aeuicey/bilibili-halo-plugin-player/actions/workflows/build.yml)

为 [Halo](https://github.com/halo-dev/halo) 博客系统提供 B 站视频播放器嵌入插件，支持扫码登录获取高清晰度、DASH 音视频分离播放、多清晰度动态切换、分辨率自适应画幅比例、智能省流分发。

<img width="1653" height="1003" alt="image" src="https://github.com/user-attachments/assets/00635f57-3a9c-4303-b5d1-8b55a3098150" />
<img width="1416" height="676" alt="image" src="https://github.com/user-attachments/assets/ee8a142a-2856-4f5d-8ee7-f8aed3e3320c" />


## 功能特性

- **扫码登录** — 管理后台生成 B 站登录二维码，扫码授权后自动持久化登录状态，解锁 1080P 及以上清晰度（4K 需大会员）
- **多清晰度支持** — 360P ~ 8K，播放页内一键切换，切换不中断播放进度
- **DASH 音画分离播放** — `<video>` + 隐藏 `<audio>` 双元素逐帧同步，零外部依赖
- **智能省流** — 三级流分发：浏览器直连 → Cloudflare Worker 代理 → 服务器代理兜底，观众播放不再只消耗服务器带宽
- **播放容错** — CDN 多地址回退、解码失败自动换编码/降清晰度、DASH 失败自动切 MP4 单文件
- **分辨率自适应** — 自动识别横屏/竖屏/方形视频，嵌入代码与播放器同步画幅比例
- **编辑器集成** — 文章编辑器工具箱/斜杠命令直接插入 B 站视频块，可视化配置 BV 号与分 P
- **实时日志** — 内置日志面板，实时推送后端与播放器行为，方便排查问题

## 现行方案

- **视频解析**：WBI 签名调用 B 站 playurl 接口，四级降级链（全量 DASH → 基础 DASH → MP4 → html5 免鉴权兜底），视频信息接口带风控回退
- **播放**：服务端生成自包含嵌入页（Video.js + DASH 双元素同步），一次拉流、前端本地换轨
- **分发**：流地址候选链自动降级，默认直连 B 站 CDN，不占服务器带宽

完整技术细节（解析机制、流选择算法、同步原理、容错链）见 **[docs/TECH.md](./docs/TECH.md)**；与 Halo 官方编辑器集成的调研见 **[docs/EDITOR-INTEGRATION.md](./docs/EDITOR-INTEGRATION.md)**。

## 安装

1. 在 [Releases](https://github.com/aeuicey/bilibili-halo-plugin-player/releases) 或 [Actions](https://github.com/aeuicey/bilibili-halo-plugin-player/actions/workflows/build.yml) 页面下载最新 JAR 包
2. Halo 后台 → 插件管理 → 上传插件，选择下载的 JAR 文件
3. 在已安装插件列表中找到「BiliBili播放器」，确认已启用

## 使用方法

### 1. 扫码登录（可选）

> 未登录时清晰度受限；登录后可用 1080P，大会员账号可到 4K。

- Halo 后台 → 左侧菜单「B 站播放器」→ 账号登录
- 点击「生成登录二维码」，使用 B 站客户端扫码并确认
- 登录状态持久化到服务器，Halo 重启后自动恢复

### 2. 配置流分发（可选，推荐）

- 插件管理 → 点击「BiliBili播放器」→ **设置**页签
- **流分发模式**：默认 `smart`（直连优先，自动降级）；直连不可用时切 `worker`；`server` 为全部走服务器中转的保守模式
- 已部署 Cloudflare Worker 代理时，填写 **Worker 地址**（和访问令牌，若在 Worker 端配置了的话）

### 3. 在文章编辑器中插入视频（推荐）

- 文章编辑器 → 工具箱（或输入 `/` 呼出斜杠命令）→ 选择「B站视频」
- 在弹窗中输入 B 站视频链接或 BV 号 → 解析 → 多 P 视频可选择分 P → 插入
- 插入后正文显示视频卡片（封面/标题/UP 主），双击卡片可重新配置
- 保存后正文存储为 `<div data-bilibili-player>` 节点，前端按真实分辨率画幅渲染播放器

### 4. 生成嵌入代码（手动方式）

- 「视频嵌入」页签 → 输入 B 站视频链接或 BV 号 → 解析
- 多 P 视频可选择分 P；系统自动识别分辨率与横/竖屏
- 点击复制代码，粘贴到文章编辑器即可（粘贴的嵌入代码会被编辑器自动识别为视频节点）

### 5. 读者端播放

- 默认 1080P 起播，画质菜单一键切换（4K/8K 取决于视频与登录状态）
- 横/竖屏自动匹配画幅比例，支持倍速、画中画、全屏
- 播放异常时自动降级换源，读者无感知

## 开发与部署

### 环境要求

- JDK 21（Gradle toolchain 编译目标）
- Node.js 20+ 与 pnpm 10+（仅开发 UI 时需要）

### 构建

```bash
git clone https://github.com/aeuicey/bilibili-halo-plugin-player.git
cd bilibili-halo-plugin-player

./gradlew build -x test
# 产物：build/libs/plugin-bilibili-player-<version>.jar
```

### 本地调试

```bash
# 启动 Halo 2.24 开发实例（需 Docker），插件自动热部署
# 控制台 http://localhost:18090/console，账号 admin / admin
./gradlew halo:dev

# 另开终端，UI 改动监听重建（产物输出到 src/main/resources/console/）
cd ui && pnpm install && pnpm dev
```

### UI 开发命令

```bash
cd ui
pnpm build        # 构建（含类型检查）
pnpm type-check   # 仅类型检查
pnpm lint         # 代码检查
pnpm test:unit    # 单元测试
```

### CI/CD

GitHub Actions 在每次推送时自动构建（JDK 21 + Node 20 + pnpm），产物见 Actions 页面的 Artifacts。

### 部署 Cloudflare Worker 代理（可选）

用于「智能省流」的第二级分发，观众播放流量经 CF 边缘节点而非你的服务器：

1. 复制 [`workers/bili-proxy.js`](./workers/bili-proxy.js) 到 Cloudflare Dashboard → Workers → 新建 Worker 的编辑器中，部署
2. （可选）在 Worker 设置中添加密钥变量 `PROXY_TOKEN` 防止被当开放代理滥用
3. 绑定自定义域名（`workers.dev` 默认域名在中国大陆不可稳定访问，Worker 自定义域名要求域名 NS 托管至 Cloudflare）
4. 在插件设置页填写 Worker 地址（及令牌）

免费额度 10 万请求/天、流量不计费，约支撑每日上千次完整播放；量级更大时请评估 CF 服务条款限制。

## 更新日志

### v1.5.0 (2026-09-09)

- **新增** Halo 编辑器集成：文章编辑器工具箱/斜杠命令直接插入 B 站视频块，可视化配置弹窗，存量嵌入代码自动升级
- **新增** 智能省流三级分发：浏览器直连 → Cloudflare Worker 代理 → 服务器代理兜底，插件设置页可配置
- **重构** 视频解析机制：fnval=3344 全量 DASH（支持 4K/8K/AV1）、四级降级链、view 接口风控自动回退 WBI 签名接口
- **重构** 管理端 UI：VPageHeader + 三页签（账号登录/视频嵌入/运行日志），嵌入页按功能分区排版
- **重构** 播放器控制栏：画质选择并入 Video.js 控制栏，单轴线布局
- **修复** 扫码登录接口变更导致无法获取 SESSDATA 的问题（改从 Set-Cookie 提取）
- **修复** 视频卡顿时音频反复重放同一小段的同步问题
- **修复** 插件设置页不显示（settings.yaml 移至 extensions/ 目录并声明 settingName）
- **修复** 日志面板 SSE 重连导致的日志重复

> 注意：Halo 生产模式下 Console 资源缓存以「插件名+版本号」为 key，**同版本号重新部署不会刷新管理端 UI**（服务端缓存 + 浏览器一年强缓存）。升级插件请务必使用新版本号构建的 JAR。

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
