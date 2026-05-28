(function() {
  'use strict';

  var signalCount = 0;
  var MAX_SIGNALS = 5;

  function signal() {
    if (signalCount >= MAX_SIGNALS) return;
    signalCount++;
    console.log('[Bilibili Ext] Signaling player page (attempt ' + signalCount + '/' + MAX_SIGNALS + ')');

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

  signal();
  setTimeout(signal, 300);
  setTimeout(signal, 800);
  setTimeout(signal, 1500);
  setTimeout(signal, 3000);

  window.addEventListener('message', function(event) {
    if (event.data && event.data.source === 'bilibili-player' && event.data.type === 'ping') {
      console.log('[Bilibili Ext] Received ping from player, responding with full status...');
      signalCount = 0;
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

  // --- DASH URL forwarding ---
  window.addEventListener('message', function(event) {
    if (!event.data || event.data.source !== 'bilibili-player') return;

    if (event.data.type === 'requestDash') {
      var reqId = event.data.reqId;
      chrome.runtime.sendMessage({
        type: 'getDashUrl',
        bvid: event.data.bvid,
        cid: event.data.cid,
        qn: event.data.qn,
        fnval: event.data.fnval || 4048
      }, function(resp) {
        console.log('[Bilibili Ext] getDashUrl response ok='+(resp&&resp.ok)+' error='+(resp&&resp.error)+' code='+(resp&&resp.code)+' url='+(resp&&resp.url));
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
          acceptQuality: resp ? resp.acceptQuality : null,
          acceptDescription: resp ? resp.acceptDescription : null,
          error: resp ? resp.error : 'no_response',
          code: resp ? resp.code : undefined,
          raw: resp ? resp.raw : undefined
        }, '*');
      });
    }
  });

  // --- Streaming fetch proxy via chrome.runtime.connect ---
  var bgPort = null;
  function getBgPort() {
    if (!bgPort) {
      bgPort = chrome.runtime.connect({ name: 'fetchProxy' });
      bgPort.onDisconnect.addListener(function() { bgPort = null; });
    }
    return bgPort;
  }

  window.addEventListener('message', function(event) {
    if (!event.data || event.data.source !== 'bilibili-player') return;

    if (event.data.type === 'fetchProxy') {
      var reqId = event.data.reqId;
      console.log('[Bilibili Ext CS] fetchProxy from page', reqId, event.data.url.substring(0,80));
      var port = getBgPort();

      port.postMessage({
        type: 'fetchProxy',
        reqId: reqId,
        url: event.data.url,
        options: event.data.options
      });

      var listener = function(msg) {
        if (msg.reqId !== reqId) return;
        console.log('[Bilibili Ext CS] bg->page', reqId, 'ok=', msg.ok, 'chunk=', !!msg.chunk, 'done=', !!msg.done);

        if (msg.done || msg.error || msg.ok === false) {
          port.onMessage.removeListener(listener);
        }

        var transferList = msg.chunk ? [msg.chunk] : undefined;
        window.postMessage({
          source: 'bilibili-player-extension',
          type: 'fetchProxyResponse',
          reqId: reqId,
          ok: msg.ok,
          status: msg.status,
          headers: msg.headers,
          chunk: msg.chunk,
          done: msg.done,
          error: msg.error
        }, '*', transferList);
      };

      port.onMessage.addListener(listener);
    }
  });

  // Record connection when on a Halo player page
  var url = window.location.href;
  if (url.indexOf('/bilibili-player/embed') > -1 ||
      url.indexOf('/console/bilibili-player') > -1) {
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
