// background.js - Service worker for Bilibili Player Extension v2.0
// Handles: Bilibili login, dynamic DNR rules, intercept record tracking

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const QRCODE_GENERATE = 'https://passport.bilibili.com/x/passport-login/web/qrcode/generate';
const QRCODE_POLL = 'https://passport.bilibili.com/x/passport-login/web/qrcode/poll';
const NAV_URL = 'https://api.bilibili.com/x/web-interface/nav';
const DNR_RULE_ID = 200;

let pollTimer = null;
// Global cache for webRequest sync access (DNR modifyHeaders doesn't work for fetch in Chrome 148+)
let cachedCookie = '';
let currentBvid = '';

// --- webRequest header modification (replaces broken DNR requestHeaders) ---
chrome.webRequest.onBeforeSendHeaders.addListener(
  (details) => {
    const headers = details.requestHeaders || [];
    const referer = currentBvid
      ? 'https://www.bilibili.com/video/' + currentBvid + '/'
      : 'https://www.bilibili.com';

    // Update Referer
    let idx = headers.findIndex(h => h.name.toLowerCase() === 'referer');
    if (idx >= 0) headers[idx].value = referer;
    else headers.push({name: 'Referer', value: referer});

    // Update Origin
    idx = headers.findIndex(h => h.name.toLowerCase() === 'origin');
    if (idx >= 0) headers[idx].value = 'https://www.bilibili.com';
    else headers.push({name: 'Origin', value: 'https://www.bilibili.com'});

    // Add Cookie (SESSDATA + buvid3)
    if (cachedCookie) {
      idx = headers.findIndex(h => h.name.toLowerCase() === 'cookie');
      if (idx >= 0) headers[idx].value = cachedCookie;
      else headers.push({name: 'Cookie', value: cachedCookie});
    }

    return {requestHeaders: headers};
  },
  {
    urls: [
      '*://*.bilivideo.com/*',
      '*://*.akamaized.net/*',
      '*://*.hdslb.com/*'
    ]
  },
  ['blocking', 'requestHeaders', 'extraHeaders']
);

// --- webRequest CORS response modification ---
chrome.webRequest.onHeadersReceived.addListener(
  (details) => {
    const headers = details.responseHeaders || [];
    let idx = headers.findIndex(h => h.name.toLowerCase() === 'access-control-allow-origin');
    if (idx >= 0) headers[idx].value = '*';
    else headers.push({name: 'Access-Control-Allow-Origin', value: '*'});

    idx = headers.findIndex(h => h.name.toLowerCase() === 'access-control-allow-credentials');
    if (idx >= 0) headers[idx].value = 'true';
    else headers.push({name: 'Access-Control-Allow-Credentials', value: 'true'});

    return {responseHeaders: headers};
  },
  {
    urls: [
      '*://*.bilivideo.com/*',
      '*://*.akamaized.net/*',
      '*://*.hdslb.com/*'
    ]
  },
  ['responseHeaders', 'extraHeaders']
);


// --- Initialize DNR rules on EVERY service worker start ---
// Manifest V3 terminates SW after ~30s idle. onInstalled/onStartup don't fire on wake.
// Non-blocking: fire-and-forget, messages are handled immediately.
function restoreDnrRules() {
  chrome.storage.local.get(['sessdata', 'buvid3'], (data) => {
    updateDnrRules(data.sessdata || null, data.buvid3 || '');
  });
}
restoreDnrRules();

chrome.runtime.onInstalled.addListener(() => {
  chrome.storage.local.get(['sessdata', 'buvid3'], (data) => {
    if (data.sessdata) updateDnrRules(data.sessdata, data.buvid3 || '');
  });
});

chrome.runtime.onStartup?.addListener(() => {
  chrome.storage.local.get(['sessdata', 'buvid3'], (data) => {
    if (data.sessdata) updateDnrRules(data.sessdata, data.buvid3 || '');
  });
});

