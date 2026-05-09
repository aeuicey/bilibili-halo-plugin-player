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

type TabId = 'login' | 'embed' | 'help'
const activeTabId = ref<TabId>('login')

/* ---------- 登录状态 ---------- */
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
const qrMessage = ref('')
const qrError = ref('')
const pollCount = ref(0)
const pollDebug = ref('')
const pollErrors = ref(0)
let pollTimer: ReturnType<typeof setInterval> | null = null

/* ---------- 嵌入代码状态 ---------- */
const embedBvid = ref('')
const embedCid = ref('')
const embedLoading = ref(false)
const embedCode = ref('')
const embedPreview = ref('')
const embedWidth = ref('100')
const widthOptions = [
  { value: '100', label: '100% 自适应' },
  { value: '800', label: '固定 800px' },
  { value: '640', label: '固定 640px' },
  { value: '480', label: '固定 480px' },
]
const videoInfo = ref<null | {
  title: string
  pic: string
  ownerName: string
  pages: Array<{ cid: number; page: number; part: string }>
  width: number
  height: number
}>(null)
const copied = ref(false)

/* ---------- 日志状态 ---------- */
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
    loading: '正在生成二维码…',
    pending: '请使用哔哩哔哩 App 扫码',
    scanned: '已扫描，请在手机上确认',
    success: '登录成功',
    expired: '二维码已过期，请重新生成',
    error: '发生错误',
    idle: '',
    generating: '生成中…',
  }
  return map[qrMessage.value] || qrMessage.value || map[qrStatus.value] || ''
})

/* ---------- 生命周期 ---------- */
onMounted(async () => {
  await checkLogin()
})
onUnmounted(() => {
  stopPoll()
  stopLogPoll()
})

/* ---------- 日志轮询 ---------- */
function startLogPoll() {
  stopLogPoll()
  logPollTimer = setInterval(async () => {
    try {
      const { data } = await axios.get(`${API}/logs/history`)
      const list = Array.isArray(data) ? data : data.value || data || []
      logEntries.value = list.slice(-800)
      if (logAutoScroll.value) {
        nextTick(() => {
          const el = logContainer.value
          if (el) el.scrollTop = el.scrollHeight
        })
      }
    } catch (_) {
      // ignore
    }
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

/* ---------- 登录逻辑 ---------- */
async function checkLogin() {
  try {
    const { data } = await axios.get(`${API}/login/status`)
    const p = typeof data === 'string' ? JSON.parse(data) : data
    if (p.isLogin) {
      loginUser.value = p
      activeTabId.value = 'embed'
    }
  } catch (_) {
    // ignore
  }
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
    qrMessage.value = 'pending'
    startPoll()
  } catch (e: unknown) {
    qrStatus.value = 'error'
    qrError.value = (e as { message?: string })?.message || '未知错误'
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
        qrError.value = '响应数据异常'
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
          qrError.value = 'SESSDATA 校验失败'
        }
      } else if (p.status === 'scanned') {
        qrStatus.value = 'scanned'
        qrMessage.value = 'scanned'
      } else if (p.status === 'expired' || p.status === 'error') {
        qrStatus.value = 'expired'
        qrMessage.value = 'expired'
        stopPoll()
      }
    } catch (_e: unknown) {
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
  } catch (_) {
    // ignore
  }
  loginUser.value = null
  qrImage.value = ''
  qrStatus.value = 'idle'
  qrMessage.value = ''
  qrError.value = ''
  activeTabId.value = 'login'
}

/* ---------- 嵌入逻辑 ---------- */
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
    const cid = embedCid.value || String(info.pages?.[0]?.cid || '')
    if (!embedCid.value && info.pages?.length > 0) embedCid.value = String(info.pages[0].cid)
    try {
      const { data: pd } = await axios.get(
        `${API}/video/playurl?bvid=${p.bvid}&cid=${cid}&qn=80&fnval=16`,
      )
      const playData = typeof pd === 'string' ? JSON.parse(pd) : pd
      let w = 0
      let h = 0
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
  } catch (_) {
    embedCode.value = ''
    embedPreview.value = ''
  }
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

/* ---------- VIP 标签计算 ---------- */
const vipLabel = computed(() => {
  if (!loginUser.value?.vipStatus) return ''
  return loginUser.value.vipType === 2 ? '年度大会员' : '大会员'
})

function onWidthChange(v: string) {
  embedWidth.value = v
  generateCode(parseBvid(embedBvid.value)?.bvid || '', embedCid.value)
}

const tabs: Array<{ id: TabId; label: string; icon: string }> = [
  { id: 'login', label: '账号登录', icon: '👤' },
  { id: 'embed', label: '视频嵌入', icon: '🎬' },
  { id: 'help', label: '使用说明', icon: '📖' },
]
</script>

