<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick, computed } from 'vue'
import axios from 'axios'
import QRCode from 'qrcode'

/* ===== Constants ===== */
const API = '/plugins/bilibili-player/api'

/* ===== Tab State ===== */
const activeTab = ref<'login' | 'embed'>('login')

/* ===== Login State ===== */
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

/* ===== Embed State ===== */
const embedBvid = ref('')
const embedCid = ref('')
const embedLoading = ref(false)
const embedCode = ref('')
const embedPreview = ref('')
const videoInfo = ref<null | {
  title: string; pic: string; ownerName: string
  pages: Array<{ cid: number; page: number; part: string }>
}>(null)
const copied = ref(false)

/* ===== Log State ===== */
const showLogs = ref(false)
const logEntries = ref<Array<{ time: string; level: string; msg: string }>>([])
const logFilter = ref<'ALL' | 'INFO' | 'WARN' | 'ERROR' | 'DEBUG'>('ALL')
const logAutoScroll = ref(true)
const logContainer = ref<HTMLElement | null>(null)
let logPollTimer: ReturnType<typeof setInterval> | null = null

/* ===== Computed ===== */
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

/* ===== Lifecycle ===== */
onMounted(async () => {
  await checkLogin()
})
onUnmounted(() => {
  stopPoll()
  stopLogPoll()
})

/* ===== Log Polling ===== */
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

function stopLogPoll() {
  if (logPollTimer) {
    clearInterval(logPollTimer)
    logPollTimer = null
  }
}

function toggleLogs() {
  showLogs.value = !showLogs.value
  if (showLogs.value) {
    startLogPoll()
    nextTick(() => {
      const el = logContainer.value
      if (el) el.scrollTop = el.scrollHeight
    })
  } else {
    stopLogPoll()
  }
}

function clearLogs() {
  logEntries.value = []
}

/* ===== Login Logic ===== */
async function checkLogin() {
  try {
    const { data } = await axios.get(`${API}/login/status`)
    const p = typeof data === 'string' ? JSON.parse(data) : data
    if (p.isLogin) {
      loginUser.value = p
      activeTab.value = 'embed'
    }
  } catch (_) {}
  loginChecked.value = true
}

async function genQr() {
  qrStatus.value = 'loading'
  qrImage.value = ''
  qrMessage.value = 'loading'
  qrError.value = ''
  pollDebug.value = ''
  stopPoll()
  try {
    const { data } = await axios.get(`${API}/login/qrcode/generate`)
    const p = typeof data === 'string' ? JSON.parse(data) : data
    if (!p.qrcodeKey) {
      qrStatus.value = 'error'
      qrError.value = 'API data error'
      return
    }
    qrKey.value = p.qrcodeKey
    qrImage.value = await QRCode.toDataURL(p.url, {
      width: 240,
      margin: 2,
      color: { dark: '#fafafa', light: '#0a0a0f' },
    })
    qrStatus.value = 'pending'
    qrMessage.value = 'pending'
    startPoll()
  } catch (e: any) {
    qrStatus.value = 'error'
    qrError.value = e?.message || 'Unknown error'
  }
}

function startPoll() {
  stopPoll()
  qrError.value = ''
  pollCount.value = 0
  pollErrors.value = 0
  pollDebug.value = ''
  if (!qrKey.value) {
    pollDebug.value = 'key-missing'
    return
  }
  pollTimer = setInterval(async () => {
    if (qrStatus.value === 'success' || qrStatus.value === 'expired') return
    try {
      const { data } = await axios.get(`${API}/login/qrcode/poll`, {
        params: { qrcode_key: qrKey.value },
      })
      const p = typeof data === 'string' ? JSON.parse(data) : data
      pollCount.value++
      pollErrors.value = 0
      pollDebug.value = '#P' + pollCount.value + ' ' + p.status
      if (!p?.status) {
        qrError.value = 'Bad response'
        return
      }
      if (p.status === 'success') {
        qrStatus.value = 'success'
        qrMessage.value = 'success'
        stopPoll()
        await new Promise((r) => setTimeout(r, 800))
        await checkLogin()
        if (!loginUser.value) {
          qrStatus.value = 'error'
          qrError.value = 'SESSDATA verification failed'
        }
      } else if (p.status === 'scanned') {
        qrStatus.value = 'scanned'
        qrMessage.value = 'scanned'
      } else if (p.status === 'expired' || p.status === 'error') {
        qrStatus.value = 'expired'
        qrMessage.value = 'expired'
        stopPoll()
      }
    } catch (_e: any) {
      pollCount.value++
      pollErrors.value++
      pollDebug.value = 'err#' + pollErrors.value + ' #P' + pollCount.value
      if (pollErrors.value >= 3) {
        qrStatus.value = 'expired'
        qrMessage.value = 'expired'
        stopPoll()
      }
    }
  }, 2000)
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function doLogout() {
  try {
    await axios.post(`${API}/login/logout`)
  } catch (_) {}
  loginUser.value = null
  qrImage.value = ''
  qrStatus.value = 'idle'
  qrMessage.value = ''
  qrError.value = ''
  activeTab.value = 'login'
}

/* ===== Embed Logic ===== */
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
  if (!p) {
    embedCode.value = ''
    embedPreview.value = ''
    return
  }
  embedLoading.value = true
  embedCode.value = ''
  videoInfo.value = null
  try {
    const { data } = await axios.get(`${API}/video/info?bvid=${p.bvid}`)
    const info = typeof data === 'string' ? JSON.parse(data) : data
    videoInfo.value = info
    const cid = embedCid.value || String(info.pages?.[0]?.cid || '')
    if (!embedCid.value && info.pages?.length > 0) embedCid.value = String(info.pages[0].cid)
    generateCode(p.bvid, cid)
  } catch (_) {
    embedCode.value = ''
    embedPreview.value = ''
  }
  embedLoading.value = false
}

