<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { nodeViewProps, NodeViewWrapper } from '@halo-dev/richtext-editor'
import { axiosInstance } from '@halo-dev/api-client'
import { Toast, VButton, VModal } from '@halo-dev/components'
import RiBilibiliLine from '~icons/ri/bilibili-line'
import { API_BASE, parseBvid, parseData, proxyImage } from '@/utils/bilibili'

interface VideoPage {
  cid: number
  page: number
  part: string
}
interface VideoInfo {
  title: string
  pic: string
  ownerName: string
  duration?: number
  pages?: VideoPage[]
  error?: string
}

const props = defineProps(nodeViewProps)

const bvid = computed(() => (props.node.attrs.bvid as string) || '')

// 横竖屏标签沿用节点属性中的分辨率（配置时由 playurl 写入）
const orientation = computed(() => {
  const w = Number(props.node.attrs.width)
  const h = Number(props.node.attrs.height)
  if (w <= 0 || h <= 0) return ''
  return h > w ? '竖屏' : '横屏'
})

/* ---------- 节点卡片信息 ---------- */
const videoInfo = ref<VideoInfo | null>(null)
const infoLoading = ref(false)
const coverFailed = ref(false)
const coverSrc = computed(() =>
  videoInfo.value?.pic && !coverFailed.value ? proxyImage(videoInfo.value.pic) : '',
)
const durationText = computed(() => {
  const d = Number(videoInfo.value?.duration)
  if (!Number.isFinite(d) || d <= 0) return ''
  const m = Math.floor(d / 60)
  const s = Math.floor(d % 60)
  return `${m}:${String(s).padStart(2, '0')}`
})

async function fetchVideoInfo(bv: string): Promise<VideoInfo> {
  const { data } = await axiosInstance.get(`${API_BASE}/video/info?bvid=${bv}`)
  const info = parseData<VideoInfo>(data)
  if (info.error) throw new Error(info.error)
  return info
}

async function loadCardInfo() {
  if (!bvid.value) return
  coverFailed.value = false
  infoLoading.value = true
  try {
    videoInfo.value = await fetchVideoInfo(bvid.value)
  } catch {
    // 卡片信息拉取失败不阻塞编辑，仅退化为展示 bvid
    videoInfo.value = null
  } finally {
    infoLoading.value = false
  }
}

/* ---------- 配置对话框 ---------- */
const dialogVisible = ref(false)
const input = ref('')
const resolving = ref(false)
const dialogInfo = ref<VideoInfo | null>(null)
const dialogBvid = ref('')
const selectedCid = ref<number | ''>('')
const confirming = ref(false)

function resetDialog() {
  input.value = bvid.value
  dialogInfo.value = null
  dialogBvid.value = ''
  selectedCid.value = ''
  resolving.value = false
  confirming.value = false
}

function openDialog() {
  resetDialog()
  dialogVisible.value = true
}

async function resolveVideo() {
  const parsed = parseBvid(input.value)
  if (!parsed) {
    Toast.error('无法识别 BV 号或链接，请检查输入')
    return
  }
  resolving.value = true
  dialogInfo.value = null
  try {
    const info = await fetchVideoInfo(parsed.bvid)
    const firstCid = info.pages?.[0]?.cid
    if (!firstCid) throw new Error('未获取到分 P 信息')
    dialogInfo.value = info
    dialogBvid.value = parsed.bvid
    selectedCid.value = firstCid
  } catch (e) {
    Toast.error(`解析失败：${e instanceof Error && e.message ? e.message : '请检查 BV 号或链接'}`)
  } finally {
    resolving.value = false
  }
}

async function confirm() {
  if (!dialogBvid.value || !selectedCid.value) return
  confirming.value = true
  try {
    // 取 playurl 的 dash.video[0] 分辨率，用于渲染真实 aspect-ratio；失败时回退 16:9
    let width = 0
    let height = 0
    try {
      const { data } = await axiosInstance.get(
        `${API_BASE}/video/playurl?bvid=${dialogBvid.value}&cid=${selectedCid.value}&qn=80&fnval=16`,
      )
      const track = parseData<{ dash?: { video?: Array<{ width?: number; height?: number }> } }>(
        data,
      )?.dash?.video?.[0]
      width = Number(track?.width) || 0
      height = Number(track?.height) || 0
    } catch {
      /* 分辨率获取失败时按 16:9 渲染 */
    }
    props.updateAttributes({
      bvid: dialogBvid.value,
      cid: String(selectedCid.value),
      width,
      height,
    })
    dialogVisible.value = false
  } catch (e) {
    Toast.error(`保存失败：${e instanceof Error && e.message ? e.message : '未知错误'}`)
  } finally {
    confirming.value = false
  }
}