<template>
  <div class="bp">
    <!-- 顶部品牌横幅 -->
    <header class="bp-hero">
      <div class="bp-hero__bg" aria-hidden="true"></div>
      <div class="bp-hero__inner">
        <div class="bp-hero__brand">
          <div class="bp-hero__logo">
            <svg viewBox="0 0 24 24" width="28" height="28" fill="currentColor" aria-hidden="true">
              <path
                d="M18.223 3.086a1.25 1.25 0 0 1 0 1.768L17.08 5.996h1.17A3.75 3.75 0 0 1 22 9.747v7.5a3.75 3.75 0 0 1-3.75 3.75H5.75A3.75 3.75 0 0 1 2 17.247v-7.5a3.75 3.75 0 0 1 3.75-3.75h1.166L5.775 4.855a1.25 1.25 0 1 1 1.767-1.77l2.652 2.654.1.258h3.411l.1-.258 2.654-2.653a1.25 1.25 0 0 1 1.768 0zM18.25 8.496H5.75a1.25 1.25 0 0 0-1.243 1.122l-.007.128v7.5c0 .643.487 1.172 1.112 1.243l.138.007h12.5a1.25 1.25 0 0 0 1.243-1.122l.007-.128v-7.5a1.25 1.25 0 0 0-1.25-1.25zM8.5 11a1.25 1.25 0 0 1 1.25 1.25v1.5a1.25 1.25 0 1 1-2.5 0v-1.5A1.25 1.25 0 0 1 8.5 11zm7 0a1.25 1.25 0 0 1 1.25 1.25v1.5a1.25 1.25 0 1 1-2.5 0v-1.5A1.25 1.25 0 0 1 15.5 11z"
              />
            </svg>
          </div>
          <div class="bp-hero__title">
            <h1>BiliBili 播放器</h1>
            <p>在你的博客里优雅地嵌入 B 站视频，支持高清画质、扫码登录、DASH 音画分离</p>
          </div>
        </div>
        <div class="bp-hero__meta">
          <VTag>v1.0</VTag>
          <VTag theme="primary">Halo 2.24+</VTag>
          <VTag v-if="loginUser" theme="primary">已登录</VTag>
          <VTag v-else theme="default">未登录</VTag>
        </div>
      </div>
    </header>

    <!-- 标签导航 -->
    <nav class="bp-tabs" role="tablist" aria-label="功能分区">
      <button
        v-for="t in tabs"
        :key="t.id"
        class="bp-tabs__item"
        :class="{ 'is-active': activeTabId === t.id }"
        role="tab"
        :aria-selected="activeTabId === t.id"
        @click="activeTabId = t.id"
      >
        <span class="bp-tabs__icon" aria-hidden="true">{{ t.icon }}</span>
        <span class="bp-tabs__label">{{ t.label }}</span>
      </button>
    </nav>

    <main class="bp-main">
      <!-- === 账号登录 === -->
      <section v-show="activeTabId === 'login'" class="bp-section">
        <!-- 未登录视图 -->
        <VCard v-if="!loginUser" class="bp-card bp-card--login">
          <template #header>
            <div class="bp-card__header">
              <div class="bp-card__title">
                <span class="bp-card__title-icon">🔐</span>
                <span>扫码登录 B 站账号</span>
              </div>
              <div class="bp-card__desc">
                登录后可获取高清画质视频，支持 1080P / 4K / DASH 音画分离播放
              </div>
            </div>
          </template>

          <div class="bp-login">
            <!-- 左：二维码 -->
            <div class="bp-login__qr">
              <div class="bp-qr">
                <div
                  class="bp-qr__frame"
                  :data-status="qrStatus"
                  role="img"
                  aria-label="登录二维码"
                >
                  <!-- 初始占位 -->
                  <div v-if="qrStatus === 'idle'" class="bp-qr__placeholder">
                    <div class="bp-qr__placeholder-icon">📱</div>
                    <div class="bp-qr__placeholder-text">点击按钮生成二维码</div>
                  </div>

                  <!-- 加载中 -->
                  <div v-else-if="qrStatus === 'loading'" class="bp-qr__placeholder">
                    <div class="bp-qr__spinner"></div>
                    <div class="bp-qr__placeholder-text">二维码生成中…</div>
                  </div>

                  <!-- 二维码 -->
                  <template v-else>
                    <img v-if="qrImage" :src="qrImage" alt="登录二维码" />

                    <!-- 遮罩：已扫描 -->
                    <div v-if="qrStatus === 'scanned'" class="bp-qr__mask bp-qr__mask--scanned">
                      <div class="bp-qr__mask-icon">✓</div>
                      <div class="bp-qr__mask-text">扫描成功</div>
                      <div class="bp-qr__mask-sub">请在手机上确认登录</div>
                    </div>
                    <!-- 遮罩：已过期 -->
                    <div v-if="qrStatus === 'expired'" class="bp-qr__mask bp-qr__mask--expired">
                      <div class="bp-qr__mask-icon">⟳</div>
                      <div class="bp-qr__mask-text">二维码已过期</div>
                      <VButton size="sm" type="primary" @click="genQr">重新生成</VButton>
                    </div>
                    <!-- 遮罩：成功 -->
                    <div v-if="qrStatus === 'success'" class="bp-qr__mask bp-qr__mask--success">
                      <div class="bp-qr__mask-icon">🎉</div>
                      <div class="bp-qr__mask-text">登录成功</div>
                    </div>
                  </template>
                </div>

                <div class="bp-qr__status" :data-status="qrStatus">
                  <span class="bp-qr__dot"></span>
                  <span>{{ qrStatusLabel || '等待操作' }}</span>
                </div>
              </div>
            </div>

            <!-- 右：引导 + 操作 -->
            <div class="bp-login__guide">
              <h3 class="bp-guide__title">登录步骤</h3>
              <ol class="bp-guide__steps">
                <li><span class="bp-guide__no">1</span>打开哔哩哔哩手机 App</li>
                <li><span class="bp-guide__no">2</span>点击右上角扫一扫图标</li>
                <li><span class="bp-guide__no">3</span>对准左侧二维码进行扫描</li>
                <li><span class="bp-guide__no">4</span>在手机上点击"确认登录"</li>
              </ol>

              <div class="bp-guide__actions">
                <VButton type="primary" :loading="qrStatus === 'loading'" @click="genQr">
                  {{
                    qrStatus === 'idle' || qrStatus === 'error'
                      ? '生成二维码'
                      : qrStatus === 'expired'
                        ? '重新生成'
                        : '刷新二维码'
                  }}
                </VButton>
                <span v-if="pollDebug" class="bp-guide__hint">{{ pollDebug }}</span>
              </div>

              <VAlert v-if="qrError" type="error" :title="'错误：' + qrError" :closable="false" />

              <div class="bp-guide__tips">
                <div class="bp-guide__tip-item">
                  <span class="bp-guide__tip-icon">💡</span>
                  登录信息仅保存在服务端，不会上传至任何第三方
                </div>
                <div class="bp-guide__tip-item">
                  <span class="bp-guide__tip-icon">🔒</span>
                  SESSDATA 使用 HttpOnly 方式加密存储
                </div>
              </div>
            </div>
          </div>
        </VCard>

        <!-- 已登录视图 -->
        <VCard v-else class="bp-card bp-card--user">
          <div class="bp-user">
            <div class="bp-user__avatar">
              <VAvatar :src="loginUser.face" :alt="loginUser.uname" size="lg" circle />
              <span class="bp-user__badge">Lv {{ loginUser.level }}</span>
            </div>
            <div class="bp-user__info">
              <div class="bp-user__name-row">
                <span class="bp-user__name">{{ loginUser.uname }}</span>
                <VTag v-if="vipLabel" class="bp-user__vip">{{ vipLabel }}</VTag>
              </div>
              <div class="bp-user__hint">登录状态良好，可以使用所有高清播放功能</div>
              <div class="bp-user__actions">
                <VSpace>
                  <VButton size="sm" type="primary" @click="activeTabId = 'embed'">
                    去生成嵌入代码
                  </VButton>
                  <VButton size="sm" type="default" @click="doLogout">退出登录</VButton>
                </VSpace>
              </div>
            </div>
          </div>
        </VCard>
      </section>

      <!-- === 视频嵌入 === -->
      <section v-show="activeTabId === 'embed'" class="bp-section">
        <VCard class="bp-card">
          <template #header>
            <div class="bp-card__header">
              <div class="bp-card__title">
                <span class="bp-card__title-icon">🎬</span>
                <span>生成视频嵌入代码</span>
              </div>
              <div class="bp-card__desc">输入 BV 号或 av 号，一键生成可嵌入文章的响应式代码</div>
            </div>
          </template>

          <div class="bp-embed">
            <!-- 输入区 -->
            <div class="bp-field">
              <label class="bp-field__label">BV 号 / av 号 / 视频链接</label>
              <div class="bp-field__row">
                <input
                  v-model="embedBvid"
                  class="bp-input"
                  placeholder="BV1xx411c7mD 或 https://www.bilibili.com/video/BV…"
                  @keyup.enter="fetchVideo"
                />
                <VButton type="primary" :loading="embedLoading" @click="fetchVideo">
                  获取视频
                </VButton>
              </div>
            </div>

            <!-- 视频信息卡片 -->
            <div v-if="videoInfo" class="bp-video">
              <img class="bp-video__cover" :src="videoInfo.pic" :alt="videoInfo.title" />
              <div class="bp-video__meta">
                <div class="bp-video__title">{{ videoInfo.title }}</div>
                <div class="bp-video__sub">
                  <span>UP 主：{{ videoInfo.ownerName }}</span>
                  <span v-if="videoInfo.width && videoInfo.height">
                    · 分辨率 {{ videoInfo.width }}×{{ videoInfo.height }}
                  </span>
                </div>
                <div v-if="videoInfo.pages && videoInfo.pages.length > 1" class="bp-video__pages">
                  <label class="bp-field__label">选择分 P</label>
                  <select
                    v-model="embedCid"
                    class="bp-input"
                    @change="generateCode(parseBvid(embedBvid)?.bvid || '', embedCid)"
                  >
                    <option v-for="p in videoInfo.pages" :key="p.cid" :value="p.cid">
                      P{{ p.page }} · {{ p.part }}
                    </option>
                  </select>
                </div>
              </div>
            </div>

            <!-- 尺寸设置 -->
            <div v-if="embedCode" class="bp-field">
              <label class="bp-field__label">嵌入宽度</label>
              <div class="bp-chip-group">
                <button
                  v-for="opt in widthOptions"
                  :key="opt.value"
                  class="bp-chip"
                  :class="{ 'is-active': embedWidth === opt.value }"
                  @click="onWidthChange(opt.value)"
                >
                  {{ opt.label }}
                </button>
              </div>
            </div>

            <!-- 代码输出 -->
            <div v-if="embedCode" class="bp-field">
              <div class="bp-field__label-row">
                <label class="bp-field__label">嵌入代码</label>
                <VButton size="sm" :type="copied ? 'default' : 'primary'" @click="copyCode">
                  {{ copied ? '✓ 已复制' : '复制代码' }}
                </VButton>
              </div>
              <pre class="bp-code"><code>{{ embedCode }}</code></pre>
            </div>

            <!-- 预览 -->
            <div v-if="embedPreview" class="bp-field">
              <label class="bp-field__label">效果预览</label>
              <div class="bp-preview">
                <iframe
                  :src="embedPreview"
                  allowfullscreen
                  allow="autoplay;encrypted-media"
                  loading="lazy"
                ></iframe>
              </div>
            </div>

            <VEmpty
              v-if="!videoInfo && !embedLoading"
              title="暂无视频信息"
              message="在上方输入 BV 号或粘贴 B 站视频链接后点击获取视频"
            />
          </div>
        </VCard>
      </section>

      <!-- === 使用说明 === -->
      <section v-show="activeTabId === 'help'" class="bp-section">
        <div class="bp-grid">
          <VCard class="bp-card bp-help">
            <template #header>
              <div class="bp-card__header">
                <div class="bp-card__title">
                  <span class="bp-card__title-icon">✨</span>
                  <span>特性一览</span>
                </div>
              </div>
            </template>
            <ul class="bp-list">
              <li><b>高清画质</b>：登录后支持 1080P/4K 等高清晰度视频</li>
              <li><b>DASH 音画分离</b>：自适应码率，节省带宽</li>
              <li><b>响应式嵌入</b>：根据视频原始比例自动铺满容器</li>
              <li><b>分 P 支持</b>：多分 P 视频可自由选择</li>
              <li><b>安全存储</b>：登录态仅存于服务端，前端无法读取</li>
            </ul>
          </VCard>

          <VCard class="bp-card bp-help">
            <template #header>
              <div class="bp-card__header">
                <div class="bp-card__title">
                  <span class="bp-card__title-icon">📌</span>
                  <span>使用步骤</span>
                </div>
              </div>
            </template>
            <ol class="bp-list bp-list--ol">
              <li>在"账号登录"扫码登录 B 站账号（可选，但推荐）</li>
              <li>切换到"视频嵌入"，粘贴 BV 号或视频链接</li>
              <li>点击"获取视频"，系统会解析视频信息</li>
              <li>选择嵌入宽度，复制代码粘贴到文章编辑器中</li>
            </ol>
          </VCard>

          <VCard class="bp-card bp-help">
            <template #header>
              <div class="bp-card__header">
                <div class="bp-card__title">
                  <span class="bp-card__title-icon">❓</span>
                  <span>常见问题</span>
                </div>
              </div>
            </template>
            <div class="bp-faq">
              <details class="bp-faq__item">
                <summary>为什么画质只有 360P？</summary>
                <p>未登录时 B 站限制了画质，请在"账号登录"处扫码登录后再生成嵌入代码。</p>
              </details>
              <details class="bp-faq__item">
                <summary>二维码一直显示未扫描？</summary>
                <p>请使用最新版 B 站手机 App 扫码；网络异常时可点击"重新生成"按钮。</p>
              </details>
              <details class="bp-faq__item">
                <summary>嵌入的视频比例不对？</summary>
                <p>本插件会自动根据视频原始分辨率设置比例，若异常请点击"获取视频"重新生成。</p>
              </details>
            </div>
          </VCard>
        </div>
      </section>
    </main>

    <!-- 浮动日志按钮 -->
    <button class="bp-logs-fab" :class="{ 'is-open': showLogs }" @click="toggleLogs">
      <span class="bp-logs-fab__icon">📋</span>
      <span class="bp-logs-fab__text">{{ showLogs ? '关闭日志' : '查看日志' }}</span>
    </button>

    <!-- 日志抽屉 -->
    <Transition name="drawer">
      <aside v-if="showLogs" class="bp-drawer" role="dialog" aria-label="运行日志">
        <header class="bp-drawer__header">
          <div class="bp-drawer__title">
            <span>📋</span>
            <span>运行日志</span>
            <span class="bp-drawer__count">{{ filteredLogs.length }} 条</span>
          </div>
          <div class="bp-drawer__actions">
            <div class="bp-chip-group bp-chip-group--sm">
              <button
                v-for="lv in (['ALL', 'INFO', 'WARN', 'ERROR', 'DEBUG'] as const)"
                :key="lv"
                class="bp-chip bp-chip--sm"
                :class="{ 'is-active': logFilter === lv }"
                @click="logFilter = lv"
              >
                {{ lv }}
              </button>
            </div>
            <VSwitch v-model="logAutoScroll" />
            <span class="bp-drawer__hint">自动滚动</span>
            <VButton size="xs" @click="clearLogs">清空</VButton>
            <VButton size="xs" type="default" @click="toggleLogs">✕</VButton>
          </div>
        </header>
        <div ref="logContainer" class="bp-drawer__body">
          <VEmpty v-if="filteredLogs.length === 0" title="暂无日志" message="操作后将显示日志" />
          <div v-for="(l, i) in filteredLogs" :key="i" class="bp-log" :data-level="l.level">
            <span class="bp-log__time">{{ l.time }}</span>
            <span class="bp-log__level">{{ l.level }}</span>
            <span class="bp-log__msg">{{ l.msg }}</span>
          </div>
        </div>
      </aside>
    </Transition>
  </div>
