<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick, computed } from 'vue'
import axios from 'axios'
import {
  VCard,
  VButton,
  VAlert,
  VTag,
  VSwitch,
  VEmpty,
  VSpace,
  VAvatar,
} from '@halo-dev/components'

const API = '/plugins/bilibili-player/api'
const EMBED_PATH = '/plugins/bilibili-player/embed'
const BVID_REGEX = /BV[a-zA-Z0-9]{10}/
const AVID_REGEX = /av(\d+)/i

/* ---------- Extension Status ---------- */
const extInstalled = ref(false)
const extLoggedIn = ref(false)
const extChecked = ref(false)
const extUserInfo = ref<null | { uname: string; face: string; level: number }>(null)
const showInstallGuide = ref(false)

/* ---------- Embed ---------- */
const embedBvid = ref('')
const embedCid = ref('')
const embedLoading = ref(false)
const embedCode = ref('')
const embedPreview = ref('')
const showSizeSettings = ref(false)
const embedWidth = ref('100')
const videoInfo = ref<null | {
  title: string; pic: string; ownerName: string
  pages: Array<{ cid: number; page: number; part: string }>
  width: number; height: number; picWidth?: number; picHeight?: number
  stat?: { view: number; danmaku: number; like: number }
}>(null)
const copied = ref(false)
const coverFailed = ref(false)
const COVER_PLACEHOLDER = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 400 250'><defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'><stop offset='0' stop-color='%23ffeaf2'/><stop offset='1' stop-color='%23e6f7fd'/></linearGradient></defs><rect width='400' height='250' fill='url(%23g)'/><circle cx='200' cy='125' r='36' fill='%23fb7299' opacity='0.85'/><polygon points='188,108 220,125 188,142' fill='white'/></svg>"

/* ---------- Logs ---------- */
const showLogs = ref(false)
const logEntries = ref<Array<{ time: string; level: string; msg: string }>>([])
const logFilter = ref<'ALL' | 'INFO' | 'WARN' | 'ERROR' | 'DEBUG'>('ALL')
const logAutoScroll = ref(true)
const logContainer = ref<HTMLElement | null>(null)
let logPollTimer: ReturnType<typeof setInterval> | null = null

const filteredLogs = computed(() =>
  logFilter.value === 'ALL' ? logEntries.value : logEntries.value.filter(e => e.level === logFilter.value)
)

/* ---------- Lifecycle ---------- */
onMounted(() => {
  window.addEventListener('message', onExtMessage)
  window.postMessage({ source: 'bilibili-player', type: 'ping' }, '*')
  setTimeout(() => { if (!extInstalled.value) extChecked.value = true }, 3000)
})
onUnmounted(() => {
  window.removeEventListener('message', onExtMessage)
  stopLogPoll()
})

/* ---------- Extension logic ---------- */
function onExtMessage(e: MessageEvent) {
  if (e.data?.source === 'bilibili-player-extension') {
    extInstalled.value = true
    extChecked.value = true
    if (e.data.type === 'status') {
      extLoggedIn.value = e.data.login === true
      extUserInfo.value = e.data.userInfo
    }
  }
}

const extStatusText = computed(() => {
  if (!extInstalled.value) return '未安装浏览器扩展'
  if (!extLoggedIn.value) return '扩展已安装，未登录 B 站'
  return '扩展已安装，已登录 B 站'
})

const extStatusColor = computed(() => {
  if (!extInstalled.value) return 'gray'
  if (!extLoggedIn.value) return 'yellow'
  return 'green'
})

/* ---------- Embed logic ---------- */
function parseBvid(input: string) {
  const trimmed = (input || '').trim()
  if (!trimmed) return null
  const m = trimmed.match(BVID_REGEX); if (m) return { bvid: m[0], cid: '' }
  const a = trimmed.match(AVID_REGEX); if (a) return { bvid: 'av' + a[1], cid: '' }
  return null
}

function extractResolution(playData: unknown) {
  const data = playData as { dash?: { video?: Array<{ width?: number; height?: number }> } } | null
  const track = data?.dash?.video?.[0]
  return { width: Number(track?.width) > 0 ? Number(track?.width) : 0, height: Number(track?.height) > 0 ? Number(track?.height) : 0 }
}

function parseData<T = unknown>(data: unknown): T { return (typeof data === 'string' ? JSON.parse(data) : data) as T }

