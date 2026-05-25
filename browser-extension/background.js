// background.js - Service worker for Bilibili Player Extension v2.0
// Handles: Bilibili login, dynamic DNR rules, intercept record tracking

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const QRCODE_GENERATE = 'https://passport.bilibili.com/x/passport-login/web/qrcode/generate';
const QRCODE_POLL = 'https://passport.bilibili.com/x/passport-login/web/qrcode/poll';
const NAV_URL = 'https://api.bilibili.com/x/web-interface/nav';
const DNR_RULE_ID = 200;

let pollTimer = null;

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
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxxinfoc'.replace(/[xy]/g, function(c) {
    var r = Math.random() * 16 | 0, v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16).toUpperCase();
  });
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
    cdnIndex++;
    return u.toString();
  } catch(e) { return url; }
}

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

  if (msg.type === 'getDashUrl') {
    chrome.storage.local.get(['sessdata', 'buvid3'], async (data) => {
      const sessdata = data.sessdata;
      const buvid3 = data.buvid3 || generateBuvid3();
      if (!sessdata) {
        sendResponse({ok: false, error: 'not_logged_in'});
        return;
      }
      try {
        const url = 'https://api.bilibili.com/x/player/playurl?bvid=' + encodeURIComponent(msg.bvid) +
          '&cid=' + encodeURIComponent(msg.cid) + '&qn=' + encodeURIComponent(msg.qn) +
          '&fnval=' + encodeURIComponent(msg.fnval || 16) + '&fnver=0&fourk=1&platform=html5';
        const resp = await fetch(url, {
          headers: {
            'User-Agent': UA,
            'Referer': 'https://www.bilibili.com',
            'Cookie': 'buvid3=' + buvid3 + '; SESSDATA=' + sessdata
          }
        });
        const json = await resp.json();
        if (json.code === -101) {
          sendResponse({ok: false, error: 'login_expired'});
          return;
        }
        if (json.code !== 0) {
          sendResponse({ok: false, error: 'api_error: ' + (json.message || ('code=' + json.code))});
          return;
        }
        const dash = json.data && json.data.dash;
        if (!dash) {
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
        sendResponse({
          ok: true,
          videoUrl: videoUrl,
          audioUrl: audioUrl,
          quality: quality,
          codecs: {v: videoStream.codecs, a: audioStream.codecs},
          width: videoStream.width,
          height: videoStream.height
        });
      } catch (err) {
        sendResponse({ok: false, error: 'api_error: ' + err.message});
      }
    });
    return true;
  }
});
