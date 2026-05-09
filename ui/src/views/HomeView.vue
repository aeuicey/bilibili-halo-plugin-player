<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick, computed } from 'vue'
import axios from 'axios'
import QRCode from 'qrcode'
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

const activeTabId = ref('login')

/* Login State */
const loginUser = ref<null | {
  uname: string; face: string; level: number
  vipStatus: number; vipType: number
}>(null)
const loginChecked = ref(false)
const qrImage = ref('')
const qrKey = ref('')
const qrStatus = ref<'idle' | 'loading' | 'pending' | 'scanned' | 'expired' | 'success' | 'error'>('idle')
const qrMessage = ref('')
const qrError = ref('')
const pollCount = ref(0)
const pollDebug = ref('')
const pollErrors = ref(0)
let pollTimer: ReturnType<typeof setInterval> | null = null

/* Embed State */
const embedBvid = ref('')
const embedCid = ref('')
const embedLoading = ref(false)
const embedCode = ref('')
const embedPreview = ref('')
const embedWidth = ref('100')
const widthOptions = [
  { value: '100', label: '100% (Responsive)' },
  { value: '800', label: '800px' },
  { value: '640', label: '640px' },
  { value: '480', label: '480px' },
]
const videoInfo = ref<null | {
  title: string; pic: string; ownerName: string
  pages: Array<{ cid: number; page: number; part: string }>
  width: number; height: number
}>(null)
const copied = ref(false)

/* Log State */
const showLogs = ref(false)
const logEntries = ref<Array<{ time: string; level: string; msg: string }>>([])
const logFilter = ref<'ALL' | 'INFO' | 'WARN' | 'ERROR' | 'DEBUG'>('ALL')
const logAutoScroll = ref(true)
const logContainer = ref<HTMLElement | null>(null)
let logPollTimer: ReturnType<typeof setInterval> | null = null

const filteredLogs = computed(() => {
  if (logFilter.value === 'ALL') return logEntries.value
  return logEntries.value.filter((e) => e.level === logFilter.value)
})

const qrStatusLabel = computed(() => {
  const map: Record<string, string> = {
    loading: 'Generating QR code...',
    pending: 'Scan with Bilibili App',
    scanned: 'Confirm on your phone',
    success: 'Login complete',
    expired: 'Code expired, regenerate',
    error: 'Error occurred',
    idle: '',
    generating: 'Generating...',
  }
  return map[qrMessage.value] || qrMessage.value || map[qrStatus.value] || ''
})

/* Lifecycle */
onMounted(async () => { await checkLogin() })
onUnmounted(() => { stopPoll(); stopLogPoll() })

/* Log Polling */
function startLogPoll() {
  stopLogPoll()
  logPollTimer = setInterval(async () => {
    try {
      const { data } = await axios.get(`${API}/logs/history`)
      const list = Array.isArray(data) ? data : (data.value || data || [])
      logEntries.value = list.slice(-800)
      if (logAutoScroll.value) {
        nextTick(() => {
          const el = logContainer.value
          if (el) el.scrollTop = el.scrollHeight
        })
      }
    } catch (_) {}
  }, 2000)
}
function stopLogPoll() { if (logPollTimer) { clearInterval(logPollTimer); logPollTimer = null } }
function toggleLogs() {
  showLogs.value = !showLogs.value
  if (showLogs.value) { startLogPoll(); nextTick(() => { const el = logContainer.value; if (el) el.scrollTop = el.scrollHeight }) }
  else { stopLogPoll() }
}
function clearLogs() { logEntries.value = [] }

