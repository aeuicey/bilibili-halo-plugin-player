package com.halo.bilibiliplayer.controller;

import com.halo.bilibiliplayer.service.BilibiliApiService;
import com.halo.bilibiliplayer.service.LogService;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.*;
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
                .connectTimeout(Duration.ofSeconds(10))
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
        Map<String, Object> body = Map.of("event", event, "bvid", bvid, "cid", cid, "detail", detail);
        return Mono.fromRunnable(() -> logTelemetry(body));
    }

    private void logTelemetry(Map<String, Object> body) {
        try {
            String event = (String) body.getOrDefault("event", "unknown");
            String bvid = (String) body.getOrDefault("bvid", "");
            String detail = body.getOrDefault("detail", "").toString();
            String prefix = bvid.isEmpty() ? "" : "[" + bvid.substring(0, Math.min(bvid.length(), 12)) + "] ";
            logService.debug(prefix + "PLAYER " + event + " " + (detail.isEmpty() ? "" : detail));
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
            @RequestParam(required = false) String range
    ) {
        return Mono.fromCallable(() -> {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://www.bilibili.com")
                    .header("Origin", "https://www.bilibili.com")
                    .timeout(Duration.ofSeconds(60));

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
                    String lower = k.toLowerCase();
                    if (lower.equals("content-type") || lower.equals("content-length") ||
                            lower.equals("content-range") || lower.equals("accept-ranges") ||
                            lower.equals("content-disposition")) {
                        headers.add(k, String.join(",", v));
                    }
                });
            }
            headers.set("Access-Control-Allow-Origin", "*");
            headers.set("Access-Control-Expose-Headers", "Content-Range, Accept-Ranges, Content-Length");

            if (status >= 300) {
                return ResponseEntity.status(status).headers(headers).body(Flux.empty());
            }

            InputStream inputStream = response.body();
            Flux<DataBuffer> flux = Flux.generate(
                () -> inputStream,
                (stream, sink) -> {
                    try {
                        byte[] buf = new byte[65536];
                        int n = stream.read(buf);
                        if (n == -1) {
                            stream.close();
                            sink.complete();
                        } else {
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
        });
    }

    @GetMapping(value = "/plugins/bilibili-player/embed", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<String> embedPlayer(@RequestParam String bvid, @RequestParam(defaultValue = "") String cid) {
        return Mono.fromCallable(() -> {
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
            html.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
            html.append("<title>Bilibili Player</title>");
            html.append("<style>");
            html.append("*{margin:0;padding:0;box-sizing:border-box}");
            html.append("body{background:#000;display:flex;align-items:center;justify-content:center;min-height:100vh;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}");
            html.append("#container{position:relative;width:100%;max-width:100%}");
            html.append("video{width:100%;display:block}");
            html.append(".loading{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;color:#fff;gap:12px;z-index:5}");
            html.append(".spinner{width:32px;height:32px;border:3px solid rgba(255,255,255,.3);border-top-color:#fff;border-radius:50%;animation:spin .8s linear infinite}");
            html.append("@keyframes spin{to{transform:rotate(360deg)}}");
            html.append(".error{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:#f87171;background:rgba(0,0,0,.85);z-index:5;text-align:center;padding:20px}");
            html.append(".quality-bar{position:absolute;bottom:0;left:0;right:0;display:flex;justify-content:flex-end;padding:8px 12px;z-index:10;background:linear-gradient(transparent,rgba(0,0,0,.7))}");
            html.append(".quality-select{position:relative}");
            html.append(".quality-btn{display:flex;align-items:center;gap:4px;padding:4px 8px;font-size:12px;color:#fff;background:rgba(255,255,255,.1);border:none;border-radius:4px;cursor:pointer}");
            html.append(".quality-btn:hover{background:rgba(255,255,255,.2)}");
            html.append(".quality-menu{position:absolute;bottom:100%;right:0;margin-bottom:8px;background:rgba(0,0,0,.9);border:1px solid rgba(255,255,255,.15);border-radius:6px;overflow:hidden;display:none;min-width:110px;max-height:200px;overflow-y:auto}");
            html.append(".quality-menu.show{display:block}");
            html.append(".quality-item{padding:8px 14px;font-size:13px;color:#fff;cursor:pointer;white-space:nowrap}");
            html.append(".quality-item:hover{background:rgba(255,255,255,.1)}");
            html.append(".quality-item.active{color:#00a1d6;background:rgba(0,161,214,.15)}");
            html.append("</style></head><body>");
            html.append("<div id=\"container\"><div id=\"loading\" class=\"loading\"><div class=\"spinner\"></div><span id=\"loadText\">Loading...</span></div><div id=\"error\" class=\"error\" style=\"display:none\"></div><video id=\"player\" controls autoplay muted playsinline crossorigin=\"anonymous\"></video><div class=\"quality-bar\"><span id=\"bufInfo\" style=\"color:#888;font-size:10px;margin-right:auto\"></span><div class=\"quality-select\"><button id=\"qualityBtn\" class=\"quality-btn\" onclick=\"tQM()\">Quality</button><div id=\"qualityMenu\" class=\"quality-menu\"></div></div></div></div>");
            html.append("<script>");
            html.append("var API='/plugins/bilibili-player/api';var BVID='").append(bvid).append("';var CID='").append(cid).append("';");
            html.append("var v=document.getElementById('player'),ld=document.getElementById('loading'),lt=document.getElementById('loadText'),er=document.getElementById('error'),qm=document.getElementById('qualityMenu'),bi=document.getElementById('bufInfo'),cq=0,aq=[],ad=[],ms=null,vs=null,as=null,vS=[],aS=[];");
            html.append("function hL(){ld.style.display='none'}function sE(m){er.style.display='flex';er.textContent=m;ld.style.display='none'}function px(u){return API+'/video/proxy?url='+encodeURIComponent(u)}function bM(m,c){return m.split(';')[0]+';codecs=\"'+c+'\"'}");
            html.append("function tL(e,d){var u=API+'/player/log?event='+encodeURIComponent(e)+'&bvid='+BVID+'&cid='+CID+'&detail='+encodeURIComponent(d||'');fetch(u,{keepalive:true,mode:'no-cors'}).catch(function(){})}");
            html.append("function aB(sb,buf){return new Promise(function(rs){function da(){if(!sb){rs();return}if(sb.updating){sb.addEventListener('updateend',function x(){sb.removeEventListener('updateend',x);da()});return}sb.addEventListener('updateend',function x(){sb.removeEventListener('updateend',x);rs()});try{sb.appendBuffer(buf)}catch(e){rs()}}da()})};");
            html.append("var firstPlay=true;");
            html.append("v.addEventListener('play',function(){if(firstPlay){firstPlay=false;hL();v.muted=false;}tL('play','time='+v.currentTime.toFixed(1))});");
            html.append("v.addEventListener('pause',function(){tL('pause','time='+v.currentTime.toFixed(1))});");
            html.append("v.addEventListener('ended',function(){tL('ended','')});");
            html.append("v.addEventListener('seeked',function(){tL('seeked','target='+v.currentTime.toFixed(1))});");
            html.append("v.addEventListener('error',function(){tL('error','code='+(v.error?v.error.code:'unknown'))});");
            html.append("v.addEventListener('waiting',function(){tL('stall','buffer='+v.currentTime.toFixed(1));var t=setTimeout(function(){tL('stallRecover','timeout');v.play().catch(function(){})},1500);v.addEventListener('canplay',function x(){tL('stallRecover','canplay');clearTimeout(t);v.removeEventListener('canplay',x);v.play().catch(function(){});},{once:true})});");
            html.append("async function lV(qn){tL('loadVideo','qn='+qn);ld.style.display='flex';er.style.display='none';RM();lt.textContent='Fetching info...';try{var r=await fetch(API+'/video/playurl?bvid='+BVID+'&cid='+CID+'&qn='+(qn||64)+'&fnval=80');var d=await r.json();cq=d.quality;aq=d.acceptQuality;ad=d.acceptDescription;BQ();if(d.dash){vS=d.dash.video;aS=d.dash.audio;tL('DASH','video='+vS.length+' audio='+aS.length);iD()}else if(d.durl&&d.durl.length>0){tL('FLV','');v.src=px(d.durl[0].url);hL()}else{sE('No stream')}}catch(e){sE(e.message);tL('loadError',e.message)}}");
            html.append("function RM(){if(vs)try{vs.abort()}catch(e){}if(as)try{as.abort()}catch(e){}if(ms){try{if(ms.readyState!=='closed'){var sbs=ms.sourceBuffers;while(sbs.length>0)ms.removeSourceBuffer(sbs[0])}}catch(e){}}if(v.src&&v.src.startsWith('blob'))URL.revokeObjectURL(v.src);vs=null;as=null;ms=null;firstPlay=true}");
            html.append("function iD(){ms=new MediaSource();v.src=URL.createObjectURL(ms);ms.addEventListener('sourceopen',function iD_srcopen(){try{tL('sourceopen','');var vi=vS[0],ai=aS[0];for(var i=0;i<aS.length;i++)if(aS[i].bandwidth>ai.bandwidth)ai=aS[i];vs=ms.addSourceBuffer(bM(vi.mimeType,vi.codecs));as=ms.addSourceBuffer(bM(ai.mimeType,ai.codecs));var vd=false,ad=false,bt=0;function mg(cs){var t=0;for(var i=0;i<cs.length;i++)t+=cs[i].length;var m=new Uint8Array(t),p=0;for(var i=0;i<cs.length;i++){m.set(cs[i],p);p+=cs[i].length}return m}async function fd(sb,it,cb,lbl){tL('streamStart',lbl);var rp=await fetch(px(it.baseUrl));var rd=rp.body.getReader();var cs=[];while(true){var q=await rd.read();if(q.done)break;var c=new Uint8Array(q.value.buffer);bt+=c.length;cs.push(c);if(cs.length>=8){await aB(sb,mg(cs));cs=[]}if(bt>262144)v.play().catch(function(){});}if(cs.length)await aB(sb,mg(cs));tL('streamDone',lbl+' bytes='+bt);cb()}fd(vs,vi,function(){vd=true;ck()},'video');fd(as,ai,function(){ad=true;ck()},'audio');v.play().catch(function(){});function ck(){if(vd&&ad)FL().then(function(){if(ms.readyState==='open'){ms.endOfStream();tL('complete','totalBytes='+(bt/1048576|0)+'MB');bi.textContent='Loaded '+(bt/1048576|0)+'MB'}})}function FL(){return new Promise(function(rs){function tk(){if(!vs&&!as)rs();else if((!vs||!vs.updating)&&(!as||!as.updating))rs();else setTimeout(tk,30)}tk()})}}catch(e){sE(e.message);tL('sourceopenError',e.message)}},{once:true});}");
            html.append("async function sQ(qn){lV(qn)}");
            html.append("function BQ(){var h='';for(var i=0;i<aq.length;i++)h+='<div class=\"quality-item'+(aq[i]===cq?' active':'')+'\" onclick=\"sQ('+aq[i]+')\">'+ad[i]+'</div>';qm.innerHTML=h}");
            html.append("function tQM(){qm.classList.toggle('show')}");
            html.append("document.addEventListener('click',function(e){if(!e.target.closest('.quality-select'))qm.classList.remove('show')});");
            html.append("lV(64);</script></body></html>");
            return html.toString();
        });
    }
}