function proxyImage(url: string | undefined | null): string {
  if (!url) return ''
  let secure = url.startsWith('http://') ? 'https://' + url.substring(7) : url
  if (!secure.includes('@') && (secure.includes('hdslb.com') || secure.includes('bilibili.com'))) {
    secure = secure + '@320w_200h_1e_1c'
  }
  return `${API}/video/proxy?url=${encodeURIComponent(secure)}`
}

function formatNum(n: number): string {
  if (!Number.isFinite(n) || n < 0) return '0'
  if (n >= 1e8) return (n / 1e8).toFixed(1).replace(/\.0$/, '') + '亿'
  if (n >= 1e4) return (n / 1e4).toFixed(1).replace(/\.0$/, '') + '万'
  return String(n)
}

const coverSrc = computed(() => videoInfo.value?.pic ? (coverFailed.value ? COVER_PLACEHOLDER : proxyImage(videoInfo.value.pic)) : COVER_PLACEHOLDER)

function onCoverError(e: Event) { coverFailed.value = true; (e.target as HTMLImageElement).src = COVER_PLACEHOLDER }

async function fetchVideo() {
  const parsed = parseBvid(embedBvid.value)
  if (!parsed || !parsed.bvid) { embedCode.value = ''; embedPreview.value = ''; return }
  coverFailed.value = false; embedLoading.value = true; embedCode.value = ''; embedPreview.value = ''; videoInfo.value = null; embedCid.value = ''
  try {
    const { data } = await axios.get(`${API}/video/info?bvid=${parsed.bvid}`)
    const info = parseData<{ pages?: Array<{ cid: number }> }>(data)
    const firstCid = info.pages?.[0]?.cid ? String(info.pages[0].cid) : ''
    if (firstCid) embedCid.value = firstCid
    const cid = firstCid || embedCid.value
    let resolution = { width: 0, height: 0 }
    if (cid) {
      try {
        const { data: pd } = await axios.get(`${API}/video/playurl?bvid=${parsed.bvid}&cid=${cid}&qn=80&fnval=16`)
        resolution = extractResolution(parseData(pd))
      } catch { /* ignore */ }
    }
    videoInfo.value = { ...(info as Record<string, unknown>), width: resolution.width, height: resolution.height } as NonNullable<typeof videoInfo.value>
    generateCode(parsed.bvid, cid)
  } catch { embedCode.value = ''; embedPreview.value = '' } finally { embedLoading.value = false }
}

function generateCode(bvid: string, cid: string) {
  if (!bvid || !cid) { embedCode.value = ''; embedPreview.value = ''; return }
  const origin = window.location.origin
  const src = `${origin}${EMBED_PATH}?bvid=${encodeURIComponent(bvid)}&cid=${encodeURIComponent(cid)}`

  if (!showSizeSettings.value) {
    embedCode.value = `<iframe src="${src}" style="width:100%;aspect-ratio:16/9;border:none;border-radius:8px" allowfullscreen allow="autoplay;encrypted-media" loading="lazy"></iframe>`
  } else {
    const maxWidth = embedWidth.value === '100' ? '100%' : embedWidth.value + 'px'
    const w = videoInfo.value?.width ?? 0
    const h = videoInfo.value?.height ?? 0
    const aspectRatio = w > 0 && h > 0 ? `${w}/${h}` : '16/9'
    const containerStyle = `position:relative;width:100%;max-width:${maxWidth};aspect-ratio:${aspectRatio};border-radius:8px;overflow:hidden;margin:16px 0`
    const iframeStyle = `position:absolute;top:0;left:0;width:100%;height:100%;border:none`
    embedCode.value = `<div data-bilibili-player="true" data-bvid="${bvid}" data-cid="${cid}" style="${containerStyle}"><iframe src="${src}" style="${iframeStyle}" allowfullscreen allow="autoplay;encrypted-media" loading="lazy"></iframe></div>`
  }
  embedPreview.value = src
}

function onWidthChange() {
  const parsed = parseBvid(embedBvid.value)
  if (parsed) generateCode(parsed.bvid, embedCid.value)
}

function onSizeSettingsChange() {
  const parsed = parseBvid(embedBvid.value)
  if (parsed) generateCode(parsed.bvid, embedCid.value)
}

