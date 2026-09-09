package com.halo.bilibiliplayer.controller;

/**
 * 生成 embed 内联播放页 HTML（nplayer + DASH 音画分离双元素 RAF 同步架构）。
 *
 * 播放器策略（对齐 B 站 Web 端）：
 *  - 首次加载调一次 playurl（后端降级链）拿全量 DASH 轨，之后清晰度切换纯前端换轨；
 *    仅在 CDN 报错（疑似 403/过期）或距上次获取超过 110 分钟时才重新请求 API。
 *  - 流选择：按目标 qn 过滤，缺流时向 accept_quality 相邻更低档降级；同 qn 多编码时
 *    canPlayType 探测，优先级 avc1 > av01(probably) > hevc(probably)；同编码取带宽适中者；
 *    音频取最高 bandwidth 轨。
 *  - 流分发：三级候选模式。smart（默认）= 浏览器直连（no-referrer）→ Cloudflare Worker
 *    代理（若已配置）→ 服务器代理兜底；worker = Worker → 服务器；server = 仅服务器代理。
 *    tier 优先，同 tier 内 baseUrl → backupUrl；video/audio 元素各自独立递进候选索引。
 *  - 解码回退：选流后 5s 内无 loadeddata/progress 判定解码失败 → 同 qn 换次优编码 →
 *    整档降 qn → 最终切 MP4 单文件模式（关闭 RAF 同步、移除隐藏 audio）。
 *  - 音画同步：RAF ±0.15s 纠偏；audio stalled 或其 buffered 落后 video 超 1s 时暂停
 *    video 等 audio canplay 再恢复。
 *
 * UI 壳为 nplayer（零依赖，UMD 单文件、CSS 内联注入）：自建 video 元素传入 opts.video，
 * 所有播放逻辑直接操作该原生元素；画质/画中画为 registerControlItem 风格的自定义控制项。
 */
public final class EmbedPageGenerator {

    private EmbedPageGenerator() {
    }

    /** 转义注入内联 JS 单引号字符串的配置值（防引号/反斜杠注入与 </script> 提前闭合） */
    private static String jsStr(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\r", "").replace("\n", "\\n")
                .replace("</", "<\\/");
    }

