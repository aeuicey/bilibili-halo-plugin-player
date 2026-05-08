package com.halo.bilibiliplayer.controller;

import com.halo.bilibiliplayer.service.BilibiliApiService;
import com.halo.bilibiliplayer.service.LogService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/plugins/bilibili-player/api")
public class LoginController {

    private final BilibiliApiService bilibiliApiService;
    private final LogService logService;

    public LoginController(BilibiliApiService bilibiliApiService, LogService logService) {
        this.bilibiliApiService = bilibiliApiService;
        this.logService = logService;
    }

    @GetMapping("/login/qrcode/generate")
    public Mono<String> generateQrCode() {
        return Mono.fromCallable(bilibiliApiService::generateQrCode);
    }

    @GetMapping("/login/qrcode/poll")
    public Mono<String> pollQrCode(@RequestParam("qrcode_key") String qrcodeKey) {
        return Mono.fromCallable(() -> bilibiliApiService.pollQrCode(qrcodeKey));
    }

    @GetMapping("/login/status")
    public Mono<String> checkLoginStatus() {
        return Mono.fromCallable(bilibiliApiService::checkLoginStatus);
    }

    @PostMapping("/login/logout")
    public Mono<String> logout() {
        return Mono.fromCallable(bilibiliApiService::logout);
    }

    @GetMapping("/logs/history")
    public Mono<java.util.List<Map<String, Object>>> logHistory() {
        return Mono.fromCallable(logService::getHistory);
    }

    @GetMapping(value = "/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public reactor.core.publisher.Flux<Map<String, Object>> logStream() {
        return logService.stream();
    }
}