/* Login Logic */
async function checkLogin() {
  try {
    const { data } = await axios.get(`${API}/login/status`)
    const p = typeof data === 'string' ? JSON.parse(data) : data
    if (p.isLogin) { loginUser.value = p; activeTabId.value = 'embed' }
  } catch (_) {}
  loginChecked.value = true
}
async function genQr() {
  qrStatus.value = 'loading'; qrImage.value = ''; qrMessage.value = 'loading'
  qrError.value = ''; pollDebug.value = ''; stopPoll()
  try {
    const { data } = await axios.get(`${API}/login/qrcode/generate`)
    const p = typeof data === 'string' ? JSON.parse(data) : data
    if (!p.qrcodeKey) { qrStatus.value = 'error'; qrError.value = 'API data error'; return }
    qrKey.value = p.qrcodeKey
    qrImage.value = await QRCode.toDataURL(p.url, { width: 240, margin: 2, color: { dark: '#fafafa', light: '#0a0a0f' } })
    qrStatus.value = 'pending'; qrMessage.value = 'pending'; startPoll()
  } catch (e: any) { qrStatus.value = 'error'; qrError.value = e?.message || 'Unknown error' }
}
function startPoll() {
  stopPoll(); qrError.value = ''; pollCount.value = 0; pollErrors.value = 0; pollDebug.value = ''
  if (!qrKey.value) { pollDebug.value = 'key-missing'; return }
  pollTimer = setInterval(async () => {
    if (qrStatus.value === 'success' || qrStatus.value === 'expired') return
    try {
      const { data } = await axios.get(`${API}/login/qrcode/poll`, { params: { qrcode_key: qrKey.value } })
      const p = typeof data === 'string' ? JSON.parse(data) : data
      pollCount.value++; pollErrors.value = 0; pollDebug.value = '#P' + pollCount.value + ' ' + p.status
      if (!p?.status) { qrError.value = 'Bad response'; return }
      if (p.status === 'success') {
        qrStatus.value = 'success'; qrMessage.value = 'success'; stopPoll()
        await new Promise((r) => setTimeout(r, 800)); await checkLogin()
        if (!loginUser.value) { qrStatus.value = 'error'; qrError.value = 'SESSDATA verification failed' }
      } else if (p.status === 'scanned') { qrStatus.value = 'scanned'; qrMessage.value = 'scanned' }
      else if (p.status === 'expired' || p.status === 'error') { qrStatus.value = 'expired'; qrMessage.value = 'expired'; stopPoll() }
    } catch (_e: any) {
      pollCount.value++; pollErrors.value++; pollDebug.value = 'err#' + pollErrors.value + ' #P' + pollCount.value
      if (pollErrors.value >= 3) { qrStatus.value = 'expired'; qrMessage.value = 'expired'; stopPoll() }
    }
  }, 2000)
}
function stopPoll() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null } }
async function doLogout() {
  try { await axios.post(`${API}/login/logout`) } catch (_) {}
  loginUser.value = null; qrImage.value = ''; qrStatus.value = 'idle'
  qrMessage.value = ''; qrError.value = ''; activeTabId.value = 'login'
}

