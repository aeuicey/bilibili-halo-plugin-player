<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick, computed, watch } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { stores } from '@halo-dev/ui-shared'
import QRCode from 'qrcode'
import {
  VAlert,
  VAvatar,
  VButton,
  VCard,
  VDescription,
  VDescriptionItem,
  VEmpty,
  VLoading,
  VPageHeader,
  VSpace,
  VStatusDot,
  VSwitch,
  VTabbar,
  VTag,
  Dialog,
  Toast,
  IconClipboardLine,
  IconLogoutCircleRLine,
  IconRefreshLine,
} from '@halo-dev/components'
import RiQrCodeLine from '~icons/ri/qr-code-line'
import {
  API_BASE as API,
  formatNum,
  parseBvid,
  parseData,
  proxyImage,
} from '@/utils/bilibili'

const EMBED_PATH = '/plugins/bilibili-player/embed'

/* ---------- Tabs ---------- */
type TabId = 'login' | 'embed' | 'logs'
const activeTabId = ref<TabId>('login')
const tabItems = [
  { id: 'login', label: '账号登录' },
  { id: 'embed', label: '视频嵌入' },
  { id: 'logs', label: '运行日志' },
]

/* ---------- Site info ---------- */
const globalInfoStore = stores.globalInfo()
const siteOrigin = computed(() =>
  (globalInfoStore.globalInfo?.externalUrl || window.location.origin).replace(/\/+$/, ''),
)

/* ---------- Login ---------- */
const loginUser = ref<null | {
  uname: string
  face: string
  level: number
  vipStatus: number
  vipType: number
}>(null)
const loginChecked = ref(false)
const qrImage = ref('')
const qrKey = ref('')
const qrStatus = ref<'idle' | 'loading' | 'pending' | 'scanned' | 'expired' | 'success' | 'error'>(
  'idle',
)
const qrError = ref('')
let pollTimer: ReturnType<typeof setInterval> | null = null
const pollErrors = ref(0)

const qrStatusLabel = computed(
  () =>
    ({
      loading: '正在生成二维码',
      pending: '请使用哔哩哔哩 App 扫码',
      scanned: '已扫描，请在手机上确认',
      success: '登录成功',
      expired: '二维码已过期，请重新生成',
      error: '发生错误',
      idle: '',
    })[qrStatus.value] || qrStatus.value,
)

const qrStatusDotState = computed<'default' | 'success' | 'warning' | 'error'>(
  () =>
    ({
      loading: 'default',
      pending: 'default',
      scanned: 'warning',
      success: 'success',
      expired: 'error',
      error: 'error',
      idle: 'default',
    })[qrStatus.value] as 'default' | 'success' | 'warning' | 'error',
)

const avatarFailed = ref(false)
const AVATAR_PLACEHOLDER =
  "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 96 96'><rect width='96' height='96' fill='%23ffeaf2'/><circle cx='48' cy='38' r='18' fill='%23fb7299'/><path d='M16 88c4-18 18-26 32-26s28 8 32 26z' fill='%23fb7299'/></svg>"
const vipLabel = computed(() =>
  loginUser.value?.vipStatus ? (loginUser.value.vipType === 2 ? '年度大会员' : '大会员') : '',
)

/* ---------- Embed ---------- */
const embedBvid = ref('')
const embedCid = ref('')
const embedLoading = ref(false)
const embedCode = ref('')
const embedPreview = ref('')

/* ---------- 尺寸与样式（重设计：模式 × 数值解耦） ---------- */
type WidthMode = 'fluid' | 'fixed'
type RadiusPreset = '0' | '8' | '16'
type AlignMode = 'center' | 'left'

const WIDTH_MIN = 200
const WIDTH_MAX = 2000
const WIDTH_DEFAULT = 640
const WIDTH_SNAPS = [480, 640, 860]
const WIDTH_SNAP_TOL = 14
// 预览/读数使用的参考正文栏宽，与典型主题正文列一致
const REF_COLUMN_WIDTH = 760

const widthModes: Array<{ id: WidthMode; label: string }> = [
  { id: 'fluid', label: '自适应栏宽' },
  { id: 'fixed', label: '限制最大宽度' },
]
const radiusPresets: Array<{ id: RadiusPreset; label: string }> = [
  { id: '0', label: '直角' },
  { id: '8', label: '8px' },
  { id: '16', label: '16px' },
]
const alignModes: Array<{ id: AlignMode; label: string }> = [
  { id: 'center', label: '居中' },
  { id: 'left', label: '靠左' },
]

const widthMode = ref<WidthMode>('fluid')
const maxWidth = ref(WIDTH_DEFAULT)
const radiusPreset = ref<RadiusPreset>('8')
const alignMode = ref<AlignMode>('center')
const widthClamped = ref(false)
let clampHintTimer: ReturnType<typeof setTimeout> | null = null

const snapTicks = computed(() =>
  WIDTH_SNAPS.map((v) => ({
    value: v,
    left: `${(((v - WIDTH_MIN) / (WIDTH_MAX - WIDTH_MIN)) * 100).toFixed(2)}%`,
  })),
)
const widthFillPercent = computed(
  () => `${(((maxWidth.value - WIDTH_MIN) / (WIDTH_MAX - WIDTH_MIN)) * 100).toFixed(2)}%`,
)
const widthModeHint = computed(() =>
  widthMode.value === 'fixed'
    ? `播放器不超过设定宽度，在正文栏中${alignMode.value === 'center' ? '居中' : '靠左'}显示。`
    : '播放器撑满正文栏，高度按真实画幅比自动推导。',
)

const videoInfo = ref<null | {
  title: string
  pic: string
  ownerName: string
  pages: Array<{ cid: number; page: number; part: string }>
  width: number
  height: number
  picWidth?: number
  picHeight?: number
  stat?: { view: number; danmaku: number; like: number }
}>(null)
const coverFailed = ref(false)
const COVER_PLACEHOLDER =
  "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 400 250'><defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'><stop offset='0' stop-color='%23ffeaf2'/><stop offset='1' stop-color='%23e6f7fd'/></linearGradient></defs><rect width='400' height='250' fill='url(%23g)'/><circle cx='200' cy='125' r='36' fill='%23fb7299' opacity='0.85'/><polygon points='188,108 220,125 188,142' fill='white'/></svg>"