// --- Dynamic DNR rules: inject SESSDATA cookie + Referer into CDN requests ---
async function updateDnrRules(sessdata, buvid3) {
  // Cache cookie for webRequest sync access
  cachedCookie = buvid3 && sessdata ? 'buvid3=' + buvid3 + '; SESSDATA=' + sessdata : '';
  try {
    const existingRules = await chrome.declarativeNetRequest.getDynamicRules();
    const existingIds = existingRules.map(r => r.id);

    if (!sessdata) {
      await chrome.declarativeNetRequest.updateDynamicRules({
        removeRuleIds: existingIds,
        addRules: []
      });
      console.log('[Bilibili Ext BG] DNR dynamic rules cleared (not logged in, static rules handle Referer+Origin+CORS)');
      return;
    }

    // Logged in: add Cookie+SESSDATA for each CDN domain (separate urlFilter rules)
    const domains = ['bilivideo.com', 'akamaized.net', 'hdslb.com'];
    const rules = domains.map((domain, i) => ({
      id: DNR_RULE_ID + i,
      priority: 2,
      action: {
        type: 'modifyHeaders',
        requestHeaders: [
          { header: 'Referer', operation: 'set', value: 'https://www.bilibili.com' },
          { header: 'Origin', operation: 'set', value: 'https://www.bilibili.com' },
          { header: 'Cookie', operation: 'set', value: 'buvid3=' + (buvid3 || '') + '; SESSDATA=' + sessdata }
        ],
        responseHeaders: [
          { header: 'Access-Control-Allow-Origin', operation: 'set', value: '*' }
        ]
      },
      condition: {
        urlFilter: '*://*' + domain + '/*',
        resourceTypes: ['media', 'xmlhttprequest', 'sub_frame', 'other']
      }
    }));

    await chrome.declarativeNetRequest.updateDynamicRules({
      removeRuleIds: existingIds,
      addRules: rules
    });
    console.log('[Bilibili Ext BG] DNR dynamic rules updated, login=true, rules=' + rules.length);
  } catch (e) {
    console.error('[Bilibili Ext BG] DNR update error:', e);
  }
}

// --- Intercept record tracking ---
let interceptRecords = [];

try {
  chrome.declarativeNetRequest.onRuleMatchedDebug?.addListener((info) => {
    interceptRecords.push({
      time: Date.now(),
      url: (info.request?.url || '').substring(0, 200),
      tabId: info.request?.tabId || 0,
      ruleId: info.rule?.ruleId || 0
    });
    if (interceptRecords.length > 50) interceptRecords.shift();
    chrome.storage.local.set({ interceptRecords });
  });
} catch (e) {
  console.log('[Bilibili Ext BG] onRuleMatchedDebug not available:', e.message);
}

// --- QR code login flow ---
async function generateQrCode() {
  const resp = await fetch(QRCODE_GENERATE, { headers: { 'User-Agent': UA } });
  if (!resp.ok) throw new Error('QR generate failed: HTTP ' + resp.status);
  const json = await resp.json();
  if (json.code !== 0) throw new Error('QR generate failed: ' + (json.message || 'unknown'));
  return { url: json.data.url, key: json.data.qrcode_key };
}

async function pollQrCode(qrcodeKey) {
  const resp = await fetch(QRCODE_POLL + '?qrcode_key=' + encodeURIComponent(qrcodeKey), {
    headers: { 'User-Agent': UA }
  });
  if (!resp.ok) return { status: 'error', message: 'HTTP ' + resp.status };
  const json = await resp.json();
  if (json.data.code === 0) {
    // Login success: extract SESSDATA from callback URL
    const params = new URLSearchParams(new URL(json.data.url).search.substring(1) || json.data.url.split('?')[1] || '');
    const sessdata = params.get('SESSDATA');
    const buvid3 = params.get('buvid3') || generateBuvid3();
    if (sessdata) {
      await chrome.storage.local.set({ sessdata, buvid3, loginTs: Date.now() });
      await updateDnrRules(sessdata, buvid3);
      // Verify login by fetching user info
      try {
        const navResp = await fetch(NAV_URL, {
          headers: { 'User-Agent': UA, 'Cookie': 'SESSDATA=' + sessdata }
        });
        const navJson = await navResp.json();
        if (navJson.data?.isLogin) {
          await chrome.storage.local.set({
            userInfo: {
              uname: navJson.data.uname || '',
              face: navJson.data.face || '',
              level: navJson.data.level_info?.current_level || 0
            }
          });
        }
      } catch (e) { /* nav fetch is optional */ }
      return { status: 'success' };
    }
    return { status: 'error', message: 'No SESSDATA in callback' };
  } else if (json.data.code === 86038) {
    return { status: 'expired' };
  } else if (json.data.code === 86090) {
    return { status: 'scanned' };
  } else if (json.data.code === 86101) {
    return { status: 'waiting' };
  }
  return { status: 'waiting', code: json.data.code };
}

