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

            if (status >= 300) return ResponseEntity.status(status).headers(headers).body(Flux.empty());

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
        });
    }

    @GetMapping(value = "/plugins/bilibili-player/embed", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<ResponseEntity<String>> embedPlayer(@RequestParam String bvid, @RequestParam(defaultValue = "") String cid) {
        return Mono.fromCallable(() -> {
            StringBuilder h = new StringBuilder();
            h.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
            h.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
            h.append("<title>Bilibili Player</title><style>");
            h.append("*{margin:0;padding:0;box-sizing:border-box}");
            h.append("body{background:#000;display:flex;align-items:center;justify-content:center;min-height:100vh;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}");
            h.append("#ct{position:relative;width:100%;max-width:100%}video{width:100%;display:block}");
            h.append(".ld{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;color:#fff;gap:12px;z-index:5}");
            h.append(".sp{width:32px;height:32px;border:3px solid rgba(255,255,255,.3);border-top-color:#fff;border-radius:50%;animation:sp .8s linear infinite}");
            h.append("@keyframes sp{to{transform:rotate(360deg)}}");
            h.append(".er{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:#f87171;background:rgba(0,0,0,.85);z-index:5;text-align:center;padding:20px}");
            h.append(".qb{position:absolute;bottom:0;left:0;right:0;display:flex;justify-content:flex-end;padding:8px 12px;z-index:10;background:linear-gradient(transparent,rgba(0,0,0,.7))}");
            h.append(".qs{position:relative}.qbtn{display:flex;align-items:center;gap:4px;padding:4px 8px;font-size:12px;color:#fff;background:rgba(255,255,255,.1);border:none;border-radius:4px;cursor:pointer}");
            h.append(".qbtn:hover{background:rgba(255,255,255,.2)}");
            h.append(".qm{position:absolute;bottom:100%;right:0;margin-bottom:8px;background:rgba(0,0,0,.9);border:1px solid rgba(255,255,255,.15);border-radius:6px;overflow:hidden;display:none;min-width:110px;max-height:200px;overflow-y:auto}");
            h.append(".qm.s{display:block}.qi{padding:8px 14px;font-size:13px;color:#fff;cursor:pointer;white-space:nowrap}.qi:hover{background:rgba(255,255,255,.1)}.qi.ac{color:#00a1d6;background:rgba(0,161,214,.15)}");
            h.append("</style></head><body><div id=\"ct\">");
            h.append("<div id=\"ld\" class=\"ld\"><div class=\"sp\"></div><span id=\"lt\">Loading...</span></div>");
            h.append("<div id=\"er\" class=\"er\" style=\"display:none\"></div>");
            h.append("<video id=\"v\" controls autoplay muted playsinline crossorigin=\"anonymous\"></video>");
            h.append("<div class=\"qb\">");
            h.append("<span id=\"bi\" style=\"color:#888;font-size:10px;margin-right:auto\"></span>");
            h.append("<div class=\"qs\"><button id=\"qbtn\" class=\"qbtn\" onclick=\"tq()\">Quality</button><div id=\"qm\" class=\"qm\"></div></div>");
            h.append("</div></div><script>");
            h.append("var A='/plugins/bilibili-player/api';var B='").append(bvid).append("';var C='").append(cid).append("';");
            h.append("var v=document.getElementById('v'),ld=document.getElementById('ld'),lt=document.getElementById('lt'),er=document.getElementById('er'),qm=document.getElementById('qm'),bi=document.getElementById('bi'),cq=0,aq=[],ad=[],ms=null,vs=null,as=null,vS=[],aS=[];");
            h.append("function hl(){ld.style.display='none'}function se(m){er.style.display='flex';er.textContent=m;ld.style.display='none'}");
            h.append("function pu(u){return A+'/video/proxy?url='+encodeURIComponent(u)}");
            h.append("function bm(m,c){return m.split(';')[0]+';codecs=\"'+c+'\"'}");
            h.append("function tl(e,d){var u=A+'/player/log?event='+encodeURIComponent(e)+'&bvid='+B+'&cid='+C+'&detail='+encodeURIComponent(d||'');fetch(u,{keepalive:true,mode:'no-cors'}).catch(function(){})}");
            h.append("function ab(sb,buf){return new Promise(function(rs){function da(){if(!sb){rs();return}if(sb.updating){sb.addEventListener('updateend',function x(){sb.removeEventListener('updateend',x);da()});return}sb.addEventListener('updateend',function x(){sb.removeEventListener('updateend',x);rs()});try{sb.appendBuffer(buf)}catch(e){rs()}}da()})};");
            h.append("var firstPlay=true;v.addEventListener('play',function(){if(firstPlay){firstPlay=false;v.muted=false;}tl('play','t='+v.currentTime.toFixed(1))});");
            h.append("v.addEventListener('pause',function(){tl('pause','t='+v.currentTime.toFixed(1))});");
            h.append("v.addEventListener('seeked',function(){tl('seeked','t='+v.currentTime.toFixed(1))});");
            h.append("v.addEventListener('ended',function(){tl('ended','')});");
            h.append("v.addEventListener('error',function(){tl('error','c='+(v.error?v.error.code:'?'))});");
            h.append("v.addEventListener('waiting',function(){var t0=v.currentTime;tl('stall','t='+t0.toFixed(1));var tr=setTimeout(function(){tl('recover','timer');v.play().catch(function(){})},2000);v.addEventListener('canplay',function x(){tl('recover','canplay');clearTimeout(tr);v.removeEventListener('canplay',x);v.play().catch(function(){});},{once:true})});");
            h.append("async function lv(qn){tl('load','qn='+qn);ld.style.display='flex';er.style.display='none';rm();lt.textContent='Connecting...';try{var r=await fetch(A+'/video/playurl?bvid='+B+'&cid='+C+'&qn='+(qn||64)+'&fnval=80');var d=await r.json();cq=d.quality;aq=d.acceptQuality;ad=d.acceptDescription;bq();if(d.dash){vS=d.dash.video;aS=d.dash.audio;tl('dash','v='+vS.length+' a='+aS.length);id()}else if(d.durl&&d.durl.length>0){tl('flv','');v.src=pu(d.durl[0].url);hl()}else{se('No stream')}}catch(e){se(e.message);tl('loadErr',e.message)}}");
            h.append("function rm(){[vs,as].forEach(function(x){try{x.abort()}catch(e){}});if(ms){var sb_=ms.sourceBuffers;try{if(ms.readyState!=='closed')while(sb_.length>0)ms.removeSourceBuffer(sb_[0])}catch(e){}}if(v.src&&v.src.startsWith('blob'))URL.revokeObjectURL(v.src);vs=null;as=null;ms=null;firstPlay=true}");
            h.append("var fst=true;");
            h.append("async function st0(sb,url,cb){var r=await fetch(pu(url));var rd=r.body.getReader();var cs=[],total=0;while(true){var q=await rd.read();if(q.done)break;var c=new Uint8Array(q.value.buffer);total+=c.length;cs.push(c);if(total>=2097152&&fst){fst=false;ab(sb,mg(cs)).then(function(){return fl()}).then(function(){v.play().catch(function(){});hl();tl(\"playing\",\"\"+total/1048576|0+\"MB\")});cs=[]}else if(cs.length>=8){await ab(sb,mg(cs));cs=[]}}if(cs.length)await ab(sb,mg(cs));cb()}");
            h.append("function mg(cs){var t=0;for(var i=0;i<cs.length;i++)t+=cs[i].length;var m=new Uint8Array(t),p=0;for(var i=0;i<cs.length;i++){m.set(cs[i],p);p+=cs[i].length}return m}");
            h.append("function fl(){return new Promise(function(rs){function tk(){if(!vs&&!as)rs();else if((!vs||!vs.updating)&&(!as||!as.updating))rs();else setTimeout(tk,30)}tk()})}");
            h.append("function id(){ms=new MediaSource();v.src=URL.createObjectURL(ms);ms.addEventListener('sourceopen',async function so(){try{tl('so','');var vi=vS[0],ai=aS[0];for(var i=0;i<aS.length;i++)if(aS[i].bandwidth>ai.bandwidth)ai=aS[i];vs=ms.addSourceBuffer(bm(vi.mimeType,vi.codecs));as=ms.addSourceBuffer(bm(ai.mimeType,ai.codecs));fst=true;var vd=false,ad=false;st0(vs,vi.baseUrl,function(){vd=true;ck()});st0(as,ai.baseUrl,function(){ad=true;ck()});function ck(){if(vd&&ad)fl().then(function(){if(ms.readyState==='open'){ms.endOfStream();tl('done','');bi.textContent='Done'}})}catch(e){se(e.message);tl('soErr',e.message)}},{once:true});}");
            h.append("function sq(qn){lv(qn)}");
            h.append("function bq(){var h='';for(var i=0;i<aq.length;i++)h+='<div class=\"qi'+(aq[i]===cq?' ac':'')+'\" onclick=\"sq('+aq[i]+')\">'+ad[i]+'</div>';qm.innerHTML=h}");
            h.append("function tq(){qm.classList.toggle('s')}");
            h.append("document.addEventListener('click',function(e){if(!e.target.closest('.qs'))qm.classList.remove('s')});");
            h.append("lv(64);</script></body></html>");
            return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(h.toString());
        });
    }
}