async function copyCode() {
  if (!embedCode.value) return
  try { await navigator.clipboard.writeText(embedCode.value) }
  catch {
    const ta = document.createElement('textarea')
    ta.value = embedCode.value; ta.style.position = 'fixed'; ta.style.opacity = '0'; document.body.appendChild(ta)
    ta.select(); document.execCommand('copy'); document.body.removeChild(ta)
  }
  copied.value = true; setTimeout(() => { copied.value = false }, 2000)
}

/* ---------- Logs ---------- */
function startLogPoll() {
  stopLogPoll()
  logPollTimer = setInterval(async () => {
    try {
      const { data } = await axios.get(`${API}/logs/history`)
      logEntries.value = (Array.isArray(data) ? data : data?.value || data || []).slice(-800)
      if (logAutoScroll.value) nextTick(() => { const el = logContainer.value; if (el) el.scrollTop = el.scrollHeight })
    } catch { /* ignore */ }
  }, 2000)
}
function stopLogPoll() { if (logPollTimer) { clearInterval(logPollTimer); logPollTimer = null } }
function toggleLogs() {
  showLogs.value = !showLogs.value
  if (showLogs.value) { startLogPoll(); nextTick(() => { const el = logContainer.value; if (el) el.scrollTop = el.scrollHeight }) }
  else stopLogPoll()
}
function clearLogs() { logEntries.value = [] }
</script>