function generateBuvid3() {
  return 'BUV3' + 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    var r = Math.random() * 16 | 0, v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16).toUpperCase();
  }) + 'infoc';
}

// --- WBI Sign ---
const MIXIN_KEY_ENC_TAB = [46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52];
let cachedWbiKeys = null;
let wbiKeysExpire = 0;

function md5(string) {
  function rotateLeft(lValue, iShiftBits) { return (lValue << iShiftBits) | (lValue >>> (32 - iShiftBits)); }
  function addUnsigned(lX, lY) {
    var lX4 = (lX & 0x40000000), lY4 = (lY & 0x40000000), lX8 = (lX & 0x80000000), lY8 = (lY & 0x80000000);
    var lResult = (lX & 0x3FFFFFFF) + (lY & 0x3FFFFFFF);
    if (lX4 & lY4) return (lResult ^ 0x80000000 ^ lX8 ^ lY8);
    if (lX4 | lY4) return (lResult & 0x40000000) ? (lResult ^ 0xC0000000 ^ lX8 ^ lY8) : (lResult ^ 0x40000000 ^ lX8 ^ lY8);
    return (lResult ^ lX8 ^ lY8);
  }
  function f(x,y,z){ return (x & y) | ((~x) & z); }
  function g(x,y,z){ return (x & z) | (y & (~z)); }
  function h(x,y,z){ return (x ^ y ^ z); }
  function i(x,y,z){ return (y ^ (x | (~z))); }
  function ff(a,b,c,d,x,s,ac){ a = addUnsigned(a, addUnsigned(addUnsigned(f(b,c,d), x), ac)); return addUnsigned(rotateLeft(a,s), b); }
  function gg(a,b,c,d,x,s,ac){ a = addUnsigned(a, addUnsigned(addUnsigned(g(b,c,d), x), ac)); return addUnsigned(rotateLeft(a,s), b); }
  function hh(a,b,c,d,x,s,ac){ a = addUnsigned(a, addUnsigned(addUnsigned(h(b,c,d), x), ac)); return addUnsigned(rotateLeft(a,s), b); }
  function ii(a,b,c,d,x,s,ac){ a = addUnsigned(a, addUnsigned(addUnsigned(i(b,c,d), x), ac)); return addUnsigned(rotateLeft(a,s), b); }
  function convertToWordArray(string) {
    var lMessageLength = string.length;
    var lNumberOfWordsTemp1 = lMessageLength + 8;
    var lNumberOfWordsTemp2 = (lNumberOfWordsTemp1 - (lNumberOfWordsTemp1 % 64)) / 64;
    var lNumberOfWords = (lNumberOfWordsTemp2 + 1) * 16;
    var lWordArray = new Array(lNumberOfWords - 1);
    var lBytePosition = 0, lByteCount = 0;
    while (lByteCount < lMessageLength) {
      var lWordCount = (lByteCount - (lByteCount % 4)) / 4;
      lBytePosition = (lByteCount % 4) * 8;
      lWordArray[lWordCount] = (lWordArray[lWordCount] || 0) | (string.charCodeAt(lByteCount) << lBytePosition);
      lByteCount++;
    }
    var lWordCount = (lByteCount - (lByteCount % 4)) / 4;
    lBytePosition = (lByteCount % 4) * 8;
    lWordArray[lWordCount] = (lWordArray[lWordCount] || 0) | (0x80 << lBytePosition);
    lWordArray[lNumberOfWords - 2] = lMessageLength << 3;
    lWordArray[lNumberOfWords - 1] = lMessageLength >>> 29;
    return lWordArray;
  }
  function wordToHex(lValue) {
    var wordToHexValue = '';
    for (var lCount = 0; lCount <= 3; lCount++) {
      var lByte = (lValue >>> (lCount * 8)) & 255;
      wordToHexValue += ('0' + lByte.toString(16)).substr(-2);
    }
    return wordToHexValue;
  }
  var x = [], k, AA, BB, CC, DD, a, b, c, d;
  var S11=7,S12=12,S13=17,S14=22,S21=5,S22=9,S23=14,S24=20,S31=4,S32=11,S33=16,S34=23,S41=6,S42=10,S43=15,S44=21;
  string = unescape(encodeURIComponent(string));
  x = convertToWordArray(string);
  a = 0x67452301; b = 0xEFCDAB89; c = 0x98BADCFE; d = 0x10325476;
  for (k = 0; k < x.length; k += 16) {
    AA=a; BB=b; CC=c; DD=d;
    a=ff(a,b,c,d,x[k+0],S11,0xD76AA478); d=ff(d,a,b,c,x[k+1],S12,0xE8C7B756); c=ff(c,d,a,b,x[k+2],S13,0x242070DB); b=ff(b,c,d,a,x[k+3],S14,0xC1BDCEEE);
    a=ff(a,b,c,d,x[k+4],S11,0xF57C0FAF); d=ff(d,a,b,c,x[k+5],S12,0x4787C62A); c=ff(c,d,a,b,x[k+6],S13,0xA8304613); b=ff(b,c,d,a,x[k+7],S14,0xFD469501);
    a=ff(a,b,c,d,x[k+8],S11,0x698098D8); d=ff(d,a,b,c,x[k+9],S12,0x8B44F7AF); c=ff(c,d,a,b,x[k+10],S13,0xFFFF5BB1); b=ff(b,c,d,a,x[k+11],S14,0x895CD7BE);
    a=ff(a,b,c,d,x[k+12],S11,0x6B901122); d=ff(d,a,b,c,x[k+13],S12,0xFD987193); c=ff(c,d,a,b,x[k+14],S13,0xA679438E); b=ff(b,c,d,a,x[k+15],S14,0x49B40821);
    a=gg(a,b,c,d,x[k+1],S21,0xF61E2562); d=gg(d,a,b,c,x[k+6],S22,0xC040B340); c=gg(c,d,a,b,x[k+11],S23,0x265E5A51); b=gg(b,c,d,a,x[k+0],S24,0xE9B6C7AA);
    a=gg(a,b,c,d,x[k+5],S21,0xD62F105D); d=gg(d,a,b,c,x[k+10],S22,0x2441453); c=gg(c,d,a,b,x[k+15],S23,0xD8A1E681); b=gg(b,c,d,a,x[k+4],S24,0xE7D3FBC8);
    a=gg(a,b,c,d,x[k+9],S21,0x21E1CDE6); d=gg(d,a,b,c,x[k+14],S22,0xC33707D6); c=gg(c,d,a,b,x[k+3],S23,0xF4D50D87); b=gg(b,c,d,a,x[k+8],S24,0x455A14ED);
    a=gg(a,b,c,d,x[k+13],S21,0xA9E3E905); d=gg(d,a,b,c,x[k+2],S22,0xFCEFA3F8); c=gg(c,d,a,b,x[k+7],S23,0x676F02D9); b=gg(b,c,d,a,x[k+12],S24,0x8D2A4C8A);
    a=hh(a,b,c,d,x[k+5],S31,0xFFFA3942); d=hh(d,a,b,c,x[k+8],S32,0x8771F681); c=hh(c,d,a,b,x[k+11],S33,0x6D9D6122); b=hh(b,c,d,a,x[k+14],S34,0xFDE5380C);
    a=hh(a,b,c,d,x[k+1],S31,0xA4BEEA44); d=hh(d,a,b,c,x[k+4],S32,0x4BDECFA9); c=hh(c,d,a,b,x[k+7],S33,0xF6BB4B60); b=hh(b,c,d,a,x[k+10],S34,0xBEBFBC70);
    a=hh(a,b,c,d,x[k+13],S31,0x289B7EC6); d=hh(d,a,b,c,x[k+0],S32,0xEAA127FA); c=hh(c,d,a,b,x[k+3],S33,0xD4EF3085); b=hh(b,c,d,a,x[k+6],S34,0x4881D05);
    a=hh(a,b,c,d,x[k+9],S31,0xD9D4D039); d=hh(d,a,b,c,x[k+12],S32,0xE6DB99E5); c=hh(c,d,a,b,x[k+15],S33,0x1FA27CF8); b=hh(b,c,d,a,x[k+2],S34,0xC4AC5665);
    a=ii(a,b,c,d,x[k+0],S41,0xF4292244); d=ii(d,a,b,c,x[k+7],S42,0x432AFF97); c=ii(c,d,a,b,x[k+14],S43,0xAB9423A7); b=ii(b,c,d,a,x[k+5],S44,0xFC93A039);
    a=ii(a,b,c,d,x[k+12],S41,0x655B59C3); d=ii(d,a,b,c,x[k+3],S42,0x8F0CCC92); c=ii(c,d,a,b,x[k+10],S43,0xFFEFF47D); b=ii(b,c,d,a,x[k+1],S44,0x85845DD1);
    a=ii(a,b,c,d,x[k+8],S41,0x6FA87E4F); d=ii(d,a,b,c,x[k+15],S42,0xFE2CE6E0); c=ii(c,d,a,b,x[k+6],S43,0xA3014314); b=ii(b,c,d,a,x[k+13],S44,0x4E0811A1);
    a=ii(a,b,c,d,x[k+4],S41,0xF7537E82); d=ii(d,a,b,c,x[k+11],S42,0xBD3AF235); c=ii(c,d,a,b,x[k+2],S43,0x2AD7D2BB); b=ii(b,c,d,a,x[k+9],S44,0xEB86D391);
    a = addUnsigned(a, AA); b = addUnsigned(b, BB); c = addUnsigned(c, CC); d = addUnsigned(d, DD);
  }
  return wordToHex(a) + wordToHex(b) + wordToHex(c) + wordToHex(d);
}

