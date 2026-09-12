package com.xinyv.median;

import java.util.Arrays;

public final class TailnetDomainPolicySelfTest {
    public static void main(String[] args) {
        TailnetDomainPolicy policy = new TailnetDomainPolicy(Arrays.asList(
            "*.Example.Internal", "例子.中国", "example.internal."));

        matches(policy, "ts.net");
        matches(policy, "device.tail123.ts.net");
        matches(policy, "DEVICE.TAIL123.TS.NET.");
        matches(policy, "example.internal");
        matches(policy, "service.example.internal");
        matches(policy, "www.例子.中国");

        rejects(policy, "evilts.net");
        rejects(policy, "ts.net.example.com");
        rejects(policy, "example.internal.attacker.test");
        rejects(policy, "https://device.ts.net");
        rejects(policy, "device.ts.net:443");
        rejects(policy, "user@device.ts.net");
        rejects(policy, "100.64.0.1");

        check(policy.rules().size() == 3, "normalized duplicate rule was retained");
        check("example.internal".equals(TailnetDomainPolicy.normalizeRule("*.Example.Internal.")),
                "wildcard rule was not normalized");
        expectInvalid("shortname");
        expectInvalid("https://example.internal");
        expectInvalid("*.*.example.com");
        expectInvalid("example.com:443");
        expectInvalid("example..com");
        expectInvalid(".example.com");
        System.out.println("TailnetDomainPolicySelfTest passed");
    }

    private static void matches(TailnetDomainPolicy policy, String host) {
        check(policy.matches(host), "Tailnet host did not match: " + host);
    }

    private static void rejects(TailnetDomainPolicy policy, String host) {
        check(!policy.matches(host), "non-Tailnet host matched: " + host);
    }

    private static void expectInvalid(String source) {
        try {
            TailnetDomainPolicy.normalizeRule(source);
            throw new AssertionError("invalid Tailnet rule accepted: " + source);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}