<template>
  <div class="bp-wrap">
    <!-- Header -->
    <div class="bp-header">
      <div class="bp-header__brand">
        <svg class="bp-logo" viewBox="0 0 24 24" width="28" height="28" fill="currentColor">
          <path d="M18.223 3.086a1.25 1.25 0 0 1 0 1.768L17.08 5.996h1.17A3.75 3.75 0 0 1 22 9.747v7.5a3.75 3.75 0 0 1-3.75 3.75H5.75A3.75 3.75 0 0 1 2 17.247v-7.5a3.75 3.75 0 0 1 3.75-3.75h1.166L5.775 4.855a1.25 1.25 0 1 1 1.767-1.77l2.652 2.654.1.258h3.411l.1-.258 2.654-2.653a1.25 1.25 0 0 1 1.768 0zM18.25 8.496H5.75a1.25 1.25 0 0 0-1.243 1.122l-.007.128v7.5c0 .643.487 1.172 1.112 1.243l.138.007h12.5a1.25 1.25 0 0 0 1.243-1.122l.007-.128v-7.5a1.25 1.25 0 0 0-1.25-1.25zM8.5 11a1.25 1.25 0 0 1 1.25 1.25v1.5a1.25 1.25 0 1 1-2.5 0v-1.5A1.25 1.25 0 0 1 8.5 11zm7 0a1.25 1.25 0 0 1 1.25 1.25v1.5a1.25 1.25 0 1 1-2.5 0v-1.5A1.25 1.25 0 0 1 15.5 11z" />
        </svg>
        <div>
          <div class="bp-header__title">Bilibili Player</div>
          <div class="bp-header__desc">在博客中嵌入 B 站视频，支持高清画质与 DASH 音画分离</div>
        </div>
      </div>
      <div class="bp-header__meta">
        <span class="bp-status-label">
          <span class="bp-status-dot" :class="extStatusColor" />
          <span>{{ extStatusText }}</span>
        </span>
      </div>
    </div>

    <!-- Extension Status Card -->
    <VCard class="bp-card bp-status-card">
      <template #header>
        <div class="bp-card__header">
          <span class="bp-card__title">扩展状态</span>
          <span class="bp-card__desc">浏览器扩展决定可播放的清晰度上限</span>
        </div>
      </template>
      <div class="bp-status-body">
        <!-- Not installed -->
        <div v-if="!extInstalled" class="bp-status-row">
          <div class="bp-status-main">
            <span class="bp-status-dot lg gray" />
            <div class="bp-status-info">
              <div class="bp-status-title">未安装浏览器扩展</div>
              <div class="bp-status-desc">安装扩展后可播放 1080P+ 高清视频，流量由客户端网络直连 CDN 承载</div>
            </div>
          </div>
          <VButton type="secondary" size="sm" @click="showInstallGuide = !showInstallGuide">
            {{ showInstallGuide ? '收起指南' : '安装指南' }}
          </VButton>
        </div>

        <!-- Installed, not logged in -->
        <div v-else-if="!extLoggedIn" class="bp-status-row">
          <div class="bp-status-main">
            <span class="bp-status-dot lg yellow" />
            <div class="bp-status-info">
              <div class="bp-status-title">扩展已安装，未登录 B 站</div>
              <div class="bp-status-desc">请点击浏览器工具栏上的扩展图标，在弹窗中扫码登录 B 站账号</div>
            </div>
          </div>
        </div>

        <!-- Installed and logged in -->
        <div v-else class="bp-status-row">
          <div class="bp-status-main">
            <VAvatar :src="extUserInfo?.face ? proxyImage(extUserInfo.face) : ''" :alt="extUserInfo?.uname || '用户'" size="md" circle />
            <div class="bp-status-info">
              <div class="bp-status-title">
                {{ extUserInfo?.uname || '已登录' }}
                <VTag v-if="extUserInfo?.level" size="sm">Lv {{ extUserInfo.level }}</VTag>
              </div>
              <div class="bp-status-desc">扩展已安装并登录，可播放 1080P+ 高清视频</div>
            </div>
          </div>
          <span class="bp-status-badge green">已就绪</span>
        </div>

        <!-- Install guide -->
        <Transition name="bp-collapse">
          <div v-if="!extInstalled && showInstallGuide" class="bp-install-guide">
            <ol class="bp-steps">
              <li>下载插件文件夹 <code>browser-extension</code></li>
              <li>打开 <code>chrome://extensions</code>，开启"开发者模式"</li>
              <li>点击"加载已解压的扩展程序"，选择 browser-extension 目录</li>
              <li>点击扩展图标，在弹窗中"扫码登录" B 站账号</li>
              <li>安装后刷新本页面，即可享受 1080P+ 直连播放</li>
            </ol>
          </div>
        </Transition>
      </div>
    </VCard>

    <!-- Strategy Cards -->
    <div class="bp-strategy-grid">
      <VCard class="bp-card bp-strategy-card">
        <div class="bp-strategy-icon flv">FLV</div>
        <div class="bp-strategy-title">默认策略（≤720P）</div>
        <div class="bp-strategy-desc">后端直链解析，无需扩展即可播放。适合未安装扩展的访客，自动使用 FLV 格式直连 CDN。</div>
      </VCard>
      <VCard class="bp-card bp-strategy-card">
        <div class="bp-strategy-icon dash">DASH</div>
        <div class="bp-strategy-title">高清策略（1080P+）</div>
        <div class="bp-strategy-desc">需要安装扩展并登录 B 站。流量由客户端网络直连 CDN 承载，支持 1080P 及以上清晰度。</div>
      </VCard>
    </div>

    <!-- Embed Generator -->
    <VCard class="bp-card">
      <template #header>
        <div class="bp-card__header">
          <span class="bp-card__title">生成视频嵌入代码</span>
          <span class="bp-card__desc">输入 BV 号或粘贴 B 站视频链接，生成可粘贴到文章中的嵌入代码</span>
        </div>
      </template>
      <div class="bp-embed">
        <div class="bp-search">
          <input v-model="embedBvid" class="bp-input" placeholder="BV1xx411c7mD 或 https://www.bilibili.com/video/BV..." @keyup.enter="fetchVideo" />
          <VButton type="primary" :loading="embedLoading" @click="fetchVideo">获取视频</VButton>
        </div>

        <div v-if="videoInfo" class="bp-video">
          <img class="bp-video__cover" :src="coverSrc" :alt="videoInfo.title" loading="lazy" @error="onCoverError" />
          <div class="bp-video__meta">
            <div class="bp-video__title">{{ videoInfo.title }}</div>
            <div class="bp-video__sub">
              <span>UP 主：{{ videoInfo.ownerName }}</span>
              <span v-if="videoInfo.width && videoInfo.height"> &middot; {{ videoInfo.width }}&times;{{ videoInfo.height }}</span>
            </div>
            <div v-if="videoInfo.stat" class="bp-video__stats">
              <span>{{ formatNum(videoInfo.stat.view) }} 播放</span>
              <span>{{ formatNum(videoInfo.stat.danmaku) }} 弹幕</span>
              <span>{{ formatNum(videoInfo.stat.like) }} 点赞</span>
            </div>
            <div v-if="videoInfo.pages && videoInfo.pages.length > 1" class="bp-video__pages">
              <select v-model="embedCid" class="bp-input" @change="!embedLoading && generateCode(parseBvid(embedBvid)?.bvid || '', embedCid)">
                <option v-for="p in videoInfo.pages" :key="p.cid" :value="p.cid">P{{ p.page }} &middot; {{ p.part }}</option>
              </select>
            </div>
          </div>
        </div>

        <div v-if="embedCode" class="bp-code-section">
          <div class="bp-code-toolbar">
            <span class="bp-code-label">嵌入代码</span>
            <VButton size="sm" :type="copied ? 'default' : 'primary'" @click="copyCode">{{ copied ? '已复制' : '复制代码' }}</VButton>
          </div>
          <pre class="bp-code-block"><code>{{ embedCode }}</code></pre>

          <!-- Collapsible size settings -->
          <div class="bp-size-settings">
            <button class="bp-size-toggle" @click="showSizeSettings = !showSizeSettings; onSizeSettingsChange()">
              <svg :class="{ rotated: showSizeSettings }" class="bp-chevron" viewBox="0 0 24 24" width="16" height="16" fill="currentColor">
                <path d="M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6 1.41-1.41z"/>
              </svg>
              尺寸设置
              <span v-if="!showSizeSettings" class="bp-size-hint">默认自适应宽度</span>
            </button>
            <Transition name="bp-collapse">
              <div v-if="showSizeSettings" class="bp-size-options">
                <span class="bp-size-label">最大宽度：</span>
                <button v-for="opt in [{v:'100',l:'自适应'},{v:'800',l:'800px'},{v:'640',l:'640px'},{v:'480',l:'480px'}]" :key="opt.v"
                  class="bp-chip" :class="{ active: embedWidth === opt.v }" @click="embedWidth = opt.v; onWidthChange()">{{ opt.l }}</button>
              </div>
            </Transition>
          </div>

          <div class="bp-preview">
            <iframe v-if="embedPreview" :src="embedPreview" allowfullscreen allow="autoplay;encrypted-media" loading="lazy" />
          </div>
        </div>

        <VEmpty v-if="!videoInfo && !embedLoading" title="暂无视频信息" message="在上方输入 BV 号后点击获取视频" />
      </div>
    </VCard>

    <!-- Log FAB -->
    <button class="bp-log-fab" :class="{ open: showLogs }" @click="toggleLogs">
      <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor">
        <path d="M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H5.17L4 17.17V4h16v12z"/>
        <path d="M7 9h10v2H7zm0-3h10v2H7zm0 6h7v2H7z"/>
      </svg>
      <span>{{ showLogs ? '关闭日志' : '运行日志' }}</span>
    </button>

    <!-- Log Drawer -->
    <Transition name="bp-drawer">
      <aside v-if="showLogs" class="bp-log-drawer">
        <div class="bp-log-drawer__header">
          <div class="bp-log-drawer__title">
            <span>运行日志</span>
            <span class="bp-log-count">{{ filteredLogs.length }} 条</span>
          </div>
          <div class="bp-log-drawer__actions">
            <div class="bp-log-filter-group">
              <button v-for="lv in (['ALL','INFO','WARN','ERROR','DEBUG'] as const)" :key="lv"
                class="bp-chip sm" :class="{ active: logFilter === lv }" @click="logFilter = lv">{{ lv }}</button>
            </div>
            <VSwitch v-model="logAutoScroll" />
            <span class="bp-autoscroll-label">自动滚动</span>
            <VButton size="xs" @click="clearLogs">清空</VButton>
            <VButton size="xs" @click="toggleLogs">&times;</VButton>
          </div>
        </div>
        <div ref="logContainer" class="bp-log-drawer__body">
          <VEmpty v-if="filteredLogs.length === 0" title="暂无日志" message="操作后将显示日志" />
          <div v-for="(l,i) in filteredLogs" :key="i" class="bp-log" :data-level="l.level">
            <span class="bp-log__time">{{ l.time }}</span>
            <span class="bp-log__level">{{ l.level }}</span>
            <span class="bp-log__msg">{{ l.msg }}</span>
          </div>
        </div>
      </aside>
    </Transition>
  </div>