async function getPlayInfoFromPage(bvid, sessdata) {
  try {
    console.log('[Bilibili Ext BG] Fetching __playinfo__ from Bilibili page for', bvid, 'with login=', !!sessdata);
    const headers = {
      'User-Agent': UA,
      'Referer': 'https://www.bilibili.com',
      'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
    };
    if (sessdata) {
      headers['Cookie'] = 'SESSDATA=' + sessdata;
    }
    const resp = await fetch('https://www.bilibili.com/video/' + bvid + '/', { headers });
    const html = await resp.text();
    const match = html.match(/window\.__playinfo__\s*=\s*(\{.*?\})<\/script>/);
    if (!match) {
      console.log('[Bilibili Ext BG] __playinfo__ not found in page HTML');
      return null;
    }
    const playinfo = JSON.parse(match[1]);
    console.log('[Bilibili Ext BG] __playinfo__ parsed, code:', playinfo.code,
      'hasDash:', !!(playinfo.data && playinfo.data.dash),
      'quality:', playinfo.data && playinfo.data.quality,
      'acceptQuality:', playinfo.data && playinfo.data.accept_quality);
    return playinfo;
  } catch (e) {
    console.error('[Bilibili Ext BG] getPlayInfoFromPage error:', e.message);
    return null;
  }
}

