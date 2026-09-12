package com.xinyv.median;

import java.net.IDN;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Selects host names that must use the Tailnet transport. */
final class TailnetDomainPolicy {
    static final String DEFAULT_RULE = "ts.net";

    private final List<String> rules;

    TailnetDomainPolicy(Iterable<String> customRules) {
        Set<String> normalized = new LinkedHashSet<>();
        normalized.add(DEFAULT_RULE);
        if (customRules != null) {
            for (String rule : customRules) normalized.add(normalizeRule(rule));
        }
        rules = Collections.unmodifiableList(new ArrayList<>(normalized));
    }

    List<String> rules() {
        return rules;
    }

    boolean matches(String host) {
        String normalized;
        try {
            normalized = normalizeHost(host);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        for (String rule : rules) {
            if (normalized.equals(rule) || normalized.endsWith("." + rule)) return true;
        }
        return false;
    }

    static String normalizeRule(String source) {
        if (source == null) throw new IllegalArgumentException("Tailnet 域名为空");
        String value = source.trim();
        if (value.startsWith("*.")) value = value.substring(2);
        if (value.indexOf('.') < 0)
            throw new IllegalArgumentException("Tailnet 域名必须包含完整域名后缀");
        return normalizeHost(value);
    }

    private static String normalizeHost(String source) {
        if (source == null) throw new IllegalArgumentException("Tailnet 域名为空");
        String value = source.trim();
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.length() == 0 || value.indexOf('*') >= 0 || value.indexOf('/') >= 0 ||
                value.indexOf(':') >= 0 || value.indexOf('@') >= 0 || value.indexOf('\\') >= 0)
            throw new IllegalArgumentException("Tailnet 域名无效");
        try {
                String ascii = IDN.toASCII(value,
                    IDN.USE_STD3_ASCII_RULES | IDN.ALLOW_UNASSIGNED)
                    .toLowerCase(Locale.ROOT);
            if (ascii.length() == 0 || ascii.length() > 253 || ascii.startsWith(".") ||
                    ascii.endsWith(".") || ascii.contains(".."))
                throw new IllegalArgumentException("Tailnet 域名无效");
            return ascii;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Tailnet 域名无效");
        }
    }
}