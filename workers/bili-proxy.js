// BiliBili CDN 代理 Worker —— 部署到 Cloudflare Workers 后，在插件设置页填写其地址即可。
// 功能：伪装 Referer/Origin 拉取 B 站 CDN 流，透传 Range（拖动进度条），流式回传。
// 缓存：2MB 块对齐边缘缓存（caches.default），Range 归一化到块边界，命中免回源；
//       B 站流 URL 120 分钟过期，缓存块带时间戳，超 100 分钟自动失效重取。
// 安全：仅放行 B 站相关域名；可选共享密钥（Workers 密钥变量 PROXY_TOKEN）。

const ALLOWED_HOSTS = [
  '.bilivideo.com', '.bilivideo.cn', '.mcdn.bilivideo.cn',
  '.biliapi.net', '.hdslb.com', '.bilibili.com',
]
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

const BLOCK = 2 * 1024 * 1024 // 2MB 缓存块
const BLOCK_CACHE_MAX_AGE_MS = 100 * 60 * 1000 // 流 URL 120min 过期，留 20min 余量
const MAX_CACHEABLE_TOTAL = 500 * 1024 * 1024 // CF 免费版单文件缓存上限 512MB，留余量
const MAX_ASSEMBLE_BLOCKS = 8 // Range 跨块超过此数走流式拼装，避免大内存驻留

export default {
  async fetch(request, env, ctx) {
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders() })
    }
    const reqUrl = new URL(request.url)
    const target = reqUrl.searchParams.get('url')
    if (!target) return new Response('missing url', { status: 400 })

    let t
    try {
      t = new URL(target)
    } catch {
      return new Response('bad url', { status: 400 })
    }
    if (t.protocol !== 'https:' || !ALLOWED_HOSTS.some((h) => t.hostname.endsWith(h))) {
      return new Response('host not allowed', { status: 403 })
    }
    if (env.PROXY_TOKEN && reqUrl.searchParams.get('token') !== env.PROXY_TOKEN) {
      return new Response('forbidden', { status: 403 })
    }

    const range = request.headers.get('Range')
    try {
      if (range) return await handleRange(t.toString(), range, ctx)
      return await handleFull(t.toString(), ctx)
    } catch (e) {
      return new Response('upstream error: ' + (e && e.message), {
        status: 502,
        headers: corsHeaders(),
      })
    }
  },
}

// —— 上游请求 ————————————————————————————————————————————————

function fetchOrigin(url, range) {
  const headers = new Headers()
  headers.set('User-Agent', UA)
  headers.set('Referer', 'https://www.bilibili.com')
  headers.set('Origin', 'https://www.bilibili.com')
  if (range) headers.set('Range', range)
  return fetch(url, { headers, redirect: 'follow' })
}

// —— 块缓存 ——————————————————————————————————————————————————

async function urlHash(url) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(url))
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

function blockKey(hash, idx) {
  return new Request(`https://bili-proxy-block.cache/${hash}/${idx}`)
}

/** 取一个块：优先边缘缓存（校验新鲜度），未命中回源并写入缓存。返回 { buf, total } */
async function getBlock(url, hash, idx, ctx) {
  const cache = caches.default
  const key = blockKey(hash, idx)
  const hit = await cache.match(key)
  if (hit) {
    const at = Number(hit.headers.get('x-cached-at') || 0)
    if (Date.now() - at < BLOCK_CACHE_MAX_AGE_MS) {
      return { buf: await hit.arrayBuffer(), total: Number(hit.headers.get('x-total') || 0) }
    }
  }

  const start = idx * BLOCK
  const resp = await fetchOrigin(url, `bytes=${start}-${start + BLOCK - 1}`)
  if (resp.status !== 200 && resp.status !== 206) {
    throw new Error(`origin block ${idx} status ${resp.status}`)
  }
  const buf = await resp.arrayBuffer()

  let total = 0
  const cr = resp.headers.get('content-range') // bytes s-e/total
  if (cr) {
    const m = cr.match(/\/(\d+)$/)
    if (m) total = Number(m[1])
  }

  // 尾块不足 2MB 属正常；空块不写缓存
  if (buf.byteLength > 0 && total <= MAX_CACHEABLE_TOTAL) {
    ctx.waitUntil(
      cache.put(
        key,
        new Response(buf, {
          headers: { 'x-cached-at': String(Date.now()), 'x-total': String(total) },
        }),
      ),
    )
  }
  return { buf, total }
}

// —— Range 请求：块对齐缓存 + 精确切片/拼装 ——————————————————————

