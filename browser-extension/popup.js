// popup.js - Bilibili Player Extension Popup v2.0
// Shows: login status, ruleset status, player connection, CDN match log, intercept records

var domains = [
  '*.bilivideo.com',
  '*.akamaized.net',
  '*.hdslb.com'
];

// --- Login section ---
var loginPollTimer = null;

function updateLoginUI(status) {
  var dot = document.getElementById('loginDot');
  var label = document.getElementById('loginLabel');
  var info = document.getElementById('loginInfo');
  var loginBtn = document.getElementById('loginBtn');
  var logoutBtn = document.getElementById('logoutBtn');

  if (status.login && status.userInfo) {
    dot.className = 'login-dot logged-in';
    label.textContent = status.userInfo.uname || '已登录';
    label.style.color = '#4caf50';
    info.textContent = 'Lv.' + (status.userInfo.level || '?') + ' · 已启用高清代理';
    loginBtn.style.display = 'none';
    logoutBtn.style.display = 'inline-block';
  } else if (status.login) {
    dot.className = 'login-dot logged-in';
    label.textContent = '已登录';
    label.style.color = '#4caf50';
    info.textContent = '动态规则已激活';
    loginBtn.style.display = 'none';
    logoutBtn.style.display = 'inline-block';
  } else {
    dot.className = 'login-dot';
    label.textContent = '未登录';
    label.style.color = '#aaa';
    info.textContent = '登录后可播放 1080P+ 高清视频（720P 及以下无需登录）';
    loginBtn.style.display = 'inline-block';
    logoutBtn.style.display = 'none';
  }
}

// Login button click: start QR flow
document.getElementById('loginBtn').addEventListener('click', function() {
  var qrContainer = document.getElementById('qrContainer');
  var qrStatus = document.getElementById('qrStatus');
  qrContainer.style.display = 'block';
  qrStatus.textContent = '正在生成二维码...';

  chrome.runtime.sendMessage({ type: 'generateQr' }, function(resp) {
    if (!resp || !resp.ok) {
      qrStatus.textContent = '生成失败: ' + ((resp && resp.error) || '未知错误');
      return;
    }
    // Display QR code image
    document.getElementById('qrImage').src =
      'https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=' + encodeURIComponent(resp.url);
    qrStatus.textContent = '请使用B站App扫码';

    // Start polling
    var qrcodeKey = resp.key;
    loginPollTimer = setInterval(function() {
      chrome.runtime.sendMessage({ type: 'pollQr', qrcodeKey: qrcodeKey }, function(pollResp) {
        if (!pollResp || !pollResp.ok) return;
        if (pollResp.status === 'success') {
          clearInterval(loginPollTimer);
          loginPollTimer = null;
          qrContainer.style.display = 'none';
          qrStatus.textContent = '登录成功！';
          refreshAll();
        } else if (pollResp.status === 'expired') {
          clearInterval(loginPollTimer);
          loginPollTimer = null;
          qrStatus.textContent = '二维码已过期，请重新生成';
        } else if (pollResp.status === 'scanned') {
          qrStatus.textContent = '已扫描，请在手机上确认';
        }
      });
    }, 2000);
  });
});

// Logout button
document.getElementById('logoutBtn').addEventListener('click', function() {
  chrome.runtime.sendMessage({ type: 'logout' }, function(resp) {
    if (resp && resp.ok) refreshAll();
  });
});

// --- Refresh button ---
document.getElementById('refreshHint').addEventListener('click', refreshAll);