function generateCode(bvid: string, cid: string) {
  const origin = window.location.origin
  embedCode.value = `<div style="position:relative;width:100%;max-width:100%;aspect-ratio:16/9;border-radius:8px;overflow:hidden;margin:16px 0"><iframe src="${origin}/plugins/bilibili-player/embed?bvid=${bvid}&cid=${cid}" style="position:absolute;top:0;left:0;width:100%;height:100%;border:none" allowfullscreen allow="autoplay;encrypted-media" loading="lazy"></iframe></div>`
  embedPreview.value = `${origin}/plugins/bilibili-player/embed?bvid=${bvid}&cid=${cid}`
}

async function copyCode() {
  try {
    await navigator.clipboard.writeText(embedCode.value)
  } catch (_) {
    const ta = document.createElement('textarea')
    ta.value = embedCode.value
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
  }
  copied.value = true
  setTimeout(() => {
    copied.value = false
  }, 2000)
}
</script>

<template>
  <div class="dash-root">
    <!-- ====== Hero Gradient Background ====== -->
    <div class="dash-bg" aria-hidden="true">
      <div class="dash-bg-glow dash-bg-glow--1"></div>
      <div class="dash-bg-glow dash-bg-glow--2"></div>
      <div class="dash-bg-grid"></div>
    </div>

    <!-- ====== Header ====== -->
    <header class="dash-header">
      <div class="dash-header__inner">
        <div class="dash-header__brand">
          <svg class="dash-header__logo" width="22" height="22" viewBox="0 0 24 24">
            <path d="M17.813 4.653h.854c1.51.054 2.769.578 3.773 1.574a5.32 5.32 0 011.56 3.76v7.36a5.32 5.32 0 01-1.56 3.773c-1.004.995-2.262 1.524-3.773 1.56H5.333a5.32 5.32 0 01-3.773-1.56A5.32 5.32 0 010 17.347v-7.36a5.32 5.32 0 011.56-3.76c1.004-.996 2.262-1.52 3.773-1.574h.774l-1.174-1.12a1.23 1.23 0 01-.373-.906c0-.356.124-.658.373-.907l.027-.027a1.29 1.29 0 011.84 0L9.653 4.44c.071.071.134.142.187.213h4.267a.84.84 0 01.16-.213l2.853-2.747a1.29 1.29 0 011.84 0c.267.249.391.551.391.907 0 .355-.124.657-.373.906zM5.333 7.24c-.746.018-1.373.276-1.88.773-.506.498-.769 1.13-.786 1.894v7.52c.017.764.28 1.395.786 1.893.507.498 1.134.756 1.88.773h13.334c.746-.017 1.373-.275 1.88-.773a2.65 2.65 0 00.786-1.893v-7.52c-.017-.765-.28-1.396-.786-1.894-.507-.497-1.134-.755-1.88-.773zm2.667 3.867c.373 0 .684.124.933.373.25.249.383.569.4.96v1.173c-.017.391-.15.711-.4.96-.249.25-.56.374-.933.374s-.684-.125-.933-.374c-.25-.249-.383-.569-.4-.96V12.44c0-.373.129-.689.386-.947.258-.257.574-.386.947-.386zm8 0c.373 0 .684.124.933.373.25.249.383.569.4.96v1.173c-.017.391-.15.711-.4.96a1.32 1.32 0 01-1.867 0c-.25-.249-.383-.569-.4-.96V12.44c.017-.391.15-.711.4-.96.249-.249.56-.373.933-.373z" fill="#00a1d6"/>
          </svg>
          <span class="dash-header__title">BiliBili Player</span>
          <span class="dash-header__version">v1.2</span>
        </div>

        <div class="dash-header__actions">
          <div v-if="loginChecked && loginUser" class="user-chip" @click="activeTab = 'login'">
            <img :src="loginUser.face" class="user-chip__avatar" alt="" />
            <span class="user-chip__name">{{ loginUser.uname }}</span>
            <span class="user-chip__level">Lv{{ loginUser.level }}</span>
          </div>

          <button class="debug-trigger" :class="{ active: showLogs }" @click="toggleLogs" title="Debug Logs">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>
            <span>Logs</span>
            <span v-if="logEntries.length" class="debug-trigger__badge">{{ logEntries.length > 99 ? '99+' : logEntries.length }}</span>
          </button>
        </div>
      </div>
    </header>

    <!-- ====== Tab Navigation ====== -->
    <nav class="dash-tabs">
      <button class="dash-tabs__item" :class="{ active: activeTab === 'login' }" @click="activeTab = 'login'">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"/><polyline points="10 17 15 12 10 7"/><line x1="15" y1="12" x2="3" y2="12"/></svg>
        <span>Account Login</span>
      </button>
      <button class="dash-tabs__item" :class="{ active: activeTab === 'embed' }" @click="activeTab = 'embed'">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
        <span>Embed Code</span>
      </button>
    </nav>

    <!-- ====== Main Content ====== -->
    <main class="dash-main">
      <Transition name="dash-fade" mode="out-in">

        <!-- ====== LOGIN CARD: Not Logged In ====== -->
        <section v-if="activeTab === 'login' && !loginUser" key="qr" class="card card--qr">
          <div class="card__qr-stage">
            <!-- QR Frame -->
            <div class="qr-frame" :class="{
              'qr-frame--scanned': qrStatus === 'scanned' || qrStatus === 'pending',
              'qr-frame--success': qrStatus === 'success',
              'qr-frame--expired': qrStatus === 'expired',
            }">
              <img v-if="qrImage" :src="qrImage" class="qr-frame__image" alt="Bilibili login QR code" />
              <div v-else class="qr-frame__empty">
                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" stroke-linecap="round"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>
                <span>Click button below to generate</span>
              </div>
              <!-- Status Overlay -->
              <div v-if="qrStatus === 'expired'" class="qr-frame__overlay qr-frame__overlay--expired">
                <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="1.8" stroke-linecap="round"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>
              </div>
              <div v-else-if="qrStatus === 'scanned'" class="qr-frame__overlay qr-frame__overlay--scanned">
                <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="#f59e0b" stroke-width="1.8" stroke-linecap="round"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
              </div>
              <div v-else-if="qrStatus === 'success'" class="qr-frame__overlay qr-frame__overlay--success">
                <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="1.8" stroke-linecap="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>
              </div>
            </div>

            <!-- Status Text -->
            <div v-if="qrStatusLabel" class="qr-status" :data-status="qrStatus">{{ qrStatusLabel }}</div>

            <!-- Poll Debug -->
            <div v-if="pollDebug" class="qr-poll-debug">{{ pollDebug }}</div>

            <!-- Error -->
            <div v-if="qrError" class="qr-error-text">{{ qrError }}</div>

            <!-- Action Button -->
            <button class="btn btn--primary btn--qr" :disabled="qrStatus === 'loading'" @click="genQr">
              <svg v-if="qrStatus === 'loading'" class="btn__spinner" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>
              {{ qrStatus === 'loading' ? 'Generating...' : qrImage ? 'Regenerate QR Code' : 'Generate Login QR Code' }}
            </button>

            <p class="card__caption">Login unlocks 1080P / 4K / 8K high-quality playback</p>
          </div>
        </section>

        <!-- ====== LOGIN CARD: Logged In ====== -->
        <section v-else-if="activeTab === 'login'" key="profile" class="card card--profile">
          <div class="profile">
            <div class="profile__avatar-wrap">
              <img :src="loginUser!.face" class="profile__avatar" alt="" />
              <span v-if="loginUser!.vipStatus" class="profile__badge">{{ loginUser!.vipType === 2 ? 'Annual' : 'VIP' }}</span>
            </div>
            <div class="profile__info">
              <div class="profile__name">{{ loginUser!.uname }}</div>
              <div class="profile__tags">
                <span class="tag tag--lv">Lv{{ loginUser!.level }}</span>
                <span v-if="loginUser!.vipType === 2" class="tag tag--bigvip">Annual VIP</span>
                <span v-else-if="loginUser!.vipStatus" class="tag tag--vip">VIP</span>
              </div>
              <div class="profile__hint">Premium quality unlocked &mdash; 1080P+ available</div>
            </div>
            <button class="btn btn--ghost" @click="doLogout">Sign Out</button>
          </div>
        </section>

        <!-- ====== EMBED CARD ====== -->
        <section v-if="activeTab === 'embed'" key="embed" class="card card--embed">
          <!-- Guest Warning -->
          <div v-if="!loginUser" class="alert-banner">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#d97706" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><circle cx="12" cy="16" r="0.6" fill="#d97706"/></svg>
            <span>Login for higher resolution. Guests limited to 480P.</span>
            <button class="link-text" @click="activeTab = 'login'">Go to Login</button>
          </div>

          <!-- Input -->
          <div class="embed-form">
            <label class="field-label">Bilibili Video URL or BV ID</label>
            <div class="input-group">
              <input v-model="embedBvid" class="field-input" placeholder="e.g. BV1GJ411x7h7 or paste full link" @keyup.enter="fetchVideo" />
              <button class="btn btn--primary" :disabled="embedLoading" @click="fetchVideo">
                <svg v-if="embedLoading" class="btn__spinner" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>
                {{ embedLoading ? 'Analyzing...' : 'Analyze' }}
              </button>
            </div>
          </div>

          <!-- Video Info -->
          <Transition name="dash-expand">
            <div v-if="videoInfo" class="video-preview">
              <div class="video-preview__body">
                <img v-if="videoInfo.pic" :src="videoInfo.pic + '@200w'" class="video-preview__thumb" alt="" />
                <div class="video-preview__meta">
                  <div class="video-preview__title">{{ videoInfo.title }}</div>
                  <div class="video-preview__author">{{ videoInfo.ownerName }}</div>
                </div>
              </div>
              <div v-if="videoInfo.pages && videoInfo.pages.length > 1" class="video-preview__pages">
                <label class="field-label" style="margin-bottom:0;white-space:nowrap">Select Part</label>
                <select v-model="embedCid" class="field-select" @change="generateCode(parseBvid(embedBvid)?.bvid || '', embedCid)">
                  <option v-for="p in videoInfo.pages" :key="p.cid" :value="String(p.cid)">P{{ p.page }} &middot; {{ p.part }}</option>
                </select>
              </div>
            </div>
          </Transition>

          <!-- Embed Result -->
          <Transition name="dash-expand">
            <div v-if="embedCode" class="embed-result">
              <label class="field-label">Embed Code &mdash; paste into your article HTML editor</label>
              <div class="code-block">
                <code class="code-block__text">{{ embedCode }}</code>
                <button class="code-block__copy" :class="{ done: copied }" @click="copyCode" :aria-label="copied ? 'Copied' : 'Copy code'">
                  <svg v-if="!copied" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                  <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2.5" stroke-linecap="round"><polyline points="20 6 9 17 4 12"/></svg>
                </button>
              </div>
              <div class="embed-result__bar">
                <button class="btn btn--primary btn--sm" @click="copyCode">{{ copied ? 'Copied' : 'Copy Code' }}</button>
                <span class="card__caption">Paste into article HTML &mdash; the player appears for your readers</span>
              </div>

              <!-- Preview -->
              <div v-if="embedPreview" class="embed-preview">
                <label class="field-label">Live Preview</label>
                <div class="embed-preview__frame">
                  <iframe :src="embedPreview" title="Player Preview" allowfullscreen allow="autoplay" loading="lazy"></iframe>
                </div>
              </div>
            </div>
          </Transition>
        </section>
      </Transition>
    </main>

    <!-- ====== DEBUG LOG DRAWER ====== -->
    <Transition name="drawer-slide">
      <aside v-if="showLogs" class="log-drawer">
        <div class="log-drawer__header">
          <div class="log-drawer__header-left">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
            <span>Debug Logs</span>
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
            <button :class="{ on: logAutoScroll }" @click="logAutoScroll = !logAutoScroll" title="Auto-scroll">&#9660;</button>
            <button @click="clearLogs" title="Clear">&#10005;</button>
            <button @click="showLogs = false; stopLogPoll()" title="Close">&#8722;</button>
          </div>
        </div>
        <div ref="logContainer" class="log-drawer__body" @scroll="logAutoScroll = logContainer ? logContainer.scrollHeight - logContainer.scrollTop - logContainer.clientHeight < 35 : true">
          <div v-if="filteredLogs.length === 0" class="log-drawer__empty">Waiting for log events...</div>
          <div v-for="(entry, i) in filteredLogs" :key="i" class="log-entry" :data-level="entry.level">
            <span class="log-entry__time">[{{ entry.time }}]</span>
            <span class="log-entry__level">{{ entry.level }}</span>
            <span class="log-entry__msg">{{ entry.msg }}</span>
          </div>
        </div>
      </aside>
    </Transition>
  </div>
