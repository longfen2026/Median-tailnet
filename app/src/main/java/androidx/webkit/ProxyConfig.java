package androidx.webkit;

/** Minimal proxy configuration surface for the process-scoped WebView override. */
public final class ProxyConfig {
    final String proxyUrl;

    private ProxyConfig(String proxyUrl) {
        this.proxyUrl = proxyUrl;
    }

    public static final class Builder {
        private String proxyUrl;

        public Builder addProxyRule(String value) {
            if (value == null || value.trim().length() == 0)
                throw new IllegalArgumentException("proxyUrl must not be empty");
            proxyUrl = value.trim();
            return this;
        }

        public ProxyConfig build() {
            if (proxyUrl == null) throw new IllegalStateException("proxy rule required");
            return new ProxyConfig(proxyUrl);
        }
    }
}