# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development Commands

```bash
# Build everything (Java + UI)
./gradlew build

# Build skipping tests
./gradlew build -x test

# UI dev (watch mode)
cd ui && pnpm dev

# UI build only
cd ui && pnpm build

# Type check
cd ui && pnpm type-check

# Lint
cd ui && pnpm lint

# Run unit tests (vitest)
cd ui && pnpm test:unit

# Format code
cd ui && pnpm prettier

# Halo dev server (gradle.properties configures port 18090)
./gradlew halo:dev
```

## Architecture

### Backend (Java 21 / Spring WebFlux / Halo Plugin API)

- **`BilibiliPlayerPlugin.java`** — Plugin entry point (extends `BasePlugin`)
- **`BilibiliApiService.java`** — Core service calling Bilibili APIs: QR code login/poll, video info, play URLs with WBI signing, SESSDATA persistence to `~/.halo-bilibili-player/sessdata`. `getVideoPlayUrlWithFallback()` implements a 4-level fallback chain: fnval=3344 (DASH|4K|8K|AV1, qn=127) → fnval=16/qn=80 → fnval=1/qn=64 (durl MP4) → platform=html5&high_quality=1 (no-Referer 1080P MP4). HDR/Dolby bits (64/256/512) intentionally excluded — HEVC/Dolby-only streams unplayable in native `<video>`.
- **`WbiSignUtil.java`** — Bilibili WBI signature (mixin key + MD5)
- **`LogService.java`** — In-memory ring buffer (max 300 entries) + Reactor `Sinks.Many` SSE streaming
- **`VideoController.java`** — REST endpoints:
  - Video info/playurl APIs (playurl defaults qn=127/fnval=3344 via fallback chain; explicit params bypass the chain). playurl responses carry additive fields: `fetchedAt` (stream URLs expire in 120min), `strategy` (dash-full/dash-basic/mp4/mp4-html5), `supportFormats`, `dash.dolby`, `dash.flac`
  - CDN proxy: `HttpClient` → `InputStream` → `Flux<DataBuffer>` streaming (Referer/Origin spoofed to bilibili.com)
- **`EmbedPageGenerator.java`** — Generates the self-contained embed player HTML+JS (extracted from VideoController). Three-tier stream delivery candidates injected from plugin settings: `smart` (browser direct w/ no-referrer → Cloudflare Worker proxy → server proxy fallback) / `worker` (Worker → server) / `server` (server proxy only)
- **`LoginController.java`** — QR code login flow + log history/SSE streaming
- **Plugin settings** (`src/main/resources/settings.yaml`, group `basic`): `proxyMode` (smart/worker/server, default smart), `workerUrl` (Cloudflare Worker proxy base), `workerToken` (optional); read via `ReactiveSettingFetcher.getSettingValue("basic")` in the embed endpoint with defensive fallbacks
- API prefix: `/plugins/bilibili-player/api`

### Frontend (Vue 3 + TypeScript / Vite / pnpm)

- **`ui/src/index.ts`** — Plugin entry: registers admin routes (sidebar + plugin config tab)
- **`ui/src/views/HomeView.vue`** — Main admin panel, Halo-native design: `VPageHeader` (login status `VStatusDot` + logout in `#actions`) + `VTabbar` with 3 tabs — 账号登录 (QR flow, `VLoading`/`VStatusDot`/`VAlert`, account info via `VAvatar`+`VDescription`) / 视频嵌入 (parse BV/link, multi-P select, resolution & orientation detection, minimal iframe code + collapsible size settings, copy via `Toast`) / 运行日志 (history + `EventSource` SSE with 2s polling fallback, level filter, `VSwitch` autoscroll). Uses `axiosInstance` from `@halo-dev/api-client` (never bare axios), site URL from `stores.globalInfo().externalUrl` with origin fallback, `Dialog.warning` for logout confirm. All styles scoped with `.bp-` prefix, 4px radius, no global `:root` variables.

### Embed Player (server-generated inline page)

The `/plugins/bilibili-player/embed` endpoint (rendered by `EmbedPageGenerator`) returns a self-contained HTML page with:
- **Video.js** loaded from CDN with Bilibili pink theme CSS overrides, playbackRates `[0.5..2]`
- **DASH dual-element sync**: `<video>` element for video + hidden `<audio>` element for audio, synced via `requestAnimationFrame` (every frame, ±150ms tolerance); audio stalled/buffered >1s behind video pauses video until audio recovers
- **Single-fetch quality switching**: one playurl call fetches the full DASH track list; quality switches are pure client-side track swaps (re-fetch only on 403/CDN failure or after 110min)
- **Track selection**: filter by target qn (fall to nearest lower `accept_quality` if missing), codec priority `avc1` > `av01`/`hev1` only when `canPlayType` says "probably", audio = highest bandwidth track
- **Fallbacks**: on media error the element advances to the next candidate URL (keeps currentTime); video/audio elements track independent candidate indexes; 5s decode watchdog switches codec → lower qn → MP4 single-file mode (RAF sync disabled, MP4 `durl` also goes through `candidates()`)
- **Three-tier stream delivery**: `candidates([baseUrl, backupUrl])` builds an ordered URL list by `PROXY_MODE` — tier-first, `baseUrl`→`backupUrl` within a tier. `smart` = direct (page sets `<meta name="referrer" content="no-referrer">`, B站 CDN allows empty Referer) → Worker (`WORKER_BASE?url=…&token=…`, only when configured) → server proxy (always last); `worker` = Worker → server; `server` = server proxy only. Candidate switches report `proxyTier` telemetry (tier + idx)
- **CDN proxy**: `/api/video/proxy?url=` spoofs Referer/Origin and streams via `Flux<DataBuffer>`; remains the final fallback in every mode
- **Unmute hint**: autoplay starts muted, button appears for user to unmute
- **Player telemetry**: sends events to `/api/player/log` (play, pause, seek, quality switch, codec/bandwidth choice, fallback reasons, errors)

### Key Patterns

- **Streaming proxy**: `HttpClient.sendAsync()` → `InputStream` → `Flux.generate()` → `DataBuffer` chunks (64KB), CORS headers set
- **Login flow**: QR code generation → poll every 2s → extracted `SESSDATA` from redirect URL params → persisted to file → restored on restart
- **Resolution detection**: embed code uses actual `width/height` from DASH tracks for `aspect-ratio`, not hardcoded 16/9
- **Quality levels**: QN < 80 → muxed MP4 (single `<video>`), QN ≥ 80 → DASH (dual-element sync)
- **Embed iframe**: minimal `<iframe>` by default; optional collapsible "尺寸设置" panel generates `<div data-bilibili-player>` wrapper with custom `max-width` and aspect-ratio from DASH tracks

### Halo Plugin Conventions

- Plugin metadata in `src/main/resources/plugin.yaml`
- Console UI resources in `src/main/resources/console/` (copied from `ui/dist/` by `processUiResources` Gradle task)
- Routes registered via `@halo-dev/ui-shared` `definePlugin()`
