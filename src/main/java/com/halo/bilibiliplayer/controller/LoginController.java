package com.halo.bilibiliplayer.controller;

import com.halo.bilibiliplayer.service.LogService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/plugins/bilibili-player/api")
public class LoginController {

    private final LogService logService;

    public LoginController(LogService logService) {
        this.logService = logService;
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