</template>

<style lang="scss" scoped>
/* ============================================================
   BiliBili 播放器插件 — 美化版 UI
   设计：浅色为主、B 站粉为主色、卡片化、清晰排版
   适配：Halo 控制台亮/暗主题
   ============================================================ */

/* ---------- 主题变量 ---------- */
.bp {
  --bp-pink: #fb7299;
  --bp-pink-soft: #ffeaf2;
  --bp-pink-deep: #e85a82;
  --bp-blue: #00a1d6;
  --bp-blue-soft: #e6f7fd;
  --bp-text: #1f2329;
  --bp-text-2: #4e5969;
  --bp-text-3: #86909c;
  --bp-bg: #f7f8fa;
  --bp-surface: #ffffff;
  --bp-border: #e5e6eb;
  --bp-border-strong: #c9cdd4;
  --bp-success: #00b42a;
  --bp-warn: #ff7d00;
  --bp-danger: #f53f3f;
  --bp-radius-sm: 6px;
  --bp-radius: 10px;
  --bp-radius-lg: 14px;
  --bp-shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.04);
  --bp-shadow: 0 6px 24px rgba(20, 30, 50, 0.06);
  --bp-shadow-lg: 0 12px 40px rgba(20, 30, 50, 0.12);
  --bp-ease: cubic-bezier(0.22, 0.61, 0.36, 1);

  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 20px 22px 100px;
  max-width: 1180px;
  margin: 0 auto;
  width: 100%;
  color: var(--bp-text);
  font-size: 14px;
  line-height: 1.55;
  -webkit-font-smoothing: antialiased;

  *,
  *::before,
  *::after {
    box-sizing: border-box;
  }

  @media (max-width: 640px) {
    padding: 12px 12px 96px;
    gap: 14px;
  }
}

