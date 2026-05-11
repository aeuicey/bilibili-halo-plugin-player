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
const EMBED_PATH = '/plugins/bilibili-player/embed'
const BVID_REGEX = /BV[a-zA-Z0-9]{10}/
const AVID_REGEX = /av(\d+)/i

/* ---------- Tabs ---------- */
type TabId = 'login' | 'embed'
const activeTabId = ref<TabId>('login')

/* ---------- Login ---------- */
const loginUser = ref<null | {
  uname: string; face: string; level: number
  vipStatus: number; vipType: number
}>(null)
const loginChecked = ref(false)
const qrImage = ref('')
const qrKey = ref('')
const qrStatus = ref<'idle' | 'loading' | 'pending' | 'scanned' | 'expired' | 'success' | 'error'>('idle')
const qrError = ref('')
let pollTimer: ReturnType<typeof setInterval> | null = null
const pollErrors = ref(0)

const qrStatusLabel = computed(() => ({
  loading: '正在生成二维码',
  pending: '请使用哔哩哔哩 App 扫码',
  scanned: '已扫描，请在手机上确认',
  success: '登录成功',
  expired: '二维码已过期，请重新生成',
  error: '发生错误',
  idle: '',
}[qrStatus.value] || qrStatus.value))

const avatarFailed = ref(false)
const AVATAR_PLACEHOLDER = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 96 96'><rect width='96' height='96' fill='%23ffeaf2'/><circle cx='48' cy='38' r='18' fill='%23fb7299'/><path d='M16 88c4-18 18-26 32-26s28 8 32 26z' fill='%23fb7299'/></svg>"
const vipLabel = computed(() => loginUser.value?.vipStatus ? (loginUser.value.vipType === 2 ? '年度大会员' : '大会员') : '')

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
onMounted(async () => { await checkLogin() })
onUnmounted(() => { stopPoll(); stopLogPoll() })

/* ---------- Login logic ---------- */
async function checkLogin() {
  try {
    const { data } = await axios.get(`${API}/login/status`)
    const p = typeof data === 'string' ? JSON.parse(data) : data
    if (p.isLogin) { avatarFailed.value = false; loginUser.value = p; activeTabId.value = 'embed' }
  } catch { /* ignore */ }
  loginChecked.value = true
}

async function genQr() {
  qrStatus.value = 'loading'; qrImage.value = ''; qrError.value = ''; stopPoll()
  try {
    const { data } = await axios.get(`${API}/login/qrcode/generate`)
    const p = typeof data === 'string' ? JSON.parse(data) : data
    if (!p.qrcodeKey) { qrStatus.value = 'error'; qrError.value = '接口返回数据异常'; return }
    qrKey.value = p.qrcodeKey
    qrImage.value = await QRCode.toDataURL(p.url, { width: 240, margin: 1, color: { dark: '#18191c', light: '#ffffff' } })
    qrStatus.value = 'pending'
    startPoll()
  } catch (e: unknown) { qrStatus.value = 'error'; qrError.value = (e as { message?: string })?.message || '未知错误' }
}

function startPoll() {
  stopPoll(); qrError.value = ''; pollErrors.value = 0
  if (!qrKey.value) return
  pollTimer = setInterval(async () => {
    if (qrStatus.value === 'success' || qrStatus.value === 'expired') return
    try {
      const { data } = await axios.get(`${API}/login/qrcode/poll`, { params: { qrcode_key: qrKey.value } })
      const p = typeof data === 'string' ? JSON.parse(data) : data
      if (!p?.status) return
      if (p.status === 'success') {
        qrStatus.value = 'success'; stopPoll()
        await new Promise(r => setTimeout(r, 800))
        await checkLogin()
        if (!loginUser.value) { qrStatus.value = 'error'; qrError.value = 'SESSDATA 校验失败' }
      } else if (p.status === 'scanned') { qrStatus.value = 'scanned' }
      else if (p.status === 'expired' || p.status === 'error') { qrStatus.value = 'expired'; stopPoll() }
    } catch {
      pollErrors.value++
      if (pollErrors.value >= 3) { qrStatus.value = 'expired'; stopPoll() }
    }
  }, 2000)
}
function stopPoll() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null } }

