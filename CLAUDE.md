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
- **`BilibiliApiService.java`** — Core service calling Bilibili APIs: QR code login/poll, video info, play URLs with WBI signing, SESSDATA persistence to `~/.halo-bilibili-player/sessdata`
- **`WbiSignUtil.java`** — Bilibili WBI signature (mixin key + MD5)
- **`LogService.java`** — In-memory ring buffer (max 300 entries) + Reactor `Sinks.Many` SSE streaming
- **`VideoController.java`** — REST endpoints:
  - Video info/playurl APIs
  - CDN proxy: `HttpClient` → `InputStream` → `Flux<DataBuffer>` streaming (Referer/Origin spoofed to bilibili.com)
  - Embed page generation: returns inline HTML+JS with Video.js player
- **`LoginController.java`** — QR code login flow + log history/SSE streaming
- API prefix: `/plugins/bilibili-player/api`

### Frontend (Vue 3 + TypeScript / Vite / pnpm)

- **`ui/src/index.ts`** — Plugin entry: registers admin routes (sidebar + plugin config tab)
- **`ui/src/views/HomeView.vue`** — Main admin panel with login and embed code generator. Default generates minimal iframe; collapsible size settings for wrapper div. Includes live log drawer with SSE polling.

### Embed Player (server-generated inline page)

The `/plugins/bilibili-player/embed` endpoint returns a self-contained HTML page with:
- **Video.js** loaded from CDN with Bilibili pink theme CSS overrides
- **DASH dual-element sync**: `<video>` element for video + hidden `<audio>` element for audio, synced via `requestAnimationFrame` (every frame, ±150ms tolerance)
- **Quality switching**: top bar dropdown, saves/restores playback position via `PlayerState`
- **CDN proxy**: all video/audio URLs go through `/api/video/proxy?url=` to spoof Referer/Origin
- **Unmute hint**: autoplay starts muted, button appears for user to unmute
- **Player telemetry**: sends events to `/api/player/log` (play, pause, seek, quality switch, errors)

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