</template>

<style>
/* DESIGN.md Tokens + Legacy */
:root {
  /* DESIGN.md Tokens */
  --bp-primary: #111111;
  --bp-primary-active: #242424;
  --bp-primary-disabled: #e5e7eb;
  --bp-canvas: #ffffff;
  --bp-surface-soft: #f8f9fa;
  --bp-surface-card: #f5f5f5;
  --bp-surface-strong: #e5e7eb;
  --bp-ink: #111111;
  --bp-body: #374151;
  --bp-muted: #6b7280;
  --bp-muted-soft: #898989;
  --bp-hairline: #e5e7eb;
  --bp-hairline-soft: #f3f4f6;
  --bp-on-primary: #ffffff;
  --bp-brand: #fb7299;
  --bp-success: #10b981;
  --bp-warning: #f59e0b;
  --bp-error: #ef4444;
  --bp-rounded-md: 8px;
  --bp-rounded-lg: 12px;
  --bp-rounded-xl: 16px;
  --bp-rounded-pill: 9999px;

  /* Legacy variables */
  --bp-pink: #fb7299;
  --bp-pink-light: #ffeaf2;
  --bp-text: #1f2329;
  --bp-text-secondary: #4e5969;
  --bp-text-muted: #86909c;
  --bp-border: #e5e6eb;
  --bp-border-light: #d0d5dd;
  --bp-bg: #f7f8fa;
  --bp-radius: 8px;
  --bp-radius-sm: 6px;
  --bp-radius-pill: 999px;
}