/* 暗色主题（跟随 Halo 控制台 .dark）*/
:global(.dark) .bp,
.bp:where([data-theme='dark']) {
  --bp-text: #e8eaed;
  --bp-text-2: #c5c8ce;
  --bp-text-3: #8a8f99;
  --bp-bg: #14161a;
  --bp-surface: #1c1f24;
  --bp-border: #2a2e35;
  --bp-border-strong: #3a3f47;
  --bp-pink-soft: rgba(251, 114, 153, 0.14);
  --bp-blue-soft: rgba(0, 161, 214, 0.14);
  --bp-shadow: 0 6px 24px rgba(0, 0, 0, 0.35);
  --bp-shadow-lg: 0 12px 40px rgba(0, 0, 0, 0.5);
}

/* ---------- 顶部 Hero ---------- */
.bp-hero {
  position: relative;
  border-radius: var(--bp-radius-lg);
  overflow: hidden;
  background: var(--bp-surface);
  box-shadow: var(--bp-shadow);
  border: 1px solid var(--bp-border);

  &__bg {
    position: absolute;
    inset: 0;
    background:
      radial-gradient(circle at 12% 20%, rgba(251, 114, 153, 0.18), transparent 45%),
      radial-gradient(circle at 90% 80%, rgba(0, 161, 214, 0.16), transparent 45%),
      linear-gradient(135deg, rgba(251, 114, 153, 0.05), rgba(0, 161, 214, 0.05));
    pointer-events: none;
  }

  &__inner {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 20px;
    padding: 28px 28px;
    flex-wrap: wrap;
  }

  &__brand {
    display: flex;
    align-items: center;
    gap: 16px;
    min-width: 0;
  }

  &__logo {
    flex-shrink: 0;
    width: 52px;
    height: 52px;
    display: grid;
    place-items: center;
    color: #fff;
    background: linear-gradient(135deg, var(--bp-pink), var(--bp-pink-deep));
    border-radius: 14px;
    box-shadow: 0 8px 20px rgba(251, 114, 153, 0.35);
  }

  &__title h1 {
    margin: 0 0 4px;
    font-size: 22px;
    font-weight: 700;
    letter-spacing: 0.2px;
    color: var(--bp-text);
  }

  &__title p {
    margin: 0;
    color: var(--bp-text-2);
    font-size: 13px;
    max-width: 560px;
  }

  &__meta {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }

  @media (max-width: 640px) {
    &__inner {
      padding: 20px 18px;
    }
    &__title h1 {
      font-size: 18px;
    }
  }
}

