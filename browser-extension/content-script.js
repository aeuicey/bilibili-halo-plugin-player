(function() {
  'use strict';

  var signalCount = 0;
  var MAX_SIGNALS = 5;

  function signal() {
    if (signalCount >= MAX_SIGNALS) return;
    signalCount++;
    console.log('[Bilibili Ext] Signaling player page (attempt ' + signalCount + '/' + MAX_SIGNALS + ')');

    // Query background for full status FIRST, then send a single consolidated message.
    // This avoids the race condition where player sees 'installed' but misses delayed 'status'.
    chrome.runtime.sendMessage({ type: 'getStatus' }, function(status) {
      var payload = {
        source: 'bilibili-player-extension',
        type: 'status',
        version: '2.1',
        installed: true,
        login: status ? status.login === true : false,
        userInfo: status ? (status.userInfo || null) : null,
        timestamp: Date.now()
      };
      window.postMessage(payload, '*');
      console.log('[Bilibili Ext] Sent status, login=' + payload.login);
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

      // Query background and send consolidated status (same as signal())
      chrome.runtime.sendMessage({ type: 'getStatus' }, function(status) {
        var payload = {
          source: 'bilibili-player-extension',
          type: 'status',
          version: '2.1',
          installed: true,
          login: status ? status.login === true : false,
          userInfo: status ? (status.userInfo || null) : null,
          timestamp: Date.now()
        };
        window.postMessage(payload, '*');
        console.log('[Bilibili Ext] Ping response sent, login=' + payload.login);
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
