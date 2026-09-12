package com.xinyv.median;

public final class TailnetPolicySelfTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(TailnetPolicy.isLoopbackSocksEndpoint("127.0.0.1", 1080),
                "IPv4 loopback SOCKS endpoint rejected");
        check(TailnetPolicy.isLoopbackSocksEndpoint("::1", 1080),
                "IPv6 loopback SOCKS endpoint rejected");
        check(!TailnetPolicy.isLoopbackSocksEndpoint("10.0.0.1", 1080),
                "private LAN endpoint accepted as Tailnet proxy");
        check(!TailnetPolicy.isLoopbackSocksEndpoint("example.com", 1080),
                "hostname endpoint accepted as Tailnet proxy");
        check(!TailnetPolicy.isLoopbackSocksEndpoint("localhost", 1080),
                "resolvable hostname endpoint accepted as Tailnet proxy");
        check(!TailnetPolicy.isLoopbackSocksEndpoint("127.0.0.1", 0),
                "invalid SOCKS port accepted");
        check(TailnetPolicy.mayUseTailnetProxy(":tailnet", "127.0.0.1", 1080),
                "isolated Tailnet process rejected");
        check(!TailnetPolicy.mayUseTailnetProxy(":private", "127.0.0.1", 1080),
                "private profile accepted Tailnet proxy");
        check("http://127.0.0.1:9080".equals(
                        TailnetPolicy.normalizeLoopbackHttpProxyUrl("http://127.0.0.1:9080")),
                "IPv4 HTTP proxy endpoint was not normalized");
        expectInvalidHttpProxyUrl("http://localhost:9080");
        expectInvalidHttpProxyUrl("https://127.0.0.1:9080");
        expectInvalidHttpProxyUrl("http://127.0.0.1:9080/path");
                check("socks://127.0.0.1:1080".equals(
                                                TailnetPolicy.normalizeLoopbackSocksUrl("socks5://127.0.0.1:1080")),
                                "IPv4 SOCKS endpoint was not normalized");
                check("socks://[::1]:1080".equals(
                                                TailnetPolicy.normalizeLoopbackSocksUrl("socks5://[::1]:1080")),
                                "IPv6 SOCKS endpoint was not normalized");
                expectInvalidSocksUrl("socks5://localhost:1080");
                expectInvalidSocksUrl("socks5://127.0.0.1:1080/path");
        System.out.println("TailnetPolicySelfTest passed");
    }

        private static void expectInvalidHttpProxyUrl(String source) {
                try {
                        TailnetPolicy.normalizeLoopbackHttpProxyUrl(source);
                        throw new AssertionError("invalid HTTP proxy endpoint accepted: " + source);
                } catch (IllegalArgumentException expected) {
                        // Expected.
                }
        }

        private static void expectInvalidSocksUrl(String source) {
                try {
                        TailnetPolicy.normalizeLoopbackSocksUrl(source);
                        throw new AssertionError("invalid SOCKS endpoint accepted: " + source);
                } catch (IllegalArgumentException expected) {
                        // Expected.
                }
        }
}