/* ---------- 标签导航 ---------- */
.bp-tabs {
  display: flex;
  gap: 6px;
  padding: 6px;
  background: var(--bp-surface);
  border: 1px solid var(--bp-border);
  border-radius: 999px;
  box-shadow: var(--bp-shadow-sm);
  width: fit-content;
  max-width: 100%;
  overflow-x: auto;

  &__item {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 9px 18px;
    border: none;
    background: transparent;
    color: var(--bp-text-2);
    font-size: 13px;
    font-weight: 500;
    border-radius: 999px;
    cursor: pointer;
    white-space: nowrap;
    transition: all 0.2s var(--bp-ease);

    &:hover {
      color: var(--bp-text);
      background: var(--bp-bg);
    }

    &.is-active {
      color: #fff;
      background: linear-gradient(135deg, var(--bp-pink), var(--bp-pink-deep));
      box-shadow: 0 4px 12px rgba(251, 114, 153, 0.4);
    }
  }

  &__icon {
    font-size: 14px;
  }
}

/* ---------- 主体区域 ---------- */
.bp-main {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.bp-section {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ---------- 卡片 ---------- */
.bp-card {
  border-radius: var(--bp-radius-lg) !important;
  border: 1px solid var(--bp-border) !important;
  box-shadow: var(--bp-shadow) !important;
  overflow: hidden;
  background: var(--bp-surface) !important;

  &__header {
    padding: 4px 0;
  }

  &__title {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 16px;
    font-weight: 600;
    color: var(--bp-text);
  }

  &__title-icon {
    font-size: 18px;
  }

  &__desc {
    margin-top: 6px;
    color: var(--bp-text-3);
    font-size: 13px;
  }
}

/* ---------- 登录区 ---------- */
.bp-login {
  display: grid;
  grid-template-columns: minmax(280px, 320px) 1fr;
  gap: 32px;
  padding: 8px 4px;

  @media (max-width: 768px) {
    grid-template-columns: 1fr;
    gap: 24px;
  }
}

.bp-qr {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;

  &__frame {
    position: relative;
    width: 240px;
    height: 240px;
    padding: 10px;
    background: #fff;
    border: 1px solid var(--bp-border);
    border-radius: var(--bp-radius);
    box-shadow: var(--bp-shadow-sm);
    display: grid;
    place-items: center;
    transition: border-color 0.3s var(--bp-ease);

    img {
      width: 100%;
      height: 100%;
      display: block;
      border-radius: 6px;
    }

    &[data-status='scanned'] {
      border-color: var(--bp-warn);
    }
    &[data-status='success'] {
      border-color: var(--bp-success);
    }
    &[data-status='expired'] {
      border-color: var(--bp-danger);
    }
  }

  &__placeholder {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 10px;
    color: var(--bp-text-3);
    font-size: 13px;
    text-align: center;
    width: 100%;
    height: 100%;
    border: 1.5px dashed var(--bp-border-strong);
    border-radius: 8px;
  }

  &__placeholder-icon {
    font-size: 38px;
  }

  &__spinner {
    width: 32px;
    height: 32px;
    border: 3px solid var(--bp-border);
    border-top-color: var(--bp-pink);
    border-radius: 50%;
    animation: bp-spin 0.8s linear infinite;
  }

  &__mask {
    position: absolute;
    inset: 0;
    border-radius: var(--bp-radius);
    backdrop-filter: blur(4px);
    background: rgba(255, 255, 255, 0.85);
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 8px;
    color: var(--bp-text);
    animation: bp-fade 0.25s var(--bp-ease);

    &-icon {
      font-size: 44px;
      line-height: 1;
    }

    &-text {
      font-size: 15px;
      font-weight: 600;
    }

    &-sub {
      font-size: 12px;
      color: var(--bp-text-3);
    }

    &--scanned &-icon {
      color: var(--bp-warn);
    }
    &--success &-icon {
      color: var(--bp-success);
    }
    &--expired &-icon {
      color: var(--bp-danger);
    }
  }

  &__status {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 6px 14px;
    background: var(--bp-bg);
    border-radius: 999px;
    font-size: 12px;
    color: var(--bp-text-2);

    &[data-status='success'] {
      color: var(--bp-success);
      background: rgba(0, 180, 42, 0.1);
    }
    &[data-status='expired'],
    &[data-status='error'] {
      color: var(--bp-danger);
      background: rgba(245, 63, 63, 0.1);
    }
    &[data-status='scanned'] {
      color: var(--bp-warn);
      background: rgba(255, 125, 0, 0.1);
    }
  }

  &__dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: currentColor;
    box-shadow: 0 0 0 0 currentColor;
    animation: bp-pulse 1.6s var(--bp-ease) infinite;
  }
}

.bp-guide {
  &__title {
    margin: 0 0 14px;
    font-size: 15px;
    font-weight: 600;
    color: var(--bp-text);
  }

  &__steps {
    list-style: none;
    margin: 0 0 18px;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 10px;

    li {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 10px 14px;
      background: var(--bp-bg);
      border-radius: var(--bp-radius);
      border: 1px solid transparent;
      color: var(--bp-text-2);
      font-size: 13px;
      transition: all 0.2s var(--bp-ease);

      &:hover {
        border-color: var(--bp-pink-soft);
        background: var(--bp-pink-soft);
      }
    }
  }

  &__no {
    flex-shrink: 0;
    width: 22px;
    height: 22px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    background: linear-gradient(135deg, var(--bp-pink), var(--bp-pink-deep));
    color: #fff;
    font-size: 11px;
    font-weight: 700;
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 14px;
    flex-wrap: wrap;
  }

  &__hint {
    font-size: 11px;
    color: var(--bp-text-3);
    font-family: 'JetBrains Mono', 'Fira Code', monospace;
  }

  &__tips {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin-top: 14px;
    padding-top: 14px;
    border-top: 1px dashed var(--bp-border);
  }

  &__tip-item {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 12px;
    color: var(--bp-text-3);
  }

  &__tip-icon {
    font-size: 14px;
  }
}

/* ---------- 已登录用户卡片 ---------- */
.bp-user {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 8px 4px;

  &__avatar {
    position: relative;
    flex-shrink: 0;
  }

  &__badge {
    position: absolute;
    bottom: -4px;
    right: -6px;
    padding: 2px 7px;
    background: linear-gradient(135deg, var(--bp-pink), var(--bp-pink-deep));
    color: #fff;
    font-size: 10px;
    font-weight: 700;
    border-radius: 999px;
    border: 2px solid var(--bp-surface);
  }

  &__info {
    flex: 1;
    min-width: 0;
  }

  &__name-row {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }

  &__name {
    font-size: 18px;
    font-weight: 600;
    color: var(--bp-text);
  }

  &__vip :deep(.tag) {
    background: linear-gradient(135deg, #ffb84d, #ff7d00);
    color: #fff;
    border: none;
  }

  &__hint {
    margin-top: 4px;
    color: var(--bp-text-3);
    font-size: 13px;
  }

  &__actions {
    margin-top: 14px;
  }

  @media (max-width: 640px) {
    flex-direction: column;
    align-items: flex-start;
  }
}

/* ---------- 嵌入区 ---------- */
.bp-embed {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.bp-field {
  display: flex;
  flex-direction: column;
  gap: 8px;

  &__label {
    font-size: 12px;
    font-weight: 600;
    color: var(--bp-text-2);
    letter-spacing: 0.3px;
  }

  &__label-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    flex-wrap: wrap;
  }

  &__row {
    display: flex;
    gap: 10px;

    @media (max-width: 640px) {
      flex-direction: column;
    }
  }
}

.bp-input {
  flex: 1;
  height: 38px;
  padding: 0 14px;
  font-size: 13px;
  font-family: inherit;
  color: var(--bp-text);
  background: var(--bp-surface);
  border: 1px solid var(--bp-border);
  border-radius: var(--bp-radius);
  outline: none;
  transition: all 0.2s var(--bp-ease);

  &::placeholder {
    color: var(--bp-text-3);
  }

  &:hover {
    border-color: var(--bp-border-strong);
  }

  &:focus {
    border-color: var(--bp-pink);
    box-shadow: 0 0 0 3px rgba(251, 114, 153, 0.15);
  }
}

select.bp-input {
  cursor: pointer;
  appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%2386909c' stroke-width='2.5' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 12px center;
  padding-right: 36px;
}

.bp-chip-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;

  &--sm {
    gap: 4px;
  }
}

.bp-chip {
  padding: 6px 14px;
  font-size: 12px;
  font-family: inherit;
  color: var(--bp-text-2);
  background: var(--bp-bg);
  border: 1px solid var(--bp-border);
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.18s var(--bp-ease);

  &:hover {
    color: var(--bp-text);
    border-color: var(--bp-border-strong);
  }

  &.is-active {
    color: #fff;
    background: var(--bp-pink);
    border-color: var(--bp-pink);
    box-shadow: 0 2px 8px rgba(251, 114, 153, 0.3);
  }

  &--sm {
    padding: 3px 10px;
    font-size: 11px;
  }
}

/* 视频信息卡 */
.bp-video {
  display: grid;
  grid-template-columns: 200px 1fr;
  gap: 16px;
  padding: 14px;
  background: var(--bp-bg);
  border-radius: var(--bp-radius);
  border: 1px solid var(--bp-border);

  @media (max-width: 640px) {
    grid-template-columns: 1fr;
  }

  &__cover {
    width: 100%;
    aspect-ratio: 16 / 10;
    object-fit: cover;
    border-radius: 8px;
    background: var(--bp-border);
    box-shadow: var(--bp-shadow-sm);
  }

  &__meta {
    display: flex;
    flex-direction: column;
    gap: 8px;
    min-width: 0;
  }

  &__title {
    font-size: 15px;
    font-weight: 600;
    color: var(--bp-text);
    line-height: 1.5;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  &__sub {
    color: var(--bp-text-3);
    font-size: 12px;
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }

  &__pages {
    margin-top: auto;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
}

/* 代码块 */
.bp-code {
  margin: 0;
  padding: 14px 16px;
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.7;
  color: var(--bp-text);
  background: var(--bp-bg);
  border: 1px solid var(--bp-border);
  border-radius: var(--bp-radius);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow: auto;
}

/* 预览 */
.bp-preview {
  width: 100%;
  aspect-ratio: 16 / 9;
  border-radius: var(--bp-radius);
  overflow: hidden;
  border: 1px solid var(--bp-border);
  background: #000;

  iframe {
    width: 100%;
    height: 100%;
    border: none;
    display: block;
  }
}

/* ---------- 帮助页 ---------- */
.bp-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 16px;
}

.bp-list {
  margin: 0;
  padding: 0 0 0 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  color: var(--bp-text-2);
  font-size: 13px;

  li::marker {
    color: var(--bp-pink);
  }

  b {
    color: var(--bp-text);
    font-weight: 600;
  }
}

.bp-faq {
  display: flex;
  flex-direction: column;
  gap: 8px;

  &__item {
    border: 1px solid var(--bp-border);
    border-radius: var(--bp-radius);
    padding: 10px 14px;
    background: var(--bp-bg);
    transition: border-color 0.2s var(--bp-ease);

    &[open] {
      border-color: var(--bp-pink);
      background: var(--bp-pink-soft);
    }

    summary {
      cursor: pointer;
      font-weight: 500;
      color: var(--bp-text);
      font-size: 13px;
      list-style: none;
      display: flex;
      align-items: center;
      gap: 8px;

      &::before {
        content: '▸';
        color: var(--bp-pink);
        transition: transform 0.2s var(--bp-ease);
      }
    }

    &[open] summary::before {
      transform: rotate(90deg);
    }

    p {
      margin: 8px 0 0;
      padding-top: 8px;
      border-top: 1px dashed var(--bp-border);
      color: var(--bp-text-2);
      font-size: 12.5px;
      line-height: 1.7;
    }
  }
}

/* ---------- 浮动日志按钮 ---------- */
.bp-logs-fab {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 99;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  font-family: inherit;
  font-size: 13px;
  font-weight: 500;
  color: #fff;
  background: linear-gradient(135deg, var(--bp-pink), var(--bp-pink-deep));
  border: none;
  border-radius: 999px;
  box-shadow: 0 8px 24px rgba(251, 114, 153, 0.4);
  cursor: pointer;
  transition: transform 0.18s var(--bp-ease), box-shadow 0.18s var(--bp-ease);

  &:hover {
    transform: translateY(-2px);
    box-shadow: 0 12px 28px rgba(251, 114, 153, 0.5);
  }

  &.is-open {
    background: var(--bp-text);
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
  }

  &__icon {
    font-size: 16px;
  }

  @media (max-width: 640px) {
    right: 14px;
    bottom: 14px;
    padding: 9px 14px;
  }
}

/* ---------- 日志抽屉 ---------- */
.bp-drawer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 98;
  height: 320px;
  background: var(--bp-surface);
  border-top: 1px solid var(--bp-border);
  box-shadow: 0 -12px 40px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;

  @media (max-width: 640px) {
    height: 240px;
  }

  &__header {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 10px 16px;
    background: var(--bp-bg);
    border-bottom: 1px solid var(--bp-border);
    flex-wrap: wrap;
  }

  &__title {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;
    color: var(--bp-text);
    font-size: 13px;
  }

  &__count {
    padding: 1px 8px;
    background: var(--bp-pink-soft);
    color: var(--bp-pink-deep);
    font-size: 11px;
    font-weight: 600;
    border-radius: 999px;
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }

  &__hint {
    font-size: 11px;
    color: var(--bp-text-3);
  }

  &__body {
    flex: 1;
    overflow-y: auto;
    padding: 6px 0;
    font-family: 'JetBrains Mono', 'Fira Code', monospace;
    font-size: 12px;
  }
}

.bp-log {
  display: grid;
  grid-template-columns: 70px 56px 1fr;
  gap: 10px;
  padding: 4px 16px;
  align-items: baseline;
  border-bottom: 1px solid rgba(0, 0, 0, 0.02);

  &:hover {
    background: var(--bp-bg);
  }

  &__time {
    color: var(--bp-text-3);
    font-size: 11px;
  }

  &__level {
    font-size: 10px;
    font-weight: 700;
    text-align: center;
    padding: 1px 0;
    border-radius: 4px;
  }

  &__msg {
    color: var(--bp-text-2);
    word-break: break-all;
    line-height: 1.55;
  }

  &[data-level='ERROR'] {
    background: rgba(245, 63, 63, 0.06);
    .bp-log__level {
      color: #fff;
      background: var(--bp-danger);
    }
  }
  &[data-level='WARN'] .bp-log__level {
    color: #fff;
    background: var(--bp-warn);
  }
  &[data-level='INFO'] .bp-log__level {
    color: #fff;
    background: var(--bp-blue);
  }
  &[data-level='DEBUG'] .bp-log__level {
    color: var(--bp-text-2);
    background: var(--bp-border);
  }
}

/* ---------- 过渡动画 ---------- */
.drawer-enter-active,
.drawer-leave-active {
  transition: transform 0.28s var(--bp-ease);
}
.drawer-enter-from,
.drawer-leave-to {
  transform: translateY(100%);
}

@keyframes bp-spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes bp-pulse {
  0% {
    box-shadow: 0 0 0 0 currentColor;
    opacity: 1;
  }
  70% {
    box-shadow: 0 0 0 8px transparent;
    opacity: 0.6;
  }
  100% {
    box-shadow: 0 0 0 0 transparent;
    opacity: 1;
  }
}

@keyframes bp-fade {
  from {
    opacity: 0;
    transform: scale(0.96);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}
</style>