/* Embed Logic */
function parseBvid(input: string) {
  input = input.trim()
  const m = input.match(/BV[a-zA-Z0-9]{10}/)
  if (m) return { bvid: m[0], cid: '' }
  const a = input.match(/av(\d+)/i)
  if (a) return { bvid: 'av' + a[1], cid: '' }
  return null
}
async function fetchVideo() {
  const p = parseBvid(embedBvid.value)
  if (!p) { embedCode.value = ''; embedPreview.value = ''; return }
  embedLoading.value = true; embedCode.value = ''; videoInfo.value = null
  try {
    const { data } = await axios.get(`${API}/video/info?bvid=${p.bvid}`)
    const info = typeof data === 'string' ? JSON.parse(data) : data
    const cid = embedCid.value || String(info.pages?.[0]?.cid || '')
    if (!embedCid.value && info.pages?.length > 0) embedCid.value = String(info.pages[0].cid)
    try {
      const { data: pd } = await axios.get(`${API}/video/playurl?bvid=${p.bvid}&cid=${cid}&qn=80&fnval=16`)
      const playData = typeof pd === 'string' ? JSON.parse(pd) : pd
      let w = 0, h = 0
      if (playData.dash && playData.dash.video && playData.dash.video.length) {
        const vTrk = playData.dash.video[0]
        w = vTrk.width || 0
        h = vTrk.height || 0
      }
      videoInfo.value = { ...info, width: w, height: h }
    } catch (_) {
      videoInfo.value = { ...info, width: 0, height: 0 }
    }
    generateCode(p.bvid, cid)
  } catch (_) { embedCode.value = ''; embedPreview.value = '' }
  embedLoading.value = false
}
function generateCode(bvid: string, cid: string) {
  const origin = window.location.origin
  const w = embedWidth.value === '100' ? '100%' : embedWidth.value + 'px'
  let aspectRatio = '16/9'
  if (videoInfo.value && videoInfo.value.width > 0 && videoInfo.value.height > 0) {
    aspectRatio = videoInfo.value.width + '/' + videoInfo.value.height
  }
  embedCode.value = `<div style="position:relative;width:100%;max-width:${w};aspect-ratio:${aspectRatio};border-radius:8px;overflow:hidden;margin:16px 0"><iframe src="${origin}/plugins/bilibili-player/embed?bvid=${bvid}&cid=${cid}" style="position:absolute;top:0;left:0;width:100%;height:100%;border:none" allowfullscreen allow="autoplay;encrypted-media" loading="lazy"></iframe></div>`
  embedPreview.value = `${origin}/plugins/bilibili-player/embed?bvid=${bvid}&cid=${cid}`
}
async function copyCode() {
  try { await navigator.clipboard.writeText(embedCode.value) }
  catch (_) {
    const ta = document.createElement('textarea'); ta.value = embedCode.value
    document.body.appendChild(ta); ta.select(); document.execCommand('copy'); document.body.removeChild(ta)
  }
  copied.value = true; setTimeout(() => { copied.value = false }, 2000)
}
</script>