</template>

<style lang="scss">
/* ============================================================
   BILIBILI PLAYER DASHBOARD — Dark Editorial / Glass / Cyan
   ============================================================ */

// ─── Design Tokens ─────────────────────────────────────────
$font-sans: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
$font-mono: 'SF Mono', 'Fira Code', 'Consolas', 'Menlo', monospace;

$clr-bg:     #09090b;
$clr-bg-elevated: #111118;
$clr-surface: rgba(18, 18, 24, 0.85);
$clr-surface-hover: rgba(24, 24, 32, 0.92);
$clr-accent:  #00a1d6;
$clr-accent-hover: #00b5ee;
$clr-text-primary: #e4e4e7;
$clr-text-secondary: #a1a1aa;
$clr-text-muted: #71717a;
$clr-border: rgba(255, 255, 255, 0.06);
$clr-border-focus: rgba(255, 255, 255, 0.12);
$clr-green: #22c55e;
$clr-yellow: #f59e0b;
$clr-red: #ef4444;
$clr-code-bg: #0c0c14;

$radius-sm: 6px;
$radius-md: 10px;
$radius-lg: 16px;
$radius-xl: 24px;

$ease-out: cubic-bezier(0.16, 1, 0.3, 1);
$ease-spring: cubic-bezier(0.34, 1.56, 0.64, 1);
$ease-smooth: cubic-bezier(0.65, 0, 0.35, 1);

