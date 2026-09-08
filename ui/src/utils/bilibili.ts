export const API_BASE = '/plugins/bilibili-player/api'
export const BVID_REGEX = /BV[a-zA-Z0-9]{10}/
export const AVID_REGEX = /av(\d+)/i

/** 从用户输入（BV 号 / av 号 / 视频链接）解析 bvid */
export function parseBvid(input: string) {
  const trimmed = (input || '').trim()
  if (!trimmed) return null
  const m = trimmed.match(BVID_REGEX)
  if (m) return { bvid: m[0], cid: '' }
  const a = trimmed.match(AVID_REGEX)
  if (a) return { bvid: 'av' + a[1], cid: '' }
  return null
}

/** 后端接口偶尔返回字符串化的 JSON，统一解析 */
export function parseData<T = unknown>(data: unknown): T {
  return (typeof data === 'string' ? JSON.parse(data) : data) as T
}

/** B 站图片 CDN 走插件代理（升级 HTTPS 避免混合内容 + 追加缩略图后缀减少流量） */
export function proxyImage(url: string | undefined | null): string {
  if (!url) return ''
  let secure = url.startsWith('http://') ? 'https://' + url.substring(7) : url
  if (!secure.includes('@') && (secure.includes('hdslb.com') || secure.includes('bilibili.com'))) {
    secure = secure + '@320w_200h_1e_1c'
  }
  return `${API_BASE}/video/proxy?url=${encodeURIComponent(secure)}`
}

/** 播放量等数字格式化（万/亿） */
export function formatNum(n: number): string {
  if (!Number.isFinite(n) || n < 0) return '0'
  if (n >= 1e8) return (n / 1e8).toFixed(1).replace(/\.0$/, '') + '亿'
  if (n >= 1e4) return (n / 1e4).toFixed(1).replace(/\.0$/, '') + '万'
  return String(n)
}