watch(bvid, () => loadCardInfo())

onMounted(() => {
  if (bvid.value) loadCardInfo()
  else openDialog() // 刚插入的空节点自动弹出配置
})
</script>

<template>
  <NodeViewWrapper class="bilibili-player-node">
    <div v-if="bvid" class="bp-node-card" title="双击重新配置" @dblclick="openDialog">
      <div class="bp-node-card__cover" :class="{ 'bp-node-card__cover--loading': infoLoading }">
        <template v-if="!infoLoading">
          <img
            v-if="coverSrc"
            :src="coverSrc"
            :alt="videoInfo?.title"
            @error="coverFailed = true"
          />
          <div v-else class="bp-node-card__cover-fallback">
            <RiBilibiliLine />
          </div>
        </template>
        <span class="bp-node-card__badge"><RiBilibiliLine />B站</span>
        <span class="bp-node-card__play">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
            <path d="M8 5.14v14l11-7-11-7z" />
          </svg>
        </span>
      </div>
      <div class="bp-node-card__meta">
        <template v-if="infoLoading">
          <div class="bp-skeleton-bar" style="width: 70%"></div>
          <div class="bp-skeleton-bar" style="width: 45%"></div>
        </template>
        <template v-else>
          <div class="bp-node-card__title">{{ videoInfo?.title || bvid }}</div>
          <div class="bp-node-card__sub">
            <span>{{ videoInfo?.ownerName || '未知 UP 主' }}</span>
            <template v-if="durationText">
              <span class="bp-node-card__dot">&middot;</span>
              <span>{{ durationText }}</span>
            </template>
            <span v-if="orientation" class="bp-node-card__ori">{{ orientation }}</span>
          </div>
          <div class="bp-node-card__bvid">{{ bvid }}</div>
        </template>
      </div>
    </div>
    <div v-else class="bp-node-placeholder" @dblclick="openDialog">
      <RiBilibiliLine class="bp-node-placeholder__icon" />
      <span>B站视频 · 双击配置</span>
    </div>

    <VModal
      v-model:visible="dialogVisible"
      title="插入 B 站视频"
      :width="520"
      :mount-to-body="true"
    >
      <div class="bp-dialog">
        <div class="bp-dialog__row">
          <input
            v-model="input"
            class="bp-dialog__input"
            placeholder="BV1xx411c7mD 或 https://www.bilibili.com/video/BV..."
            @keyup.enter="resolveVideo"
          />
          <VButton type="primary" :loading="resolving" @click="resolveVideo">解析</VButton>
        </div>
        <div v-if="dialogInfo" class="bp-dialog__info">
          <img class="bp-dialog__cover" :src="proxyImage(dialogInfo.pic)" :alt="dialogInfo.title" />
          <div class="bp-dialog__meta">
            <div class="bp-dialog__title">{{ dialogInfo.title }}</div>
            <div class="bp-dialog__up">UP 主：{{ dialogInfo.ownerName }}</div>
            <select
              v-if="dialogInfo.pages && dialogInfo.pages.length > 1"
              v-model="selectedCid"
              class="bp-dialog__input bp-dialog__pages"
            >
              <option v-for="p in dialogInfo.pages" :key="p.cid" :value="p.cid">
                P{{ p.page }} · {{ p.part }}
              </option>
            </select>
          </div>
        </div>
      </div>
      <template #footer>
        <VButton
          type="primary"
          :disabled="!dialogInfo || !selectedCid"
          :loading="confirming"
          @click="confirm"
        >
          插入视频
        </VButton>
      </template>
    </VModal>
  </NodeViewWrapper>
</template>

<style scoped>
.bilibili-player-node {
  margin: 8px 0;
}

