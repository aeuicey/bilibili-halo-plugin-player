package com.halo.bilibiliplayer.controller;

import com.halo.bilibiliplayer.service.BilibiliApiService;
import com.halo.bilibiliplayer.service.LogService;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@RestController
public class VideoController {

    private final BilibiliApiService bilibiliApiService;
    private final LogService logService;
    private final HttpClient proxyClient;
    private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    public VideoController(BilibiliApiService bilibiliApiService, LogService logService) {
        this.bilibiliApiService = bilibiliApiService;
        this.logService = logService;
        this.proxyClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @GetMapping("/plugins/bilibili-player/api/video/info")
    public Mono<String> getVideoInfo(@RequestParam String bvid) {
        return Mono.fromCallable(() -> bilibiliApiService.getVideoInfo(bvid));
    }

    @PostMapping("/plugins/bilibili-player/api/player/log")
    public Mono<Void> receivePlayerLog(@RequestBody Map<String, Object> body,
                                       ServerHttpRequest request) {
        return Mono.fromRunnable(() -> logTelemetry(body, request));
    }

    @GetMapping("/plugins/bilibili-player/api/player/log")
    public Mono<Void> receivePlayerLogGet(
            @RequestParam String event,
            @RequestParam(defaultValue = "") String bvid,
            @RequestParam(defaultValue = "") String cid,
            @RequestParam(defaultValue = "") String detail,
            @RequestParam(defaultValue = "") String page,
            ServerHttpRequest request) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("event", event);
        body.put("bvid", bvid);
        body.put("cid", cid);
        body.put("detail", detail);
        body.put("page", page);
        return Mono.fromRunnable(() -> logTelemetry(body, request));
    }

    /** 截断字符串避免日志过长 */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private void logTelemetry(Map<String, Object> body, ServerHttpRequest request) {
        try {
            String event = String.valueOf(body.getOrDefault("event", "unknown"));
            String bvid = String.valueOf(body.getOrDefault("bvid", ""));
            String cid = String.valueOf(body.getOrDefault("cid", ""));
            String detail = String.valueOf(body.getOrDefault("detail", ""));
            String page = String.valueOf(body.getOrDefault("page", ""));
            String referer = request != null ? request.getHeaders().getFirst("Referer") : null;

            String shortBvid = bvid.isEmpty() ? "-" : truncate(bvid, 12);
            String refSource = !page.isEmpty() ? truncate(page, 200)
                    : (referer != null ? truncate(referer, 200) : "-");

            // 结构化日志：[BV] event=xxx cid=xxx ref=xxx detail=xxx
            logService.info("[player] bvid={} cid={} event={} ref={} detail={}",
                    shortBvid, cid, event, refSource, truncate(detail, 200));
        } catch (Exception ignored) {
        }
    }

    @GetMapping("/plugins/bilibili-player/api/video/playurl")
    public Mono<String> getVideoPlayUrl(
            @RequestParam String bvid,
            @RequestParam long cid,
            @RequestParam(defaultValue = "64") int qn,
            @RequestParam(defaultValue = "80") int fnval,
            @RequestParam(defaultValue = "0") int nocache
    ) {
        return Mono.fromCallable(() ->
                bilibiliApiService.getVideoPlayUrl(bvid, String.valueOf(cid), qn, fnval, nocache == 1))
            .onErrorResume(e -> Mono.just(
                "{\"acceptQuality\":[],\"acceptDescription\":[],\"quality\":0,\"error\":\""
                + e.getMessage().replace("\"", "\\\"") + "\"}"
            ));
    }