async function getWbiKeys() {
  const now = Date.now();
  if (cachedWbiKeys && now < wbiKeysExpire) {
    console.log('[Bilibili Ext BG] WBI keys cache hit');
    return cachedWbiKeys;
  }
  try {
    console.log('[Bilibili Ext BG] Fetching WBI keys...');
    const resp = await fetch('https://api.bilibili.com/x/web-interface/nav', {
      headers: { 'User-Agent': UA, 'Referer': 'https://www.bilibili.com' }
    });
    const json = await resp.json();
    const imgUrl = json.data && json.data.wbi_img && json.data.wbi_img.img_url || '';
    const subUrl = json.data && json.data.wbi_img && json.data.wbi_img.sub_url || '';
    const imgKey = imgUrl.split('/').pop().split('.')[0];
    const subKey = subUrl.split('/').pop().split('.')[0];
    console.log('[Bilibili Ext BG] WBI keys fetched, imgKey=' + imgKey.substring(0,8) + '... subKey=' + subKey.substring(0,8) + '...');
    cachedWbiKeys = { imgKey, subKey };
    wbiKeysExpire = now + 55 * 60 * 1000;
    return cachedWbiKeys;
  } catch (e) {
    console.error('[Bilibili Ext BG] WBI keys fetch failed:', e.message);
    return cachedWbiKeys || { imgKey: '', subKey: '' };
  }
}