/* 已配置：紧凑横向信息卡（固定高度，不受视频宽高比影响） */
.bp-node-card {
  display: flex;
  align-items: stretch;
  height: 110px;
  background: #fff;
  border: 1px solid rgb(234, 236, 240);
  border-radius: 8px;
  overflow: hidden;
  cursor: default;
  user-select: none;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}
.bp-node-card:hover {
  border-color: rgba(251, 114, 153, 0.45);
  box-shadow: 0 2px 10px rgba(251, 114, 153, 0.12);
}

.bp-node-card__cover {
  position: relative;
  width: 180px;
  flex-shrink: 0;
  background: #f7f8fa;
  overflow: hidden;
}
.bp-node-card__cover--loading {
  animation: bp-skeleton 1.4s ease-in-out infinite;
}
.bp-node-card__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.bp-node-card__cover-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 30px;
  color: #fb7299;
  opacity: 0.45;
  background: #f7f8fa;
}
.bp-node-card__badge {
  position: absolute;
  right: 6px;
  bottom: 6px;
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 6px;
  font-size: 11px;
  line-height: 1.4;
  color: #fff;
  background: rgba(251, 114, 153, 0.92);
  border-radius: 4px;
}
.bp-node-card__play {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding-left: 2px;
  color: #fff;
  background: rgba(0, 0, 0, 0.45);
  border-radius: 50%;
  opacity: 0;
  transition: opacity 0.15s ease;
  pointer-events: none;
}
.bp-node-card:hover .bp-node-card__play {
  opacity: 1;
}

.bp-node-card__meta {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 6px;
  min-width: 0;
  flex: 1;
  padding: 12px 16px;
}
.bp-node-card__title {
  font-size: 14px;
  font-weight: 600;
  color: #1f2329;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.bp-node-card__sub {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #86909c;
  white-space: nowrap;
  overflow: hidden;
}
.bp-node-card__dot {
  color: #c9cdd4;
}
.bp-node-card__ori {
  padding: 1px 6px;
  font-size: 11px;
  color: #fb7299;
  border: 1px solid rgba(251, 114, 153, 0.4);
  border-radius: 4px;
}
.bp-node-card__bvid {
  font-size: 11px;
  color: #c9cdd4;
  font-family: 'JetBrains Mono', monospace;
}

/* 骨架屏 */
.bp-skeleton-bar {
  height: 14px;
  border-radius: 4px;
  background: rgb(234, 236, 240);
  animation: bp-skeleton 1.4s ease-in-out infinite;
}
@keyframes bp-skeleton {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.45;
  }
}

/* 未配置：占位卡 */
.bp-node-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 110px;
  font-size: 13px;
  color: rgb(100, 116, 139);
  background: #fff;
  border: 1px dashed rgb(234, 236, 240);
  border-radius: 8px;
  cursor: default;
  user-select: none;
  transition:
    border-color 0.15s ease,
    color 0.15s ease;
}
.bp-node-placeholder:hover {
  color: #fb7299;
  border-color: rgba(251, 114, 153, 0.45);
}
.bp-node-placeholder__icon {
  font-size: 24px;
}

.bp-dialog {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.bp-dialog__row {
  display: flex;
  gap: 8px;
}
.bp-dialog__input {
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
.bp-dialog__input:focus {
  border-color: var(--color-primary, #4ccba0);
  box-shadow: 0 0 0 3px rgba(76, 203, 160, 0.15);
}
.bp-dialog__info {
  display: flex;
  gap: 12px;
  padding: 12px;
  background: #f7f8fa;
  border: 1px solid rgb(234, 236, 240);
  border-radius: 4px;
}
.bp-dialog__cover {
  width: 140px;
  aspect-ratio: 16/10;
  object-fit: cover;
  border-radius: 4px;
  flex-shrink: 0;
  background: rgb(234, 236, 240);
}
.bp-dialog__meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  flex: 1;
}
.bp-dialog__title {
  font-size: 13px;
  font-weight: 600;
  color: #1f2329;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.bp-dialog__up {
  font-size: 12px;
  color: #86909c;
}
.bp-dialog__pages {
  margin-top: auto;
  width: 100%;
}
</style>