async function handleRange(url, range, ctx) {
  const m = range.match(/bytes=(\d+)-(\d*)/)
  if (!m) return await proxyStream(url, range, ctx) // 无法解析的 Range 头直接透传
  const start = Number(m[1])
  const openEnd = m[2] === ''
  const hash = await urlHash(url)

  // 首块必取（顺带拿到 total）
  const firstIdx = Math.floor(start / BLOCK)
  const first = await getBlock(url, hash, firstIdx, ctx)
  const total = first.total
  if (!total) return await proxyStream(url, range, ctx) // 拿不到总长则透传不缓存

  const end = openEnd ? total - 1 : Math.min(Number(m[2]), total - 1)
  if (end < start) return new Response('bad range', { status: 416, headers: corsHeaders() })
  const lastIdx = Math.floor(end / BLOCK)

  const baseHeaders = corsHeaders()
  baseHeaders.set('Content-Type', 'video/mp4')
  baseHeaders.set('Accept-Ranges', 'bytes')
  baseHeaders.set('Content-Range', `bytes ${start}-${end}/${total}`)
  baseHeaders.set('Content-Length', String(end - start + 1))

  // 单块：直接切片返回（浏览器 seek/分段加载的最常见路径）
  if (firstIdx === lastIdx) {
    const body = first.buf.slice(start - firstIdx * BLOCK, end - firstIdx * BLOCK + 1)
    return new Response(body, { status: 206, headers: baseHeaders })
  }

  // 跨块较少：内存拼装
  if (lastIdx - firstIdx + 1 <= MAX_ASSEMBLE_BLOCKS) {
    const parts = []
    for (let i = firstIdx; i <= lastIdx; i++) {
      parts.push(i === firstIdx ? first : await getBlock(url, hash, i, ctx))
    }
    const whole = new Uint8Array(parts.reduce((n, p) => n + p.buf.byteLength, 0))
    let off = 0
    for (const p of parts) {
      whole.set(new Uint8Array(p.buf), off)
      off += p.buf.byteLength
    }
    const body = whole.slice(start - firstIdx * BLOCK, end - firstIdx * BLOCK + 1)
    return new Response(body, { status: 206, headers: baseHeaders })
  }

  // 大范围（如 bytes=0- 的整体渐进加载）：逐块流式输出，客户端断开即停
  const stream = new ReadableStream({
    async start(controller) {
      for (let i = firstIdx; i <= lastIdx; i++) {
        const blk = i === firstIdx ? first : await getBlock(url, hash, i, ctx)
        const s = Math.max(start - i * BLOCK, 0)
        const e = Math.min(end - i * BLOCK, blk.buf.byteLength - 1)
        controller.enqueue(new Uint8Array(blk.buf, s, e - s + 1))
      }
      controller.close()
    },
  })
  return new Response(stream, { status: 206, headers: baseHeaders })
}

// —— 无 Range 的整体请求：流式透传 + 后台 tee 进块缓存 ————————————————

async function handleFull(url, ctx) {
  const upstream = await fetchOrigin(url, null)
  const headers = passthroughHeaders(upstream)
  const total = Number(upstream.headers.get('content-length') || 0)

  if (total > 0 && total <= MAX_CACHEABLE_TOTAL) {
    const hash = await urlHash(url)
    const [clientBranch, cacheBranch] = upstream.body.tee()
    ctx.waitUntil(populateCache(cacheBranch, hash, total))
    return new Response(clientBranch, { status: upstream.status, headers })
  }
  return new Response(upstream.body, { status: upstream.status, headers })
}

async function populateCache(stream, hash, total) {
  const cache = caches.default
  const reader = stream.getReader()
  let idx = 0
  let acc = new Uint8Array(BLOCK)
  let used = 0
  const put = async (buf, i) => {
    await cache.put(
      blockKey(hash, i),
      new Response(buf, {
        headers: { 'x-cached-at': String(Date.now()), 'x-total': String(total) },
      }),
    )
  }
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      let off = 0
      while (off < value.byteLength) {
        const n = Math.min(BLOCK - used, value.byteLength - off)
        acc.set(value.subarray(off, off + n), used)
        used += n
        off += n
        if (used === BLOCK) {
          await put(acc.slice(), idx++)
          used = 0
        }
      }
    }
    if (used > 0) await put(acc.slice(0, used), idx) // 尾块
  } catch {
    /* 客户端中断等，缓存尽力而为 */
  }
}

// —— 透传（解析失败/无法缓存的兜底路径） ——————————————————————————

async function proxyStream(url, range, ctx) {
  const upstream = await fetchOrigin(url, range)
  return new Response(upstream.body, {
    status: upstream.status,
    headers: passthroughHeaders(upstream),
  })
}

function passthroughHeaders(upstream) {
  const headers = corsHeaders()
  for (const k of [
    'content-type',
    'content-length',
    'content-range',
    'accept-ranges',
    'content-disposition',
  ]) {
    const v = upstream.headers.get(k)
    if (v) headers.set(k, v)
  }
  return headers
}

function corsHeaders() {
  return new Headers({
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Expose-Headers': 'Content-Range, Accept-Ranges, Content-Length',
  })
}
