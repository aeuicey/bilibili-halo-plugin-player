package com.halo.bilibiliplayer.service;

import java.security.MessageDigest;
import java.util.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class WbiSignUtil {

    private static final int[] MIXIN_KEY_ENC_TAB = {
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    public static String getMixinKey(String imgKey, String subKey) {
        String s = imgKey + subKey;
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            key.append(s.charAt(MIXIN_KEY_ENC_TAB[i]));
        }
        return key.toString();
    }

    public static String signParams(Map<String, Object> params, String imgKey, String subKey) {
        String mixinKey = getMixinKey(imgKey, subKey);
        params.put("wts", System.currentTimeMillis() / 1000);

        TreeMap<String, Object> sortedParams = new TreeMap<>(params);

        StringJoiner param = new StringJoiner("&");
        for (Map.Entry<String, Object> entry : sortedParams.entrySet()) {
            String value = encodeValue(entry.getValue().toString());
            param.add(entry.getKey() + "=" + value);
        }

        String queryString = param.toString();
        String sign = md5(queryString + mixinKey);

        return queryString + "&w_rid=" + sign;
    }

    private static String encodeValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("!", "%21")
            .replace("'", "%27")
            .replace("(", "%28")
            .replace(")", "%29")
            .replace("*", "%2A");
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 hash failed", e);
        }
    }
}
