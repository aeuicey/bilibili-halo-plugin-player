// BiliBili CDN 代理 Worker —— 部署到 Cloudflare Workers 后，在插件设置页填写其地址即可。
// 功能：伪装 Referer/Origin 拉取 B 站 CDN 流，透传 Range（拖动进度条），流式回传。
// 安全：仅放行 B 站相关域名；可选共享密钥（Workers 密钥变量 PROXY_TOKEN）。

const ALLOWED_HOSTS = [
  '.bilivideo.com', '.bilivideo.cn', '.mcdn.bilivideo.cn',
  '.biliapi.net', '.hdslb.com', '.bilibili.com',
]
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

export default {
  async fetch(request, env) {
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

    const headers = new Headers()
    headers.set('User-Agent', UA)
    headers.set('Referer', 'https://www.bilibili.com')
    headers.set('Origin', 'https://www.bilibili.com')
    const range = request.headers.get('Range')
    if (range) headers.set('Range', range)

    const upstream = await fetch(t.toString(), { headers, redirect: 'follow' })

    const respHeaders = corsHeaders()
    for (const k of [
      'content-type',
      'content-length',
      'content-range',
      'accept-ranges',
      'content-disposition',
    ]) {
      const v = upstream.headers.get(k)
      if (v) respHeaders.set(k, v)
    }
    return new Response(upstream.body, { status: upstream.status, headers: respHeaders })
  },
}

function corsHeaders() {
  return new Headers({
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Expose-Headers': 'Content-Range, Accept-Ranges, Content-Length',
  })
}
