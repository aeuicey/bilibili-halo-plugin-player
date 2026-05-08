package com.halo.bilibiliplayer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class LogService {

    private static final int MAX_HISTORY = 300;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final Logger slf4j = LoggerFactory.getLogger("bilibili-player");

    private final ConcurrentLinkedQueue<Map<String, Object>> history = new ConcurrentLinkedQueue<>();
    private final Sinks.Many<Map<String, Object>> sink =
            Sinks.many().multicast().onBackpressureBuffer(200, false);

    public void log(String level, String message, Object... args) {
        String formatted = args.length > 0 ? String.format(message.replace("{}", "%s"), args) : message;
        Map<String, Object> entry = Map.of(
                "time", TIME_FMT.format(Instant.now()),
                "level", level,
                "msg", formatted
        );
        history.offer(entry);
        while (history.size() > MAX_HISTORY) {
            history.poll();
        }
        sink.tryEmitNext(entry);
        switch (level) {
            case "ERROR": slf4j.error(formatted); break;
            case "WARN":  slf4j.warn(formatted); break;
            case "DEBUG": slf4j.debug(formatted); break;
            default:      slf4j.info(formatted); break;
        }
    }

    public void info(String msg, Object... args) { log("INFO", msg, args); }
    public void warn(String msg, Object... args) { log("WARN", msg, args); }
    public void error(String msg, Object... args) { log("ERROR", msg, args); }
    public void debug(String msg, Object... args) { log("DEBUG", msg, args); }

    public List<Map<String, Object>> getHistory() {
        return new ArrayList<>(history);
    }

    public Flux<Map<String, Object>> stream() {
        return sink.asFlux();
    }
}
