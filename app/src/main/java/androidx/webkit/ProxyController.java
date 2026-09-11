package androidx.webkit;

import androidx.webkit.internal.WebViewGlueCommunicator;

import java.util.concurrent.Executor;

/** Controls the System WebView proxy override for the current application process. */
public final class ProxyController {
    private static final ProxyController INSTANCE = new ProxyController();

    private ProxyController() {}

    public static ProxyController getInstance() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            throw new UnsupportedOperationException("Proxy override not supported by this WebView provider");
        }
        return INSTANCE;
    }

    public void setProxyOverride(ProxyConfig config, Executor executor, Runnable listener) {
        if (config == null || executor == null || listener == null) throw new NullPointerException();
        WebViewGlueCommunicator.setProxyOverride(config.proxyUrl, executor, listener);
    }

    public void clearProxyOverride(Executor executor, Runnable listener) {
        if (executor == null || listener == null) throw new NullPointerException();
        WebViewGlueCommunicator.clearProxyOverride(executor, listener);
    }
}