<template>
  <div class="plugin-root">
    <!-- Tab Navigation -->
    <nav class="plugin-tabs">
      <button class="plugin-tab" :class="{ active: activeTabId === 'login' }" @click="activeTabId = 'login'">
        Account Login
      </button>
      <button class="plugin-tab" :class="{ active: activeTabId === 'embed' }" @click="activeTabId = 'embed'">
        Embed Code
      </button>
    </nav>

    <div class="plugin-main">
      <!-- ====== LOGIN: QR Code ====== -->
      <template v-if="activeTabId === 'login' && !loginUser">
        <VCard class="plugin-card plugin-card--sm" :body-style="{ padding: '32px' }">
          <div class="qr-stage">
            <div class="qr-frame" :class="{
              'qr-frame--scanned': qrStatus === 'scanned' || qrStatus === 'pending',
              'qr-frame--success': qrStatus === 'success',
              'qr-frame--expired': qrStatus === 'expired',
            }">
              <img v-if="qrImage" :src="qrImage" class="qr-frame__image" alt="QR Code" />
              <div v-else class="qr-frame__empty">
                <span>Click button below to generate</span>
              </div>
              <div v-if="qrStatus === 'expired'" class="qr-frame__overlay qr-frame__overlay--expired">&#10005;</div>
              <div v-else-if="qrStatus === 'scanned'" class="qr-frame__overlay qr-frame__overlay--scanned">&#8986;</div>
              <div v-else-if="qrStatus === 'success'" class="qr-frame__overlay qr-frame__overlay--success">&#10003;</div>
            </div>
            <div v-if="qrStatusLabel" class="qr-status" :data-status="qrStatus">{{ qrStatusLabel }}</div>
            <div v-if="pollDebug" class="qr-poll-debug">{{ pollDebug }}</div>
            <VAlert v-if="qrError" type="error" :message="qrError" class="qr-error" />
            <VButton type="primary" :loading="qrStatus === 'loading'" @click="genQr">
              {{ qrStatus === 'loading' ? 'Generating...' : qrImage ? 'Regenerate QR Code' : 'Generate Login QR Code' }}
            </VButton>
            <p class="plugin-hint">Login unlocks 1080P / 4K / 8K high-quality playback</p>
          </div>
        </VCard>
      </template>

      <!-- ====== LOGIN: Profile ====== -->
      <template v-else-if="activeTabId === 'login' && loginUser">
        <VCard class="plugin-card plugin-card--sm" :body-style="{ padding: '32px' }">
          <div class="profile">
            <div class="profile__avatar-wrap">
              <VAvatar :src="loginUser!.face" size="lg" />
              <VTag v-if="loginUser!.vipStatus" class="profile__badge">
                {{ loginUser!.vipType === 2 ? 'Annual' : 'VIP' }}
              </VTag>
            </div>
            <div class="profile__info">
              <div class="profile__name">{{ loginUser!.uname }}</div>
              <VSpace class="profile__tags" spacing="xs">
                <VTag>Lv{{ loginUser!.level }}</VTag>
                <VTag v-if="loginUser!.vipType === 2" class="tag--bigvip">Annual VIP</VTag>
                <VTag v-else-if="loginUser!.vipStatus" class="tag--vip">VIP</VTag>
              </VSpace>
              <div class="profile__hint">Premium quality unlocked &mdash; 1080P+ available</div>
            </div>
            <VButton type="default" @click="doLogout">Sign Out</VButton>
          </div>
        </VCard>
      </template>

      <!-- ====== EMBED TAB ====== -->
      <template v-if="activeTabId === 'embed'">
        <VCard class="plugin-card" :body-style="{ padding: '32px' }">
          <VAlert v-if="!loginUser" type="warning" message="Login for higher resolution. Guests limited to 480P." class="embed-alert" />

          <div class="embed-form">
            <label class="plugin-label">Bilibili Video URL or BV ID</label>
            <div class="embed-input-row">
              <input
                v-model="embedBvid"
                class="plugin-input"
                placeholder="e.g. BV1GJ411x7h7 or paste full link"
                @keyup.enter="fetchVideo"
              />
              <VButton type="primary" :loading="embedLoading" @click="fetchVideo">
                {{ embedLoading ? 'Analyzing...' : 'Analyze' }}
              </VButton>
            </div>
          </div>

          <div v-if="videoInfo" class="video-preview">
            <div class="video-preview__body">
              <img v-if="videoInfo.pic" :src="videoInfo.pic + '@200w'" class="video-preview__thumb" alt="" />
              <div class="video-preview__meta">
                <div class="video-preview__title">{{ videoInfo.title }}</div>
                <div class="video-preview__author">{{ videoInfo.ownerName }}</div>
              </div>
            </div>
            <div v-if="videoInfo.pages && videoInfo.pages.length > 1" class="video-preview__row">
              <label class="plugin-label plugin-label--inline">Select Part</label>
              <select v-model="embedCid" class="plugin-select" @change="generateCode(parseBvid(embedBvid)?.bvid || '', embedCid)">
                <option v-for="p in videoInfo.pages" :key="p.cid" :value="String(p.cid)">P{{ p.page }} &middot; {{ p.part }}</option>
              </select>
            </div>
            <div v-if="videoInfo.width > 0 && videoInfo.height > 0" class="video-preview__row">
              <label class="plugin-label plugin-label--inline">Resolution</label>
              <span class="plugin-hint" style="text-align:left">{{ videoInfo.width }}×{{ videoInfo.height }} · {{ videoInfo.width > videoInfo.height ? 'Landscape' : 'Portrait' }}</span>
            </div>
            <div class="video-preview__row">
              <label class="plugin-label plugin-label--inline">Player Width</label>
              <select v-model="embedWidth" class="plugin-select" @change="generateCode(parseBvid(embedBvid)?.bvid || '', embedCid)">
                <option v-for="o in widthOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
              </select>
            </div>
          </div>

          <template v-if="embedCode">
            <div class="embed-result">
              <label class="plugin-label">Embed Code &mdash; paste into your article HTML editor</label>
              <div class="code-block">
                <code class="code-block__text">{{ embedCode }}</code>
                <button class="code-block__copy" :class="{ done: copied }" @click="copyCode" :aria-label="copied ? 'Copied' : 'Copy code'">
                  {{ copied ? '✓' : 'Copy' }}
                </button>
              </div>
              <div class="embed-result__bar">
                <VButton type="primary" size="sm" @click="copyCode">{{ copied ? 'Copied' : 'Copy Code' }}</VButton>
                <span class="plugin-hint">Paste into article HTML &mdash; the player appears for your readers</span>
              </div>
              <div v-if="embedPreview" class="embed-preview">
                <label class="plugin-label">Live Preview</label>
                <div class="embed-preview__frame" :style="videoInfo && videoInfo.width > 0 ? { aspectRatio: videoInfo.width + '/' + videoInfo.height } : {}">
                  <iframe :src="embedPreview" title="Player Preview" allowfullscreen allow="autoplay" loading="lazy" />
                </div>
              </div>
            </div>
          </template>
        </VCard>
      </template>
    </div>

    <!-- Debug Log Drawer -->
    <Transition name="drawer-slide">
      <aside v-if="showLogs" class="log-drawer">
        <div class="log-drawer__header">
          <div class="log-drawer__header-left">
            <span class="log-drawer__title">Debug Logs</span>
            <span class="log-drawer__count">{{ filteredLogs.length }}</span>
          </div>
          <div class="log-drawer__header-right">
            <select v-model="logFilter" class="log-drawer__select">
              <option value="ALL">All</option>
              <option value="INFO">INFO</option>
              <option value="WARN">WARN</option>
              <option value="ERROR">ERROR</option>
              <option value="DEBUG">DEBUG</option>
            </select>
            <VSwitch v-model:value="logAutoScroll" />
            <button class="log-drawer__btn" @click="clearLogs" title="Clear">&times;</button>
            <button class="log-drawer__btn" @click="showLogs = false; stopLogPoll()" title="Close">&minus;</button>
          </div>
        </div>
        <div
          ref="logContainer"
          class="log-drawer__body"
          @scroll="logAutoScroll = logContainer ? logContainer.scrollHeight - logContainer.scrollTop - logContainer.clientHeight < 35 : true"
        >
          <VEmpty v-if="filteredLogs.length === 0" message="Waiting for log events..." />
          <div v-for="(entry, i) in filteredLogs" :key="i" class="log-entry" :data-level="entry.level">
            <span class="log-entry__time">[{{ entry.time }}]</span>
            <span class="log-entry__level">{{ entry.level }}</span>
            <span class="log-entry__msg">{{ entry.msg }}</span>
          </div>
        </div>
      </aside>
    </Transition>

    <!-- Debug Trigger -->
    <button class="debug-trigger" :class="{ active: showLogs }" @click="toggleLogs" title="Debug Logs">
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>
      <span>Logs</span>
      <span v-if="logEntries.length" class="debug-trigger__badge">{{ logEntries.length > 99 ? '99+' : logEntries.length }}</span>
    </button>
  </div>