// --- Ruleset status ---
function loadRulesetStatus() {
  return chrome.declarativeNetRequest.getEnabledRulesets()
    .then(function(rulesets) {
      var active = rulesets.includes('rules');
      var dot = document.getElementById('dot');
      var label = document.getElementById('statusLabel');
      var desc = document.getElementById('statusDesc');
      var list = document.getElementById('ruleList');

      if (active) {
        dot.className = 'dot active';
        label.textContent = '已激活 (' + rulesets.length + ' ruleset)';
        desc.textContent = '静态规则: 移除外域 Referer/Origin';
      } else {
        dot.className = 'dot inactive';
        label.textContent = '未激活';
        desc.textContent = '规则集未启用';
      }

      list.innerHTML = domains.map(function(d) {
        return '<div class="rule-item"><span class="rule-dot"></span>' + d + '</div>';
      }).join('');

      // Check dynamic rules
      return chrome.declarativeNetRequest.getDynamicRules();
    })
    .then(function(dynamicRules) {
      if (dynamicRules && dynamicRules.length > 0) {
        var desc = document.getElementById('statusDesc');
        desc.textContent += ' | 动态规则: ' + dynamicRules.length + '条 (已登录)';
      }
      // Check matched static rules
      return chrome.declarativeNetRequest.getMatchedRules({
        minTimeStamp: Date.now() - 600000
      });
    })
    .then(function(matched) {
      var logEl = document.getElementById('matchLog');
      if (!logEl) return;

      var info = matched ? matched.rulesMatchedInfo : null;
      var count = info ? info.length : 0;

      if (!matched) {
        logEl.innerHTML = '<div class="log-empty">getMatchedRules 返回 null</div>';
      } else if (count > 0) {
        logEl.innerHTML = '<div class="log-title">最近 ' + count + ' 条匹配</div>' +
          info.slice(-8).map(function(m) {
            var url = (m.request && m.request.url) || (m.url || 'unknown');
            var ruleId = (m.rule && m.rule.ruleId) || '?';
            return '<div class="log-item"><span class="log-time">' +
              new Date(m.timeStamp).toLocaleTimeString() + '</span>' +
              '<span class="log-url" title="' + escapeHtml(url) + '">R' + ruleId + ' ' +
              escapeHtml((url || '').substring(0, 55)) + '</span></div>';
          }).join('');
      } else {
        logEl.innerHTML = '<div class="log-empty">最近 10 分钟无匹配记录</div>';
      }
    })
    .catch(function(err) {
      document.getElementById('dot').className = 'dot inactive';
      document.getElementById('statusLabel').textContent = '状态异常';
      document.getElementById('statusDesc').textContent = err.message;
    });
}

// --- Connection status ---
function loadConnectionStatus() {
  chrome.storage.local.get(['lastConnection'], function(result) {
    var conn = result.lastConnection;
    var el = document.getElementById('connStatus');
    if (!el) return;

    if (conn && (Date.now() - conn.timestamp) < 600000) {
      var ago = Math.round((Date.now() - conn.timestamp) / 1000);
      var agoStr = ago < 60 ? ago + '秒前' : Math.round(ago / 60) + '分钟前';
      el.innerHTML =
        '<div class="conn-row">' +
          '<span class="conn-dot active"></span>' +
          '<span class="conn-text active">已连接：' + conn.domain + '</span>' +
        '</div>' +
        '<div class="conn-info">' + agoStr + ' · 播放器连接正常</div>';
    } else if (conn) {
      var agoM = Math.round((Date.now() - conn.timestamp) / 60000);
      el.innerHTML =
        '<div class="conn-row">' +
          '<span class="conn-dot"></span>' +
          '<span class="conn-text">上次连接：' + agoM + '分钟前</span>' +
        '</div>';
    } else {
      el.innerHTML =
        '<div class="conn-row">' +
          '<span class="conn-dot"></span>' +
          '<span class="conn-text">等待播放器连接...</span>' +
        '</div>';
    }
  });
}

// --- Intercept records ---
function loadInterceptRecords() {
  chrome.runtime.sendMessage({ type: 'getInterceptRecords' }, function(resp) {
    var el = document.getElementById('interceptLog');
    if (!el) return;
    var records = (resp && resp.records) ? resp.records : [];

    if (records.length > 0) {
      el.innerHTML = '<div class="log-title">最近 ' + records.length + ' 条拦截</div>' +
        records.slice(-8).reverse().map(function(r) {
          return '<div class="log-item"><span class="log-time">' +
            new Date(r.time).toLocaleTimeString() + '</span>' +
            '<span class="log-url" title="' + escapeHtml(r.url) + '">' +
            (r.ruleId >= 200 ? '[动态] ' : '[静态] ') +
            escapeHtml(r.url.substring(0, 50)) + '</span></div>';
        }).join('');
    } else {
      el.innerHTML = '<div class="log-empty">暂无拦截记录</div>';
    }
  });
}

// --- Full refresh ---
function refreshAll() {
  chrome.runtime.sendMessage({ type: 'getStatus' }, function(status) {
    if (status) updateLoginUI(status);
  });
  loadRulesetStatus();
  loadConnectionStatus();
  loadInterceptRecords();
}

// --- Initial load ---
refreshAll();

// --- Auto-refresh every 5 seconds for real-time status ---
setInterval(refreshAll, 5000);

function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