const videoAspectRatio = computed(() => {
  const w = videoInfo.value?.width ?? 0
  const h = videoInfo.value?.height ?? 0
  return w > 0 && h > 0 ? `${w}/${h}` : '16/9'
})
const videoOrientation = computed(() => {
  const w = videoInfo.value?.width ?? 0
  const h = videoInfo.value?.height ?? 0
  if (w <= 0 || h <= 0) return ''
  return h > w ? '竖屏' : '横屏'
})
const videoAspectLabel = computed(() => {
  const w = videoInfo.value?.width ?? 0
  const h = videoInfo.value?.height ?? 0
  if (w <= 0 || h <= 0) return ''
  const gcd = (a: number, b: number): number => (b ? gcd(b, a % b) : a)
  const g = gcd(w, h)
  const rw = w / g
  const rh = h / g
  return rw <= 40 && rh <= 40 ? `${rw}:${rh}` : `${(w / h).toFixed(2)}:1`
})
const aspectBoxStyle = computed(() => {
  const w = videoInfo.value?.width ?? 0
  const h = videoInfo.value?.height ?? 0
  const ratio = w > 0 && h > 0 ? w / h : 16 / 9
  const maxW = 180
  const maxH = 110
  let bw = maxW
  let bh = maxW / ratio
  if (bh > maxH) {
    bh = maxH
    bw = maxH * ratio
  }
  return { width: `${bw.toFixed(1)}px`, height: `${bh.toFixed(1)}px` }
})

// 宽高联动读数：按真实画幅比推出嵌入后的实际高度
const resultSizeLabel = computed(() => {
  const w = videoInfo.value?.width ?? 0
  const h = videoInfo.value?.height ?? 0
  if (w <= 0 || h <= 0) return ''
  if (widthMode.value === 'fixed') {
    const px = clampWidth(maxWidth.value)
    const rh = Math.round((px * h) / w)
    const extra =
      px > REF_COLUMN_WIDTH ? `，超过参考栏宽时实显 ${REF_COLUMN_WIDTH}px 宽` : ''
    return `${px} × ${rh} px${extra}`
  }
  const rh = Math.round((REF_COLUMN_WIDTH * h) / w)
  return `100% × 自动（按 ${REF_COLUMN_WIDTH}px 栏宽 ≈ ${REF_COLUMN_WIDTH} × ${rh} px）`
})

// 嵌入代码预览随尺寸/圆角/对齐联动
const previewStyle = computed(() => {
  const style: Record<string, string> = {
    aspectRatio: videoAspectRatio.value,
    borderRadius: `${radiusPreset.value}px`,
  }
  if (widthMode.value === 'fixed') {
    style.maxWidth = `${clampWidth(maxWidth.value)}px`
    if (alignMode.value === 'center') {
      style.marginLeft = 'auto'
      style.marginRight = 'auto'
    }
  }
  return style
})

/* ---------- Logs ---------- */
const logEntries = ref<Array<{ time: string; level: string; msg: string }>>([])
const logLevels = ['ALL', 'INFO', 'WARN', 'ERROR', 'DEBUG'] as const
const logFilter = ref<(typeof logLevels)[number]>('ALL')
const logAutoScroll = ref(true)
const logContainer = ref<HTMLElement | null>(null)
let logPollTimer: ReturnType<typeof setInterval> | null = null
let logEventSource: EventSource | null = null

const filteredLogs = computed(() =>
  logFilter.value === 'ALL'
    ? logEntries.value
    : logEntries.value.filter((e) => e.level === logFilter.value),
)

/* ---------- Lifecycle ---------- */
onMounted(async () => {
  restoreSizeStyle()
  await checkLogin()
})
onUnmounted(() => {
  stopPoll()
  stopLogs()
  if (clampHintTimer) {
    clearTimeout(clampHintTimer)
    clampHintTimer = null
  }
})

/* ---------- Login logic ---------- */
async function checkLogin() {
  try {
    const { data } = await axiosInstance.get(`${API}/login/status`)
    const p = typeof data === 'string' ? JSON.parse(data) : data
    if (p.isLogin) {
      avatarFailed.value = false
      loginUser.value = p
      activeTabId.value = 'embed'
    }
  } catch {
    /* ignore */
  }
  loginChecked.value = true
}

async function genQr() {
  qrStatus.value = 'loading'
  qrImage.value = ''
  qrError.value = ''
  stopPoll()
  try {
    const { data } = await axiosInstance.get(`${API}/login/qrcode/generate`)
    const p = typeof data === 'string' ? JSON.parse(data) : data
    if (!p.qrcodeKey) {
      qrStatus.value = 'error'
      qrError.value = '接口返回数据异常'
      return
    }
    qrKey.value = p.qrcodeKey
    qrImage.value = await QRCode.toDataURL(p.url, {
      width: 240,
      margin: 1,
      color: { dark: '#18191c', light: '#ffffff' },
    })
    qrStatus.value = 'pending'
    startPoll()
  } catch (e: unknown) {
    qrStatus.value = 'error'
    qrError.value = (e as { message?: string })?.message || '未知错误'
  }
}