    public static String build(String bvid, String cid, String proxyMode, String workerUrl, String workerToken) {
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        h.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        h.append("<meta http-equiv=\"Cache-Control\" content=\"no-cache,no-store,must-revalidate\">");
        h.append("<meta name=\"referrer\" content=\"no-referrer\">");
        h.append("<title>Bilibili Player</title>");
        // nplayer UMD（CSS 内联注入，无需单独引样式）；主 CDN 失败时 document.write 同步降级到备选 CDN
        h.append("<script src=\"https://unpkg.com/nplayer@1.0.15/dist/index.min.js\"></script>");
        h.append("<script>if(!window.NPlayer){document.write('<script src=\"https://cdn.jsdelivr.net/npm/nplayer@1.0.15/dist/index.min.js\"><\\/script>')}</script>");
        h.append("<style>");
        // 页面布局：视频区占满整个页面，nplayer 容器挂载进 .pwrap
        h.append("*{margin:0;padding:0;box-sizing:border-box}");
        h.append("html,body{height:100%}body{background:#000;overflow:hidden;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}");
        h.append(".pwrap{position:relative;width:100%;height:100%;background:#000}");
        // 大播放按钮（nplayer Poster 部件，未播放/未就绪时显示）：粉色半透明圆形，风格对齐 B 站
        h.append(".nplayer_poster_play{background:rgba(251,114,153,.85);border:2px solid #fff;border-radius:50%;width:70px;height:70px;display:flex;align-items:center;justify-content:center;transition:transform .2s,background .2s}");
        h.append(".nplayer_poster_play:hover{background:#fb7299;transform:translate(-50%,-50%) scale(1.08)}");
        h.append(".nplayer_poster_play svg{width:28px;height:28px;margin-left:3px}");
        // —— 画质控制项：14px 加粗文字按钮（opacity .8 → hover 1）+ Popover 上弹面板 ——
        h.append(".nplayer .npq:hover{background:transparent}");
        h.append(".npq{display:flex;align-items:center}");
        h.append(".npq-label{font-size:14px;font-weight:700;color:#fff;opacity:.8;padding:0 6px;white-space:nowrap;transition:opacity .2s,color .2s}");
        h.append(".npq:hover .npq-label,.npq.npq-open .npq-label{opacity:1}");
        h.append(".npq.npq-open .npq-label{color:#fb7299}");
        h.append(".npq-item{padding:5px 20px;font-size:13px;color:#fff;cursor:pointer;text-align:center;white-space:nowrap}");
        h.append(".npq-item:hover{background:rgba(255,255,255,.3)}");
        h.append(".npq-item.ac{color:#fb7299}");
        // 「解除静音」悬浮层：autoplay 必须 muted，提示用户点击恢复声音
        h.append(".unmute-hint{position:absolute;top:12px;right:12px;z-index:60;display:none;align-items:center;gap:6px;padding:8px 14px;background:rgba(251,114,153,.92);color:#fff;border:none;border-radius:999px;font-size:13px;font-weight:500;cursor:pointer;box-shadow:0 4px 12px rgba(0,0,0,.35);backdrop-filter:blur(4px);transition:transform .2s}");
        h.append(".unmute-hint.show{display:inline-flex}");
        h.append(".unmute-hint:hover{transform:scale(1.04)}");
        // 错误覆盖层：盖在 nplayer 各部件（最高 z-index 50）之上
        h.append(".err-disp{position:absolute;inset:0;display:none;align-items:center;justify-content:center;color:#f87171;background:rgba(0,0,0,.85);z-index:60;text-align:center;padding:20px}");
        h.append("</style></head><body>");
        h.append("<div class=\"pwrap\" id=\"pwrap\"><div class=\"err-disp\" id=\"er\"></div>");
        h.append("<button id=\"unmuteBtn\" class=\"unmute-hint\" type=\"button\">🔊 点击解除静音</button></div>");
        h.append("<script>");
        h.append("var API='/plugins/bilibili-player/api';var BVID='").append(jsStr(bvid)).append("';var CID='").append(jsStr(cid)).append("';");
        h.append("var PROXY_MODE='").append(jsStr(proxyMode)).append("';var WORKER_BASE='").append(jsStr(workerUrl)).append("';var WORKER_TOKEN='").append(jsStr(workerToken)).append("';");
        h.append("var player=null,vEl=null,ps=null,aEl=null,rafId=0,usingDash=false,mp4Mode=false,switching=false;");
        h.append("var playData=null,fetchedAt=0,curQn=0,curVTrack=null,curATrack=null,failedCodecs={};");
        h.append("var vCands=[],vCandIdx=0,aCands=[],aCandIdx=0,mp4Cands=[],mp4CandIdx=0;");
        h.append("var loadWatchdog=0,decodeOk=false,waitingAudio=false,audioStalled=false,videoStalled=false,reloaded=false;");
        h.append("var qItemEl=null,qLabel=null,qPanel=null,qPopover=null;");
        h.append("var RELOAD_MS=110*60*1000;");

        // PlayerState for seamless quality switch
        h.append("function PlayerState(){this.ongoing=false;this.switchTime=0;this.isPlaying=false}");
        h.append("PlayerState.prototype.save=function(){if(this.ongoing&&vEl.currentTime===0)return;this.ongoing=false;this.switchTime=vEl.currentTime;this.isPlaying=!vEl.paused};");
        h.append("PlayerState.prototype.apply=function(){vEl.currentTime=this.switchTime;if(this.isPlaying){var p=vEl.play();if(p&&p.catch)p.catch(function(){})}this.ongoing=true};");
        h.append("ps=new PlayerState();");

        h.append("function getRefPage(){try{if(window.parent&&window.parent!==window&&window.parent.location&&window.parent.location.href)return window.parent.location.href}catch(e){}return document.referrer||''}");
        h.append("function tl(e,d){var u=API+'/player/log?event='+encodeURIComponent(e)+'&bvid='+BVID+'&cid='+CID+'&detail='+encodeURIComponent(d||'')+'&page='+encodeURIComponent(getRefPage());fetch(u,{keepalive:true,mode:'no-cors'}).catch(function(){})}");
        // 三级流分发：按 PROXY_MODE 产出有序候选 URL 数组（tier 优先，同 tier 内 baseUrl→backupUrl）
        //   smart: direct(baseUrl,backupUrl) → worker(…,仅已配置) → server(…,兜底)
        //   worker: worker → server；server: 仅服务器代理
        h.append("function candidates(rawUrls){var b=rawUrls[0]||'',bk=rawUrls[1]||'',out=[];if(bk===b)bk='';"
                + "function tier(fn){if(b)out.push(fn(b));if(bk)out.push(fn(bk))}"
                + "var direct=function(u){return u};"
                + "var worker=function(u){return WORKER_BASE+'?url='+encodeURIComponent(u)+(WORKER_TOKEN?'&token='+encodeURIComponent(WORKER_TOKEN):'')};"
                + "var server=function(u){return API+'/video/proxy?url='+encodeURIComponent(u)};"
                + "if(PROXY_MODE==='server'){tier(server)}else{if(PROXY_MODE!=='worker')tier(direct);if(WORKER_BASE)tier(worker);tier(server)}"
                + "return out}");
        h.append("function tierOf(u){if(WORKER_BASE&&u.indexOf(WORKER_BASE)===0)return 'worker';if(u.indexOf(API+'/video/proxy')===0)return 'server';return 'direct'}");
        h.append("function se(m){var er=document.getElementById('er');er.style.display='flex';er.textContent=m}");

        // Destroy audio element + RAF loop (MP4 模式下同样调用，关闭音画同步)
        h.append("function destroyAudio(){if(rafId){cancelAnimationFrame(rafId);rafId=0}if(aEl){try{aEl.pause();aEl.removeAttribute('src');aEl.load();aEl.parentNode.removeChild(aEl)}catch(e){}aEl=null}usingDash=false;waitingAudio=false;audioStalled=false;videoStalled=false}");

        // —— 流选择工具 ——
        h.append("function codecFamily(c){c=(c||'').toLowerCase();if(c.indexOf('avc1')===0)return 'avc1';if(c.indexOf('av01')===0)return 'av01';if(c.indexOf('hev1')===0||c.indexOf('hvc1')===0)return 'hevc';return 'other'}");
        h.append("function probe(t){try{var el=document.createElement('video');return el.canPlayType((t.mimeType||'video/mp4')+'; codecs=\"'+t.codecs+'\"')}catch(e){return ''}}");
        // 同编码多条码流取 bandwidth 适中者
        h.append("function pickModerate(list){list=list.slice().sort(function(a,b){return a.bandwidth-b.bandwidth});return list[Math.floor(list.length/2)]}");
        // 音频取最高 bandwidth 轨
        h.append("function pickAudioTrack(){if(!playData||!playData.dash)return null;var auds=playData.dash.audio||[];if(!auds.length)return null;var best=auds[0];for(var i=0;i<auds.length;i++){if(auds[i].bandwidth>best.bandwidth)best=auds[i]}return best}");
        // 按目标 qn 过滤；qn 缺流时向 accept_quality 相邻更低档降级；编码优先级 avc1 > av01(probably) > hevc(probably)
        h.append("function pickVideoTrack(qn){"
                + "if(!playData||!playData.dash)return null;"
                + "var vids=playData.dash.video||[];if(!vids.length)return null;"
                + "var aq=(playData.acceptQuality||[]).slice().sort(function(a,b){return b-a});"
                + "var qnUsed=qn,cands=vids.filter(function(t){return t.id===qnUsed});"
                + "while(!cands.length){var next=-1;for(var i=0;i<aq.length;i++){if(aq[i]<qnUsed){var cc=vids.filter(function(t){return t.id===aq[i]});if(cc.length){next=aq[i];break}}}if(next<0)return null;qnUsed=next;cands=vids.filter(function(t){return t.id===qnUsed});}"
                + "var failed=failedCodecs[qnUsed]||{};"
                + "var avc=[],av1=[],hev=[],other=[];"
                + "cands.forEach(function(t){var f=codecFamily(t.codecs);if(failed[f])return;if(f==='avc1')avc.push(t);else if(f==='av01')av1.push(t);else if(f==='hevc')hev.push(t);else other.push(t)});"
                + "if(avc.length)return{track:pickModerate(avc),qnUsed:qnUsed};"
                + "var av1ok=av1.filter(function(t){return probe(t)==='probably'});if(av1ok.length)return{track:pickModerate(av1ok),qnUsed:qnUsed};"
                + "var hevok=hev.filter(function(t){return probe(t)==='probably'});if(hevok.length)return{track:pickModerate(hevok),qnUsed:qnUsed};"
                + "return null}");

        // —— 数据获取：首次拿全量 DASH 列表，之后清晰度切换纯前端换轨；仅强制/过期才重取 ——
        h.append("async function ensureData(force){"
                + "if(force||!playData||(Date.now()-fetchedAt>RELOAD_MS)){"
                + "tl('fetch',force?'force':(playData?'expire':'init'));"
                + "var r=await fetch(API+'/video/playurl?bvid='+BVID+'&cid='+CID);var d=await r.json();"
                + "if(d.error)throw new Error(d.error);"
                + "playData=d;fetchedAt=Date.now();failedCodecs={};updateResMenu();"
                + "tl('fetched','strategy='+(d.strategy||'?')+' quality='+d.quality+' vTracks='+(d.dash&&d.dash.video?d.dash.video.length:0));"
                + "}}");

        h.append("async function applyQuality(qn){"
                + "tl('load','qn='+qn);"
                + "try{"
                + "await ensureData(false);"
                + "if(!playData.dash||!playData.dash.video||!playData.dash.video.length){"
                + "if(playData.durl&&playData.durl.length){playMp4(playData.durl[0].url,playData.durl[0].backupUrl||'');return}"
                + "se('No stream');return}"
                + "var target=qn||playData.quality||80;"
                + "var p=pickVideoTrack(target);"
                + "if(!p){switchToMp4('noTrack');return}"
                + "if(p.qnUsed!==target)tl('qnFallback','req='+target+' use='+p.qnUsed);"
                + "applyPicked(p);"
                + "}catch(e){se(e.message);tl('loadErr',e.message)}}");

        h.append("function applyPicked(p){var aT=pickAudioTrack();if(!aT){switchToMp4('noAudio');return}curQn=p.qnUsed;updateResMenu();ps.save();playDASH(p.track,aT);ps.apply()}");

        // DASH 播放：video 元素承载视频，隐藏 audio 元素承载音频，通过 RAF 同步。
        //   1) 先 pause 再切 src：避免上一次 play() Promise 被 load() 内部重置打断
        //   2) 用 canplay 事件作为 play() 的触发点，不在 load() 还在清缓冲时立即 play
        //   3) 避免重复调用 play：由 started 标记只触发一次
        h.append("function playDASH(vT,aT){"
                + "destroyAudio();clearWatchdog();switching=true;"
                + "curVTrack=vT;curATrack=aT;decodeOk=false;"
                + "vCands=candidates([vT.baseUrl,vT.backupUrl||'']);vCandIdx=0;"
                + "aCands=candidates([aT.baseUrl,aT.backupUrl||'']);aCandIdx=0;"
                + "try{vEl.pause()}catch(e){}"
                + "vEl.removeAttribute('src');vEl.load();vEl.src=vCands[0];vEl.load();"
                + "aEl=document.createElement('audio');aEl.style.display='none';aEl.preload='auto';"
                + "aEl.addEventListener('error',function(){handleMediaError('a')});"
                + "aEl.addEventListener('waiting',function(){audioStalled=true});"
                + "aEl.addEventListener('stalled',function(){audioStalled=true});"
                + "aEl.addEventListener('canplay',function(){audioStalled=false;resumeFromWait()});"
                + "aEl.addEventListener('playing',function(){audioStalled=false;resumeFromWait()});"
                + "document.body.appendChild(aEl);aEl.src=aCands[0];aEl.load();aEl.volume=vEl.volume;"
                + "usingDash=true;mp4Mode=false;"
                + "tl('dash','qn='+vT.id+' v='+vT.codecs+' bw='+vT.bandwidth+' a='+aT.codecs+' abw='+aT.bandwidth+' '+vT.width+'x'+vT.height);"
                + "armWatchdog();"
                + "var started=false;function tryPlay(reason){if(started)return;started=true;var p=vEl.play();if(p&&typeof p.catch==='function')p.catch(function(err){if(err&&err.name==='AbortError')return;tl('playErr',err.name+':'+err.message);se('播放失败：'+err.message)});tl('playTrigger',reason)}"
                + "vEl.addEventListener('canplay',function on1(){vEl.removeEventListener('canplay',on1);tryPlay('canplay')},{once:true});"
                + "vEl.addEventListener('loadedmetadata',function on2(){vEl.removeEventListener('loadedmetadata',on2);setTimeout(function(){tryPlay('metadata')},100)},{once:true});"
                + "switching=false;syncLoop()}");

        h.append("function resumeFromWait(){if(!waitingAudio)return;waitingAudio=false;if(vEl&&vEl.paused&&!vEl.ended){var p=vEl.play();if(p&&p.catch)p.catch(function(){})}tl('syncResume','')}");

        // 解码失败看门狗：选流后 5s 内无 loadeddata/progress 判定解码失败
        h.append("function armWatchdog(){clearWatchdog();loadWatchdog=setTimeout(function(){if(decodeOk||mp4Mode)return;if(vEl&&vEl.readyState>=2){decodeOk=true;return}onDecodeFail()},5000)}");
        h.append("function clearWatchdog(){if(loadWatchdog){clearTimeout(loadWatchdog);loadWatchdog=0}}");

        // 解码回退链：同 qn 换次优编码 → 整档降 qn → MP4 单文件模式
        h.append("function onDecodeFail(){"
                + "if(mp4Mode||!curVTrack)return;"
                + "var qn=curVTrack.id,fam=codecFamily(curVTrack.codecs);"
                + "if(!failedCodecs[qn])failedCodecs[qn]={};failedCodecs[qn][fam]=true;"
                + "tl('decodeFail','qn='+qn+' codec='+curVTrack.codecs);"
                + "var p=pickVideoTrack(qn);"
                + "if(p){tl('fallback','sameQn codec='+p.track.codecs);applyPicked(p);return}"
                + "var aq=(playData.acceptQuality||[]).slice().sort(function(a,b){return b-a});"
                + "for(var i=0;i<aq.length;i++){if(aq[i]>=qn)continue;var lp=pickVideoTrack(aq[i]);if(lp){tl('fallback','degrade qn='+lp.qnUsed);applyPicked(lp);return}}"
                + "switchToMp4('decodeFail')}");

        // MP4 单文件模式：关闭 RAF 同步、移除隐藏 audio，音画同体直出
        h.append("async function switchToMp4(reason){"
                + "if(mp4Mode)return;"
                + "tl('mp4Fallback',reason);"
                + "var url='',bk='';"
                + "if(playData&&playData.durl&&playData.durl.length){url=playData.durl[0].url;bk=playData.durl[0].backupUrl||''}"
                + "else{try{var r=await fetch(API+'/video/playurl?bvid='+BVID+'&cid='+CID+'&qn=64&fnval=1');var d=await r.json();if(d.durl&&d.durl.length){url=d.durl[0].url;bk=d.durl[0].backupUrl||''}}catch(e){}}"
                + "if(!url){se('无法获取可用流');return}"
                + "playMp4(url,bk)}");

        h.append("function playMp4(url,backup){"
                + "destroyAudio();clearWatchdog();"
                + "mp4Mode=true;mp4Cands=candidates([url,backup||'']);mp4CandIdx=0;"
                + "try{vEl.pause()}catch(e){}"
                + "vEl.removeAttribute('src');vEl.load();vEl.src=mp4Cands[0];vEl.load();"
                + "tl('mp4',url.substring(0,60));"
                + "var p=vEl.play();if(p&&p.catch)p.catch(function(e){if(e.name!=='AbortError')tl('playErr',e.name)});"
                + "try{setQualityLabel('MP4')}catch(e){}}");

        // 候选回退：error 时换下一个候选 URL 重试并保持 currentTime；全部耗尽则重取 playurl（可能 403/过期）
        h.append("function handleMediaError(kind){"
                + "if(switching)return;"
                + "var v=vEl;var el=kind==='v'?v:aEl;"
                + "if(!el||!el.currentSrc)return;"
                + "tl('mediaErr',kind+' code='+(el.error?el.error.code:'?'));"
                + "if(mp4Mode){"
                + "if(mp4CandIdx+1<mp4Cands.length){mp4CandIdx++;var t=el.currentTime;switching=true;el.src=mp4Cands[mp4CandIdx];el.load();switching=false;try{el.currentTime=t}catch(e){}var mp=el.play();if(mp&&mp.catch)mp.catch(function(){});tl('proxyTier',tierOf(mp4Cands[mp4CandIdx])+' idx='+mp4CandIdx)}"
                + "else maybeReload('mp4-err');return}"
                + "if(kind==='v'&&curVTrack){"
                + "if(vCandIdx+1<vCands.length){vCandIdx++;var t2=el.currentTime;switching=true;el.src=vCands[vCandIdx];el.load();switching=false;try{el.currentTime=t2}catch(e){}var vp=el.play();if(vp&&vp.catch)vp.catch(function(){});tl('proxyTier',tierOf(vCands[vCandIdx])+' idx='+vCandIdx+' t='+t2.toFixed(1))}"
                + "else maybeReload('video-err')}"
                + "else if(kind==='a'&&curATrack&&aEl){"
                + "if(aCandIdx+1<aCands.length){aCandIdx++;switching=true;aEl.src=aCands[aCandIdx];aEl.load();switching=false;try{aEl.currentTime=v.currentTime}catch(e){}tl('proxyTier',tierOf(aCands[aCandIdx])+' idx='+aCandIdx)}"
                + "else maybeReload('audio-err')}}");

        h.append("async function maybeReload(reason){"
                + "if(reloaded){se('播放失败：所有 CDN 均不可用');tl('giveUp',reason);return}"
                + "reloaded=true;tl('reload',reason);"
                + "try{"
                + "await ensureData(true);"
                + "if(!playData.dash||!playData.dash.video||!playData.dash.video.length){"
                + "if(playData.durl&&playData.durl.length){playMp4(playData.durl[0].url,playData.durl[0].backupUrl||'');return}"
                + "se('No stream');return}"
                + "var p=pickVideoTrack(curQn||playData.quality);"
                + "if(!p){switchToMp4('reload-noTrack');return}"
                + "applyPicked(p);"
                + "}catch(e){se(e.message);tl('reloadErr',e.message)}}");

        // audio 元素 buffered 末端（以 audio 当前播放点所在区间为准）
        h.append("function bufEnd(el){try{var b=el.buffered,t=el.currentTime;for(var i=0;i<b.length;i++){if(t>=b.start(i)-0.1&&t<=b.end(i)+0.1)return b.end(i)}if(b.length)return b.end(b.length-1)}catch(e){}return el.currentTime}");

        // RAF-based audio sync loop: ±0.15s 纠偏 + 播放/暂停/倍速跟随；
        // 视频卡顿（waiting）期间暂停音频并冻结纠偏，防止音频跑飞后被反复拽回重放同一小段；
        // 仅当音频真正断粮（缓冲末端落后于播放点，或 stalled 且前瞻缓冲不足 0.5s）才暂停 video，
        //   等 audio canplay 再恢复；±0.15s 纠偏产生的瞬时 seeking 不触发暂停，避免 play/pause 抖动
        h.append("function syncLoop(){if(rafId)cancelAnimationFrame(rafId);rafId=requestAnimationFrame(function tick(){rafId=requestAnimationFrame(tick);if(!aEl||!usingDash)return;var v=vEl;if(!v)return;"
                + "if(videoStalled){if(!aEl.paused)aEl.pause()}"
                + "else{var dt=v.currentTime-aEl.currentTime;if(Math.abs(dt)>0.15){if(!aEl.paused)aEl.currentTime=v.currentTime}"
                + "if(v.paused&&!aEl.paused){aEl.pause()}else if(!v.paused&&aEl.paused&&!waitingAudio){aEl.play().catch(function(){})}}"
                + "aEl.volume=v.muted?0:v.volume;aEl.playbackRate=v.playbackRate;"
                + "if(!v.paused&&!v.ended&&!waitingAudio){var abuf=bufEnd(aEl);var starved=(v.currentTime-abuf>1)||(audioStalled&&abuf<v.currentTime+0.5);if(starved){waitingAudio=true;tl('syncWait','abuf='+abuf.toFixed(1)+' vt='+v.currentTime.toFixed(1));v.pause()}}"
                + "})}");

        // Set up video-level event hooks for audio sync
        //   waiting：视频卡顿即暂停音频；playing：恢复时一次性对齐音频进度再继续
        h.append("function wireAudioHooks(){var v=vEl;v.addEventListener('seeked',function(){if(aEl)aEl.currentTime=v.currentTime});v.addEventListener('ratechange',function(){if(aEl)aEl.playbackRate=v.playbackRate});v.addEventListener('volumechange',function(){if(aEl)aEl.volume=v.muted?0:v.volume});"
                + "v.addEventListener('waiting',function(){videoStalled=true;if(aEl&&!aEl.paused)aEl.pause()});"
                + "v.addEventListener('playing',function(){if(videoStalled){videoStalled=false;if(aEl&&usingDash){try{aEl.currentTime=v.currentTime}catch(e){}if(aEl.paused)aEl.play().catch(function(){})}}})}");

        // —— 画质选择：nplayer 自定义控制项（文字按钮 + Popover 上弹面板），切换纯前端换轨，不重调 API ——
        h.append("function setQualityLabel(t){if(qLabel)qLabel.textContent=t}");
        h.append("function openQualityMenu(){if(qPopover){qPopover.show();if(qItemEl)qItemEl.classList.add('npq-open')}}");
        h.append("function closeQualityMenu(){if(qPopover)qPopover.hide()}");
        h.append("function toggleQualityMenu(){if(!qPopover)return;if(qItemEl&&qItemEl.classList.contains('npq-open'))closeQualityMenu();else openQualityMenu()}");
        h.append("function updateQbtn(){if(!playData)return;var idx=(playData.acceptQuality||[]).indexOf(curQn);setQualityLabel(idx>=0?playData.acceptDescription[idx]:'自动')}");
        h.append("function updateResMenu(){if(!qPanel||!playData||!playData.acceptQuality||!playData.acceptQuality.length)return;var aq=playData.acceptQuality,ad=playData.acceptDescription||[];qPanel.innerHTML='';for(var i=0;i<aq.length;i++){(function(qn,desc){var d=document.createElement('div');d.className='npq-item'+(qn===curQn?' ac':'');d.textContent=desc;d.addEventListener('click',function(e){e.stopPropagation();closeQualityMenu();reloaded=false;applyQuality(qn)});qPanel.appendChild(d)})(aq[i],ad[i]||(aq[i]+'P'))}updateQbtn()}");
        h.append("document.addEventListener('keydown',function(e){if(e.key==='Escape'||e.keyCode===27)closeQualityMenu()});");

        // 画中画控制项（nplayer 默认仅右键菜单提供 PiP，这里补控制栏按钮保持原有功能）
        h.append("function togglePip(){try{if(document.pictureInPictureElement){document.exitPictureInPicture()}else if(vEl){var p=vEl.requestPictureInPicture();if(p&&p.catch)p.catch(function(e){tl('pipErr',e.message)})}}catch(e){tl('pipErr',e.message)}}");

        // nplayer 事件绑定 + 解码看门狗喂狗 + 候选回退入口（全部绑定在自建 video 元素上）
        h.append("function videoEvents(){var v=vEl;v.addEventListener('play',function(){tl('play','t='+v.currentTime.toFixed(1))});v.addEventListener('pause',function(){tl('pause','t='+v.currentTime.toFixed(1))});v.addEventListener('seeked',function(){tl('seeked','t='+v.currentTime.toFixed(1))});v.addEventListener('ended',function(){tl('ended','')});v.addEventListener('error',function(){tl('error','c='+(v.error?v.error.code:'?'));handleMediaError('v')});v.addEventListener('loadeddata',function(){decodeOk=true;reloaded=false;clearWatchdog()});v.addEventListener('progress',function(){decodeOk=true;clearWatchdog()});v.addEventListener('volumechange',function(){var btn=document.getElementById('unmuteBtn');if(!btn)return;if(v.muted||v.volume===0){btn.classList.add('show')}else{btn.classList.remove('show')}});var lastWaiting=0;v.addEventListener('waiting',function(){var now=Date.now();if(now-lastWaiting<1000)return;lastWaiting=now;tl('stall','t='+v.currentTime.toFixed(1));v.addEventListener('canplay',function x(){v.removeEventListener('canplay',x);tl('recover','canplay');if(v.paused&&!v.ended&&!waitingAudio){var p=v.play();if(p&&p.catch)p.catch(function(err){if(err.name!=='AbortError')tl('recoverErr',err.name)})}},{once:true})})};");

        // 「解除静音」按钮：用户主动点击才 unmute，并同时同步到 audio 元素（DASH 模式）
        h.append("function wireUnmuteBtn(){var btn=document.getElementById('unmuteBtn');if(!btn)return;btn.addEventListener('click',function(e){e.stopPropagation();try{vEl.muted=false;if(vEl.volume===0)vEl.volume=1;if(aEl){aEl.muted=false;aEl.volume=vEl.volume}btn.classList.remove('show');tl('unmute','manual')}catch(err){tl('unmuteErr',err.message)}})};");

        // nplayer init：自建 video 元素传入（autoplay+muted+playsinline），控制栏两行布局，
        //   画质/画中画为自定义控制项（对象直接写进 controls，无需等 register 时机），
        //   主题色/进度条/音量条统一 B 站粉；settings:['speed'] 启用内置倍速面板
        h.append("var qualityItem={el:document.createElement('div'),id:'quality',tip:'清晰度',"
                + "init:function(){var el=qualityItem.el;el.classList.add('npq');qItemEl=el;"
                + "qLabel=document.createElement('span');qLabel.className='npq-label';qLabel.textContent='自动';el.appendChild(qLabel);"
                + "qPopover=new NPlayer.Popover(el,function(){el.classList.remove('npq-open')});qPanel=qPopover.panelEl;qPanel.classList.add('npq-panel');"
                + "el.addEventListener('click',function(e){e.stopPropagation();toggleQualityMenu()})},"
                + "update:function(position){if(qPopover){if(position===2)qPopover.setBottom();else qPopover.resetPos()}},"
                + "hide:function(){closeQualityMenu()}};");
        h.append("var pipItem={el:document.createElement('div'),id:'pip',tip:'画中画',"
                + "isSupport:function(){return !!document.pictureInPictureEnabled},"
                + "init:function(){var el=pipItem.el;var w=document.createElement('span');w.className='nppip';"
                + "w.innerHTML='<svg class=\"nplayer_icon\" viewBox=\"0 0 24 24\"><path d=\"M19 7h-8v6h8V7zm2-4H3c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H3V5h18v14z\"/></svg>';"
                + "el.appendChild(w);el.addEventListener('click',function(e){e.stopPropagation();togglePip()})}};");
        h.append("function initPlayer(){"
                + "vEl=document.createElement('video');vEl.autoplay=true;vEl.muted=true;vEl.playsInline=true;vEl.preload='auto';"
                + "vEl.setAttribute('playsinline','');vEl.setAttribute('webkit-playsinline','');"
                + "player=new NPlayer.Player({video:vEl,i18n:'zh-cn',themeColor:'#fb7299',progressBg:'#fb7299',volumeProgressBg:'#fb7299',"
                + "videoProps:{preload:'auto',playsinline:'true',autoplay:'true',muted:'true'},"
                + "settings:['speed'],"
                + "controls:[['play','volume','time','spacer',qualityItem,pipItem,'settings','web-fullscreen','fullscreen'],['progress']],"
                + "bpControls:{650:[['play','progress','time','web-fullscreen','fullscreen'],[],['spacer',qualityItem,pipItem,'settings']]}});"
                + "player.mount(document.getElementById('pwrap'));"
                // 控制栏自动隐藏时画质面板一并收起（ControlHide 事件）
                + "player.on((NPlayer.EVENT&&NPlayer.EVENT.CONTROL_HIDE)||'ControlHide',function(){closeQualityMenu()});"
                + "}");

        h.append("if(window.NPlayer&&NPlayer.Player){"
                + "initPlayer();"
                + "videoEvents();wireAudioHooks();wireUnmuteBtn();"
                // autoplay 成功后，提示用户点击按钮取消静音
                + "tl('ready','');applyQuality(80);"
                + "setTimeout(function(){if(vEl&&vEl.muted){var btn=document.getElementById('unmuteBtn');if(btn)btn.classList.add('show')}},800)"
                + "}else{se('播放器组件加载失败，请检查网络后刷新');tl('playerLibErr','nplayer-missing')}");
        h.append("</script></body></html>");
        return h.toString();
    }
}