</template>

<style lang="scss">
/* ============================================================
   BILIBILI PLAYER PLUGIN — Halo Native Components + Responsive
   Breakpoints: sm=640px, md=768px, lg=1024px
   ============================================================ */

$clr-bg: #09090b;
$clr-surface: rgba(18, 18, 24, 0.85);
$clr-accent: #00a1d6;
$clr-accent-hover: #00b5ee;
$clr-text-primary: #e4e4e7;
$clr-text-secondary: #a1a1aa;
$clr-text-muted: #71717a;
$clr-border: rgba(255, 255, 255, 0.06);
$clr-code-bg: #0c0c14;
$clr-green: #22c55e;
$clr-yellow: #f59e0b;
$clr-red: #ef4444;

$font-mono: 'SF Mono', 'Fira Code', 'Consolas', 'Menlo', monospace;
$radius-sm: 6px;
$radius-md: 8px;
$ease-out: cubic-bezier(0.16, 1, 0.3, 1);

/* ===== Root ===== */
.plugin-root {
  min-height: 100vh;
  padding: 20px 24px 120px;
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 20px;

  @media (max-width: 640px) { padding: 12px 12px 100px; gap: 12px; }
  @media (min-width: 641px) and (max-width: 768px) { padding: 16px 18px 110px; }
  @media (min-width: 769px) { max-width: 840px; margin: 0 auto; width: 100%; }
}

