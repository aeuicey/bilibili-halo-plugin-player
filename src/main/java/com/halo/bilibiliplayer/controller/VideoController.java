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

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
                .build();
    }

    @GetMapping("/plugins/bilibili-player/api/video/info")
    public Mono<String> getVideoInfo(@RequestParam String bvid) {
        return Mono.fromCallable(() -> bilibiliApiService.getVideoInfo(bvid));
    }

    @PostMapping("/plugins/bilibili-player/api/player/log")
    public Mono<Void> receivePlayerLog(@RequestBody Map<String, Object> body) {
        return Mono.fromRunnable(() -> logTelemetry(body));
    }

    @GetMapping("/plugins/bilibili-player/api/player/log")
    public Mono<Void> receivePlayerLogGet(
            @RequestParam String event,
            @RequestParam(defaultValue = "") String bvid,
            @RequestParam(defaultValue = "") String cid,
            @RequestParam(defaultValue = "") String detail) {
        return Mono.fromRunnable(() -> logTelemetry(
                Map.of("event", event, "bvid", bvid, "cid", cid, "detail", detail)));
    }

    private void logTelemetry(Map<String, Object> body) {
        try {
            String event = (String) body.getOrDefault("event", "unknown");
            String bvid = (String) body.getOrDefault("bvid", "");
            String detail = body.getOrDefault("detail", "").toString();
            String prefix = bvid.isEmpty() ? "" : "[" + bvid.substring(0, Math.min(bvid.length(), 12)) + "] ";
            logService.debug(prefix + "PLAYER " + event + " " + detail);
        } catch (Exception ignored) {}
    }

    @GetMapping("/plugins/bilibili-player/api/video/playurl")
    public Mono<String> getVideoPlayUrl(
            @RequestParam String bvid,
            @RequestParam long cid,
            @RequestParam(defaultValue = "64") int qn,
            @RequestParam(defaultValue = "80") int fnval
    ) {
        return Mono.fromCallable(() ->
                bilibiliApiService.getVideoPlayUrl(bvid, String.valueOf(cid), qn, fnval));
    }

    @GetMapping("/plugins/bilibili-player/api/video/proxy")
    public Mono<ResponseEntity<Flux<DataBuffer>>> proxyVideo(
            @RequestParam String url,
            ServerHttpRequest serverRequest
    ) {
        return Mono.fromCallable(() -> {
            URI uri;
            try {
                uri = URI.create(url);
            } catch (Exception e) {
                logService.debug("Invalid proxy URI: " + url.substring(0, Math.min(url.length(), 100)));
                return ResponseEntity.status(400).body(Flux.<DataBuffer>empty());
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
            CompletableFuture<HttpResponse<InputStream>> futureResp =
                    proxyClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> response = futureResp.join();
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

            if (status >= 300) return ResponseEntity.status(status).headers(headers).body(Flux.<DataBuffer>empty());

            InputStream inputStream = response.body();
            Flux<DataBuffer> flux = Flux.generate(
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
                    } catch (Exception e) {
                        try { stream.close(); } catch (Exception ignored) {}
                        sink.error(e);
                    }
                    return stream;
                },
                stream -> { try { stream.close(); } catch (Exception ignored) {} }
            );
            return ResponseEntity.status(status).headers(headers).body(flux);
        }).onErrorResume(e -> {
            logService.debug("Proxy error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return Mono.just(ResponseEntity.status(500).body(Flux.<DataBuffer>empty()));
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
            h.append("<link href=\"https://vjs.zencdn.net/8.23.4/video-js.css\" rel=\"stylesheet\"/>");
            h.append("<style>");
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
            h.append(".qmi.ac{color:#00a1d6}");
            h.append(".pwrap{flex:1;position:relative;width:100%;min-height:0;max-width:100%;margin:0 auto;aspect-ratio:16/9}");
            h.append(".pwrap .video-js{width:100%;height:100%}");
            h.append(".video-js .vjs-control-bar{background:linear-gradient(0deg,rgba(0,0,0,.7),transparent);height:48px}");
            h.append(".video-js .vjs-button>.vjs-icon-placeholder:before{line-height:48px}");
            h.append(".video-js .vjs-time-control{line-height:48px}");
            h.append(".video-js .vjs-progress-control{position:absolute;top:-.7em;width:100%;height:0;z-index:1;padding:10px 0}");
            h.append(".video-js .vjs-progress-holder{position:absolute;margin:0 .5em!important;width:calc(100% - 1em)}");
            h.append(".video-js .vjs-play-progress{background-color:#00a1d6}");
            h.append(".video-js .vjs-play-progress::before{color:#00a1d6;font-size:1em}");
            h.append(".video-js .vjs-load-progress div{background:rgba(255,255,255,.3)}");
            h.append(".video-js .vjs-slider{background:rgba(255,255,255,.2)}");
            h.append(".video-js .vjs-big-play-button{background:none;border:2px solid #fff;border-radius:50%;width:70px;height:70px;line-height:66px;font-size:30px;top:50%;left:50%;margin-top:-35px;margin-left:-35px}");
            h.append(".video-js .vjs-volume-panel{order:2}.video-js .vjs-picture-in-picture-control{order:8}");
            h.append(".vjs-error-disp{position:absolute;inset:0;display:none;align-items:center;justify-content:center;color:#f87171;background:rgba(0,0,0,.85);z-index:5;text-align:center;padding:20px}");
            h.append("</style></head><body>");
            h.append("<div class=\"qbar\"><span class=\"qlabel\">Quality</span><div class=\"qselect\"><button id=\"qbtn\" class=\"qsbtn\">720P <span class=\"arr\">▾</span></button><div id=\"qmenu\" class=\"qmenu\"></div></div></div>");
            h.append("<div class=\"pwrap\"><div class=\"vjs-error-disp\" id=\"er\"></div>");
            h.append("<video id=\"v\" class=\"video-js vjs-default-skin\" controls autoplay muted playsinline></video></div>");
            h.append("<script src=\"https://vjs.zencdn.net/8.23.4/video.min.js\"></script>");
            h.append("<script>");
            h.append("var API='/plugins/bilibili-player/api';var BVID='").append(bvid).append("';var CID='").append(cid).append("';");
            h.append("var player=null,ps=null,cq=0,aq=[],ad=[],firstPlay=true,aEl=null,rafId=0,usingDash=false;");

            // PlayerState for seamless quality switch
            h.append("function PlayerState(){this.ongoing=false;this.switchTime=0;this.isPlaying=false}");
            h.append("PlayerState.prototype.save=function(){if(this.ongoing&&player.currentTime()===0)return;this.ongoing=false;this.switchTime=player.currentTime();this.isPlaying=!player.paused()};");
            h.append("PlayerState.prototype.apply=function(){player.currentTime(this.switchTime);if(this.isPlaying)player.play();this.ongoing=true};");
            h.append("ps=new PlayerState();");

            h.append("function tl(e,d){var u=API+'/player/log?event='+encodeURIComponent(e)+'&bvid='+BVID+'&cid='+CID+'&detail='+encodeURIComponent(d||'');fetch(u,{keepalive:true,mode:'no-cors'}).catch(function(){})}");
            h.append("function pu(u){return API+'/video/proxy?url='+encodeURIComponent(u)}");
            h.append("function se(m){var er=document.getElementById('er');er.style.display='flex';er.textContent=m;try{player.addClass('vjs-error')}catch(e){}}");

            // Destroy audio element + RAF loop
            h.append("function destroyAudio(){if(rafId){cancelAnimationFrame(rafId);rafId=0}if(aEl){try{aEl.pause();aEl.removeAttribute('src');aEl.load();aEl.parentNode.removeChild(aEl)}catch(e){}aEl=null}usingDash=false}");

            // DASH playback: video in <video>, audio in hidden <audio>, RAF-synced
            h.append("function playDASH(vUrl,aUrl,codecs,w,h){destroyAudio();var vEl=player.el_.querySelector('video');vEl.src=pu(vUrl);player.load();aEl=document.createElement('audio');aEl.style.display='none';aEl.crossOrigin='anonymous';document.body.appendChild(aEl);aEl.src=pu(aUrl);aEl.load();aEl.volume=player.volume();player.play().catch(function(){});usingDash=true;tl('dash','v='+codecs.v+' a='+codecs.a+' '+w+'x'+h);if(w&&h){var ar=h>w?(w+'/'+h):(w+'/'+h);document.querySelector('.pwrap').style.aspectRatio=ar};syncLoop()}");

            // RAF-based audio sync loop: follow video time with ±100ms tolerance, handle play/pause/seek
            h.append("function syncLoop(){if(rafId)cancelAnimationFrame(rafId);rafId=requestAnimationFrame(function tick(){rafId=requestAnimationFrame(tick);if(!aEl||!usingDash)return;var v=player.el_.querySelector('video');if(!v)return;var dt=v.currentTime-aEl.currentTime;if(Math.abs(dt)>0.15){if(!aEl.paused)aEl.currentTime=v.currentTime}if(v.paused&&!aEl.paused){aEl.pause()}else if(!v.paused&&aEl.paused){aEl.play().catch(function(){})}aEl.volume=v.muted?0:player.volume();aEl.playbackRate=v.playbackRate})}");

            // Set up video-level event hooks for audio sync
            h.append("function wireAudioHooks(){var v=player.el_.querySelector('video');v.addEventListener('seeked',function(){if(aEl)aEl.currentTime=v.currentTime});v.addEventListener('ratechange',function(){if(aEl)aEl.playbackRate=v.playbackRate});v.addEventListener('volumechange',function(){if(aEl)aEl.volume=v.muted?0:player.volume()})}");

            // Direct MP4 playback (≤720P, already muxed)
            h.append("function playDirect(url){destroyAudio();var u=pu(url);tl('direct',url.substring(0,60));player.el_.querySelector('video').src=u;player.load();player.play().catch(function(){})}");

            // Load quality: QN≥80 → DASH dual-element, QN<80 → muxed MP4
            h.append("async function loadQuality(qn){tl('load','qn='+qn);try{var fnval=qn>=80?16:1;var r=await fetch(API+'/video/playurl?bvid='+BVID+'&cid='+CID+'&qn='+(qn||64)+'&fnval='+fnval);var d=await r.json();cq=d.quality;aq=d.acceptQuality;ad=d.acceptDescription;updateResMenu();if(d.dash&&d.dash.video&&d.dash.video.length&&d.dash.audio&&d.dash.audio.length){var vT=d.dash.video,aT=d.dash.audio,vTr=vT[0],aTr=aT[0],i,altV=null;for(i=0;i<vT.length;i++){if(vT[i].id===d.quality){if(!vTr||vT[i].codecs.indexOf('avc1')!==-1)vTr=vT[i];else altV=vT[i]}}if(!vTr)vTr=altV||vT[0];for(i=0;i<aT.length;i++)if(aT[i].bandwidth>aTr.bandwidth)aTr=aT[i];playDASH(vTr.baseUrl,aTr.baseUrl,{v:vTr.codecs,a:aTr.codecs},vTr.width,vTr.height)}else if(d.durl&&d.durl.length>0){playDirect(d.durl[0].url)}else{se('No stream')}}catch(e){se(e.message);tl('loadErr',e.message)}}");

            // Quality menu outside video bar
            h.append("function updateResMenu(){var m=document.getElementById('qmenu');var b=document.getElementById('qbtn');if(!aq.length)return;m.innerHTML='';b.childNodes[0].textContent=ad[0]||'720P';for(var i=0;i<aq.length;i++){(function(qn,desc){var d=document.createElement('div');d.className='qmi'+(aq[i]===cq?' ac':'');d.textContent=desc;d.addEventListener('click',function(e){e.stopPropagation();m.classList.remove('show');b.classList.remove('open');ps.save();loadQuality(qn);ps.apply()});m.appendChild(d)})(aq[i],ad[i])}}");
            h.append("document.getElementById('qbtn').addEventListener('click',function(e){e.stopPropagation();var m=document.getElementById('qmenu');var b=this;b.classList.toggle('open');m.classList.toggle('show')});");
            h.append("document.addEventListener('click',function(e){var qs=document.querySelector('.qselect');if(!qs.contains(e.target)){document.getElementById('qmenu').classList.remove('show');document.getElementById('qbtn').classList.remove('open')}});");

            // Video.js event wiring
            h.append("function videoEvents(){var v=player.el_.querySelector('video');v.addEventListener('play',function(){if(firstPlay){firstPlay=false;v.muted=false}tl('play','t='+player.currentTime().toFixed(1))});v.addEventListener('pause',function(){tl('pause','t='+player.currentTime().toFixed(1))});v.addEventListener('seeked',function(){tl('seeked','t='+player.currentTime().toFixed(1))});v.addEventListener('ended',function(){tl('ended','')});v.addEventListener('error',function(){tl('error','c='+(v.error?v.error.code:'?'))});v.addEventListener('waiting',function(){var t0=player.currentTime();tl('stall','t='+t0.toFixed(1));setTimeout(function(){tl('recover','timer');if(v.paused)return;player.play().catch(function(){})},2000);v.addEventListener('canplay',function x(){tl('recover','canplay');v.removeEventListener('canplay',x);if(!v.paused)player.play().catch(function(){})},{once:true})})};");

            // Video.js init — default to 1080P if logged in, 720P otherwise
            h.append("player=videojs('v',{controls:true,preload:'auto',fluid:true,autoplay:true,muted:true,controlBar:{children:['playToggle','volumePanel','currentTimeDisplay','timeDivider','durationDisplay','progressControl','pictureInPictureToggle','fullscreenToggle']}});");
            h.append("player.addClass('vjs-bilibili-theme');");
            h.append("videoEvents();wireAudioHooks();");
            h.append("player.ready(function(){tl('ready','');loadQuality(80)});");
            h.append("</script></body></html>");
            return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(h.toString());
        });
    }
}