.bp-wrap {
  display: flex;
  flex-direction: column;
  gap: 24px;
  padding: 24px;
  max-width: 920px;
  margin: 0 auto;
}

/* Header */
.bp-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
  padding: 8px 0;
}
.bp-header__brand {
  display: flex;
  align-items: center;
  gap: 14px;
}
.bp-logo {
  flex-shrink: 0;
  color: var(--bp-brand);
  width: 28px;
  height: 28px;
}
.bp-header__title {
  font-size: 22px;
  font-weight: 600;
  color: var(--bp-ink);
  letter-spacing: -0.3px;
  line-height: 1.3;
}
.bp-header__desc {
  font-size: 13px;
  color: var(--bp-muted);
  line-height: 1.4;
  margin-top: 2px;
}
.bp-header__meta {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* Status dot */
.bp-status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.bp-status-dot.lg { width: 12px; height: 12px; }
.bp-status-dot.gray { background: var(--bp-muted); }
.bp-status-dot.yellow { background: var(--bp-warning); }
.bp-status-dot.green { background: var(--bp-success); }

.bp-status-label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 500;
  color: var(--bp-body);
  padding: 6px 12px;
  background: var(--bp-surface-card);
  border-radius: var(--bp-rounded-pill);
}

/* Cards */
.bp-card {
  background: var(--bp-canvas);
  border: 1px solid var(--bp-hairline);
  border-radius: var(--bp-rounded-lg);
}
.bp-card .card-header {
  padding: 20px 24px;
  border-bottom: 1px solid var(--bp-hairline-soft);
}
.bp-card .card-body {
  padding: 24px;
}
.bp-card__header {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}
.bp-card__title { font-size: 16px; font-weight: 600; color: var(--bp-ink); line-height: 1.4; }
.bp-card__desc { font-size: 13px; color: var(--bp-muted); line-height: 1.4; }

/* Status card */
.bp-status-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.bp-status-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
.bp-status-main {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.bp-status-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.bp-status-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--bp-ink);
  line-height: 1.4;
  display: flex;
  align-items: center;
  gap: 8px;
}
.bp-status-desc {
  font-size: 13px;
  color: var(--bp-muted);
  line-height: 1.5;
}
.bp-status-badge {
  display: inline-flex;
  align-items: center;
  padding: 4px 12px;
  border-radius: var(--bp-rounded-pill);
  font-size: 12px;
  font-weight: 600;
}
.bp-status-badge.green { background: rgba(16,185,129,0.1); color: var(--bp-success); }

.bp-install-guide {
  padding: 16px 20px;
  background: var(--bp-surface-card);
  border-radius: var(--bp-rounded-md);
}
.bp-steps {
  margin: 0;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 13px;
  color: var(--bp-body);
  line-height: 1.6;
}
.bp-steps code {
  background: rgba(251,114,153,0.1);
  color: var(--bp-brand);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 12px;
}

/* Strategy grid */
.bp-strategy-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}
@media (max-width: 640px) { .bp-strategy-grid { grid-template-columns: 1fr; } }