function getMixinKey(imgKey, subKey) {
  const raw = imgKey + subKey;
  let mixinKey = '';
  for (let i = 0; i < 32; i++) {
    mixinKey += raw.charAt(MIXIN_KEY_ENC_TAB[i]);
  }
  return mixinKey;
}

async function signWbi(params) {
  const keys = await getWbiKeys();
  const mixinKey = getMixinKey(keys.imgKey, keys.subKey);
  const wts = Math.round(Date.now() / 1000);
  params.wts = wts;
  const sortedKeys = Object.keys(params).sort();
  const query = sortedKeys.map(function(k) {
    // Filter out !'()* characters from values before signing (Bilibili WBI requirement)
    var filteredVal = String(params[k]).replace(/[!'()*]/g, '');
    return encodeURIComponent(k) + '=' + encodeURIComponent(filteredVal);
  }).join('&');
  const w_rid = md5(query + mixinKey);
  return query + '&w_rid=' + w_rid;
}

// --- CDN upgrade for Bilibili video URLs ---
const MIRROR_CDNS = [
  'upos-sz-mirrorali.bilivideo.com',
  'upos-sz-mirrorcos.bilivideo.com',
  'upos-sz-mirrorhw.bilivideo.com',
  'upos-sz-mirrorbd.bilivideo.com'
];
let cdnIndex = 0;
function upgradeCdn(url) {
  try {
    const u = new URL(url);
    if (/mirror/.test(u.hostname)) return url;
    u.hostname = MIRROR_CDNS[cdnIndex % MIRROR_CDNS.length];
    if (u.port === '8082') u.port = '';
    cdnIndex++;
    return u.toString();
  } catch(e) { return url; }
}

// --- Connect-based streaming fetch proxy (zero-copy via transfer) ---
chrome.runtime.onConnect.addListener(function(port) {
  if (port.name !== 'fetchProxy') return;
  console.log('[Bilibili Ext BG] Connect opened for fetchProxy');
  port.onMessage.addListener(async function(msg) {
    if (msg.type !== 'fetchProxy') return;
    console.log('[Bilibili Ext BG] fetchProxy req', msg.reqId, msg.url.substring(0,80));
    try {
      var opts = msg.options || {};
      var headers = {};
      if (opts.headers && typeof opts.headers === 'object') {
        for (var k in opts.headers) { headers[k] = opts.headers[k]; }
      }
      var resp = await fetch(msg.url, {
        method: opts.method || 'GET',
        headers: headers
      });
      console.log('[Bilibili Ext BG] fetch status', msg.reqId, resp.status);
      var reader = resp.body.getReader();
      var headerEntries = {};
      resp.headers.forEach(function(v, k) { headerEntries[k] = v; });
      port.postMessage({
        reqId: msg.reqId,
        ok: true,
        status: resp.status,
        headers: headerEntries
      });
      var chunkCount = 0;
      while (true) {
        var result = await reader.read();
        if (result.done) {
          console.log('[Bilibili Ext BG] fetch done', msg.reqId, 'chunks=', chunkCount);
          port.postMessage({ reqId: msg.reqId, done: true });
          break;
        }
        chunkCount++;
        port.postMessage({ reqId: msg.reqId, chunk: result.value.buffer }, [result.value.buffer]);
      }
    } catch (err) {
      console.error('[Bilibili Ext BG] fetchProxy err', msg.reqId, err.message);
      port.postMessage({ reqId: msg.reqId, ok: false, error: err.message });
    }
  });
});

// --- Message handling from popup/content-script ---
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg.type === 'getStatus') {
    chrome.storage.local.get(['sessdata', 'loginTs', 'lastConnection', 'userInfo'], (data) => {
      sendResponse({
        login: !!data.sessdata,
        loginTs: data.loginTs || 0,
        lastConnection: data.lastConnection || null,
        userInfo: data.userInfo || null
      });
    });
    return true; // async
  }

  if (msg.type === 'generateQr') {
    generateQrCode().then(result => sendResponse({ ok: true, ...result }))
      .catch(err => sendResponse({ ok: false, error: err.message }));
    return true;
  }

  if (msg.type === 'pollQr') {
    pollQrCode(msg.qrcodeKey).then(result => {
      if (result.status === 'success' || result.status === 'expired') {
        if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
      }
      sendResponse({ ok: true, ...result });
    }).catch(err => sendResponse({ ok: false, error: err.message }));
    return true;
  }

  if (msg.type === 'logout') {
    chrome.storage.local.remove(['sessdata', 'loginTs', 'userInfo']);
    updateDnrRules(null, null);
    sendResponse({ ok: true });
    return true;
  }

  if (msg.type === 'getInterceptRecords') {
    chrome.storage.local.get(['interceptRecords'], (data) => {
      sendResponse({ records: data.interceptRecords || [] });
    });
    return true;
  }

  if (msg.type === 'testWbi') {
    chrome.storage.local.get(['sessdata', 'buvid3'], async (data) => {
      const sessdata = data.sessdata;
      const buvid3 = data.buvid3 || generateBuvid3();
      if (!sessdata) {
        sendResponse({ok: false, error: 'not_logged_in'});
        return;
      }
      try {
        const params = {
          bvid: msg.bvid || 'BV1R1e4zKEh1',
          cid: String(msg.cid || 31870356198),
          qn: String(msg.qn || 120),
          fnval: String(msg.fnval || 4048),
          fnver: '0',
          fourk: '1'
        };
        const signedQuery = await signWbi(params);
        const url = 'https://api.bilibili.com/x/player/wbi/playurl?' + signedQuery;
        console.log('[Bilibili Ext BG] testWbi request:', url.substring(0, 120));
        const resp = await fetch(url, {
          headers: {
            'User-Agent': UA,
            'Referer': 'https://www.bilibili.com',
            'Cookie': 'SESSDATA=' + sessdata
          }
        });
        const json = await resp.json();
        console.log('[Bilibili Ext BG] testWbi response code:', json.code, 'message:', json.message);
        sendResponse({
          ok: true,
          code: json.code,
          message: json.message,
          dataKeys: json.data ? Object.keys(json.data) : [],
          quality: json.data?.quality,
          acceptQuality: json.data?.accept_quality,
          acceptDescription: json.data?.accept_description,
          hasDash: !!json.data?.dash,
          dashVideoCount: json.data?.dash?.video?.length || 0,
          dashAudioCount: json.data?.dash?.audio?.length || 0,
          durlCount: json.data?.durl?.length || 0,
          rawData: JSON.stringify(json.data).substring(0, 1000)
        });
      } catch (err) {
        console.error('[Bilibili Ext BG] testWbi exception:', err);
        sendResponse({ok: false, error: err.message});
      }
    });
    return true;
  }

  if (msg.type === 'fetchProxy') {
    (async () => {
      try {
        var opts = msg.options || {};
        var headers = {};
        if (opts.headers && typeof opts.headers === 'object') {
          for (var k in opts.headers) { headers[k] = opts.headers[k]; }
        }
        var resp = await fetch(msg.url, {
          method: opts.method || 'GET',
          headers: headers
        });
        var data = await resp.arrayBuffer();
        var headerEntries = {};
        resp.headers.forEach(function(v, k) { headerEntries[k] = v; });
        sendResponse({ ok: true, status: resp.status, headers: headerEntries, data: data });
      } catch (err) {
        sendResponse({ ok: false, error: err.message });
      }
    })();
    return true;
  }

  if (msg.type === 'getDashUrl') {
    chrome.storage.local.get(['sessdata', 'buvid3'], async (data) => {
      const sessdata = data.sessdata;
      const buvid3 = data.buvid3 || generateBuvid3();
      // Cache for webRequest sync access
      cachedCookie = buvid3 && sessdata ? 'buvid3=' + buvid3 + '; SESSDATA=' + sessdata : '';
      currentBvid = msg.bvid || '';
      console.log('[Bilibili Ext BG] webRequest cache set, bvid=' + currentBvid + ' cookie_len=' + cachedCookie.length);
      if (!sessdata) {
        sendResponse({ok: false, error: 'not_logged_in'});
        return;
      }
      try {
        // Strategy 1: Parse __playinfo__ from Bilibili official page (most reliable for DASH)
        // Must pass SESSDATA to get HD/4K playinfo, otherwise only low quality is returned
        let playinfo = await getPlayInfoFromPage(msg.bvid, sessdata);
        let json = null;
        if (playinfo && playinfo.code === 0 && playinfo.data && playinfo.data.dash) {
          json = playinfo;
          console.log('[Bilibili Ext BG] Using __playinfo__ from page, quality:', json.data.quality);
        } else {
          // Strategy 2: Fallback to WBI API
          // Match original backend parameters exactly (no 'platform' param)
          const params = {
            bvid: msg.bvid,
            cid: String(msg.cid),
            qn: String(msg.qn || 80),
            fnval: String(msg.fnval || 4048),
            fnver: '0',
            fourk: '1'
          };
          const signedQuery = await signWbi(params);
          const url = 'https://api.bilibili.com/x/player/wbi/playurl?' + signedQuery;
          console.log('[Bilibili Ext BG] getDashUrl WBI request:', url);
          const resp = await fetch(url, {
            headers: {
              'User-Agent': UA,
              'Referer': 'https://www.bilibili.com',
              'Cookie': 'SESSDATA=' + sessdata
            }
          });
          json = await resp.json();
          console.log('[Bilibili Ext BG] getDashUrl WBI response code:', json.code, 'message:', json.message);
        }

        if (!json) {
          sendResponse({ok: false, error: 'api_error: failed to get play info'});
          return;
        }

        console.log('[Bilibili Ext BG] getDashUrl raw data keys:', json.data ? Object.keys(json.data).join(',') : 'no data');
        if (json.code === -101) {
          sendResponse({ok: false, error: 'login_expired'});
          return;
        }
        if (json.code !== 0) {
          console.log('[Bilibili Ext BG] getDashUrl API error, code:', json.code, 'message:', json.message);
          sendResponse({ok: false, error: 'api_error: ' + (json.message || ('code=' + json.code)), code: json.code, raw: JSON.stringify(json).substring(0, 500)});
          return;
        }
        const dash = json.data && json.data.dash;
        if (!dash) {
          console.log('[Bilibili Ext BG] getDashUrl no dash data, data:', JSON.stringify(json.data).substring(0, 500));
          sendResponse({ok: false, error: 'api_error: no dash data'});
          return;
        }
        const quality = json.data.quality;
        let videoStream = null;
        if (dash.video && dash.video.length) {
          videoStream = dash.video.find(function(v) {
            return v.id === quality && v.codecs && v.codecs.indexOf('avc1') > -1;
          });
          if (!videoStream) {
            videoStream = dash.video.find(function(v) {
              return v.codecs && v.codecs.indexOf('avc1') > -1;
            });
          }
          if (!videoStream) {
            videoStream = dash.video[0];
          }
        }
        let audioStream = null;
        if (dash.audio && dash.audio.length) {
          audioStream = dash.audio.reduce(function(max, cur) {
            return (cur.bandwidth > max.bandwidth) ? cur : max;
          });
        }
        if (!videoStream || !audioStream) {
          sendResponse({ok: false, error: 'api_error: missing video or audio stream'});
          return;
        }
        const videoUrl = upgradeCdn(videoStream.baseUrl || videoStream.base_url);
        const audioUrl = upgradeCdn(audioStream.baseUrl || audioStream.base_url);
        console.log('[Bilibili Ext BG] getDashUrl success, quality:', quality, 'v:', videoUrl.substring(0, 60), 'a:', audioUrl.substring(0, 60));
        sendResponse({
          ok: true,
          videoUrl: videoUrl,
          audioUrl: audioUrl,
          quality: quality,
          codecs: {v: videoStream.codecs, a: audioStream.codecs},
          width: videoStream.width,
          height: videoStream.height,
          acceptQuality: json.data.accept_quality || [],
          acceptDescription: json.data.accept_description || []
        });
      } catch (err) {
        console.error('[Bilibili Ext BG] getDashUrl exception:', err);
        sendResponse({ok: false, error: 'api_error: ' + err.message});
      }
    });
    return true;
  }
});