async function doLogout() {
  try { await axios.post(`${API}/login/logout`) } catch { /* ignore */ }
  loginUser.value = null; qrImage.value = ''; qrStatus.value = 'idle'; qrError.value = ''; activeTabId.value = 'login'
}

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
  // B站图片 CDN 常为 HTTP，升级为 HTTPS 避免混合内容 + 重定向
  let secure = url.startsWith('http://') ? 'https://' + url.substring(7) : url
  // 添加 @ 后缀获取适合卡片展示的缩略图尺寸，减少流量
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

const faceSrc = computed(() => loginUser.value?.face ? (avatarFailed.value ? AVATAR_PLACEHOLDER : proxyImage(loginUser.value.face)) : AVATAR_PLACEHOLDER)
const coverSrc = computed(() => videoInfo.value?.pic ? (coverFailed.value ? COVER_PLACEHOLDER : proxyImage(videoInfo.value.pic)) : COVER_PLACEHOLDER)

function onAvatarError(e: Event) { avatarFailed.value = true; (e.target as HTMLImageElement).src = AVATAR_PLACEHOLDER }
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
    <VCard class="bp-header">
      <template #header>
        <div class="bp-header__inner">
          <div class="bp-header__brand">
            <svg class="bp-logo" viewBox="0 0 24 24" width="24" height="24" fill="currentColor">
              <path d="M18.223 3.086a1.25 1.25 0 0 1 0 1.768L17.08 5.996h1.17A3.75 3.75 0 0 1 22 9.747v7.5a3.75 3.75 0 0 1-3.75 3.75H5.75A3.75 3.75 0 0 1 2 17.247v-7.5a3.75 3.75 0 0 1 3.75-3.75h1.166L5.775 4.855a1.25 1.25 0 1 1 1.767-1.77l2.652 2.654.1.258h3.411l.1-.258 2.654-2.653a1.25 1.25 0 0 1 1.768 0zM18.25 8.496H5.75a1.25 1.25 0 0 0-1.243 1.122l-.007.128v7.5c0 .643.487 1.172 1.112 1.243l.138.007h12.5a1.25 1.25 0 0 0 1.243-1.122l.007-.128v-7.5a1.25 1.25 0 0 0-1.25-1.25zM8.5 11a1.25 1.25 0 0 1 1.25 1.25v1.5a1.25 1.25 0 1 1-2.5 0v-1.5A1.25 1.25 0 0 1 8.5 11zm7 0a1.25 1.25 0 0 1 1.25 1.25v1.5a1.25 1.25 0 1 1-2.5 0v-1.5A1.25 1.25 0 0 1 15.5 11z" />
            </svg>
            <div>
              <div class="bp-header__title">BILIBILI 播放器</div>
              <div class="bp-header__desc">在博客中嵌入 B 站视频，支持扫码登录、高清画质、DASH 音画分离</div>
            </div>
          </div>
          <div class="bp-header__meta">
            <VTag>v1.4.0</VTag>
            <VTag v-if="loginUser" theme="primary">已登录</VTag>
            <VTag v-else>未登录</VTag>
            <VButton v-if="loginUser" size="sm" @click="doLogout" style="margin-left:4px">退出登录</VButton>
          </div>
        </div>
      </template>
      <div class="bp-tabs" role="tablist">
        <button class="bp-tab" :class="{ active: activeTabId === 'login' }" @click="activeTabId = 'login'">账号登录</button>
        <button class="bp-tab" :class="{ active: activeTabId === 'embed' }" @click="activeTabId = 'embed'">视频嵌入</button>
      </div>
    </VCard>

    <!-- Login Tab -->
    <section v-show="activeTabId === 'login'">
      <VCard v-if="!loginUser" class="bp-card">
        <template #header>
          <div class="bp-card__header">
            <span class="bp-card__title">扫码登录 B 站账号</span>
            <span class="bp-card__desc">登录后可获取 720P 及以上清晰度，支持 1080P / 4K</span>
          </div>
        </template>
        <div class="bp-login">
          <div class="bp-qr">
            <div class="bp-qr__frame" :data-status="qrStatus">
              <div v-if="qrStatus === 'idle'" class="bp-qr__placeholder">
                <div class="bp-qr__placeholder-icon">
                  <svg viewBox="0 0 24 24" width="32" height="32" fill="currentColor">
                    <path d="M3 3h8v8H3V3zm2 2v4h4V5H5zm-2 8h8v8H3v-8zm2 2v4h4v-4H5zm10-10h8v8h-8V3zm2 2v4h4V5h-4zm-2 8h8v8h-8v-8zm2 2v4h4v-4h-4z"/>
                  </svg>
                </div>
                <div class="bp-qr__placeholder-text">点击下方按钮生成二维码</div>
              </div>
              <div v-else-if="qrStatus === 'loading'" class="bp-qr__placeholder">
                <div class="bp-spinner"></div>
                <div class="bp-qr__placeholder-text">二维码生成中</div>
              </div>
              <template v-else>
                <img v-if="qrImage" :src="qrImage" alt="登录二维码" />
                <div v-if="qrStatus === 'scanned'" class="bp-qr__overlay bp-qr__overlay--warn">
                  <div class="bp-qr__overlay-icon">&#10003;</div>
                  <div>扫描成功</div>
                  <div class="bp-qr__overlay-hint">请在手机上确认登录</div>
                </div>
                <div v-if="qrStatus === 'expired'" class="bp-qr__overlay bp-qr__overlay--danger">
                  <div class="bp-qr__overlay-icon">&#8635;</div>
                  <div>二维码已过期</div>
                  <VButton size="sm" type="primary" @click="genQr">重新生成</VButton>
                </div>
                <div v-if="qrStatus === 'success'" class="bp-qr__overlay bp-qr__overlay--success">
                  <div class="bp-qr__overlay-icon">&#10003;</div>
                  <div>登录成功</div>
                </div>
              </template>
            </div>
            <div class="bp-qr__status" :data-status="qrStatus">
              <span class="bp-qr__dot"></span>
              <span>{{ qrStatusLabel || '等待操作' }}</span>
            </div>
          </div>
          <div class="bp-qr__side">
            <div class="bp-qr__steps-title">登录步骤</div>
            <ol class="bp-steps">
              <li>打开哔哩哔哩手机 App</li>
              <li>点击右上角扫一扫图标</li>
              <li>对准二维码进行扫描</li>
              <li>在手机上点击"确认登录"</li>
            </ol>
            <VSpace>
              <VButton type="primary" :loading="qrStatus === 'loading'" @click="genQr">{{
                qrStatus === 'idle' || qrStatus === 'error' ? '生成二维码' : qrStatus === 'expired' ? '重新生成' : '刷新二维码'
              }}</VButton>
            </VSpace>
            <VAlert v-if="qrError" type="error" :title="qrError" :closable="false" style="margin-top:12px" />
          </div>
        </div>
      </VCard>
      <VCard v-else class="bp-card">
        <template #header>
          <div class="bp-card__header">
            <span class="bp-card__title">已登录</span>
          </div>
        </template>
        <div class="bp-user">
          <VAvatar :src="faceSrc" :alt="loginUser.uname" size="lg" circle @error="onAvatarError" />
          <div>
            <div class="bp-user__name">
              <span>{{ loginUser.uname }}</span>
              <VTag v-if="vipLabel">{{ vipLabel }}</VTag>
              <VTag>Lv {{ loginUser.level }}</VTag>
            </div>
            <VSpace style="margin-top:10px">
              <VButton size="sm" type="primary" @click="activeTabId = 'embed'">去生成嵌入代码</VButton>
              <VButton size="sm" @click="doLogout">退出登录</VButton>
            </VSpace>
          </div>
        </div>
      </VCard>
    </section>

    <!-- Embed Tab -->
    <section v-show="activeTabId === 'embed'">
      <VCard class="bp-card">
        <template #header>
          <div class="bp-card__header">
            <span class="bp-card__title">生成视频嵌入代码</span>
            <span class="bp-card__desc">输入 BV 号或粘贴 B 站视频链接，生成可粘贴到文章中的嵌入代码</span>
          </div>
        </template>
        <div class="bp-embed">
          <div class="bp-search">
            <input v-model="embedBvid" class="bp-input" placeholder="BV1xx411c7mD 或 https://www.bilibili.com/video/BV" @keyup.enter="fetchVideo" />
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
    </section>

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
:root {
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
  gap: 16px;
  padding: 24px;
  max-width: 920px;
  margin: 0 auto;
}