$break-sm: 480px;
$break-md: 768px;
$break-lg: 1024px;

// ─── Root & Background ─────────────────────────────────────
.dash-root {
  min-height: 100vh;
  background: $clr-bg;
  color: $clr-text-primary;
  font-family: $font-sans;
  font-size: 14px;
  line-height: 1.5;
  display: flex;
  flex-direction: column;
  position: relative;
  isolation: isolate;
  overflow-x: hidden;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

// Ambient glow orbs
.dash-bg {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: 0;
  overflow: hidden;
}
.dash-bg-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(100px);
  opacity: 0.28;
  will-change: transform;
  animation: glow-drift 24s ease-in-out infinite alternate;
  &--1 { width: 640px; height: 640px; background: radial-gradient(circle, rgba(0,161,214,.18), transparent 70%); top: -15%; left: -8%; }
  &--2 { width: 480px; height: 480px; background: radial-gradient(circle, rgba(8,145,178,.14), transparent 70%); bottom: -10%; right: -10%; animation-delay: -10s; }
}
@keyframes glow-drift {
  0% { transform: translate(0, 0) scale(1); }
  100% { transform: translate(80px, 40px) scale(1.12); }
}

// Subtle grid texture
.dash-bg-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255,255,255,0.015) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255,255,255,0.015) 1px, transparent 1px);
  background-size: 60px 60px;
}