/* ===== Tab Navigation ===== */
.plugin-tabs {
  display: flex; gap: 2px;
}
.plugin-tab {
  display: inline-flex; align-items: center; gap: 7px;
  padding: 10px 20px; font-size: 13px; font-weight: 500;
  color: $clr-text-muted; background: none; border: none;
  border-radius: 8px 8px 0 0; cursor: pointer;
  transition: all .2s $ease-out; position: relative; font-family: inherit;

  @media (max-width: 640px) { padding: 9px 14px; font-size: 12px; }

  &::after {
    content: ''; position: absolute; bottom: 0; left: 0; right: 0; height: 2px;
    background: $clr-accent; border-radius: 2px 2px 0 0; opacity: 0;
    transform: scaleX(0.6); transition: opacity .2s $ease-out, transform .25s ease;
  }

  &:hover { color: $clr-text-secondary; background: rgba(255, 255, 255, 0.03); }
  &.active { color: $clr-accent; background: rgba(0, 161, 214, 0.06); &::after { opacity: 1; transform: scaleX(1); } }
}

/* ===== Main ===== */
.plugin-main { display: flex; flex-direction: column; gap: 16px; }
.plugin-card--sm { @media (min-width: 769px) { max-width: 560px; margin: 0 auto; } }
.embed-alert { margin-bottom: 20px; }

/* ===== QR ===== */
.qr-stage { display: flex; flex-direction: column; align-items: center; gap: 16px; }
.qr-status { font-size: 14px; color: $clr-text-secondary; text-align: center; &[data-status="success"] { color: $clr-green; font-weight: 500; } &[data-status="error"], &[data-status="expired"] { color: $clr-red; } }
.qr-poll-debug {
  font-size: 11px; color: $clr-accent; text-align: center;
  padding: 3px 10px; background: rgba(0, 161, 214, 0.08);
  border: 1px solid rgba(0, 161, 214, 0.15); border-radius: 100px; font-family: $font-mono;
}
.qr-error { margin-top: 4px; }

.qr-frame {
  padding: 16px; background: rgba(255, 255, 255, 0.03); border: 1px solid $clr-border;
  border-radius: $radius-md; position: relative; transition: border-color .35s $ease-out;

  &--scanned { border-color: rgba($clr-yellow, 0.4); }
  &--success { border-color: rgba($clr-green, 0.5); }
  &--expired { border-color: rgba($clr-red, 0.5); }

  &__image { width: 240px; height: 240px; display: block; border-radius: 6px; @media (max-width: 640px) { width: 200px; height: 200px; } }
  &__empty {
    width: 240px; height: 240px; display: flex; align-items: center; justify-content: center;
    color: $clr-text-muted; font-size: 12px; border: 1.5px dashed rgba(255, 255, 255, 0.08); border-radius: 6px;
    @media (max-width: 640px) { width: 200px; height: 200px; }
  }
  &__overlay {
    position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
    font-size: 36px; border-radius: $radius-md;
    &--expired { background: rgba($clr-red, 0.08); color: $clr-red; }
    &--scanned { background: rgba($clr-yellow, 0.08); color: $clr-yellow; }
    &--success { background: rgba($clr-green, 0.08); color: $clr-green; }
  }
}

