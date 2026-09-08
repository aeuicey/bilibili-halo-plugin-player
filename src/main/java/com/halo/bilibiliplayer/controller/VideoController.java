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
import run.halo.app.plugin.ReactiveSettingFetcher;
import tools.jackson.databind.JsonNode;

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
    private final ReactiveSettingFetcher settingFetcher;
    private final HttpClient proxyClient;
    private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    public VideoController(BilibiliApiService bilibiliApiService, LogService logService,
                           ReactiveSettingFetcher settingFetcher) {
        this.bilibiliApiService = bilibiliApiService;
        this.logService = logService;
        this.settingFetcher = settingFetcher;
        this.proxyClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @GetMapping("/plugins/bilibili-player/api/video/info")
    public Mono<String> getVideoInfo(@RequestParam String bvid) {
        return Mono.fromCallable(() -> bilibiliApiService.getVideoInfo(bvid))
            .onErrorResume(e -> Mono.just(
                "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}"
            ));
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
            @RequestParam(defaultValue = "127") int qn,
            @RequestParam(defaultValue = "3344") int fnval
    ) {
        return Mono.fromCallable(() ->
                // 默认参数（前端未显式指定）走降级链；显式传参保持单次请求语义
                (qn == 127 && fnval == 3344)
                        ? bilibiliApiService.getVideoPlayUrlWithFallback(bvid, String.valueOf(cid))
                        : bilibiliApiService.getVideoPlayUrl(bvid, String.valueOf(cid), qn, fnval))
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
                    } catch (java.io.EOFException e) {
                        // CDN dropped connection or client disconnected — complete normally
                        try { stream.close(); } catch (Exception ignored) {}
                        sink.complete();
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

    /** 三级流分发配置：smart（直连→Worker→服务器）/ worker（Worker→服务器）/ server（仅服务器） */
    private record StreamProxyConfig(String proxyMode, String workerUrl, String workerToken) {
        static final StreamProxyConfig DEFAULT = new StreamProxyConfig("smart", "", "");
    }

    /** 读取播放设置；setting 不存在或字段缺失时回退 smart、无 Worker；非法 Worker 地址按未配置处理 */
    private StreamProxyConfig readProxyConfig(JsonNode setting) {
        String mode = "smart";
        String workerUrl = "";
        String workerToken = "";
        if (setting != null && !setting.isMissingNode() && !setting.isNull()) {
            JsonNode m = setting.path("proxyMode");
            if (m.isString()) {
                String v = m.asString();
                if (v.equals("smart") || v.equals("worker") || v.equals("server")) {
                    mode = v;
                }
            }
            JsonNode u = setting.path("workerUrl");
            if (u.isString()) workerUrl = u.asString().trim();
            JsonNode t = setting.path("workerToken");
            if (t.isString()) workerToken = t.asString().trim();
        }
        if (!workerUrl.isEmpty()) {
            while (workerUrl.endsWith("/")) {
                workerUrl = workerUrl.substring(0, workerUrl.length() - 1);
            }
            boolean valid = false;
            try {
                URI uri = URI.create(workerUrl);
                String scheme = uri.getScheme();
                valid = uri.getHost() != null
                        && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
            } catch (Exception ignored) {
            }
            if (!valid) {
                logService.warn("[settings] 非法 Worker 地址，按未配置处理: {}", workerUrl);
                workerUrl = "";
            }
        }
        return new StreamProxyConfig(mode, workerUrl, workerToken);
    }

    @GetMapping(value = "/plugins/bilibili-player/embed", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<ResponseEntity<String>> embedPlayer(@RequestParam String bvid, @RequestParam(defaultValue = "") String cid) {
        return settingFetcher.getSettingValue("basic")
                .map(this::readProxyConfig)
                .switchIfEmpty(Mono.just(StreamProxyConfig.DEFAULT))
                .onErrorResume(e -> {
                    logService.warn("[settings] 读取播放设置失败，回退默认 smart 模式: {}", e.getMessage());
                    return Mono.just(StreamProxyConfig.DEFAULT);
                })
                .map(cfg -> ResponseEntity.ok().cacheControl(CacheControl.noCache())
                        .body(EmbedPageGenerator.build(bvid, cid, cfg.proxyMode(), cfg.workerUrl(), cfg.workerToken())));
    }
}