// ─── Header ────────────────────────────────────────────────
.dash-header {
  position: sticky;
  top: 0;
  z-index: 100;
  backdrop-filter: blur(20px) saturate(200%);
  background: rgba(9, 9, 11, 0.72);
  border-bottom: 1px solid $clr-border;
  flex-shrink: 0;

  &__inner {
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: 52px;
    padding: 0 24px;
    max-width: 840px;
    margin: 0 auto;
    width: 100%;

    @media (max-width: $break-sm) {
      padding: 0 16px;
    }
  }

  &__brand {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  &__logo {
    flex-shrink: 0;
  }

  &__title {
    font-size: 15px;
    font-weight: 600;
    color: $clr-text-primary;
    letter-spacing: -0.01em;
  }

  &__version {
    font-size: 10px;
    font-weight: 500;
    color: $clr-text-muted;
    background: rgba(255, 255, 255, 0.06);
    padding: 2px 7px;
    border-radius: 100px;
    letter-spacing: 0.03em;
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: 10px;
  }
}

// User chip
.user-chip {
  display: flex;
  align-items: center;
  gap: 7px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid $clr-border;
  padding: 3px 12px 3px 4px;
  border-radius: 100px;
  cursor: pointer;
  transition: background .2s $ease-out, transform .2s $ease-spring;

  &:hover {
    background: rgba(255, 255, 255, 0.08);
    transform: scale(1.03);
  }
  &:active { transform: scale(0.97); }

  &__avatar {
    width: 26px;
    height: 26px;
    border-radius: 50%;
    object-fit: cover;
    border: 1.5px solid $clr-border-focus;
  }
  &__name {
    font-size: 12px;
    font-weight: 500;
    max-width: 100px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: $clr-text-secondary;
  }
  &__level {
    font-size: 10px;
    color: $clr-text-muted;
    background: rgba(255, 255, 255, 0.06);
    padding: 1px 6px;
    border-radius: 100px;
  }
}

// Debug trigger
.debug-trigger {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 5px 12px;
  font-size: 11px;
  font-family: inherit;
  color: $clr-text-muted;
  background: none;
  border: 1px solid $clr-border;
  border-radius: $radius-sm;
  cursor: pointer;
  transition: all .2s $ease-out;

  &:hover { background: rgba(255, 255, 255, 0.04); color: $clr-text-secondary; }
  &.active { background: rgba(0, 161, 214, 0.1); color: $clr-accent; border-color: rgba(0, 161, 214, 0.3); }

  &__badge {
    font-size: 9px;
    font-weight: 700;
    background: $clr-red;
    color: #fff;
    padding: 0 5px;
    border-radius: 100px;
    min-width: 18px;
    text-align: center;
    line-height: 16px;
  }
}

// ─── Tab Navigation ────────────────────────────────────────
.dash-tabs {
  display: flex;
  gap: 2px;
  padding: 16px 24px 0;
  max-width: 840px;
  margin: 0 auto;
  width: 100%;
  position: relative;
  z-index: 1;

  @media (max-width: $break-sm) {
    padding: 12px 16px 0;
  }
}
.dash-tabs__item {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 10px 20px;
  font-size: 13px;
  font-weight: 500;
  font-family: inherit;
  color: $clr-text-muted;
  background: none;
  border: none;
  border-radius: $radius-md $radius-md 0 0;
  cursor: pointer;
  transition: all .2s $ease-out;
  position: relative;

  @media (max-width: $break-sm) {
    padding: 9px 14px;
    font-size: 12px;
  }

  svg { flex-shrink: 0; }

  &::after {
    content: '';
    position: absolute;
    bottom: 0;
    left: 0;
    right: 0;
    height: 2px;
    background: $clr-accent;
    border-radius: 2px 2px 0 0;
    opacity: 0;
    transform: scaleX(0.6);
    transition: opacity .2s $ease-out, transform .25s $ease-spring;
  }

  &:hover { color: $clr-text-secondary; background: rgba(255, 255, 255, 0.03); }

  &.active {
    color: $clr-accent;
    background: rgba(0, 161, 214, 0.06);

    &::after { opacity: 1; transform: scaleX(1); }
  }
}

// ─── Main Stage ────────────────────────────────────────────
.dash-main {
  flex: 1;
  max-width: 840px;
  width: 100%;
  margin: 0 auto;
  padding: 24px 24px 120px;
  position: relative;
  z-index: 1;

  @media (max-width: $break-sm) {
    padding: 20px 16px 100px;
  }
}

// ─── Glass Card ────────────────────────────────────────────
.card {
  background: $clr-surface;
  backdrop-filter: blur(24px) saturate(180%);
  border: 1px solid $clr-border;
  border-radius: $radius-lg;
  padding: 32px;
  position: relative;
  box-shadow: 0 2px 12px rgba(0,0,0,0.4), inset 0 1px 0 rgba(255,255,255,0.04);
  transition: border-color .3s $ease-out, box-shadow .3s $ease-out;

  @media (max-width: $break-sm) {
    padding: 24px 20px;
    border-radius: $radius-md;
  }

  &:hover {
    border-color: $clr-border-focus;
    box-shadow: 0 4px 20px rgba(0,0,0,0.5), inset 0 1px 0 rgba(255,255,255,0.06);
  }

  &__caption {
    font-size: 12px;
    color: $clr-text-muted;
    margin: 0;
    text-align: center;
    line-height: 1.5;
  }

  &--qr {
    max-width: 480px;
    margin: 0 auto;
  }

  &--profile {
    max-width: 560px;
    margin: 0 auto;
  }

  &--embed {
    max-width: 100%;
  }
}

// ─── QR Stage ──────────────────────────────────────────────
.card__qr-stage {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}

.qr-frame {
  position: relative;
  padding: 16px;
  background: $clr-bg-elevated;
  border: 1px solid $clr-border;
  border-radius: $radius-lg;
  transition: border-color .35s $ease-out;

  &--scanned { border-color: rgba($clr-yellow, 0.4); }
  &--success { border-color: rgba($clr-green, 0.5); }
  &--expired { border-color: rgba($clr-red, 0.5); }

  &__image {
    width: 240px;
    height: 240px;
    display: block;
    border-radius: 8px;
    background: $clr-bg-elevated;
  }

  &__empty {
    width: 240px;
    height: 240px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 10px;
    color: $clr-text-muted;
    font-size: 12px;
    border: 1.5px dashed rgba(255, 255, 255, 0.08);
    border-radius: 8px;
  }

  &__overlay {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: $radius-lg;
    &--expired { background: rgba(239, 68, 68, 0.06); }
    &--scanned { background: rgba(245, 158, 11, 0.06); }
    &--success { background: rgba(34, 197, 94, 0.06); }
  }
}

.qr-status {
  font-size: 14px;
  color: $clr-text-secondary;
  text-align: center;
  &[data-status="success"] { color: $clr-green; font-weight: 500; }
  &[data-status="error"] { color: $clr-red; }
  &[data-status="expired"] { color: $clr-red; }
}

.qr-poll-debug {
  font-size: 11px;
  color: $clr-accent;
  text-align: center;
  padding: 3px 10px;
  background: rgba(0, 161, 214, 0.08);
  border: 1px solid rgba(0, 161, 214, 0.15);
  border-radius: 100px;
  font-family: $font-mono;
}

.qr-error-text {
  font-size: 12px;
  color: $clr-red;
  margin: 0;
  text-align: center;
  max-width: 280px;
  word-break: break-all;
}

// ─── Buttons ───────────────────────────────────────────────
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 500;
  font-family: inherit;
  padding: 10px 22px;
  border-radius: $radius-md;
  border: 1px solid transparent;
  cursor: pointer;
  transition: all .2s $ease-out;
  white-space: nowrap;
  -webkit-tap-highlight-color: transparent;

  &:active { transform: translateY(1px); }
  &:disabled { opacity: .4; cursor: not-allowed; transform: none; }

  &--primary {
    background: $clr-accent;
    color: #fff;
    border-color: $clr-accent;
    box-shadow: 0 2px 8px rgba(0, 161, 214, 0.25);
    &:hover:not(:disabled) {
      background: $clr-accent-hover;
      border-color: $clr-accent-hover;
      box-shadow: 0 4px 14px rgba(0, 161, 214, 0.35);
    }
  }

  &--ghost {
    background: transparent;
    color: $clr-text-secondary;
    border-color: $clr-border;
    &:hover { background: rgba(255, 255, 255, 0.04); color: $clr-text-primary; border-color: $clr-border-focus; }
  }

  &--sm { padding: 6px 14px; font-size: 12px; border-radius: $radius-sm; }

  &--qr {
    margin-top: 4px;
    min-width: 200px;
  }

  &__spinner {
    animation: spin .8s linear infinite;
  }
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

// ─── Profile ───────────────────────────────────────────────
.profile {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;

  @media (max-width: $break-sm) {
    flex-direction: column;
    align-items: flex-start;
    text-align: center;
  }

  &__avatar-wrap {
    position: relative;
    flex-shrink: 0;
  }
  &__avatar {
    width: 64px;
    height: 64px;
    border-radius: 50%;
    object-fit: cover;
    border: 2px solid $clr-border-focus;
  }
  &__badge {
    position: absolute;
    bottom: -4px;
    right: -8px;
    font-size: 9px;
    font-weight: 700;
    background: $clr-yellow;
    color: #000;
    padding: 2px 7px;
    border-radius: 100px;
    white-space: nowrap;
  }

  &__info {
    flex: 1;
    min-width: 0;
  }
  &__name {
    font-size: 17px;
    font-weight: 600;
    color: $clr-text-primary;
    margin-bottom: 6px;
    letter-spacing: -0.01em;
  }
  &__tags {
    display: flex;
    gap: 6px;
    margin-bottom: 8px;
    flex-wrap: wrap;
  }
  &__hint {
    font-size: 12px;
    color: $clr-text-muted;
  }
}

// Tags
.tag {
  font-size: 10px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 100px;
  letter-spacing: 0.01em;

  &--lv { background: rgba(0, 161, 214, 0.15); color: $clr-accent; }
  &--vip { background: rgba(245, 158, 11, 0.15); color: $clr-yellow; }
  &--bigvip { background: rgba(236, 72, 153, 0.15); color: #f472b6; }
}

// ─── Alert Banner ──────────────────────────────────────────
.alert-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  background: rgba(245, 158, 11, 0.07);
  border: 1px solid rgba(245, 158, 11, 0.2);
  border-radius: $radius-sm;
  font-size: 12px;
  color: $clr-yellow;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.link-text {
  font-size: 12px;
  font-weight: 500;
  color: $clr-accent;
  background: none;
  border: none;
  cursor: pointer;
  text-decoration: underline;
  font-family: inherit;
  &:hover { color: $clr-accent-hover; }
}

// ─── Form Elements ─────────────────────────────────────────
.embed-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.field-label {
  display: block;
  font-size: 11px;
  font-weight: 600;
  color: $clr-text-muted;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 6px;
}

.input-group {
  display: flex;
  gap: 10px;

  @media (max-width: $break-sm) {
    flex-direction: column;
    gap: 8px;
    .btn { width: 100%; }
  }
}

.field-input {
  flex: 1;
  padding: 10px 14px;
  font-size: 13px;
  font-family: inherit;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid $clr-border;
  border-radius: $radius-sm;
  color: $clr-text-primary;
  outline: none;
  transition: border-color .15s $ease-out, box-shadow .15s $ease-out;

  &::placeholder { color: $clr-text-muted; }
  &:focus {
    border-color: $clr-accent;
    box-shadow: 0 0 0 3px rgba(0, 161, 214, 0.12);
  }
}

.field-select {
  padding: 8px 12px;
  font-size: 13px;
  font-family: inherit;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid $clr-border;
  border-radius: $radius-sm;
  color: $clr-text-primary;
  outline: none;
  cursor: pointer;
  min-width: 180px;

  &:focus { border-color: $clr-accent; }
}

// ─── Video Preview ─────────────────────────────────────────
.video-preview {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid $clr-border;
  border-radius: $radius-sm;
  overflow: hidden;
  margin-top: 12px;

  &__body { display: flex; gap: 14px; padding: 14px; }
  &__thumb { width: 132px; aspect-ratio: 16/9; object-fit: cover; border-radius: 4px; flex-shrink: 0; background: rgba(255,255,255,.04); }
  &__meta { min-width: 0; }
  &__title { font-size: 14px; font-weight: 500; color: $clr-text-primary; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; margin-bottom: 6px; }
  &__author { font-size: 12px; color: $clr-text-muted; }
  &__pages { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-top: 1px solid $clr-border; }
}

// ─── Embed Result ──────────────────────────────────────────
.embed-result {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 16px;

  &__bar {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
  }
}

// ─── Code Block ────────────────────────────────────────────
.code-block {
  position: relative;
  background: $clr-code-bg;
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: $radius-sm;
  padding: 16px 40px 16px 16px;
  overflow-x: auto;

  &__text {
    font-family: $font-mono;
    font-size: 12px;
    color: $clr-text-secondary;
    white-space: pre-wrap;
    word-break: break-all;
    line-height: 1.7;
  }

  &__copy {
    position: absolute;
    top: 10px;
    right: 10px;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    background: rgba(255, 255, 255, 0.05);
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 5px;
    color: $clr-text-muted;
    cursor: pointer;
    transition: all .15s $ease-out;

    &:hover { background: rgba(255, 255, 255, 0.12); color: #fff; }
    &.done { color: $clr-green; border-color: $clr-green; }
  }
}

// ─── Embed Preview ─────────────────────────────────────────
.embed-preview {
  margin-top: 12px;

  &__frame {
    border: 1px solid $clr-border;
    border-radius: $radius-sm;
    overflow: hidden;

    iframe {
      width: 100%;
      aspect-ratio: 16/9;
      border: none;
      display: block;
    }
  }
}

// ─── Log Drawer ────────────────────────────────────────────
.log-drawer {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  height: 300px;
  background: $clr-bg-elevated;
  border-top: 1px solid $clr-border;
  display: flex;
  flex-direction: column;
  z-index: 1000;
  font-family: $font-mono;
  font-size: 12px;
  box-shadow: 0 -8px 32px rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(12px);

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px 16px;
    background: rgba(0, 0, 0, 0.3);
    border-bottom: 1px solid $clr-border;
    flex-shrink: 0;
    user-select: none;
  }
  &__header-left {
    display: flex;
    align-items: center;
    gap: 8px;
    color: $clr-text-secondary;
    font-size: 12px;
    font-weight: 500;
  }
  &__count {
    font-size: 10px;
    background: rgba(255, 255, 255, 0.06);
    color: $clr-text-muted;
    padding: 1px 7px;
    border-radius: 10px;
  }
  &__header-right {
    display: flex;
    align-items: center;
    gap: 5px;

    button {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 26px;
      height: 26px;
      font-size: 12px;
      background: none;
      color: $clr-text-muted;
      border: 1px solid transparent;
      border-radius: 4px;
      cursor: pointer;
      transition: all .15s $ease-out;
      font-family: inherit;

      &:hover { background: rgba(255, 255, 255, 0.06); color: $clr-text-secondary; }
      &.on { color: $clr-accent; }
    }
  }
  &__select {
    font-size: 11px;
    padding: 3px 8px;
    background: rgba(255, 255, 255, 0.05);
    color: $clr-text-secondary;
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 4px;
    outline: none;
    cursor: pointer;
    font-family: inherit;
  }
  &__body {
    flex: 1;
    overflow-y: auto;
    padding: 6px 0;
  }
  &__empty {
    padding: 32px 16px;
    text-align: center;
    color: $clr-text-muted;
    font-style: italic;
    font-family: $font-sans;
    font-size: 13px;
  }
}

// ─── Log Entry ─────────────────────────────────────────────
.log-entry {
  display: flex;
  gap: 8px;
  padding: 3px 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.02);
  align-items: baseline;

  &[data-level="ERROR"] { background: rgba(239, 68, 68, 0.06); }
  &[data-level="WARN"] { background: rgba(245, 158, 11, 0.04); }

  &__time { color: #52525b; flex-shrink: 0; font-size: 10px; }
  &__level { flex-shrink: 0; font-weight: 600; font-size: 10px; width: 40px; }
  &__msg { color: #d4d4d8; word-break: break-all; }

  &[data-level="ERROR"] &__level { color: #fca5a5; }
  &[data-level="WARN"] &__level { color: #fcd34d; }
  &[data-level="DEBUG"] &__level { color: #94a3b8; }
  &:not([data-level="ERROR"]):not([data-level="WARN"]):not([data-level="DEBUG"]) &__level { color: #7dd3fc; }
}

// ─── Transitions ───────────────────────────────────────────
.dash-fade-enter-active { transition: all .3s $ease-out; }
.dash-fade-leave-active { transition: all .18s $ease-out; }
.dash-fade-enter-from { opacity: 0; transform: translateY(10px) scale(0.98); }
.dash-fade-leave-to { opacity: 0; transform: translateY(-6px) scale(0.99); }

.dash-expand-enter-active { transition: all .3s $ease-out; }
.dash-expand-leave-active { transition: all .15s $ease-smooth; }
.dash-expand-enter-from { opacity: 0; transform: translateY(-6px); }
.dash-expand-leave-to { opacity: 0; transform: translateY(-6px); }

.drawer-slide-enter-active { transition: transform .25s $ease-out; }
.drawer-slide-leave-active { transition: transform .2s $ease-smooth; }
.drawer-slide-enter-from { transform: translateY(100%); }
.drawer-slide-leave-to { transform: translateY(100%); }
</style>