/* ===== Profile ===== */
.profile {
  display: flex; align-items: center; gap: 18px; flex-wrap: wrap;
  @media (max-width: 640px) { flex-direction: column; align-items: flex-start; }

  &__avatar-wrap { position: relative; flex-shrink: 0; }
  &__badge { position: absolute; bottom: -4px; right: -8px; }
  &__info { flex: 1; min-width: 0; }
  &__name { font-size: 17px; font-weight: 600; color: $clr-text-primary; margin-bottom: 6px; letter-spacing: -0.01em; }
  &__tags { margin-bottom: 8px; }
  &__hint { font-size: 12px; color: $clr-text-muted; }
}
.tag--vip :deep(.v-tag) { background: rgba(245, 158, 11, 0.15); color: $clr-yellow; }
.tag--bigvip :deep(.v-tag) { background: rgba(236, 72, 153, 0.15); color: #f472b6; }

/* ===== Embed Form ===== */
.embed-form { display: flex; flex-direction: column; gap: 12px; }
.embed-input-row {
  display: flex; gap: 10px;
  @media (max-width: 640px) { flex-direction: column; gap: 8px; .v-button { width: 100%; } }
}

.plugin-label {
  display: block; font-size: 11px; font-weight: 600;
  color: $clr-text-muted; text-transform: uppercase;
  letter-spacing: 0.05em; margin-bottom: 6px;
  &--inline { margin-bottom: 0; white-space: nowrap; }
}
.plugin-input {
  flex: 1; padding: 10px 14px; font-size: 13px;
  background: rgba(255, 255, 255, 0.04); border: 1px solid $clr-border;
  border-radius: $radius-sm; color: $clr-text-primary; outline: none; font-family: inherit;
  transition: border-color .15s $ease-out;
  &::placeholder { color: $clr-text-muted; }
  &:focus { border-color: $clr-accent; box-shadow: 0 0 0 3px rgba(0, 161, 214, 0.12); }
}
.plugin-select {
  padding: 8px 12px; font-size: 13px; background: rgba(255, 255, 255, 0.04);
  border: 1px solid $clr-border; border-radius: $radius-sm; color: $clr-text-primary;
  outline: none; cursor: pointer; font-family: inherit; min-width: 180px;
  &:focus { border-color: $clr-accent; }
  @media (max-width: 640px) { min-width: 140px; }
}
.plugin-hint { font-size: 12px; color: $clr-text-muted; margin: 0; line-height: 1.5; text-align: center; }

/* ===== Video Preview ===== */
.video-preview {
  background: rgba(255, 255, 255, 0.03); border: 1px solid $clr-border;
  border-radius: $radius-sm; overflow: hidden; margin-top: 12px;

  &__body { display: flex; gap: 14px; padding: 14px; @media (max-width: 640px) { flex-direction: column; } }
  &__thumb { width: 132px; aspect-ratio: 16/9; object-fit: cover; border-radius: 4px; flex-shrink: 0; background: rgba(255,255,255,.04); @media (max-width: 640px) { width: 100%; } }
  &__meta { min-width: 0; }
  &__title { font-size: 14px; font-weight: 500; color: $clr-text-primary; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; margin-bottom: 6px; }
  &__author { font-size: 12px; color: $clr-text-muted; }
  &__row {
    display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-top: 1px solid $clr-border;
    @media (max-width: 640px) { flex-direction: column; align-items: flex-start; }
  }
}

/* ===== Embed Result ===== */
.embed-result { display: flex; flex-direction: column; gap: 12px; margin-top: 16px; &__bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; } }