    @GetMapping("/plugins/bilibili-player/api/video/proxy")
    public Mono<ResponseEntity<Flux<DataBuffer>>> proxyVideo(
            @RequestParam String url,
            ServerHttpRequest serverRequest
    ) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            logService.debug("Invalid proxy URI: " + url.substring(0, Math.min(url.length(), 100)));
            return Mono.just(ResponseEntity.status(400).body(Flux.empty()));
        }

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://www.bilibili.com")
                .header("Origin", "https://www.bilibili.com")
                .timeout(Duration.ofSeconds(60));

        String range = serverRequest.getHeaders().getFirst("Range");
        if (range != null && !range.isEmpty()) {
            reqBuilder.header("Range", range);
        }

        HttpRequest request = reqBuilder.GET().build();

        Mono<ResponseEntity<Flux<DataBuffer>>> proxyResult = Mono.fromFuture(
                proxyClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()))
                .timeout(Duration.ofSeconds(30))
                .flatMap(response -> {
                    int status = response.statusCode();

                    HttpHeaders headers = new HttpHeaders();
                    if (status >= 200 && status < 300) {
                        response.headers().map().forEach((k, v) -> {
                            String lk = k.toLowerCase();
                            if (lk.equals("content-type") || lk.equals("content-length")
                                    || lk.equals("content-range") || lk.equals("accept-ranges")
                                    || lk.equals("content-disposition")) {
                                headers.add(k, String.join(",", v));
                            }
                        });
                    }
                    headers.set("Access-Control-Allow-Origin", "*");
                    headers.set("Access-Control-Expose-Headers", "Content-Range, Accept-Ranges, Content-Length");

                    if (status >= 300) return Mono.just(ResponseEntity.status(status).headers(headers).body(Flux.empty()));

                    InputStream inputStream = response.body();
                    Flux<DataBuffer> flux = Flux.<DataBuffer, java.io.InputStream>generate(
                        () -> inputStream,
                        (stream, sink) -> {
                            try {
                                byte[] buf = new byte[65536];
                                int n = stream.read(buf);
                                if (n == -1) { stream.close(); sink.complete(); }
                                else {
                                    byte[] chunk = new byte[n];
                                    System.arraycopy(buf, 0, chunk, 0, n);
                                    sink.next(bufferFactory.wrap(chunk));
                                }
                            } catch (java.io.EOFException e) {
                                try { stream.close(); } catch (Exception ignored) {}
                                sink.complete();
                            } catch (Exception e) {
                                try { stream.close(); } catch (Exception ignored) {}
                                sink.error(e);
                            }
                            return stream;
                        },
                        stream -> { try { stream.close(); } catch (Exception ignored) {} }
                    ).subscribeOn(Schedulers.boundedElastic());
                    return Mono.just(ResponseEntity.status(status).headers(headers).body(flux));
                });

        return proxyResult.onErrorResume(e -> {
            logService.debug("Proxy error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return Mono.just(ResponseEntity.status(500).body(Flux.empty()));
        });
    }

    @GetMapping(value = "/plugins/bilibili-player/embed", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<ResponseEntity<String>> embedPlayer(@RequestParam String bvid, @RequestParam(defaultValue = "") String cid) {
        return Mono.fromCallable(() -> {
            StringBuilder h = new StringBuilder();
            h.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
            h.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
            h.append("<meta http-equiv=\"Cache-Control\" content=\"no-cache,no-store,must-revalidate\">");
            h.append("<title>Bilibili Player</title>");
            h.append("<link rel=\"preconnect\" href=\"https://vjs.zencdn.net\" crossorigin>");
            h.append("<link rel=\"preconnect\" href=\"https://upos-sz-mirrorali.bilivideo.com\" crossorigin>");
            h.append("<link rel=\"dns-prefetch\" href=\"https://upos-sz-mirrorcos.bilivideo.com\">");
            h.append("<link rel=\"dns-prefetch\" href=\"https://upos-sz-mirrorhw.bilivideo.com\">");
            h.append("<link rel=\"dns-prefetch\" href=\"https://upos-sz-mirrorbd.bilivideo.com\">");
            h.append("<link rel=\"dns-prefetch\" href=\"https://proxy-tf-all-ws.bilivideo.com\">");
            // 主 CDN + 备选 CDN：避免编辑器 iframe 中被 CSP 拦截或 CDN 单点故障导致样式不加载
            h.append("<link id=\"vjs-css\" href=\"https://vjs.zencdn.net/8.23.4/video-js.css\" rel=\"stylesheet\" crossorigin=\"anonymous\"");
            h.append(" onerror=\"this.onerror=null;this.href='https://cdn.jsdelivr.net/npm/video.js@8.23.4/dist/video-js.min.css'\"/>");
            h.append("<style>");
            // —— 关键：内联 Video.js 必备的布局样式，避免 CDN 未就绪时控件错位、主题色失效 ——
            h.append(".video-js{display:block;vertical-align:top;box-sizing:border-box;color:#fff;background-color:#000;position:relative;padding:0;font-size:10px;line-height:1;font-weight:400;font-style:normal;font-family:Arial,Helvetica,sans-serif;word-break:initial;-webkit-user-select:none;user-select:none}");
            h.append(".video-js *,.video-js *::before,.video-js *::after{box-sizing:inherit}");
            h.append(".video-js video{position:absolute;top:0;left:0;width:100%;height:100%;display:block}");
            h.append(".video-js .vjs-tech{position:absolute;top:0;left:0;width:100%;height:100%}");
            h.append(".video-js.vjs-fluid{max-width:100%;width:100%;height:0;padding-top:56.25%}");
            h.append(".video-js .vjs-control-bar{display:flex;visibility:visible;opacity:1;position:absolute;bottom:0;left:0;right:0;width:100%;height:3em;background-color:rgba(43,51,63,.7)}");
            h.append(".vjs-has-started .vjs-control-bar{display:flex;visibility:visible;opacity:1;transition:visibility .1s,opacity .1s}");
            h.append(".video-js .vjs-control{position:relative;text-align:center;margin:0;padding:0;height:100%;width:4em;flex:none}");
            h.append(".video-js .vjs-button{background:none;color:inherit;border:none;cursor:pointer;outline:none}");
            h.append(".video-js .vjs-control:focus,.video-js .vjs-control:hover{text-shadow:0 0 1em #fff}");
            h.append(".video-js .vjs-progress-control{cursor:pointer;flex:auto;display:flex;align-items:center;min-width:4em;touch-action:none}");
            h.append(".video-js .vjs-progress-holder{flex:auto;transition:all .2s;height:.3em}");
            h.append(".video-js .vjs-progress-holder .vjs-load-progress,.video-js .vjs-progress-holder .vjs-load-progress div,.video-js .vjs-progress-holder .vjs-play-progress{position:absolute;display:block;height:100%;margin:0;padding:0;width:0;left:0;top:0}");
            h.append(".video-js .vjs-time-control{flex:none;font-size:1em;line-height:3em;min-width:2em;width:auto;padding-left:1em;padding-right:1em}");
            h.append(".video-js .vjs-volume-panel{display:flex;align-items:center}");
            h.append(".video-js .vjs-volume-bar{margin:1.35em .45em}");
            h.append(".video-js .vjs-slider{position:relative;cursor:pointer;padding:0;margin:0 .45em;background-color:rgba(115,133,159,.5)}");
            h.append(".video-js .vjs-hidden{display:none!important}");
            h.append(".video-js .vjs-big-play-button{font-size:3em;line-height:1.5em;height:1.5em;width:3em;display:block;position:absolute;top:10px;left:10px;padding:0;cursor:pointer;opacity:1;border:.06666em solid #fff;background-color:#2b333f;background-color:rgba(43,51,63,.7);border-radius:.3em;transition:all .4s}");
            h.append(".vjs-big-play-button .vjs-icon-placeholder::before{content:'\\25B6';position:absolute;top:0;left:0;width:100%;height:100%;text-align:center;font-size:1.63em;line-height:2.3em}");
            h.append(".vjs-has-started .vjs-big-play-button{display:none}");
            // 原有样式
            h.append("*{margin:0;padding:0;box-sizing:border-box}");
            h.append("html,body{height:100%}body{background:#000;display:flex;flex-direction:column;overflow:hidden;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}");
            h.append(".qbar{display:flex;align-items:center;padding:0 12px;height:36px;background:rgba(0,0,0,.85);flex-shrink:0;z-index:20}");
            h.append(".qbar .qlabel{font-size:12px;color:#888;margin-right:8px}");
            h.append(".qselect{position:relative}");
            h.append(".qsbtn{display:flex;align-items:center;gap:4px;padding:4px 10px;font-size:12px;color:#fff;background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.12);border-radius:4px;cursor:pointer;font-family:inherit}");
            h.append(".qsbtn:hover{background:rgba(255,255,255,.15)}");
            h.append(".qsbtn .arr{font-size:10px;color:#888;transition:transform .2s}");
            h.append(".qsbtn.open .arr{transform:rotate(180deg)}");
            h.append(".qmenu{position:absolute;top:100%;left:0;margin-top:4px;background:rgba(0,0,0,.95);border:1px solid rgba(255,255,255,.12);border-radius:6px;overflow:hidden;display:none;min-width:100px;z-index:30}");
            h.append(".qmenu.show{display:block}");
            h.append(".qmi{padding:8px 16px;font-size:13px;color:#ccc;cursor:pointer;white-space:nowrap}");
            h.append(".qmi:hover{background:rgba(255,255,255,.1);color:#fff}");
            h.append(".qmi.ac{color:#fb7299}");
            h.append(".pwrap{flex:1;position:relative;width:100%;min-height:0;max-width:100%;margin:0 auto;aspect-ratio:16/9;background:#000}");
            h.append(".pwrap .video-js{position:absolute;inset:0;width:100%!important;height:100%!important;padding:0!important}");
            h.append(".pwrap .video-js .vjs-tech{position:absolute;top:0;left:0;width:100%;height:100%;object-fit:contain}");
            // 「解除静音」悬浮层：autoplay 必须 muted，提示用户点击恢复声音
            h.append(".unmute-hint{position:absolute;top:12px;right:12px;z-index:10;display:none;align-items:center;gap:6px;padding:8px 14px;background:rgba(251,114,153,.92);color:#fff;border:none;border-radius:999px;font-size:13px;font-weight:500;cursor:pointer;box-shadow:0 4px 12px rgba(0,0,0,.35);backdrop-filter:blur(4px);transition:transform .2s}");
            h.append(".unmute-hint.show{display:inline-flex}");
            h.append(".unmute-hint:hover{transform:scale(1.04)}");
            // —— B 站粉红主题（.vjs-bilibili-theme）强制覆盖，保证主题在编辑器 iframe 里正确呈现 ——
            h.append(".vjs-bilibili-theme .vjs-control-bar{background:linear-gradient(0deg,rgba(0,0,0,.75),rgba(0,0,0,.15));height:48px}");
            h.append(".vjs-bilibili-theme .vjs-button>.vjs-icon-placeholder:before{line-height:48px}");
            h.append(".vjs-bilibili-theme .vjs-time-control{line-height:48px}");
            h.append(".vjs-bilibili-theme .vjs-progress-control{position:absolute;top:-.7em;width:100%;height:0;z-index:1;padding:10px 0}");
            h.append(".vjs-bilibili-theme .vjs-progress-holder{position:absolute;margin:0 .5em!important;width:calc(100% - 1em)}");
            h.append(".vjs-bilibili-theme .vjs-play-progress{background-color:#fb7299!important}");
            h.append(".vjs-bilibili-theme .vjs-play-progress::before{color:#fb7299;font-size:.9em;top:-.3em}");
            h.append(".vjs-bilibili-theme .vjs-load-progress div{background:rgba(255,255,255,.3)}");
            h.append(".vjs-bilibili-theme .vjs-slider{background:rgba(255,255,255,.2)}");
            h.append(".vjs-bilibili-theme .vjs-volume-level{background:#fb7299!important}");
            h.append(".vjs-bilibili-theme .vjs-big-play-button{background:rgba(251,114,153,.85);border:2px solid #fff;border-radius:50%;width:70px;height:70px;line-height:66px;font-size:30px;top:50%;left:50%;margin-top:-35px;margin-left:-35px;transition:transform .2s,background .2s}");
            h.append(".vjs-bilibili-theme .vjs-big-play-button:hover{background:#fb7299;transform:scale(1.08)}");
            h.append(".vjs-bilibili-theme .vjs-big-play-button .vjs-icon-placeholder::before{line-height:66px}");
            h.append(".vjs-bilibili-theme .vjs-volume-panel{order:2}.vjs-bilibili-theme .vjs-picture-in-picture-control{order:8}");
            h.append(".vjs-error-disp{position:absolute;inset:0;display:none;align-items:center;justify-content:center;color:#f87171;background:rgba(0,0,0,.85);z-index:5;text-align:center;padding:20px}");
            h.append(".cdn-status{position:absolute;top:8px;left:50%;transform:translateX(-50%);z-index:10;padding:6px 16px;background:rgba(251,114,153,.9);color:#fff;border-radius:999px;font-size:12px;font-weight:500;display:none;white-space:nowrap}");
            h.append("</style></head><body>");
            h.append("<div class=\"qbar\"><span class=\"qlabel\">Quality</span><div class=\"qselect\"><button id=\"qbtn\" class=\"qsbtn\">720P <span class=\"arr\">▾</span></button><div id=\"qmenu\" class=\"qmenu\"></div></div><button id=\"refreshCdnBtn\" style=\"margin-left:8px;padding:4px 10px;font-size:12px;color:#fb7299;background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.12);border-radius:4px;cursor:pointer;font-family:inherit\" type=\"button\">🔄 刷新线路</button><span id=\"bufinfo\" style=\"margin-left:auto;font-size:11px;color:#aaa;display:none;white-space:nowrap\"></span></div>");
            h.append("<div class=\"pwrap\"><span id=\"cdnStatus\" class=\"cdn-status\"></span><div class=\"vjs-error-disp\" id=\"er\"></div>");
            h.append("<video id=\"v\" class=\"video-js vjs-default-skin vjs-bilibili-theme\" controls autoplay muted playsinline></video>");
            h.append("<button id=\"unmuteBtn\" class=\"unmute-hint\" type=\"button\">🔊 点击解除静音</button></div>");
            h.append("<script src=\"https://vjs.zencdn.net/8.23.4/video.min.js\"></script>");
            h.append("<script>");
            h.append("var API='/plugins/bilibili-player/api';var BVID='").append(bvid).append("';var CID='").append(cid).append("';");
            h.append("var player=null,ps=null,cq=0,aq=[],ad=[],firstPlay=true,aEl=null,rafId=0,usingDash=false,unmuteReq=false,preData=null,vLoadStart=0,aLoadStart=0,cdnStalls=0,cdnStallWindow=0,lastPauseTime=0,userPaused=false;");
            h.append("(function prefetchPlayurl(){fetch(API+'/video/playurl?bvid='+BVID+'&cid='+CID+'&qn=80&fnval=16').then(function(r){return r.text()}).then(function(t){if(t&&t.trim())preData=JSON.parse(t)}).catch(function(){})})();");
            h.append("function showBuffer(){var v=player&&player.el_?player.el_.querySelector('video'):null;var b=document.getElementById('bufinfo');if(!b)return;if(!v||!v.buffered||v.buffered.length===0){b.style.display='none';return}var pct=v.buffered.end(v.buffered.length-1)/v.duration*100;if(isNaN(pct)||pct<=0){b.style.display='none'}else if(pct>=99){b.style.display='none'}else{b.style.display='';b.textContent='缓冲 '+Math.round(pct)+'%'}}");

            // PlayerState for seamless quality switch
            h.append("function PlayerState(){this.ongoing=false;this.switchTime=0;this.isPlaying=false}");
            h.append("PlayerState.prototype.save=function(){if(this.ongoing&&player.currentTime()===0)return;this.ongoing=false;this.switchTime=player.currentTime();this.isPlaying=!player.paused()};");
            h.append("PlayerState.prototype.apply=function(){player.currentTime(this.switchTime);if(this.isPlaying)player.play();this.ongoing=true};");
            h.append("ps=new PlayerState();");

            h.append("function getRefPage(){try{if(window.parent&&window.parent!==window&&window.parent.location&&window.parent.location.href)return window.parent.location.href}catch(e){}return document.referrer||''}");
            h.append("function tl(e,d){var u=API+'/player/log?event='+encodeURIComponent(e)+'&bvid='+BVID+'&cid='+CID+'&detail='+encodeURIComponent(d||'')+'&page='+encodeURIComponent(getRefPage());fetch(u,{keepalive:true,mode:'no-cors'}).catch(function(){})}");
            h.append("function pu(u){return u}");
            h.append("function se(m){var er=document.getElementById('er');er.style.display='flex';er.textContent=m;try{player.addClass('vjs-error')}catch(e){}}");
            h.append("function refreshCdn(){if(cdnStalls&&cdnStalls<3)return;var st=document.getElementById('cdnStatus');st.style.display='block';st.textContent='正在优化线路...';var er=document.getElementById('er');fetch(API+'/video/playurl?bvid='+BVID+'&cid='+CID+'&qn='+cq+'&fnval=16&nocache=1').then(function(r){return r.text()}).then(function(t){if(!t||t.trim()==='')throw new Error('Empty');return JSON.parse(t)}).then(function(d){st.style.display='none';er.style.display='none';er.textContent='';player.removeClass('vjs-error');ps.save();if(d.dash&&d.dash.video&&d.dash.audio){var vT=d.dash.video,aT=d.dash.audio,vTr=vT[0],aTr=aT[0],i;for(i=0;i<vT.length;i++){if(vT[i].id===d.quality&&vT[i].codecs.indexOf('avc1')!==-1)vTr=vT[i]}for(i=0;i<aT.length;i++)if(aT[i].bandwidth>aTr.bandwidth)aTr=aT[i];playDASH(vTr.baseUrl,vTr.backupUrl||null,aTr.baseUrl,aTr.backupUrl||null,{v:vTr.codecs,a:aTr.codecs},vTr.width,vTr.height)}ps.apply();cdnStalls=0;cdnStallWindow=0;tl('cdnRefresh','ok')}).catch(function(e){st.style.display='none';er.style.display='flex';er.textContent='线路优化失败，请刷新页面';tl('cdnRefreshErr',e.message)})}");

            // Destroy audio element + RAF loop
            h.append("function destroyAudio(){if(rafId){cancelAnimationFrame(rafId);rafId=0}if(aEl){try{aEl.pause();aEl.removeAttribute('src');aEl.load();aEl.parentNode.removeChild(aEl)}catch(e){}aEl=null}usingDash=false}");

            // DASH 播放：video 元素承载视频，隐藏 audio 元素承载音频，通过 RAF 同步
            //   关键修复（AbortError: play() interrupted by pause）：
            //   1) 先 pause 再切 src：避免上一次 play() Promise 被 load() 内部重置打断
            //   2) 用 canplay 事件作为 play() 的触发点，不在 load() 还在清缓冲时立即 play
            //   3) 避免重复调用 play：由 startedOnce 标记只触发一次
            h.append("function playDASH(vUrl,vUrlBak,aUrl,aUrlBak,codecs,w,h){destroyAudio();var vEl=player.el_.querySelector('video');try{vEl.pause()}catch(e){}vEl.removeAttribute('src');vEl.load();vEl.addEventListener('loadstart',function(){vLoadStart=Date.now()},{once:true});vEl.addEventListener('canplay',function(){var t=(Date.now()-vLoadStart)/1000;if(t>5)tl('cdnSlow','video:'+t.toFixed(1)+'s')},{once:true});vEl.src=pu(vUrl);vEl.load();aEl=document.createElement('audio');aEl.style.display='none';document.body.appendChild(aEl);aEl.addEventListener('loadstart',function(){aLoadStart=Date.now()},{once:true});aEl.addEventListener('canplay',function(){var t=(Date.now()-aLoadStart)/1000;if(t>5)tl('cdnSlow','audio:'+t.toFixed(1)+'s')},{once:true});aEl.src=pu(aUrl);aEl.load();aEl.muted=true;aEl.volume=player.volume();usingDash=true;tl('dash','v='+codecs.v+' a='+codecs.a+' '+w+'x'+h);if(vUrlBak){vEl.addEventListener('error',function onVErr(){vEl.removeEventListener('error',onVErr);tl('videoFallback','using backup video URL');vEl.src=pu(vUrlBak);vEl.load();if(!player.paused())vEl.play().catch(function(){})},{once:true})}if(aUrlBak){aEl.addEventListener('error',function onAErr(){aEl.removeEventListener('error',onAErr);tl('audioFallback','using backup audio URL');aEl.src=pu(aUrlBak);aEl.load();if(!player.paused())aEl.play().catch(function(){})},{once:true})}if(unmuteReq){aEl.muted=false;aEl.volume=player.volume();aEl.play().catch(function(){})}var started=false;function tryPlay(reason){if(started)return;started=true;var p=vEl.play();if(p&&typeof p.catch==='function')p.catch(function(err){if(err&&err.name==='AbortError')return;tl('playErr',err.name+':'+err.message);se('播放失败：'+err.message)});tl('playTrigger',reason)}vEl.addEventListener('canplay',function on1(){vEl.removeEventListener('canplay',on1);tryPlay('canplay')},{once:true});vEl.addEventListener('loadedmetadata',function on2(){vEl.removeEventListener('loadedmetadata',on2);setTimeout(function(){tryPlay('metadata')},100)},{once:true});syncLoop()}");

            // RAF-based audio sync: play/pause/volume/rate. Only seek on large drift (>0.5s) with 2s cooldown to avoid stutter
            h.append("function syncLoop(){if(rafId)cancelAnimationFrame(rafId);var lastSeek=0,stalled=0,prevTime=-1,bufTick=0;rafId=requestAnimationFrame(function tick(){rafId=requestAnimationFrame(tick);if(!aEl||!usingDash)return;var v=player.el_.querySelector('video');if(!v)return;if(v.paused&&!aEl.paused){aEl.pause()}else if(!v.paused&&aEl.paused){aEl.play().catch(function(){})}aEl.volume=v.muted?0:player.volume();aEl.playbackRate=v.playbackRate;var dt=v.currentTime-aEl.currentTime,now=Date.now();if(Math.abs(dt)>0.5&&now-lastSeek>2000){if(!aEl.paused){aEl.currentTime=v.currentTime;lastSeek=now}}if(!v.paused&&!aEl.paused){if(aEl.currentTime===prevTime){stalled++;if(stalled>180){tl('audioStall','reloading');aEl.load();stalled=0}}else{stalled=0}prevTime=aEl.currentTime}if(v.paused&&!userPaused&&lastPauseTime>0&&now-lastPauseTime>15000){refreshCdn();lastPauseTime=0}bufTick++;if(bufTick%30===0)showBuffer()})}");

            // Set up video-level event hooks for audio sync
            h.append("function wireAudioHooks(){var v=player.el_.querySelector('video');v.addEventListener('seeked',function(){if(aEl)aEl.currentTime=v.currentTime});v.addEventListener('ratechange',function(){if(aEl)aEl.playbackRate=v.playbackRate});v.addEventListener('volumechange',function(){if(aEl)aEl.volume=v.muted?0:player.volume()})}");

            // Direct MP4 playback (≤720P, already muxed)
            h.append("function playDirect(url){destroyAudio();var u=pu(url);tl('direct',url.substring(0,60));player.el_.querySelector('video').src=u;player.load();player.play().catch(function(){})}");

            // Load quality: QN≥80 → DASH dual-element, QN<80 → muxed MP4
            h.append("async function loadQuality(qn){tl('load','qn='+qn);try{var d;if(qn===80&&preData){d=preData;preData=null;tl('prefetch','hit')}else{var r=await fetch(API+'/video/playurl?bvid='+BVID+'&cid='+CID+'&qn='+(qn||64)+'&fnval=16');if(!r.ok){throw new Error('HTTP '+r.status)}var t=await r.text();if(!t||t.trim()===''){throw new Error('Empty response')}d=JSON.parse(t)}cq=d.quality;aq=d.acceptQuality||[];ad=d.acceptDescription||[];updateResMenu();if(d.dash&&d.dash.video&&d.dash.video.length&&d.dash.audio&&d.dash.audio.length){var vT=d.dash.video,aT=d.dash.audio,vTr=vT[0],aTr=aT[0],i,altV=null;for(i=0;i<vT.length;i++){if(vT[i].id===d.quality){if(!vTr||vT[i].codecs.indexOf('avc1')!==-1)vTr=vT[i];else altV=vT[i]}}if(!vTr)vTr=altV||vT[0];for(i=0;i<aT.length;i++)if(aT[i].bandwidth>aTr.bandwidth)aTr=aT[i];playDASH(vTr.baseUrl,vTr.backupUrl||null,aTr.baseUrl,aTr.backupUrl||null,{v:vTr.codecs,a:aTr.codecs},vTr.width,vTr.height)}else if(d.durl&&d.durl.length>0){playDirect(d.durl[0].url)}else{se('No stream')}}catch(e){se(e.message);tl('loadErr',e.message)}}");

            // Quality menu outside video bar
            h.append("function updateResMenu(){var m=document.getElementById('qmenu');var b=document.getElementById('qbtn');if(!aq.length)return;m.innerHTML='';var ci=aq.indexOf(cq);b.childNodes[0].textContent=(ci>=0?ad[ci]:ad[0])||'720P';for(var i=0;i<aq.length;i++){(function(qn,desc){var d=document.createElement('div');d.className='qmi'+(aq[i]===cq?' ac':'');d.textContent=desc;d.addEventListener('click',function(e){e.stopPropagation();m.classList.remove('show');b.classList.remove('open');ps.save();loadQuality(qn);ps.apply()});m.appendChild(d)})(aq[i],ad[i])}}");
            h.append("document.getElementById('qbtn').addEventListener('click',function(e){e.stopPropagation();var m=document.getElementById('qmenu');var b=this;b.classList.toggle('open');m.classList.toggle('show')});");
            h.append("document.addEventListener('click',function(e){var qs=document.querySelector('.qselect');if(!qs.contains(e.target)){document.getElementById('qmenu').classList.remove('show');document.getElementById('qbtn').classList.remove('open')}});");

            // Video.js 事件绑定：
            //   - 不再在 play 事件里强制 muted=false，改由「解除静音」按钮触发
            //   - waiting 事件的恢复逻辑只在 canplay 再启动一次 play，移除 setTimeout 定时重试
            //     （定时器 + canplay 并发调用 play 会产生 AbortError: play() interrupted by pause）
            //   - CDN stall detection: 3+ waiting in 15s or 15s non-user pause → refreshCdn
            h.append("function videoEvents(){var v=player.el_.querySelector('video');v.addEventListener('play',function(){userPaused=false;lastPauseTime=0;tl('play','t='+player.currentTime().toFixed(1))});v.addEventListener('pause',function(){tl('pause','t='+player.currentTime().toFixed(1));if(!player.userActive()){lastPauseTime=Date.now()}else{userPaused=true}});v.addEventListener('seeked',function(){tl('seeked','t='+player.currentTime().toFixed(1))});v.addEventListener('ended',function(){tl('ended','')});v.addEventListener('error',function(){tl('error','c='+(v.error?v.error.code:'?'))});v.addEventListener('volumechange',function(){var btn=document.getElementById('unmuteBtn');if(!btn)return;if(v.muted||player.volume()===0){btn.classList.add('show')}else{btn.classList.remove('show')}});var lastWaiting=0;v.addEventListener('waiting',function(){var now=Date.now();if(now-lastWaiting<1000)return;lastWaiting=now;cdnStalls++;if(!cdnStallWindow){cdnStallWindow=now}if(now-cdnStallWindow>15000){cdnStalls=1;cdnStallWindow=now}if(cdnStalls>=3){refreshCdn()}tl('stall','t='+player.currentTime().toFixed(1));v.addEventListener('canplay',function x(){v.removeEventListener('canplay',x);tl('recover','canplay');if(v.paused&&!v.ended){var p=v.play();if(p&&p.catch)p.catch(function(err){if(err.name!=='AbortError')tl('recoverErr',err.name)})}},{once:true})})};");
            h.append("document.getElementById('refreshCdnBtn').addEventListener('click',function(e){e.stopPropagation();userPaused=false;refreshCdn()});");

            // 「解除静音」按钮：用户主动点击才 unmute，并同时同步到 audio 元素（DASH 模式）
            h.append("function wireUnmuteBtn(){var btn=document.getElementById('unmuteBtn');if(!btn)return;btn.addEventListener('click',function(e){e.stopPropagation();try{player.muted(false);if(player.volume()===0)player.volume(1);unmuteReq=true;if(aEl){aEl.muted=false;aEl.volume=player.volume();aEl.play().catch(function(err){if(err.name!=='AbortError')tl('unmuteErr',err.name+':'+err.message)})}btn.classList.remove('show');tl('unmute','manual')}catch(err){tl('unmuteErr',err.message)}})};");

            // Video.js init —— 不再使用 fluid:true，避免 padding-top 自撑高度与外层 aspect-ratio 冲突；
            //   尺寸完全交给 .pwrap (aspect-ratio:16/9) + .video-js (inset:0) 控制
            h.append("player=videojs('v',{controls:true,preload:'auto',fluid:false,fill:true,autoplay:true,muted:true,controlBar:{children:['playToggle','volumePanel','currentTimeDisplay','timeDivider','durationDisplay','progressControl','pictureInPictureToggle','fullscreenToggle']}});");
            h.append("player.addClass('vjs-bilibili-theme');");
            h.append("videoEvents();wireAudioHooks();wireUnmuteBtn();");
            // autoplay 成功后，提示用户点击按钮取消静音
            h.append("player.ready(function(){tl('ready','');loadQuality(80).then(function(){var v=player.el_.querySelector('video');if(aEl&&v&&v.muted){var btn=document.getElementById('unmuteBtn');if(btn)btn.classList.add('show')}}).catch(function(){var v=player.el_.querySelector('video');if(aEl&&v&&v.muted){var btn=document.getElementById('unmuteBtn');if(btn)btn.classList.add('show')}})});");
            h.append("</script></body></html>");
            return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(h.toString());
        });
    }
}