function startPoll() {
  stopPoll()
  qrError.value = ''
  pollErrors.value = 0
  if (!qrKey.value) return
  pollTimer = setInterval(async () => {
    if (qrStatus.value === 'success' || qrStatus.value === 'expired') return
    try {
      const { data } = await axiosInstance.get(`${API}/login/qrcode/poll`, {
        params: { qrcode_key: qrKey.value },
      })
      const p = typeof data === 'string' ? JSON.parse(data) : data
      if (!p?.status) return
      if (p.status === 'success') {
        qrStatus.value = 'success'
        stopPoll()
        await new Promise((r) => setTimeout(r, 800))
        await checkLogin()
        if (!loginUser.value) {
          qrStatus.value = 'error'
          qrError.value = 'SESSDATA 校验失败'
        }
      } else if (p.status === 'scanned') {
        qrStatus.value = 'scanned'
      } else if (p.status === 'expired' || p.status === 'error') {
        qrStatus.value = 'expired'
        stopPoll()
      }
    } catch {
      pollErrors.value++
      if (pollErrors.value >= 3) {
        qrStatus.value = 'expired'
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

function confirmLogout() {
  Dialog.warning({
    title: '退出登录',
    description: '退出后将无法获取 720P 及以上清晰度，确定要退出当前 B 站账号吗？',
    confirmType: 'danger',
    confirmText: '退出登录',
    cancelText: '取消',
    onConfirm: doLogout,
  })
}

async function doLogout() {
  try {
    await axiosInstance.post(`${API}/login/logout`)
  } catch {
    /* ignore */
  }
  loginUser.value = null
  qrImage.value = ''
  qrStatus.value = 'idle'
  qrError.value = ''
  if (activeTabId.value === 'logs') stopLogs()
  activeTabId.value = 'login'
}

/* ---------- Embed logic ---------- */
function extractResolution(playData: unknown) {
  const data = playData as { dash?: { video?: Array<{ width?: number; height?: number }> } } | null
  const track = data?.dash?.video?.[0]
  return {
    width: Number(track?.width) > 0 ? Number(track?.width) : 0,
    height: Number(track?.height) > 0 ? Number(track?.height) : 0,
  }
}

const faceSrc = computed(() =>
  loginUser.value?.face
    ? avatarFailed.value
      ? AVATAR_PLACEHOLDER
      : proxyImage(loginUser.value.face)
    : AVATAR_PLACEHOLDER,
)
const coverSrc = computed(() =>
  videoInfo.value?.pic
    ? coverFailed.value
      ? COVER_PLACEHOLDER
      : proxyImage(videoInfo.value.pic)
    : COVER_PLACEHOLDER,
)

function onAvatarError(e: Event) {
  avatarFailed.value = true
  ;(e.target as HTMLImageElement).src = AVATAR_PLACEHOLDER
}
function onCoverError(e: Event) {
  coverFailed.value = true
  ;(e.target as HTMLImageElement).src = COVER_PLACEHOLDER
}

async function fetchVideo() {
  const parsed = parseBvid(embedBvid.value)
  if (!parsed || !parsed.bvid) {
    embedCode.value = ''
    embedPreview.value = ''
    return
  }
  coverFailed.value = false
  embedLoading.value = true
  embedCode.value = ''
  embedPreview.value = ''
  videoInfo.value = null
  embedCid.value = ''
  try {
    const { data } = await axiosInstance.get(`${API}/video/info?bvid=${parsed.bvid}`)
    const info = parseData<{ pages?: Array<{ cid: number }>; error?: string }>(data)
    if (info.error) throw new Error(info.error)
    const firstCid = info.pages?.[0]?.cid ? String(info.pages[0].cid) : ''
    if (firstCid) embedCid.value = firstCid
    const cid = firstCid || embedCid.value
    let resolution = { width: 0, height: 0 }
    if (cid) {
      try {
        const { data: pd } = await axiosInstance.get(
          `${API}/video/playurl?bvid=${parsed.bvid}&cid=${cid}&qn=80&fnval=16`,
        )
        resolution = extractResolution(parseData(pd))
      } catch {
        /* ignore */
      }
    }
    videoInfo.value = {
      ...(info as Record<string, unknown>),
      width: resolution.width,
      height: resolution.height,
    } as NonNullable<typeof videoInfo.value>
    generateCode(parsed.bvid, cid)
  } catch (e) {
    embedCode.value = ''
    embedPreview.value = ''
    const msg = e instanceof Error && e.message ? e.message : '请检查 BV 号或链接是否正确'
    Toast.error(`获取视频信息失败：${msg}`)
  } finally {
    embedLoading.value = false
  }
}

/* ---------- 尺寸与样式逻辑 ---------- */
function clampWidth(n: number): number {
  if (!Number.isFinite(n)) return WIDTH_DEFAULT
  return Math.min(WIDTH_MAX, Math.max(WIDTH_MIN, Math.round(n)))
}

function snapWidth(n: number): number {
  for (const s of WIDTH_SNAPS) {
    if (Math.abs(n - s) <= WIDTH_SNAP_TOL) return s
  }
  return n
}

function showClampHint() {
  widthClamped.value = true
  if (clampHintTimer) clearTimeout(clampHintTimer)
  clampHintTimer = setTimeout(() => {
    widthClamped.value = false
  }, 2400)
}

function setWidthMode(mode: WidthMode) {
  widthMode.value = mode
  regenerateCode()
}
function setRadiusPreset(radius: RadiusPreset) {
  radiusPreset.value = radius
  regenerateCode()
}
function setAlignMode(align: AlignMode) {
  alignMode.value = align
  regenerateCode()
}

function onWidthSlider(e: Event) {
  maxWidth.value = clampWidth(snapWidth(Number((e.target as HTMLInputElement).value)))
  regenerateCode()
}

function onWidthFieldInput() {
  if (Number.isFinite(maxWidth.value)) regenerateCode()
}

function onWidthChange() {
  const raw = Number(maxWidth.value)
  if (!Number.isFinite(raw)) {
    maxWidth.value = clampWidth(NaN)
    regenerateCode()
    return
  }
  const clamped = clampWidth(raw)
  if (clamped !== Math.round(raw)) showClampHint()
  maxWidth.value = clamped
  regenerateCode()
}

const SIZE_STYLE_STORAGE_KEY = 'bp-size-style-v1'
function persistSizeStyle() {
  try {
    localStorage.setItem(
      SIZE_STYLE_STORAGE_KEY,
      JSON.stringify({
        mode: widthMode.value,
        width: maxWidth.value,
        radius: radiusPreset.value,
        align: alignMode.value,
      }),
    )
  } catch {
    /* 无痕模式等场景下不可写，忽略 */
  }
}
function restoreSizeStyle() {
  try {
    const saved = JSON.parse(localStorage.getItem(SIZE_STYLE_STORAGE_KEY) || 'null')
    if (!saved || typeof saved !== 'object') return
    if (saved.mode === 'fluid' || saved.mode === 'fixed') widthMode.value = saved.mode
    if (Number.isFinite(saved.width)) maxWidth.value = clampWidth(saved.width)
    if (saved.radius === '0' || saved.radius === '8' || saved.radius === '16')
      radiusPreset.value = saved.radius
    if (saved.align === 'center' || saved.align === 'left') alignMode.value = saved.align
  } catch {
    /* 忽略损坏的缓存 */
  }
}
watch([widthMode, maxWidth, radiusPreset, alignMode], persistSizeStyle)

function generateCode(bvid: string, cid: string) {
  if (!bvid || !cid) {
    embedCode.value = ''
    embedPreview.value = ''
    return
  }
  const src = `${siteOrigin.value}${EMBED_PATH}?bvid=${encodeURIComponent(bvid)}&cid=${encodeURIComponent(cid)}`
  const radius = `${radiusPreset.value}px`

  if (widthMode.value === 'fluid') {
    embedCode.value = `<iframe src="${src}" style="width:100%;aspect-ratio:${videoAspectRatio.value};border:none;border-radius:${radius}" allowfullscreen allow="autoplay;encrypted-media" loading="lazy"></iframe>`
  } else {
    const px = clampWidth(maxWidth.value)
    const margin = alignMode.value === 'center' ? '16px auto' : '16px 0'
    const containerStyle = `position:relative;width:100%;max-width:${px}px;aspect-ratio:${videoAspectRatio.value};border-radius:${radius};overflow:hidden;margin:${margin}`
    const iframeStyle = `position:absolute;top:0;left:0;width:100%;height:100%;border:none`
    embedCode.value = `<div data-bilibili-player="true" data-bvid="${bvid}" data-cid="${cid}" style="${containerStyle}"><iframe src="${src}" style="${iframeStyle}" allowfullscreen allow="autoplay;encrypted-media" loading="lazy"></iframe></div>`
  }
  embedPreview.value = src
}

function regenerateCode() {
  const parsed = parseBvid(embedBvid.value)
  if (parsed) generateCode(parsed.bvid, embedCid.value)
}

async function copyCode() {
  if (!embedCode.value) return
  try {
    await navigator.clipboard.writeText(embedCode.value)
    Toast.success('嵌入代码已复制到剪贴板')
  } catch {
    const ta = document.createElement('textarea')
    ta.value = embedCode.value
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    if (ok) Toast.success('嵌入代码已复制到剪贴板')
    else Toast.error('复制失败，请手动复制代码')
  }
}

/* ---------- Logs ---------- */
function scrollLogsToBottom() {
  if (logAutoScroll.value)
    nextTick(() => {
      const el = logContainer.value
      if (el) el.scrollTop = el.scrollHeight
    })
}

function appendLogEntry(entry: { time: string; level: string; msg: string }) {
  // 服务端 SSE 重连会回放最近缓冲，按 time+level+msg 对尾部去重
  const tail = logEntries.value.slice(-50)
  if (tail.some((e) => e.time === entry.time && e.level === entry.level && e.msg === entry.msg))
    return
  logEntries.value = [...logEntries.value, entry].slice(-800)
  scrollLogsToBottom()
}

async function fetchLogHistory() {
  try {
    const { data } = await axiosInstance.get(`${API}/logs/history`)
    logEntries.value = (Array.isArray(data) ? data : data?.value || data || []).slice(-800)
    scrollLogsToBottom()
  } catch {
    /* ignore */
  }
}

function openLogStream() {
  closeLogStream()
  stopLogPollFallback()
  try {
    const es = new EventSource(`${API}/logs/stream`)
    es.onmessage = (ev) => {
      try {
        const entry = JSON.parse(ev.data) as { time?: string; level?: string; msg?: string }
        appendLogEntry({
          time: entry.time ?? '',
          level: entry.level ?? 'INFO',
          msg: entry.msg ?? String(ev.data),
        })
      } catch {
        /* ignore malformed entry */
      }
    }
    es.onerror = () => {
      closeLogStream()
      startLogPollFallback()
    }
    logEventSource = es
  } catch {
    startLogPollFallback()
  }
}
function closeLogStream() {
  if (logEventSource) {
    logEventSource.close()
    logEventSource = null
  }
}

function startLogPollFallback() {
  closeLogStream()
  stopLogPollFallback()
  logPollTimer = setInterval(fetchLogHistory, 2000)
}
function stopLogPollFallback() {
  if (logPollTimer) {
    clearInterval(logPollTimer)
    logPollTimer = null
  }
}

function startLogs() {
  fetchLogHistory()
  openLogStream()
}
function stopLogs() {
  closeLogStream()
  stopLogPollFallback()
}
function refreshLogs() {
  fetchLogHistory()
  if (!logEventSource) openLogStream()
}
function clearLogs() {
  logEntries.value = []
}

function onTabChange(id: string | number) {
  if (id === 'logs') startLogs()
  else stopLogs()
}
</script>

<template>
  <VPageHeader title="B 站播放器">
    <template #icon>
      <svg class="bp-logo" viewBox="0 0 24 24" width="24" height="24" fill="currentColor">
        <path
          d="M18.223 3.086a1.25 1.25 0 0 1 0 1.768L17.08 5.996h1.17A3.75 3.75 0 0 1 22 9.747v7.5a3.75 3.75 0 0 1-3.75 3.75H5.75A3.75 3.75 0 0 1 2 17.247v-7.5a3.75 3.75 0 0 1 3.75-3.75h1.166L5.775 4.855a1.25 1.25 0 1 1 1.767-1.77l2.652 2.654.1.258h3.411l.1-.258 2.654-2.653a1.25 1.25 0 0 1 1.768 0zM18.25 8.496H5.75a1.25 1.25 0 0 0-1.243 1.122l-.007.128v7.5c0 .643.487 1.172 1.112 1.243l.138.007h12.5a1.25 1.25 0 0 0 1.243-1.122l.007-.128v-7.5a1.25 1.25 0 0 0-1.25-1.25zM8.5 11a1.25 1.25 0 0 1 1.25 1.25v1.5a1.25 1.25 0 1 1-2.5 0v-1.5A1.25 1.25 0 0 1 8.5 11zm7 0a1.25 1.25 0 0 1 1.25 1.25v1.5a1.25 1.25 0 1 1-2.5 0v-1.5A1.25 1.25 0 0 1 15.5 11z"
        />
      </svg>
    </template>
    <template #actions>
      <VSpace>
        <VTag>v1.4.0</VTag>
        <VStatusDot
          v-if="loginChecked"
          :state="loginUser ? 'success' : 'default'"
          :text="loginUser ? '已登录' : '未登录'"
        />
        <VButton v-if="loginUser" size="sm" @click="confirmLogout">
          <template #icon><IconLogoutCircleRLine /></template>
          退出登录
        </VButton>
      </VSpace>
    </template>
  </VPageHeader>

  <div class="bp-page">
    <VTabbar
      v-model:active-id="activeTabId"
      :items="tabItems"
      type="outline"
      id-key="id"
      label-key="label"
      @change="onTabChange"
    />

    <!-- 账号登录 -->
    <VCard v-if="activeTabId === 'login' && !loginUser" title="扫码登录 B 站账号">
      <div class="bp-login">
        <div class="bp-qr">
          <div class="bp-qr__frame" :data-status="qrStatus">
            <div v-if="qrStatus === 'idle'" class="bp-qr__placeholder">
              <RiQrCodeLine class="bp-qr__placeholder-icon" />
              <div class="bp-qr__placeholder-text">点击下方按钮生成二维码</div>
            </div>
            <div v-else-if="qrStatus === 'loading'" class="bp-qr__placeholder">
              <VLoading />
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
          <VStatusDot
            :state="qrStatusDotState"
            :animate="qrStatus === 'pending' || qrStatus === 'loading'"
            :text="qrStatusLabel || '等待操作'"
          />
        </div>
        <div class="bp-qr__side">
          <VAlert
            type="info"
            title="登录后可获取 720P 及以上清晰度"
            description="支持 1080P 高码率 / 4K，凭据仅保存在服务端"
            :closable="false"
            class="bp-login__alert"
          />
          <div class="bp-qr__steps-title">登录步骤</div>
          <ol class="bp-steps">
            <li>打开哔哩哔哩手机 App</li>
            <li>点击右上角扫一扫图标</li>
            <li>对准二维码进行扫描</li>
            <li>在手机上点击「确认登录」</li>
          </ol>
          <VSpace>
            <VButton type="primary" :loading="qrStatus === 'loading'" @click="genQr">
              {{
                qrStatus === 'idle' || qrStatus === 'error'
                  ? '生成二维码'
                  : qrStatus === 'expired'
                    ? '重新生成'
                    : '刷新二维码'
              }}
            </VButton>
          </VSpace>
          <VAlert
            v-if="qrError"
            type="error"
            :title="qrError"
            :closable="false"
            class="bp-login__alert"
          />
        </div>
      </div>
    </VCard>

    <VCard v-else-if="activeTabId === 'login' && loginUser" title="已登录账号">
      <div class="bp-user">
        <VAvatar :src="faceSrc" :alt="loginUser.uname" size="lg" circle @error="onAvatarError" />
        <VDescription class="bp-user__desc">
          <VDescriptionItem label="昵称">
            <span class="bp-user__name">{{ loginUser.uname }}</span>
          </VDescriptionItem>
          <VDescriptionItem label="等级">Lv {{ loginUser.level }}</VDescriptionItem>
          <VDescriptionItem label="大会员">
            <VTag v-if="vipLabel" theme="primary" rounded>{{ vipLabel }}</VTag>
            <span v-else class="bp-text-muted">未开通</span>
          </VDescriptionItem>
        </VDescription>
      </div>
      <VSpace class="bp-user__actions">
        <VButton size="sm" type="primary" @click="activeTabId = 'embed'">去生成嵌入代码</VButton>
        <VButton size="sm" @click="confirmLogout">退出登录</VButton>
      </VSpace>
    </VCard>

    <!-- 视频嵌入 -->
    <div v-if="activeTabId === 'embed'" class="bp-embed">
      <!-- ① 视频源 -->
      <VCard title="视频源">
        <div class="bp-section">
          <div class="bp-search">
            <input
              v-model="embedBvid"
              class="bp-input"
              placeholder="BV1xx411c7mD 或 https://www.bilibili.com/video/BV..."
              @keyup.enter="fetchVideo"
            />
            <VButton type="primary" :loading="embedLoading" @click="fetchVideo">解析视频</VButton>
          </div>
        </div>
      </VCard>

      <!-- ② 视频信息 -->
      <VCard title="视频信息">
        <div class="bp-section">
          <div v-if="videoInfo" class="bp-video">
            <img
              class="bp-video__cover"
              :src="coverSrc"
              :alt="videoInfo.title"
              loading="lazy"
              @error="onCoverError"
            />
            <div class="bp-video__meta">
              <div class="bp-video__title">{{ videoInfo.title }}</div>
              <div class="bp-video__sub">
                <span>UP 主：{{ videoInfo.ownerName }}</span>
                <template v-if="videoInfo.width && videoInfo.height">
                  <span> &middot; {{ videoInfo.width }}&times;{{ videoInfo.height }}</span>
                  <VTag v-if="videoOrientation" class="bp-video__orientation">{{
                    videoOrientation
                  }}</VTag>
                </template>
              </div>
              <div v-if="videoInfo.stat" class="bp-video__stats">
                <span>{{ formatNum(videoInfo.stat.view) }} 播放</span>
                <span>{{ formatNum(videoInfo.stat.danmaku) }} 弹幕</span>
                <span>{{ formatNum(videoInfo.stat.like) }} 点赞</span>
              </div>
              <div v-if="videoInfo.pages && videoInfo.pages.length > 1" class="bp-video__pages">
                <select
                  v-model="embedCid"
                  class="bp-input"
                  @change="!embedLoading && generateCode(parseBvid(embedBvid)?.bvid || '', embedCid)"
                >
                  <option v-for="p in videoInfo.pages" :key="p.cid" :value="p.cid">
                    P{{ p.page }} &middot; {{ p.part }}
                  </option>
                </select>
              </div>
            </div>
          </div>
          <VEmpty
            v-else
            title="暂无视频信息"
            message="在「视频源」中输入 BV 号或链接并点击解析"
          />
        </div>
      </VCard>

      <!-- ③ 尺寸与样式 -->
      <VCard title="尺寸与样式">
        <div class="bp-section">
          <div v-if="videoInfo" class="bp-form">
            <div class="bp-form-row">
              <span class="bp-form-label">宽度模式</span>
              <div class="bp-form-field">
                <div class="bp-segmented" role="radiogroup" aria-label="宽度模式">
                  <button
                    v-for="opt in widthModes"
                    :key="opt.id"
                    class="bp-segmented__item"
                    :class="{ active: widthMode === opt.id }"
                    role="radio"
                    :aria-checked="widthMode === opt.id"
                    @click="setWidthMode(opt.id)"
                  >
                    {{ opt.label }}
                  </button>
                </div>
                <p class="bp-form-hint">{{ widthModeHint }}</p>
              </div>
            </div>

            <div class="bp-form-row" :class="{ 'is-disabled': widthMode !== 'fixed' }">
              <span class="bp-form-label">最大宽度</span>
              <div class="bp-form-field">
                <div class="bp-width-ctl">
                  <div class="bp-slider-wrap">
                    <input
                      class="bp-slider"
                      type="range"
                      :min="WIDTH_MIN"
                      :max="WIDTH_MAX"
                      step="10"
                      :value="maxWidth"
                      :disabled="widthMode !== 'fixed'"
                      :style="{ '--fill': widthFillPercent }"
                      aria-label="最大宽度（像素）"
                      @input="onWidthSlider"
                    />
                    <div class="bp-slider-ticks" aria-hidden="true">
                      <span class="bp-slider-tick" style="left: 0%; transform: none">{{
                        WIDTH_MIN
                      }}</span>
                      <span
                        v-for="t in snapTicks"
                        :key="t.value"
                        class="bp-slider-tick bp-slider-tick--snap"
                        :style="{ left: t.left }"
                        >{{ t.value }}</span
                      >
                      <span
                        class="bp-slider-tick"
                        style="left: 100%; transform: translateX(-100%)"
                        >{{ WIDTH_MAX }}</span
                      >
                    </div>
                  </div>
                  <div class="bp-width-input">
                    <input
                      v-model.number="maxWidth"
                      class="bp-input"
                      type="number"
                      :min="WIDTH_MIN"
                      :max="WIDTH_MAX"
                      step="10"
                      :disabled="widthMode !== 'fixed'"
                      aria-label="最大宽度数值"
                      @input="onWidthFieldInput"
                      @change="onWidthChange"
                    />
                    <span class="bp-width-input__unit">px</span>
                  </div>
                </div>
                <p class="bp-clamp-hint" :class="{ show: widthClamped }" role="status">
                  已限制在 {{ WIDTH_MIN }} – {{ WIDTH_MAX }} px 范围内
                </p>
              </div>
            </div>

            <div class="bp-form-row">
              <span class="bp-form-label">圆角</span>
              <div class="bp-segmented" role="radiogroup" aria-label="圆角">
                <button
                  v-for="opt in radiusPresets"
                  :key="opt.id"
                  class="bp-segmented__item"
                  :class="{ active: radiusPreset === opt.id }"
                  role="radio"
                  :aria-checked="radiusPreset === opt.id"
                  @click="setRadiusPreset(opt.id)"
                >
                  {{ opt.label }}
                </button>
              </div>
            </div>

            <div class="bp-form-row" :class="{ 'is-disabled': widthMode !== 'fixed' }">
              <span class="bp-form-label">对齐</span>
              <div class="bp-form-field">
                <div class="bp-segmented" role="radiogroup" aria-label="对齐方式">
                  <button
                    v-for="opt in alignModes"
                    :key="opt.id"
                    class="bp-segmented__item"
                    :class="{ active: alignMode === opt.id }"
                    role="radio"
                    :aria-checked="alignMode === opt.id"
                    @click="setAlignMode(opt.id)"
                  >
                    {{ opt.label }}
                  </button>
                </div>
                <p class="bp-form-hint">仅「限制最大宽度」时生效；自适应模式恒为撑满。</p>
              </div>
            </div>

            <div class="bp-form-row">
              <span class="bp-form-label">画幅比例</span>
              <div class="bp-aspect">
                <div class="bp-aspect__box" :style="aspectBoxStyle">
                  <span class="bp-aspect__play">&#9654;</span>
                </div>
                <div class="bp-aspect__meta">
                  <span class="bp-aspect__ratio">{{ videoAspectLabel || '16:9' }}</span>
                  <span class="bp-aspect__size">
                    {{ videoInfo.width }}&times;{{ videoInfo.height
                    }}<template v-if="videoOrientation"> &middot; {{ videoOrientation }}</template>
                  </span>
                  <span v-if="resultSizeLabel" class="bp-aspect__result">
                    嵌入结果 {{ resultSizeLabel }}
                  </span>
                </div>
              </div>
            </div>
          </div>
          <VEmpty
            v-else
            title="暂无可设置的尺寸"
            message="解析视频后可调整嵌入宽度并查看画幅比例"
          />
        </div>
      </VCard>

      <!-- ④ 嵌入代码 -->
      <VCard title="嵌入代码">
        <div class="bp-section">
          <div v-if="embedCode" class="bp-code-section">
            <div class="bp-code-toolbar">
              <span class="bp-code-label">iframe 嵌入代码</span>
              <VButton size="sm" type="primary" @click="copyCode">
                <template #icon><IconClipboardLine /></template>
                复制代码
              </VButton>
            </div>
            <pre class="bp-code-block"><code>{{ embedCode }}</code></pre>
            <div class="bp-preview" :style="previewStyle">
              <iframe
                v-if="embedPreview"
                :src="embedPreview"
                allowfullscreen
                allow="autoplay; encrypted-media"
                loading="lazy"
              />
            </div>
          </div>
          <VEmpty
            v-else
            title="暂无嵌入代码"
            message="解析视频后自动生成嵌入代码与预览"
          />
        </div>
      </VCard>
    </div>

    <!-- 运行日志 -->
    <VCard v-if="activeTabId === 'logs'" title="运行日志">
      <template #actions>
        <div class="bp-log-actions">
          <div class="bp-log-filter-group">
            <button
              v-for="lv in logLevels"
              :key="lv"
              class="bp-chip sm"
              :class="{ active: logFilter === lv }"
              @click="logFilter = lv"
            >
              {{ lv }}
            </button>
          </div>
          <div class="bp-autoscroll">
            <VSwitch v-model="logAutoScroll" />
            <span class="bp-autoscroll-label">自动滚动</span>
          </div>
          <VButton size="sm" @click="refreshLogs">
            <template #icon><IconRefreshLine /></template>
            刷新
          </VButton>
          <VButton size="sm" @click="clearLogs">清空</VButton>
        </div>
      </template>
      <div ref="logContainer" class="bp-log-body">
        <VEmpty v-if="filteredLogs.length === 0" title="暂无日志" message="操作后将显示日志" />
        <div v-for="(l, i) in filteredLogs" :key="i" class="bp-log" :data-level="l.level">
          <span class="bp-log__time">{{ l.time }}</span>
          <span class="bp-log__level">{{ l.level }}</span>
          <span class="bp-log__msg">{{ l.msg }}</span>
        </div>
      </div>
    </VCard>
  </div>
</template>

<style scoped>
.bp-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin: 16px;
}

.bp-logo {
  color: #fb7299;
  width: 24px;
  height: 24px;
}

.bp-text-muted {
  color: #86909c;
}

/* Login */
.bp-login {
  display: flex;
  gap: 40px;
  align-items: stretch;
}
@media (max-width: 768px) {
  .bp-login {
    flex-direction: column;
    gap: 24px;
  }
}
.bp-login__alert {
  margin-bottom: 12px;
}
.bp-login__alert:last-child {
  margin-bottom: 0;
  margin-top: 12px;
}

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
  border: 1px solid rgb(234, 236, 240);
  border-radius: 4px;
  display: grid;
  place-items: center;
  position: relative;
  background: #fff;
  overflow: hidden;
  transition: border-color 0.2s;
}
.bp-qr__frame[data-status='scanned'] {
  border-color: #ff7d00;
}
.bp-qr__frame[data-status='success'] {
  border-color: #00b42a;
}
.bp-qr__frame[data-status='expired'] {
  border-color: #f53f3f;
}
.bp-qr__frame img {
  width: 220px;
  height: 220px;
  display: block;
}
.bp-qr__placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  color: #86909c;
}
.bp-qr__placeholder-icon {
  width: 32px;
  height: 32px;
  opacity: 0.4;
}
.bp-qr__placeholder-text {
  font-size: 13px;
}
.bp-qr__overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  background: rgba(255, 255, 255, 0.92);
  font-size: 14px;
  font-weight: 500;
}
.bp-qr__overlay-icon {
  font-size: 28px;
  font-weight: 700;
}
.bp-qr__overlay--warn {
  color: #ff7d00;
}
.bp-qr__overlay--danger {
  color: #f53f3f;
}
.bp-qr__overlay--success {
  color: #00b42a;
}
.bp-qr__overlay-hint {
  font-size: 12px;
}
.bp-qr__side {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.bp-qr__steps-title {
  font-weight: 600;
  margin-bottom: 12px;
  color: #1f2329;
}
.bp-steps {
  margin: 0 0 16px;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 13px;
  color: #4e5969;
}

/* User card */
.bp-user {
  display: flex;
  align-items: center;
  gap: 24px;
}
.bp-user__desc {
  flex: 1;
  min-width: 0;
}
.bp-user__name {
  font-weight: 600;
  color: #1f2329;
}
.bp-user__actions {
  margin-top: 12px;
}

/* Embed */
.bp-embed {
  display: flex;
  flex-direction: column;
  gap: 24px;
  width: 100%;
  max-width: 960px;
}
.bp-section {
  padding: 8px;
}
.bp-search {
  display: flex;
  gap: 8px;
}
@media (max-width: 640px) {
  .bp-search {
    flex-direction: column;
  }
}

.bp-input {
  flex: 1;
  height: 36px;
  padding: 0 12px;
  font-size: 13px;
  border: 1px solid rgb(234, 236, 240);
  border-radius: 4px;
  outline: none;
  font-family: inherit;
  background: #fff;
  color: #1f2329;
  transition:
    border-color 0.15s,
    box-shadow 0.15s;
}
.bp-input:focus {
  border-color: var(--color-primary, #4ccba0);
  box-shadow: 0 0 0 3px rgba(76, 203, 160, 0.15);
}
.bp-input:disabled {
  background: #f7f8fa;
  color: #86909c;
  cursor: not-allowed;
}

.bp-video {
  display: flex;
  gap: 16px;
  padding: 16px;
  background: #f7f8fa;
  border-radius: 4px;
  border: 1px solid rgb(234, 236, 240);
}
@media (max-width: 640px) {
  .bp-video {
    flex-direction: column;
  }
}
.bp-video__cover {
  width: 180px;
  aspect-ratio: 16/10;
  object-fit: cover;
  border-radius: 4px;
  flex-shrink: 0;
  background: rgb(234, 236, 240);
}
@media (max-width: 640px) {
  .bp-video__cover {
    width: 100%;
  }
}
.bp-video__meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  flex: 1;
}
.bp-video__title {
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  color: #1f2329;
}
.bp-video__sub {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #86909c;
}
.bp-video__orientation {
  margin-left: 4px;
}
.bp-video__stats {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: #86909c;
}
.bp-video__pages {
  margin-top: auto;
}

/* Code section */
.bp-code-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.bp-code-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.bp-code-label {
  font-size: 12px;
  font-weight: 600;
  color: #4e5969;
}
.bp-code-block {
  margin: 0;
  padding: 12px 14px;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px;
  line-height: 1.6;
  background: #f7f8fa;
  border: 1px solid rgb(234, 236, 240);
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 180px;
  overflow: auto;
  color: #1f2329;
}

/* Size & style form */
.bp-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.bp-form-row {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}
.bp-form-label {
  width: 72px;
  flex-shrink: 0;
  font-size: 13px;
  color: #4e5969;
  padding-top: 8px;
}
.bp-form-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
  min-width: 0;
}
.bp-form-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: #86909c;
}
.bp-form-row.is-disabled .bp-form-field,
.bp-form-row.is-disabled .bp-segmented {
  opacity: 0.45;
  pointer-events: none;
}
@media (max-width: 640px) {
  .bp-form-row {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
  .bp-form-label {
    padding-top: 0;
  }
}

.bp-segmented {
  display: inline-flex;
  gap: 2px;
  padding: 3px;
  background: #f7f8fa;
  border: 1px solid rgb(234, 236, 240);
  border-radius: 4px;
}
.bp-segmented__item {
  padding: 5px 14px;
  font-size: 12px;
  border: none;
  border-radius: 3px;
  background: transparent;
  color: #4e5969;
  cursor: pointer;
  font-family: inherit;
  white-space: nowrap;
  transition: all 0.15s ease;
}
.bp-segmented__item:hover {
  color: #1f2329;
}
.bp-segmented__item.active {
  background: #fff;
  color: #fb7299;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
}
.bp-segmented__item:focus-visible {
  outline: 2px solid #fb7299;
  outline-offset: 1px;
  color: #1f2329;
}

/* 最大宽度：滑杆 + 吸附刻度 + 数字输入 */
.bp-width-ctl {
  display: grid;
  grid-template-columns: 1fr 108px;
  gap: 16px;
  align-items: center;
  max-width: 560px;
}
@media (max-width: 640px) {
  .bp-width-ctl {
    grid-template-columns: 1fr;
    max-width: none;
    width: 100%;
  }
}
.bp-slider-wrap {
  position: relative;
  padding-bottom: 20px;
}
.bp-slider {
  -webkit-appearance: none;
  appearance: none;
  width: 100%;
  height: 4px;
  border-radius: 2px;
  margin: 12px 0 0;
  background: linear-gradient(
    to right,
    #fb7299 0 var(--fill, 24%),
    rgb(229, 230, 235) var(--fill, 24%)
  );
  outline-offset: 4px;
  cursor: pointer;
}
.bp-slider::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #fff;
  border: 2px solid #fb7299;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.18);
  cursor: grab;
}
.bp-slider::-moz-range-thumb {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #fff;
  border: 2px solid #fb7299;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.18);
  cursor: grab;
}
.bp-slider:disabled {
  cursor: not-allowed;
}
.bp-slider:focus-visible {
  outline: 2px solid #fb7299;
  outline-offset: 4px;
}
.bp-slider-ticks {
  position: absolute;
  left: 0;
  right: 0;
  top: 32px;
  height: 16px;
}
.bp-slider-tick {
  position: absolute;
  transform: translateX(-50%);
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 11px;
  color: #86909c;
  white-space: nowrap;
}
.bp-slider-tick::before {
  content: '';
  display: block;
  width: 1px;
  height: 4px;
  background: #c9cdd4;
  margin: 0 auto 2px;
}
.bp-slider-tick--snap {
  color: #1f2329;
  font-weight: 600;
}
.bp-clamp-hint {
  margin: 0;
  font-size: 12px;
  color: #fb7299;
  opacity: 0;
  transition: opacity 0.2s ease;
}
.bp-clamp-hint.show {
  opacity: 1;
}