/* ===== Code Block ===== */
.code-block {
  position: relative; background: $clr-code-bg;
  border: 1px solid rgba(255, 255, 255, 0.06); border-radius: $radius-sm;
  padding: 16px 50px 16px 16px; overflow-x: auto;

  &__text { font-family: $font-mono; font-size: 12px; color: $clr-text-secondary; white-space: pre-wrap; word-break: break-all; line-height: 1.7; }
  &__copy {
    position: absolute; top: 10px; right: 10px; display: flex; align-items: center; justify-content: center;
    min-width: 36px; height: 30px; padding: 0 8px; font-size: 11px; font-family: inherit; font-weight: 500;
    background: rgba(255, 255, 255, 0.05); border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 5px;
    color: $clr-text-muted; cursor: pointer; transition: all .15s $ease-out;
    &:hover { background: rgba(255, 255, 255, 0.12); color: #fff; }
    &.done { color: $clr-green; border-color: $clr-green; }
  }
}

/* ===== Embed Preview ===== */
.embed-preview {
   margin-top: 16px;
   &__frame {
     border: 1px solid $clr-border; border-radius: $radius-sm; overflow: hidden;
     aspect-ratio: 16/9;
     width: 100%;
     max-width: 100%;
     @media (max-width: 640px) { max-width: 100%; }
     iframe { width: 100%; height: 100%; border: none; display: block; }
   }
 }

/* ===== Debug Trigger ===== */
.debug-trigger {
  position: fixed; bottom: 20px; right: 20px; display: flex; align-items: center; gap: 5px;
  padding: 8px 14px; font-size: 11px; color: $clr-text-muted; background: rgba(18, 18, 24, 0.9);
  border: 1px solid $clr-border; border-radius: 20px; cursor: pointer; font-family: inherit; z-index: 900;
  backdrop-filter: blur(8px); transition: all .2s $ease-out;
  &:hover { background: rgba(255, 255, 255, 0.04); color: $clr-text-secondary; }
  &.active { background: rgba(0, 161, 214, 0.1); color: $clr-accent; border-color: rgba(0, 161, 214, 0.3); }
  &__badge { font-size: 9px; font-weight: 700; background: $clr-red; color: #fff; padding: 0 5px; border-radius: 100px; min-width: 18px; text-align: center; line-height: 16px; }
  @media (max-width: 640px) { bottom: 12px; right: 12px; padding: 6px 10px; font-size: 10px; }
}

/* ===== Log Drawer ===== */
.log-drawer {
  position: fixed; bottom: 0; left: 0; right: 0; height: 300px;
  background: $clr-surface; border-top: 1px solid $clr-border;
  display: flex; flex-direction: column; z-index: 1000;
  font-family: $font-mono; font-size: 12px;
  box-shadow: 0 -8px 32px rgba(0, 0, 0, 0.5); backdrop-filter: blur(12px);
  @media (max-width: 640px) { height: 200px; }

  &__header { display: flex; align-items: center; justify-content: space-between; padding: 8px 16px; background: rgba(0, 0, 0, 0.3); border-bottom: 1px solid $clr-border; flex-shrink: 0; user-select: none; }
  &__header-left { display: flex; align-items: center; gap: 8px; color: $clr-text-secondary; font-size: 12px; font-weight: 500; }
  &__title { font-family: inherit; }
  &__count { font-size: 10px; background: rgba(255, 255, 255, 0.06); color: $clr-text-muted; padding: 1px 7px; border-radius: 10px; }
  &__header-right { display: flex; align-items: center; gap: 5px; }
  &__select { font-size: 11px; padding: 3px 8px; background: rgba(255, 255, 255, 0.05); color: $clr-text-secondary; border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 4px; outline: none; cursor: pointer; font-family: inherit; }
  &__btn { display: flex; align-items: center; justify-content: center; width: 26px; height: 26px; font-size: 12px; background: none; color: $clr-text-muted; border: 1px solid transparent; border-radius: 4px; cursor: pointer; transition: all .15s; font-family: inherit; &:hover { background: rgba(255, 255, 255, 0.06); color: $clr-text-secondary; } }
  &__body { flex: 1; overflow-y: auto; padding: 6px 0; }
}

.log-entry {
  display: flex; gap: 8px; padding: 3px 16px; border-bottom: 1px solid rgba(255, 255, 255, 0.02); align-items: baseline;
  &[data-level="ERROR"] { background: rgba(239, 68, 68, 0.06); } &[data-level="WARN"] { background: rgba(245, 158, 11, 0.04); }
  &__time { color: #52525b; flex-shrink: 0; font-size: 10px; }
  &__level { flex-shrink: 0; font-weight: 600; font-size: 10px; width: 40px; }
  &__msg { color: #d4d4d8; word-break: break-all; }
  &[data-level="ERROR"] &__level { color: #fca5a5; } &[data-level="WARN"] &__level { color: #fcd34d; }
  &[data-level="DEBUG"] &__level { color: #94a3b8; }
  &:not([data-level="ERROR"]):not([data-level="WARN"]):not([data-level="DEBUG"]) &__level { color: #7dd3fc; }
}

/* ===== Transitions ===== */
.drawer-slide-enter-active { transition: transform .25s $ease-out; }
.drawer-slide-leave-active { transition: transform .2s ease; }
.drawer-slide-enter-from { transform: translateY(100%); }
.drawer-slide-leave-to { transform: translateY(100%); }
</style>
