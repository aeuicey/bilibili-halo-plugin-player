package com.halo.bilibiliplayer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class BilibiliApiService {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";
    private static final String QRCODE_GENERATE_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate";
    private static final String QRCODE_POLL_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll";
    private static final String PLAYURL_URL = "https://api.bilibili.com/x/player/wbi/playurl";
    private static final Path DATA_DIR = Paths.get(System.getProperty("user.home"), ".halo-bilibili-player");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final LogService log;

    private volatile String imgKey;
    private volatile String subKey;
    private volatile String sessdata;
    private volatile long lastKeyUpdateTime;

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
        loadSessdata();
    }

    private HttpResponse<String> sendWithTimeout(HttpRequest request, int timeoutSec) throws Exception {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .get(timeoutSec, TimeUnit.SECONDS);
    }

    private void loadSessdata() {
        try {
            Path sessFile = DATA_DIR.resolve("sessdata");
            if (Files.exists(sessFile)) {
                this.sessdata = Files.readString(sessFile).trim();
                log.info("已从文件加载SESSDATA, 长度={}", sessdata.length());
            } else {
                log.info("未找到持久化的SESSDATA文件，需要重新登录");
            }
        } catch (Exception e) {
            log.error("加载SESSDATA文件失败: {}", e.getMessage());
        }
    }

    private void saveSessdata() {
        try {
            if (sessdata != null && !sessdata.isEmpty()) {
                Files.writeString(DATA_DIR.resolve("sessdata"), sessdata);
                log.info("SESSDATA已持久化到文件, 长度={}", sessdata.length());
            }
        } catch (Exception e) {
            log.error("保存SESSDATA文件失败: {}", e.getMessage());
        }
    }

    public String getSessdata() {
        return sessdata;
    }

    public void setSessdata(String sessdata) {
        this.sessdata = sessdata;
        log.info("设置SESSDATA, 长度={}, 前10字符={}", sessdata != null ? sessdata.length() : 0,
                sessdata != null && sessdata.length() > 10 ? sessdata.substring(0, 10) + "..." : sessdata);
        saveSessdata();
    }

    public String generateQrCode() throws Exception {
        log.info("开始生成B站登录二维码...");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(QRCODE_GENERATE_URL))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = sendWithTimeout(request, 8);
        log.debug("二维码生成响应状态码: {}", response.statusCode());

        JsonNode root = objectMapper.readTree(response.body());
        int code = root.get("code").asInt();
        log.info("B站二维码API返回 code={}, message={}", code, root.get("message").asText());

        if (code != 0) {
            log.error("获取二维码失败: {}", root.get("message").asText());
            throw new RuntimeException("获取二维码失败: " + root.get("message").asText());
        }

        JsonNode data = root.get("data");
        Map<String, String> result = new HashMap<>();
        result.put("url", data.get("url").asText());
        result.put("qrcodeKey", data.get("qrcode_key").asText());

        log.info("二维码生成成功, qrcodeKey={}", data.get("qrcode_key").asText().substring(0, 8) + "...");
        return objectMapper.writeValueAsString(result);
    }

    public String pollQrCode(String qrcodeKey) {
        String shortKey = qrcodeKey != null && qrcodeKey.length() >= 8 ? qrcodeKey.substring(0, 8) : qrcodeKey;
        log.debug("轮询扫码状态, qrcodeKey={}...", shortKey);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(QRCODE_POLL_URL + "?qrcode_key=" + qrcodeKey))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();

            HttpResponse<String> response = sendWithTimeout(request, 8);
            JsonNode root = objectMapper.readTree(response.body());

            log.debug("轮询响应: code={}, message={}",
                    root.has("code") ? root.get("code").asText() : "无",
                    root.has("message") ? root.get("message").asText() : "无");

            JsonNode data = root.get("data");
            if (data == null || data.isNull()) {
                log.warn("轮询响应data为null, B站返回code={}", root.has("code") ? root.get("code").asText() : "无");
                return objectMapper.writeValueAsString(Map.of("status", "error", "message", "响应异常"));
            }

            int statusCode = data.has("code") ? data.get("code").asInt() : -1;
            Map<String, Object> result = new HashMap<>();

            log.info("轮询状态: statusCode={}, hasUrl={}", statusCode, data.has("url") && !data.get("url").isNull());

            if (statusCode == 0) {
                if (data.has("url") && !data.get("url").isNull()) {
                    String redirectUrl = data.get("url").asText();
                    log.info("扫码成功，回调URL前100字符: {}", redirectUrl.length() > 100 ? redirectUrl.substring(0, 100) + "..." : redirectUrl);

                    Map<String, String> cookies = parseUrlParams(redirectUrl);
                    log.info("从回调URL解析到参数: {}", cookies.keySet());

                    String sess = cookies.get("SESSDATA");
                    if (sess != null && !sess.isEmpty()) {
                        String decoded = URLDecoder.decode(sess, StandardCharsets.UTF_8);
                        log.info("提取到SESSDATA, 原始长度={}, 解码后长度={}", sess.length(), decoded.length());
                        setSessdata(decoded);
                        result.put("status", "success");
                        result.put("message", "登录成功");
                    } else {
                        log.error("回调URL中未找到SESSDATA参数, 解析到的参数: {}", cookies);
                        result.put("status", "error");
                        result.put("message", "未找到SESSDATA");
                    }
                } else {
                    log.warn("statusCode=0但url字段为空或null");
                    result.put("status", "pending");
                    result.put("message", "等待确认");
                }
            } else if (statusCode == 86038) {
                log.info("二维码已过期");
                result.put("status", "expired");
                result.put("message", "二维码已过期");
            } else if (statusCode == 86090) {
                log.info("已扫码，等待用户在手机上确认");
                result.put("status", "scanned");
                result.put("message", "已扫码，请在手机上确认");
            } else if (statusCode == 86101) {
                log.debug("等待扫码...");
                result.put("status", "pending");
                result.put("message", "等待扫码");
            } else {
                log.warn("未知状态码: {}, message={}", statusCode, data.has("message") ? data.get("message").asText() : "无");
                result.put("status", "pending");
                result.put("message", data.has("message") ? data.get("message").asText() : "等待扫码");
            }

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("轮询扫码状态异常: {}", e.getMessage());
            try {
                return objectMapper.writeValueAsString(Map.of("status", "error", "message", "轮询失败: " + e.getMessage()));
            } catch (Exception ex) {
                return "{\"status\":\"error\",\"message\":\"内部错误\"}";
            }
        }
    }

    private Map<String, String> parseUrlParams(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        try {
            String query = url.contains("?") ? url.substring(url.indexOf("?") + 1) : "";
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    params.put(kv[0], kv[1]);
                }
            }
        } catch (Exception e) {
            log.error("解析URL参数失败: {}", e.getMessage());
        }
        return params;
    }

    public String checkLoginStatus() throws Exception {
        log.debug("检查登录状态, SESSDATA存在={}, 长度={}",
                sessdata != null, sessdata != null ? sessdata.length() : 0);

        Map<String, Object> result = new HashMap<>();

        if (sessdata == null || sessdata.isEmpty()) {
            log.info("SESSDATA为空，未登录");
            result.put("isLogin", false);
            result.put("message", "未登录");
            return objectMapper.writeValueAsString(result);
        }

        try {
            log.debug("调用B站nav API验证登录状态...");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(NAV_URL))
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", "SESSDATA=" + sessdata)
                    .GET()
                    .build();

            HttpResponse<String> response = sendWithTimeout(request, 8);
            JsonNode root = objectMapper.readTree(response.body());

            int code = root.get("code").asInt();
            log.info("nav API响应: code={}, message={}", code,
                    root.has("message") ? root.get("message").asText() : "无");

            if (code == 0) {
                JsonNode data = root.get("data");
                boolean isLogin = data.get("isLogin").asBoolean();
                log.info("登录验证结果: isLogin={}, uname={}", isLogin,
                        data.has("uname") ? data.get("uname").asText() : "未知");

                result.put("isLogin", isLogin);
                if (isLogin) {
                    result.put("mid", data.get("mid").asLong());
                    result.put("uname", data.get("uname").asText());
                    result.put("face", data.get("face").asText());
                    result.put("vipStatus", data.get("vipStatus").asInt());
                    result.put("vipType", data.get("vipType").asInt());
                    if (data.has("level_info")) {
                        result.put("level", data.get("level_info").get("current_level").asInt());
                    }
                    log.info("用户信息: uid={}, name={}, level={}, vipType={}",
                            data.get("mid").asLong(), data.get("uname").asText(),
                            data.has("level_info") ? data.get("level_info").get("current_level").asInt() : 0,
                            data.get("vipType").asInt());
                }
            } else if (code == -101) {
                log.warn("SESSDATA已过期(code=-101)，清除登录状态");
                result.put("isLogin", false);
                result.put("message", "会话已过期，请重新登录");
                this.sessdata = null;
            } else {
                log.warn("nav API返回非0状态: code={}, message={}", code,
                        root.has("message") ? root.get("message").asText() : "无");
                result.put("isLogin", false);
                result.put("message", "会话已过期，请重新登录");
                this.sessdata = null;
            }
        } catch (Exception e) {
            log.error("验证登录状态异常: {}", e.getMessage());
            result.put("isLogin", false);
            result.put("message", "验证登录状态失败: " + e.getMessage());
        }

        return objectMapper.writeValueAsString(result);
    }

    public String logout() throws Exception {
        log.info("用户退出登录, 清除SESSDATA");
        this.sessdata = null;
        try {
            Files.deleteIfExists(DATA_DIR.resolve("sessdata"));
            log.info("已删除持久化的SESSDATA文件");
        } catch (Exception e) {
            log.error("删除SESSDATA文件失败: {}", e.getMessage());
        }
        return objectMapper.writeValueAsString(Map.of("success", true, "message", "已退出登录"));
    }

    public String getVideoInfo(String bvid) throws Exception {
        log.info("获取视频信息: bvid={}", bvid);
        String infoUrl = "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid;

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(infoUrl))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.bilibili.com");

        if (sessdata != null && !sessdata.isEmpty()) {
            requestBuilder.header("Cookie", "SESSDATA=" + sessdata);
            log.debug("携带SESSDATA请求视频信息");
        }

        HttpRequest request = requestBuilder.GET().build();
        HttpResponse<String> response = sendWithTimeout(request, 8);
        JsonNode root = objectMapper.readTree(response.body());

        int code = root.get("code").asInt();
        if (code != 0) {
            log.error("获取视频信息失败: code={}, message={}", code,
                    root.has("message") ? root.get("message").asText() : "无");
            throw new RuntimeException("获取视频信息失败: " + root.get("message").asText());
        }

        JsonNode data = root.get("data");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bvid", data.get("bvid").asText());
        result.put("aid", data.get("aid").asLong());
        result.put("title", data.get("title").asText());
        result.put("pic", data.get("pic").asText());
        result.put("duration", data.get("duration").asLong());
        result.put("ownerName", data.get("owner").get("name").asText());
        result.put("ownerFace", data.get("owner").get("face").asText());

        List<Map<String, Object>> pages = new ArrayList<>();
        for (JsonNode page : data.get("pages")) {
            Map<String, Object> pageInfo = new LinkedHashMap<>();
            pageInfo.put("cid", page.get("cid").asLong());
            pageInfo.put("page", page.get("page").asInt());
            pageInfo.put("part", page.get("part").asText());
            pageInfo.put("duration", page.get("duration").asLong());
            pages.add(pageInfo);
        }
        result.put("pages", pages);

        log.info("视频信息获取成功: title={}, pages={}", data.get("title").asText(), pages.size());
        return objectMapper.writeValueAsString(result);
    }

    public String getVideoPlayUrl(String bvid, String cid, int qn, int fnval) throws Exception {
        log.info("获取视频播放地址: bvid={}, cid={}, qn={}, fnval={}", bvid, cid, qn, fnval);

        if (imgKey == null || subKey == null ||
                System.currentTimeMillis() - lastKeyUpdateTime > 3600000) {
            log.info("刷新WBI签名密钥...");
            refreshWbiKeys();
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bvid", bvid);
        params.put("cid", cid);
        params.put("qn", qn);
        params.put("fnval", fnval);
        params.put("fnver", 0);
        params.put("fourk", 1);

        String signedQuery = WbiSignUtil.signParams(params, imgKey, subKey);
        log.debug("WBI签名完成");

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(PLAYURL_URL + "?" + signedQuery))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.bilibili.com");

        if (sessdata != null && !sessdata.isEmpty()) {
            requestBuilder.header("Cookie", "SESSDATA=" + sessdata);
        }

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
        return objectMapper.writeValueAsString(parsePlayUrlResponse(root.get("data")));
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
                vi.put("baseUrl", v.get("baseUrl").asText());
                if (v.has("backupUrl") && !v.get("backupUrl").isNull()) {
                    vi.put("backupUrl", v.get("backupUrl").get(0).asText());
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
                ai.put("baseUrl", a.get("baseUrl").asText());
                if (a.has("backupUrl") && !a.get("backupUrl").isNull()) {
                    ai.put("backupUrl", a.get("backupUrl").get(0).asText());
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
                di.put("url", d.get("url").asText());
                if (d.has("backup_url") && d.get("backup_url").size() > 0) {
                    di.put("backupUrl", d.get("backup_url").get(0).asText());
                }
                durlList.add(di);
            }
            result.put("durl", durlList);
        }

        return result;
    }

    private void refreshWbiKeys() throws Exception {
        log.debug("刷新WBI密钥...");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(NAV_URL))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = sendWithTimeout(request, 8);
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode wbiImg = root.get("data").get("wbi_img");
        String imgUrl = wbiImg.get("img_url").asText();
        String subUrl = wbiImg.get("sub_url").asText();

        this.imgKey = imgUrl.substring(imgUrl.lastIndexOf('/') + 1, imgUrl.lastIndexOf('.'));
        this.subKey = subUrl.substring(subUrl.lastIndexOf('/') + 1, subUrl.lastIndexOf('.'));
        this.lastKeyUpdateTime = System.currentTimeMillis();
        log.info("WBI密钥已刷新: imgKey={}..., subKey={}...",
                imgKey.substring(0, 8), subKey.substring(0, 8));
    }
}
