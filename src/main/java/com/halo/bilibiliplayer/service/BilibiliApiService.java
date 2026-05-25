package com.halo.bilibiliplayer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class BilibiliApiService {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String PLAYURL_URL = "https://api.bilibili.com/x/player/playurl";
    private static final Path DATA_DIR = Paths.get(System.getProperty("user.home"), ".halo-bilibili-player");
    private static final Path BUVID_FILE = DATA_DIR.resolve("buvid3");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final LogService log;

    private volatile String buvid3;

    private static final long CACHE_TTL_MS = 10 * 60 * 1000;
    private final ConcurrentHashMap<String, CacheEntry> playUrlCache = new ConcurrentHashMap<>();

    private static class CacheEntry {
        final String json;
        final long expiresAt;
        CacheEntry(String json, long expiresAt) { this.json = json; this.expiresAt = expiresAt; }
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    public BilibiliApiService(LogService log) {
        this.log = log;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.objectMapper = new ObjectMapper();
        try {
            Files.createDirectories(DATA_DIR);
            log.info("数据目录已创建: {}", DATA_DIR);
        } catch (Exception e) {
            log.error("创建数据目录失败: {}", e.getMessage());
        }
        loadBuvid3();
    }

    private HttpResponse<String> sendWithTimeout(HttpRequest request, int timeoutSec) throws Exception {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .get(timeoutSec, TimeUnit.SECONDS);
    }

    public String getBuvid3() {
        if (buvid3 == null || buvid3.isEmpty()) {
            buvid3 = generateBuvid3();
        }
        return buvid3;
    }

    private void loadBuvid3() {
        try {
            if (Files.exists(BUVID_FILE)) {
                this.buvid3 = Files.readString(BUVID_FILE).trim();
                if (buvid3.isEmpty()) {
                    this.buvid3 = generateBuvid3();
                    Files.writeString(BUVID_FILE, buvid3);
                }
                log.info("已加载buvid3, 长度={}", buvid3.length());
            } else {
                this.buvid3 = generateBuvid3();
                Files.writeString(BUVID_FILE, buvid3);
                log.info("已生成并持久化buvid3, 长度={}", buvid3.length());
            }
        } catch (Exception e) {
            this.buvid3 = generateBuvid3();
            log.warn("加载buvid3失败，使用临时值: {}", e.getMessage());
        }
    }

    private static String generateBuvid3() {
        String uuid = java.util.UUID.randomUUID().toString().toUpperCase();
        return uuid + "infoc";
    }

    public String getVideoInfo(String bvid) throws Exception {
        log.info("获取视频信息: bvid={}", bvid);
        String infoUrl = "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(infoUrl))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.bilibili.com")
                .GET()
                .build();

        HttpResponse<String> response = sendWithTimeout(request, 8);
        JsonNode root = objectMapper.readTree(response.body());

        int code = root.get("code").asInt();
        if (code != 0) {
            log.error("获取视频信息失败: code={}, message={}", code,
                    root.has("message") ? root.get("message").asText() : "无");
            throw new RuntimeException("获取视频信息失败: " + root.get("message").asText());
        }

        JsonNode data = root.get("data");
        Map<String, Object> result = buildVideoInfo(data);

        log.info("视频信息获取成功: title={}, pages={}",
                result.getOrDefault("title", ""),
                ((List<?>) result.getOrDefault("pages", List.of())).size());
        return objectMapper.writeValueAsString(result);
    }

    // 统一的 JsonNode 安全取值 -------------------------------------------------

    private static String safeText(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : "";
    }

    private static long safeLong(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asLong() : 0L;
    }

    private static int safeInt(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asInt() : 0;
    }

    /** 组装对外返回的视频信息 Map。对缺失字段做安全兜底，防止 NPE。 */
    private Map<String, Object> buildVideoInfo(JsonNode data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bvid", safeText(data, "bvid"));
        result.put("aid", safeLong(data, "aid"));
        result.put("title", safeText(data, "title"));
        result.put("pic", safeText(data, "pic"));
        result.put("duration", safeLong(data, "duration"));

        JsonNode owner = data.get("owner");
        result.put("ownerName", safeText(owner, "name"));
        result.put("ownerFace", safeText(owner, "face"));

        // 封面/视频原始尺寸，用于前端按原始比例渲染
        JsonNode dim = data.get("dimension");
        if (dim != null) {
            result.put("picWidth", safeInt(dim, "width"));
            result.put("picHeight", safeInt(dim, "height"));
        }

        // 视频互动统计数据
        JsonNode stat = data.get("stat");
        if (stat != null) {
            Map<String, Object> statMap = new LinkedHashMap<>();
            statMap.put("view", safeLong(stat, "view"));
            statMap.put("danmaku", safeLong(stat, "danmaku"));
            statMap.put("like", safeLong(stat, "like"));
            result.put("stat", statMap);
        }

        // 分 P 列表
        List<Map<String, Object>> pages = new ArrayList<>();
        JsonNode pagesNode = data.get("pages");
        if (pagesNode != null && pagesNode.isArray()) {
            for (JsonNode page : pagesNode) {
                Map<String, Object> pageInfo = new LinkedHashMap<>();
                pageInfo.put("cid", safeLong(page, "cid"));
                pageInfo.put("page", safeInt(page, "page"));
                pageInfo.put("part", safeText(page, "part"));
                pageInfo.put("duration", safeLong(page, "duration"));
                pages.add(pageInfo);
            }
        }
        result.put("pages", pages);
        return result;
    }

    public String getVideoPlayUrl(String bvid, String cid, int qn, int fnval, boolean nocache) throws Exception {
        return getVideoPlayUrl(bvid, cid, qn, fnval, nocache, "html5");
    }

    public String getVideoPlayUrl(String bvid, String cid, int qn, int fnval, boolean nocache, String platform) throws Exception {
        log.info("获取视频播放地址: bvid={}, cid={}, qn={}, fnval={}, nocache={}, platform={}", bvid, cid, qn, fnval, nocache, platform);

        if (qn >= 80) {
            log.info("拒绝高清请求(qn>=80)，提示安装浏览器扩展");
            return "{\"error\":\"高清播放需要安装浏览器扩展\",\"acceptQuality\":[],\"acceptDescription\":[],\"quality\":0}";
        }

        String cacheKey = bvid + ":" + cid + ":" + qn + ":" + fnval + ":html5";
        if (nocache) {
            playUrlCache.remove(cacheKey);
        } else if (qn <= 64 && fnval <= 1) {
            CacheEntry cached = playUrlCache.get(cacheKey);
            if (cached != null) {
                if (!cached.isExpired()) {
                    log.debug("playurl cache hit: {}", cacheKey);
                    return cached.json;
                }
                playUrlCache.remove(cacheKey);
            }
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bvid", bvid);
        params.put("cid", cid);
        params.put("qn", qn);
        params.put("fnval", fnval);
        params.put("fnver", 0);
        params.put("platform", "html5");

        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (query.length() > 0) query.append("&");
            query.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            query.append("=");
            query.append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(PLAYURL_URL + "?" + query))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.bilibili.com");

        String cookie = "buvid3=" + (buvid3 != null ? buvid3 : generateBuvid3());
        requestBuilder.header("Cookie", cookie);

        HttpRequest request = requestBuilder.GET().build();
        HttpResponse<String> response = sendWithTimeout(request, 8);
        JsonNode root = objectMapper.readTree(response.body());

        int code = root.get("code").asInt();
        if (code != 0) {
            log.error("获取播放地址失败: code={}, message={}", code,
                    root.has("message") ? root.get("message").asText() : "无");
            throw new RuntimeException("获取视频播放地址失败: " + root.get("message").asText());
        }

        log.info("播放地址获取成功, quality={}, format={}",
                root.get("data").get("quality").asInt(), root.get("data").get("format").asText());
        String jsonResult = objectMapper.writeValueAsString(parsePlayUrlResponse(root.get("data")));
        if (qn <= 64 && fnval <= 1) {
            playUrlCache.put(cacheKey, new CacheEntry(jsonResult, System.currentTimeMillis() + CACHE_TTL_MS));
        }
        return jsonResult;
    }

    private Map<String, Object> parsePlayUrlResponse(JsonNode data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quality", data.get("quality").asInt());
        result.put("format", data.get("format").asText());
        result.put("timelength", data.get("timelength").asLong());

        List<String> acceptDesc = new ArrayList<>();
        for (JsonNode desc : data.get("accept_description")) {
            acceptDesc.add(desc.asText());
        }
        result.put("acceptDescription", acceptDesc);

        List<Integer> acceptQuality = new ArrayList<>();
        for (JsonNode q : data.get("accept_quality")) {
            acceptQuality.add(q.asInt());
        }
        result.put("acceptQuality", acceptQuality);
        result.put("videoCodecid", data.get("video_codecid").asInt());

        if (data.has("dash")) {
            JsonNode dash = data.get("dash");
            Map<String, Object> dashInfo = new LinkedHashMap<>();
            dashInfo.put("duration", dash.get("duration").asInt());

            List<Map<String, Object>> videoList = new ArrayList<>();
            for (JsonNode v : dash.get("video")) {
                Map<String, Object> vi = new LinkedHashMap<>();
                vi.put("id", v.get("id").asInt());
                vi.put("baseUrl", CdnMirrorUtil.upgradeCdnHostname(v.get("baseUrl").asText()));
                if (v.has("backupUrl") && !v.get("backupUrl").isNull()) {
                    vi.put("backupUrl", CdnMirrorUtil.upgradeCdnHostname(v.get("backupUrl").get(0).asText()));
                }
                vi.put("bandwidth", v.get("bandwidth").asInt());
                vi.put("mimeType", v.get("mimeType").asText());
                vi.put("codecs", v.get("codecs").asText());
                vi.put("width", v.get("width").asInt());
                vi.put("height", v.get("height").asInt());
                vi.put("frameRate", v.has("frameRate") ? v.get("frameRate").asText() : "");
                vi.put("codecid", v.get("codecid").asInt());
                if (v.has("segment_base")) {
                    JsonNode seg = v.get("segment_base");
                    vi.put("initRange", seg.get("index_range").asText());
                }
                videoList.add(vi);
            }
            dashInfo.put("video", videoList);

            List<Map<String, Object>> audioList = new ArrayList<>();
            for (JsonNode a : dash.get("audio")) {
                Map<String, Object> ai = new LinkedHashMap<>();
                ai.put("id", a.get("id").asInt());
                ai.put("baseUrl", CdnMirrorUtil.upgradeCdnHostname(a.get("baseUrl").asText()));
                if (a.has("backupUrl") && !a.get("backupUrl").isNull()) {
                    ai.put("backupUrl", CdnMirrorUtil.upgradeCdnHostname(a.get("backupUrl").get(0).asText()));
                }
                ai.put("bandwidth", a.get("bandwidth").asInt());
                ai.put("mimeType", a.get("mimeType").asText());
                ai.put("codecs", a.get("codecs").asText());
                audioList.add(ai);
            }
            dashInfo.put("audio", audioList);
            result.put("dash", dashInfo);
        }

        if (data.has("durl")) {
            List<Map<String, Object>> durlList = new ArrayList<>();
            for (JsonNode d : data.get("durl")) {
                Map<String, Object> di = new LinkedHashMap<>();
                di.put("url", CdnMirrorUtil.upgradeCdnHostname(d.get("url").asText()));
                if (d.has("backup_url") && d.get("backup_url").size() > 0) {
                    di.put("backupUrl", CdnMirrorUtil.upgradeCdnHostname(d.get("backup_url").get(0).asText()));
                }
                durlList.add(di);
            }
            result.put("durl", durlList);
        }

        return result;
    }
}