.bp-strategy-card .card-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.bp-strategy-icon {
  align-self: flex-start;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.5px;
}
.bp-strategy-icon.flv { background: #ffc107; color: #1a1a2e; }
.bp-strategy-icon.dash { background: #2196f3; color: #fff; }
.bp-strategy-title { font-size: 15px; font-weight: 600; color: var(--bp-ink); }
.bp-strategy-desc { font-size: 13px; color: var(--bp-body); line-height: 1.6; }

/* Embed */
.bp-embed {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.bp-search {
  display: flex;
  gap: 12px;
}
@media (max-width: 640px) { .bp-search { flex-direction: column; } }

.bp-input {
  flex: 1;
  height: 40px;
  padding: 10px 14px;
  font-size: 14px;
  border: 1px solid var(--bp-hairline);
  border-radius: var(--bp-rounded-md);
  outline: none;
  font-family: inherit;
  background: var(--bp-canvas);
  color: var(--bp-ink);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.bp-input:focus { border-color: var(--bp-primary); box-shadow: 0 0 0 3px rgba(17,17,17,0.08); }
.bp-input::placeholder { color: var(--bp-muted); }

/* Override Halo VButton to match DESIGN.md primary */
.bp-wrap .btn-primary {
  background-color: var(--bp-primary) !important;
  border-color: var(--bp-primary) !important;
  color: var(--bp-on-primary) !important;
  border-radius: var(--bp-rounded-md) !important;
  height: 40px !important;
  padding: 0 20px !important;
  font-weight: 600 !important;
  font-size: 14px !important;
}
.bp-wrap .btn-primary:hover {
  background-color: var(--bp-primary-active) !important;
  border-color: var(--bp-primary-active) !important;
}
.bp-wrap .btn-secondary {
  background-color: var(--bp-canvas) !important;
  border-color: var(--bp-hairline) !important;
  color: var(--bp-ink) !important;
  border-radius: var(--bp-rounded-md) !important;
  height: 36px !important;
  padding: 0 16px !important;
  font-weight: 600 !important;
  font-size: 13px !important;
}
.bp-wrap .btn-secondary:hover {
  background-color: var(--bp-surface-soft) !important;
}

.bp-video {
  display: flex;
  gap: 16px;
  padding: 16px;
  background: var(--bp-surface-card);
  border-radius: var(--bp-rounded-lg);
  border: 1px solid var(--bp-hairline-soft);
}
@media (max-width: 640px) { .bp-video { flex-direction: column; } }
.bp-video__cover {
  width: 180px;
  aspect-ratio: 16/10;
  object-fit: cover;
  border-radius: var(--bp-rounded-md);
  flex-shrink: 0;
  background: var(--bp-hairline);
}
@media (max-width: 640px) { .bp-video__cover { width: 100%; } }
.bp-video__meta { display:flex;flex-direction:column;gap:6px;min-width:0;flex:1 }
.bp-video__title { font-size:14px;font-weight:600;line-height:1.5;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;color:var(--bp-ink) }
.bp-video__sub { font-size:12px;color:var(--bp-muted) }
.bp-video__stats { display:flex;gap:12px;font-size:12px;color:var(--bp-muted) }
.bp-video__pages { margin-top: auto; }

/* Code section */
.bp-code-section { display:flex;flex-direction:column;gap:10px }
.bp-code-toolbar { display:flex;justify-content:space-between;align-items:center }
.bp-code-label { font-size:12px;font-weight:600;color:var(--bp-body) }
.bp-code-block {
  margin: 0;
  padding: 14px 16px;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 13px;
  line-height: 1.6;
  background: var(--bp-surface-card);
  border: 1px solid var(--bp-hairline);
  border-radius: var(--bp-rounded-md);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 180px;
  overflow: auto;
  color: var(--bp-ink);
}

/* Size settings collapsible */
.bp-size-settings {
  border: 1px solid var(--bp-hairline);
  border-radius: var(--bp-rounded-md);
  overflow: hidden;
}
.bp-size-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 10px 14px;
  border: none;
  background: var(--bp-surface-soft);
  font-size: 13px;
  font-weight: 600;
  color: var(--bp-body);
  cursor: pointer;
  font-family: inherit;
  transition: background 0.15s;
}
.bp-size-toggle:hover { background: var(--bp-hairline-soft); }
.bp-chevron { transition: transform 0.2s; }
.bp-chevron.rotated { transform: rotate(180deg); }
.bp-size-hint { margin-left:auto;font-weight:400;color:var(--bp-muted);font-size:12px }
.bp-size-options {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 10px 14px 14px;
  border-top: 1px solid var(--bp-hairline);
}
.bp-size-label { font-size:13px;color:var(--bp-body) }

.bp-chip {
  padding: 4px 12px;
  font-size: 12px;
  border: 1px solid var(--bp-hairline);
  border-radius: var(--bp-rounded-pill);
  background: var(--bp-canvas);
  color: var(--bp-body);
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s ease;
}
.bp-chip:hover { border-color: var(--bp-primary); color: var(--bp-primary); }
.bp-chip.active { background: var(--bp-primary); border-color: var(--bp-primary); color: var(--bp-on-primary); }
.bp-chip.sm { padding: 2px 8px; font-size: 11px; border-radius: 4px; }

.bp-preview {
  width: 100%;
  aspect-ratio: 16/9;
  border-radius: var(--bp-rounded-md);
  overflow: hidden;
  border: 1px solid var(--bp-hairline);
  background: #000;
  margin-top: 8px;
}
.bp-preview iframe { width:100%;height:100%;border:none;display:block }

/* Log */
.bp-log-fab {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 99;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: var(--bp-brand);
  border: none;
  border-radius: var(--bp-rounded-pill);
  cursor: pointer;
  box-shadow: 0 6px 20px rgba(251,114,153,0.35);
  transition: transform 0.15s, box-shadow 0.15s;
  font-family: inherit;
}
.bp-log-fab:hover { transform: translateY(-1px); box-shadow: 0 8px 24px rgba(251,114,153,0.45); }
.bp-log-fab.open { background: var(--bp-primary); box-shadow: 0 6px 20px rgba(0,0,0,0.2); }

.bp-log-drawer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 98;
  height: 320px;
  background: var(--bp-canvas);
  border-top: 1px solid var(--bp-hairline);
  display: flex;
  flex-direction: column;
  box-shadow: 0 -12px 40px rgba(0,0,0,0.08);
}
.bp-log-drawer__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 20px;
  background: var(--bp-surface-soft);
  border-bottom: 1px solid var(--bp-hairline);
  flex-wrap: wrap;
}
.bp-log-drawer__title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 13px;
  color: var(--bp-ink);
}
.bp-log-count {
  padding: 2px 8px;
  background: var(--bp-surface-card);
  color: var(--bp-brand);
  border-radius: var(--bp-rounded-pill);
  font-size: 11px;
  font-weight: 600;
}
.bp-log-drawer__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bp-log-filter-group { display: flex; gap: 4px; }
.bp-autoscroll-label { font-size:12px; color:var(--bp-muted) }
.bp-log-drawer__body {
  flex: 1;
  overflow-y: auto;
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  padding: 4px 0;
}
.bp-log {
  display: grid;
  grid-template-columns: 70px 48px 1fr;
  gap: 8px;
  padding: 3px 20px;
  align-items: baseline;
  border-bottom: 1px solid rgba(0,0,0,0.02);
}
.bp-log:hover { background: var(--bp-surface-soft); }
.bp-log__time { font-size: 11px; color: var(--bp-muted); }
.bp-log__level { font-size: 10px; font-weight: 700; text-align: center; padding: 1px 0; border-radius: 4px; }
.bp-log__msg { color: var(--bp-body); word-break: break-all; line-height: 1.55; }
.bp-log[data-level="ERROR"] { background: rgba(239,68,68,0.05); }
.bp-log[data-level="ERROR"] .bp-log__level { color:#fff; background: var(--bp-error); }
.bp-log[data-level="WARN"] .bp-log__level { color:#fff; background: var(--bp-warning); }
.bp-log[data-level="INFO"] .bp-log__level { color:#fff; background: #3b82f6; }
.bp-log[data-level="DEBUG"] .bp-log__level { color:var(--bp-body); background:var(--bp-hairline); }

/* Transitions */
.bp-drawer-enter-active,
.bp-drawer-leave-active { transition: transform 0.25s ease; }
.bp-drawer-enter-from,
.bp-drawer-leave-to { transform: translateY(100%); }

.bp-collapse-enter-active,
.bp-collapse-leave-active { transition: all 0.2s ease; overflow: hidden; }
.bp-collapse-enter-from,
.bp-collapse-leave-to { opacity: 0; max-height: 0; }
.bp-collapse-enter-to,
.bp-collapse-leave-from { opacity: 1; max-height: 400px; }
</style>
