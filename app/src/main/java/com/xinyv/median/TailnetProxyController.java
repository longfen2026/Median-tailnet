package com.xinyv.median;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.util.concurrent.Executor;

/** Owns the process-wide WebView SOCKS override in the isolated Tailnet process. */
final class TailnetProxyController {
    interface Callback {
        void onApplied();
        void onFailure(String message);
    }

    private final String processSuffix;
    private final Executor callbackExecutor;
    private String activeProxyUrl;

    TailnetProxyController(String processSuffix, Executor callbackExecutor) {
        this.processSuffix = processSuffix;
        this.callbackExecutor = callbackExecutor;
    }

    synchronized boolean isActive() {
        return activeProxyUrl != null;
    }

    synchronized void apply(String loopbackSocksUrl, Callback callback) {
        if (callback == null) throw new NullPointerException("callback");
        Endpoint endpoint;
        try {
            endpoint = Endpoint.parse(loopbackSocksUrl);
        } catch (IllegalArgumentException error) {
            callback.onFailure(error.getMessage());
            return;
        }
        if (!TailnetPolicy.mayUseTailnetProxy(processSuffix, endpoint.host, endpoint.port)) {
            callback.onFailure("Tailnet 代理必须是隔离进程中的回环地址");
            return;
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            callback.onFailure("当前 System WebView 不支持 Tailnet 代理");
            return;
        }
        try {
            ProxyController.getInstance().setProxyOverride(
                    new ProxyConfig.Builder().addProxyRule(endpoint.proxyUrl).build(),
                    callbackExecutor, new Runnable() {
                        @Override public void run() {
                            synchronized (TailnetProxyController.this) {
                                activeProxyUrl = endpoint.proxyUrl;
                            }
                            callback.onApplied();
                        }
                    });
        } catch (RuntimeException error) {
            callback.onFailure("无法应用 Tailnet 代理: " + error.getClass().getSimpleName());
        }
    }

    synchronized void clear(final Runnable complete) {
        if (complete == null) throw new NullPointerException("complete");
        if (activeProxyUrl == null) {
            complete.run();
            return;
        }
        try {
            ProxyController.getInstance().clearProxyOverride(callbackExecutor, new Runnable() {
                @Override public void run() {
                    synchronized (TailnetProxyController.this) {
                        activeProxyUrl = null;
                    }
                    complete.run();
                }
            });
        } catch (RuntimeException ignored) {
            activeProxyUrl = null;
            complete.run();
        }
    }

    private static final class Endpoint {
        final String proxyUrl;
        final String host;
        final int port;

        Endpoint(String proxyUrl, String host, int port) {
            this.proxyUrl = proxyUrl;
            this.host = host;
            this.port = port;
        }

        static Endpoint parse(String source) {
            String proxyUrl = TailnetPolicy.normalizeLoopbackSocksUrl(source);
            int hostStart = proxyUrl.indexOf("//") + 2;
            int hostEnd = proxyUrl.lastIndexOf(':');
            String host = proxyUrl.substring(hostStart, hostEnd);
            if (host.startsWith("[") && host.endsWith("]"))
                host = host.substring(1, host.length() - 1);
            int port = Integer.parseInt(proxyUrl.substring(hostEnd + 1));
            return new Endpoint(proxyUrl, host, port);
        }
    }
}