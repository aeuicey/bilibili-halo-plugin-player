package com.halo.bilibiliplayer.service;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Bilibili CDN mirror upgrade utility.
 * Ported from bilibilianalysis-server/src/mirror-cdn.js
 */
public final class CdnMirrorUtil {

    private CdnMirrorUtil() {}

    // Premium Mirror CDN nodes (round-robin selection)
    private static final List<String> MIRROR_CDN_CHINA = List.of(
        "upos-sz-mirrorali.bilivideo.com",
        "upos-sz-mirrorcos.bilivideo.com",
        "upos-sz-mirrorhw.bilivideo.com",
        "upos-sz-mirrorbd.bilivideo.com",
        "upos-sz-mirror08c.bilivideo.com",
        "upos-sz-mirroralib.bilivideo.com",
        "upos-sz-mirrorcosb.bilivideo.com",
        "upos-sz-mirrorhwb.bilivideo.com",
        "upos-sz-mirror08h.bilivideo.com",
        "upos-sz-mirroralio1.bilivideo.com",
        "upos-sz-mirrorcoso1.bilivideo.com",
        "upos-sz-mirrorhwo1.bilivideo.com",
        "upos-sz-mirror08ct.bilivideo.com"
    );

    private static final String PROXY_TF = "proxy-tf-all-ws.bilivideo.com";

    // Mirror CDN: upos-sz-mirrorali/mirrorcos/mirrorhw etc.
    private static final Pattern MIRROR_HOST_RE = Pattern.compile(
        "^upos-(sz|hz|bstar)-mirror([0-9a-z]+)\\.(bilivideo\\.com|akamaized\\.net)$"
    );

    // MCDN resource path
    private static final Pattern MCDN_PATH_RE = Pattern.compile("^/v1/resource/");

    // Traffic-free CDN (免流): upos/proxy-*-tf-*.bilivideo.com
    private static final Pattern PROXY_TF_RE = Pattern.compile(
        "^(upos|proxy).*-tf-.*\\.bilivideo\\.com$"
    );

    // IP:Port pattern for MCDN detection
    private static final Pattern IP_PORT_RE = Pattern.compile(
        "^(\\d{1,3}\\.){3}\\d{1,3}$"
    );

    private static final AtomicInteger indexChina = new AtomicInteger(0);

    private static String pickMirrorChina() {
        int idx = indexChina.getAndUpdate(i -> (i + 1) % MIRROR_CDN_CHINA.size());
        return MIRROR_CDN_CHINA.get(idx);
    }

    private static boolean isMirrorCdn(String hostname) {
        return MIRROR_HOST_RE.matcher(hostname).matches();
    }

    private static boolean isOverseasCdn(String hostname) {
        return hostname.contains("mirrorcf")
            || (hostname.contains("mirror") && hostname.contains("ov."))
            || hostname.contains("bstar")
            || hostname.endsWith(".akamaized.net");
    }

    private static boolean isProxyTf(String hostname) {
        return PROXY_TF_RE.matcher(hostname).matches();
    }

    private static boolean isMcdnIpPort(String hostname) {
        if (hostname == null) return false;
        // Hostname may include port, strip it for IP check
        String host = hostname.contains(":") ? hostname.substring(0, hostname.indexOf(':')) : hostname;
        return IP_PORT_RE.matcher(host).matches();
    }

    private static boolean isMcdnDomain(String hostname) {
        return hostname != null && hostname.contains("mcdn.bilivideo");
    }

    /**
     * Upgrade a Bilibili CDN URL to use premium Mirror CDN when possible.
     * Mirror and traffic-free CDNs are kept as-is.
     * BCache, UPOS, and overseas CDNs are upgraded to Mirror China.
     * MCDN (P2P) URLs are routed through proxy-tf.
     *
     * @param url the original Bilibili CDN URL
     * @return the upgraded URL, or the original URL if parsing fails
     */
    public static String upgradeCdnHostname(String url) {
        if (url == null || url.isEmpty()) return url;

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return url;
        }

        String hostname = uri.getHost();
        if (hostname == null || hostname.isEmpty()) return url;

        try {
            String newHost = hostname;

            if (isProxyTf(hostname)) {
                // Traffic-free CDN, keep as-is
                return url;
            }

            if (isOverseasCdn(hostname)) {
                // Overseas CDN → upgrade to Mirror China
                newHost = pickMirrorChina();
            } else if (isMirrorCdn(hostname)) {
                // Already on Mirror tier, keep as-is
                return url;
            } else if (isMcdnIpPort(hostname)
                || (isMcdnDomain(hostname) && MCDN_PATH_RE.matcher(uri.getRawPath() != null ? uri.getRawPath() : "").matches())) {
                // MCDN P2P → route through proxy-tf
                newHost = PROXY_TF;
            } else {
                // BCache (cn-*), UPOS (estg*), etc. → upgrade to Mirror
                newHost = pickMirrorChina();
            }

            if (newHost.equals(hostname)) return url;

            int port = uri.getPort();
            String authority = port != -1 ? newHost + ":" + port : newHost;
            return new URI(uri.getScheme(), authority, uri.getRawPath(),
                uri.getRawQuery(), uri.getRawFragment()).toString();
        } catch (Exception e) {
            return url;
        }
    }
}
