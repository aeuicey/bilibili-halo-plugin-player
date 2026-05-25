(function() {
  'use strict';

  var signalCount = 0;
  var MAX_SIGNALS = 5;

  function signal() {
    if (signalCount >= MAX_SIGNALS) return;
    signalCount++;
    console.log('[Bilibili Ext] Signaling player page (attempt ' + signalCount + '/' + MAX_SIGNALS + ')');

    // Broadcast basic installed signal first
    window.postMessage({
      source: 'bilibili-player-extension',
      type: 'installed',
      version: '2.0',
      timestamp: Date.now()
    }, '*');

    // Then query background for full status
    chrome.runtime.sendMessage({ type: 'getStatus' }, function(status) {
      if (status) {
        window.postMessage({
          source: 'bilibili-player-extension',
          type: 'status',
          version: '2.0',
          installed: true,
          login: status.login,
          userInfo: status.userInfo || null,
          timestamp: Date.now()
        }, '*');
      }
    });
  }

  // Signal at intervals to ensure player listener is ready
  signal();           // t=0 (document_start)
  setTimeout(signal, 300);
  setTimeout(signal, 800);
  setTimeout(signal, 1500);
  setTimeout(signal, 3000);

  // Respond to pings from the player page (active detection)
  window.addEventListener('message', function(event) {
    if (event.data && event.data.source === 'bilibili-player' && event.data.type === 'ping') {
      console.log('[Bilibili Ext] Received ping from player, responding with full status...');
      signalCount = 0; // reset count so we always respond to pings

      // Respond immediately with installed signal
      window.postMessage({
        source: 'bilibili-player-extension',
        type: 'installed',
        version: '2.0',
        timestamp: Date.now()
      }, '*');

      // Then send full status
      chrome.runtime.sendMessage({ type: 'getStatus' }, function(status) {
        if (status) {
          window.postMessage({
            source: 'bilibili-player-extension',
            type: 'status',
            version: '2.0',
            installed: true,
            login: status.login,
            userInfo: status.userInfo || null,
            timestamp: Date.now()
          }, '*');
        }
      });
    }
  });

  // --- DASH URL forwarding: request from player page, forward to background, send back ---
  window.addEventListener('message', function(event) {
    if (!event.data || event.data.source !== 'bilibili-player') return;

    if (event.data.type === 'requestDash') {
      var reqId = event.data.reqId;
      chrome.runtime.sendMessage({
        type: 'getDashUrl',
        bvid: event.data.bvid,
        cid: event.data.cid,
        qn: event.data.qn,
        fnval: event.data.fnval || 16
      }, function(resp) {
        window.postMessage({
          source: 'bilibili-player-extension',
          type: 'dashUrl',
          reqId: reqId,
          ok: resp && resp.ok,
          videoUrl: resp ? resp.videoUrl : null,
          audioUrl: resp ? resp.audioUrl : null,
          quality: resp ? resp.quality : 0,
          codecs: resp ? resp.codecs : null,
          width: resp ? resp.width : 0,
          height: resp ? resp.height : 0,
          error: resp ? resp.error : 'no_response'
        }, '*');
      });
    }
  });

  // Record connection when on a Halo player page
  var url = window.location.href;
  if (url.indexOf('/bilibili-player/embed') > -1 ||
      url.indexOf('/console/bilibili-player') > -1) {
    // Get login status for the connection record
    chrome.runtime.sendMessage({ type: 'getStatus' }, function(status) {
      try {
        chrome.storage.local.set({
          lastConnection: {
            url: url.substring(0, 200),
            timestamp: Date.now(),
            domain: window.location.hostname,
            login: status ? status.login : false
          }
        });
        console.log('[Bilibili Ext] Connection recorded: ' + window.location.hostname + ' (login=' + (status && status.login) + ')');
      } catch (e) {
        console.error('[Bilibili Ext] Failed to save connection:', e);
      }
    });
  }
})();
