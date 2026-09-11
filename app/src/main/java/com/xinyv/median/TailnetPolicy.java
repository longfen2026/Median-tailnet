package com.xinyv.median;

import java.net.URI;
import java.net.URISyntaxException;

/** Defines the isolation boundary for the embedded Tailnet browsing profile. */
final class TailnetPolicy {
    static final String PROCESS_SUFFIX = ":tailnet";

    private TailnetPolicy() {}

    static boolean isLoopbackSocksEndpoint(String host, int port) {
        if (host == null || port < 1 || port > 65535) return false;
        String value = host.trim();
        return "127.0.0.1".equals(value) || "::1".equals(value);
    }

    static boolean mayUseTailnetProxy(String processName, String host, int port) {
        return PROCESS_SUFFIX.equals(processName) && isLoopbackSocksEndpoint(host, port);
    }

    static String normalizeLoopbackSocksUrl(String source) {
        if (source == null || source.trim().length() == 0)
            throw new IllegalArgumentException("Tailnet 代理地址为空");
        try {
            URI uri = new URI(source.trim());
            if (!"socks5".equalsIgnoreCase(uri.getScheme()))
                throw new IllegalArgumentException("Tailnet 代理必须使用 SOCKS5");
            String host = uri.getHost();
            int port = uri.getPort();
            if (host != null && host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            if (uri.getUserInfo() != null || host == null || !isLoopbackSocksEndpoint(host, port)
                    || uri.getPath().length() > 0 || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("Tailnet SOCKS5 地址无效");
            String formattedHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
            return "socks://" + formattedHost + ":" + port;
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Tailnet SOCKS5 地址无效");
        }
    }
}