.bp-header {
  position: relative;
  overflow: hidden;
}
.bp-header::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: linear-gradient(90deg, var(--bp-pink), #ff85a1, var(--bp-pink));
}
.bp-header__inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  width: 100%;
}
.bp-header__brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.bp-logo {
  flex-shrink: 0;
  color: var(--bp-pink);
  width: 28px;
  height: 28px;
}
.bp-header__title {
  font-size: 20px;
  font-weight: 800;
  color: var(--bp-text);
  letter-spacing: 0.04em;
}
.bp-header__desc {
  font-size: 12px;
  color: var(--bp-text-muted);
}
.bp-header__meta {
  display: flex;
  gap: 6px;
}

.bp-tabs {
  display: flex;
  gap: 4px;
  margin-top: 4px;
}
.bp-tab {
  padding: 8px 0;
  border: none;
  background: transparent;
  font-size: 14px;
  font-weight: 500;
  color: var(--bp-text-secondary);
  cursor: pointer;
  position: relative;
  transition: color 0.15s;
}
.bp-tab + .bp-tab {
  margin-left: 24px;
}
.bp-tab:hover { color: var(--bp-pink); }
.bp-tab.active {
  color: var(--bp-pink);
}
.bp-tab.active::after {
  content: '';
  position: absolute;
  bottom: -1px;
  left: 0;
  right: 0;
  height: 2px;
  background: var(--bp-pink);
  border-radius: 2px;
}