.bp-width-input {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bp-width-input .bp-input {
  flex: none;
  width: 100%;
}
.bp-width-input__unit {
  font-size: 13px;
  color: #86909c;
}

.bp-aspect {
  display: flex;
  align-items: center;
  gap: 16px;
}
.bp-aspect__box {
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #18191c, #2b2f36);
  border: 1px solid rgb(234, 236, 240);
  border-radius: 4px;
  transition:
    width 0.2s ease,
    height 0.2s ease;
}
.bp-aspect__play {
  font-size: 14px;
  color: #fb7299;
  opacity: 0.9;
}
.bp-aspect__meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.bp-aspect__ratio {
  font-size: 13px;
  font-weight: 600;
  color: #1f2329;
}
.bp-aspect__size {
  font-size: 12px;
  color: #86909c;
}
.bp-aspect__result {
  font-size: 12px;
  color: #4e5969;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

.bp-chip {
  padding: 4px 12px;
  font-size: 12px;
  border: 1px solid rgb(234, 236, 240);
  border-radius: 4px;
  background: #fff;
  color: #4e5969;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s ease;
}
.bp-chip:hover {
  border-color: var(--color-primary, #4ccba0);
  color: var(--color-primary, #4ccba0);
}
.bp-chip.active {
  background: var(--color-primary, #4ccba0);
  border-color: var(--color-primary, #4ccba0);
  color: #fff;
}
.bp-chip:focus-visible {
  outline: 2px solid var(--color-primary, #4ccba0);
  outline-offset: 1px;
}
.bp-chip.sm {
  padding: 2px 8px;
  font-size: 11px;
}

.bp-preview {
  width: 100%;
  aspect-ratio: 16/9;
  border-radius: 4px;
  overflow: hidden;
  border: 1px solid rgb(234, 236, 240);
  background: #000;
  margin-top: 8px;
}
.bp-preview iframe {
  width: 100%;
  height: 100%;
  border: none;
  display: block;
}

/* Logs */
.bp-log-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.bp-log-filter-group {
  display: flex;
  gap: 4px;
}
.bp-autoscroll {
  display: flex;
  align-items: center;
  gap: 6px;
}
.bp-autoscroll-label {
  font-size: 12px;
  color: #86909c;
}
.bp-log-body {
  height: 420px;
  overflow-y: auto;
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  border: 1px solid rgb(234, 236, 240);
  border-radius: 4px;
}
.bp-log {
  display: grid;
  grid-template-columns: 70px 48px 1fr;
  gap: 8px;
  padding: 3px 16px;
  align-items: baseline;
  border-bottom: 1px solid rgba(0, 0, 0, 0.02);
}
.bp-log:hover {
  background: #f7f8fa;
}
.bp-log__time {
  font-size: 11px;
  color: #86909c;
}
.bp-log__level {
  font-size: 10px;
  font-weight: 700;
  text-align: center;
  padding: 1px 0;
  border-radius: 4px;
}
.bp-log__msg {
  color: #4e5969;
  word-break: break-all;
  line-height: 1.55;
}
.bp-log[data-level='ERROR'] {
  background: rgba(245, 63, 63, 0.06);
}
.bp-log[data-level='ERROR'] .bp-log__level {
  color: #fff;
  background: #f53f3f;
}
.bp-log[data-level='WARN'] .bp-log__level {
  color: #fff;
  background: #ff7d00;
}
.bp-log[data-level='INFO'] .bp-log__level {
  color: #fff;
  background: #00a1d6;
}
.bp-log[data-level='DEBUG'] .bp-log__level {
  color: #4e5969;
  background: rgb(234, 236, 240);
}
</style>