.bp-card__header {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}
.bp-card__title { font-size: 16px; font-weight: 600; color: var(--bp-text); }
.bp-card__desc { font-size: 12px; color: var(--bp-text-muted); }

/* Login */
.bp-login {
  display: flex;
  gap: 40px;
  align-items: stretch;
}
@media (max-width: 768px) { .bp-login { flex-direction: column; gap: 24px; } }

.bp-qr {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}
.bp-qr__frame {
  width: 240px;
  height: 240px;
  border: 1px solid var(--bp-border);
  border-radius: 10px;
  display: grid;
  place-items: center;
  position: relative;
  background: #fff;
  overflow: hidden;
  transition: border-color 0.2s;
}
.bp-qr__frame[data-status="scanned"] { border-color: #ff7d00; }
.bp-qr__frame[data-status="success"] { border-color: #00b42a; }
.bp-qr__frame[data-status="expired"] { border-color: #f53f3f; }
.bp-qr__frame img { width: 220px; height: 220px; display: block; }
.bp-qr__placeholder { display:flex;flex-direction:column;align-items:center;gap:10px;color:var(--bp-text-muted) }
.bp-qr__placeholder-icon { opacity: 0.4; }
.bp-qr__placeholder-text { font-size:13px; }
.bp-qr__overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  backdrop-filter: blur(4px);
  background: rgba(255,255,255,0.85);
  font-size: 14px;
  font-weight: 500;
}
.bp-qr__overlay-icon { font-size: 28px; font-weight: 700; }
.bp-qr__overlay--warn { color: #ff7d00; }
.bp-qr__overlay--danger { color: #f53f3f; }
.bp-qr__overlay--success { color: #00b42a; }
.bp-qr__overlay-hint { font-size: 12px; }
.bp-qr__status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  background: var(--bp-bg);
  border-radius: var(--bp-radius-pill);
  font-size: 12px;
  color: var(--bp-text-secondary);
}
.bp-qr__status[data-status="success"] { color:#00b42a; background: rgba(0,180,42,0.1); }
.bp-qr__status[data-status="expired"],
.bp-qr__status[data-status="error"] { color:#f53f3f; background: rgba(245,63,63,0.1); }
.bp-qr__status[data-status="scanned"] { color:#ff7d00; background: rgba(255,125,0,0.1); }
.bp-qr__dot {
  width: 7px; height: 7px;
  border-radius: 50%;
  background: currentColor;
  animation: bp-pulse 1.6s ease infinite;
}
.bp-qr__side {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.bp-qr__steps-title { font-weight: 600; margin-bottom: 12px; color: var(--bp-text); }
.bp-steps {
  margin: 0 0 16px;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 13px;
  color: var(--bp-text-secondary);
}

/* User card */
.bp-user {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 8px 0;
}
.bp-user__name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: var(--bp-text);
}

/* Embed */
.bp-embed {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.bp-search {
  display: flex;
  gap: 8px;
}
@media (max-width: 640px) { .bp-search { flex-direction: column; } }

.bp-input {
  flex: 1;
  height: 36px;
  padding: 0 12px;
  font-size: 13px;
  border: 1px solid #d0d5dd;
  border-radius: var(--bp-radius);
  outline: none;
  font-family: inherit;
  background: #fff;
  color: var(--bp-text);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.bp-input:focus { border-color: var(--bp-pink); box-shadow: 0 0 0 3px rgba(251,114,153,0.15); }

.bp-video {
  display: flex;
  gap: 16px;
  padding: 16px;
  background: var(--bp-bg);
  border-radius: 10px;
  border: 1px solid var(--bp-border);
}
@media (max-width: 640px) { .bp-video { flex-direction: column; } }
.bp-video__cover {
  width: 180px;
  aspect-ratio: 16/10;
  object-fit: cover;
  border-radius: var(--bp-radius);
  flex-shrink: 0;
  background: var(--bp-border);
}
@media (max-width: 640px) { .bp-video__cover { width: 100%; } }
.bp-video__meta { display:flex;flex-direction:column;gap:6px;min-width:0;flex:1 }
.bp-video__title { font-size:14px;font-weight:600;line-height:1.5;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;color:var(--bp-text) }
.bp-video__sub { font-size:12px;color:var(--bp-text-muted) }
.bp-video__stats { display:flex;gap:12px;font-size:12px;color:var(--bp-text-muted) }
.bp-video__pages { margin-top: auto; }

/* Code section */
.bp-code-section { display:flex;flex-direction:column;gap:8px }
.bp-code-toolbar { display:flex;justify-content:space-between;align-items:center }
.bp-code-label { font-size:12px;font-weight:600;color:var(--bp-text-secondary) }
.bp-code-block {
  margin: 0;
  padding: 12px 14px;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px;
  line-height: 1.6;
  background: var(--bp-bg);
  border: 1px solid var(--bp-border);
  border-radius: var(--bp-radius);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 180px;
  overflow: auto;
  color: var(--bp-text);
}

/* Size settings collapsible */
.bp-size-settings {
  border: 1px solid var(--bp-border);
  border-radius: var(--bp-radius);
  overflow: hidden;
}
.bp-size-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 8px 12px;
  border: none;
  background: var(--bp-bg);
  font-size: 12px;
  font-weight: 500;
  color: var(--bp-text-secondary);
  cursor: pointer;
  font-family: inherit;
  transition: background 0.15s;
}
.bp-size-toggle:hover { background: #eef0f2; }
.bp-chevron { transition: transform 0.2s; }
.bp-chevron.rotated { transform: rotate(180deg); }
.bp-size-hint { margin-left:auto;font-weight:400;color:var(--bp-text-muted);font-size:11px }
.bp-size-options {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 8px 12px 12px;
  border-top: 1px solid var(--bp-border);
}
.bp-size-label { font-size:12px;color:var(--bp-text-secondary) }

.bp-chip {
  padding: 4px 12px;
  font-size: 12px;
  border: 1px solid var(--bp-border-light);
  border-radius: var(--bp-radius-pill);
  background: #fff;
  color: var(--bp-text-secondary);
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s ease;
}
.bp-chip:hover { border-color: var(--bp-pink); color: var(--bp-pink); }
.bp-chip.active { background: var(--bp-pink); border-color: var(--bp-pink); color: #fff; }
.bp-chip.sm { padding: 2px 8px; font-size: 11px; border-radius: 4px; }

.bp-preview {
  width: 100%;
  aspect-ratio: 16/9;
  border-radius: var(--bp-radius);
  overflow: hidden;
  border: 1px solid var(--bp-border);
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
  padding: 9px 14px;
  font-size: 13px;
  font-weight: 500;
  color: #fff;
  background: var(--bp-pink);
  border: none;
  border-radius: var(--bp-radius-pill);
  cursor: pointer;
  box-shadow: 0 6px 20px rgba(251,114,153,0.4);
  transition: transform 0.15s, box-shadow 0.15s;
  font-family: inherit;
}
.bp-log-fab:hover { transform: translateY(-1px); box-shadow: 0 8px 24px rgba(251,114,153,0.5); }
.bp-log-fab.open { background: var(--bp-text); box-shadow: 0 6px 20px rgba(0,0,0,0.25); }

.bp-log-drawer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 98;
  height: 300px;
  background: #fff;
  border-top: 1px solid var(--bp-border);
  display: flex;
  flex-direction: column;
  box-shadow: 0 -12px 40px rgba(0,0,0,0.1);
}
.bp-log-drawer__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 16px;
  background: var(--bp-bg);
  border-bottom: 1px solid var(--bp-border);
  flex-wrap: wrap;
}
.bp-log-drawer__title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 13px;
  color: var(--bp-text);
}
.bp-log-count {
  padding: 1px 8px;
  background: var(--bp-pink-light);
  color: var(--bp-pink);
  border-radius: var(--bp-radius-pill);
  font-size: 11px;
}
.bp-log-drawer__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bp-log-filter-group { display: flex; gap: 4px; }
.bp-autoscroll-label { font-size:11px; color:var(--bp-text-muted) }
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
  padding: 3px 16px;
  align-items: baseline;
  border-bottom: 1px solid rgba(0,0,0,0.02);
}
.bp-log:hover { background: var(--bp-bg); }
.bp-log__time { font-size: 11px; color: var(--bp-text-muted); }
.bp-log__level { font-size: 10px; font-weight: 700; text-align: center; padding: 1px 0; border-radius: 4px; }
.bp-log__msg { color: var(--bp-text-secondary); word-break: break-all; line-height: 1.55; }
.bp-log[data-level="ERROR"] { background: rgba(245,63,63,0.06); }
.bp-log[data-level="ERROR"] .bp-log__level { color:#fff; background:#f53f3f; }
.bp-log[data-level="WARN"] .bp-log__level { color:#fff; background:#ff7d00; }
.bp-log[data-level="INFO"] .bp-log__level { color:#fff; background:#00a1d6; }
.bp-log[data-level="DEBUG"] .bp-log__level { color:var(--bp-text-secondary); background:var(--bp-border); }

.bp-spinner {
  width: 28px; height: 28px;
  border: 3px solid var(--bp-border);
  border-top-color: var(--bp-pink);
  border-radius: 50%;
  animation: bp-spin 0.8s linear infinite;
}
@keyframes bp-spin { to { transform: rotate(360deg); } }
@keyframes bp-pulse {
  0% { box-shadow: 0 0 0 0 currentColor; opacity: 1; }
  70% { box-shadow: 0 0 0 8px transparent; opacity: 0.6; }
  100% { box-shadow: 0 0 0 0 transparent; opacity: 1; }
}

.bp-drawer-enter-active,
.bp-drawer-leave-active { transition: transform 0.25s ease; }
.bp-drawer-enter-from,
.bp-drawer-leave-to { transform: translateY(100%); }

.bp-collapse-enter-active,
.bp-collapse-leave-active { transition: all 0.2s ease; overflow: hidden; }
.bp-collapse-enter-from,
.bp-collapse-leave-to { opacity: 0; max-height: 0; }
.bp-collapse-enter-to,
.bp-collapse-leave-from { opacity: 1; max-height: 100px; }
</style>
