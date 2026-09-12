package com.xinyv.median;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;

/** Security-relevant subset of libtailscale's backend status. */
final class TailnetStatus {
    private static final String RUNNING = "Running";
    private static final String NEEDS_LOGIN = "NeedsLogin";

    final String backendState;
    final String authUrl;

    private TailnetStatus(String backendState, String authUrl) {
        this.backendState = backendState;
        this.authUrl = authUrl;
    }

    static TailnetStatus parse(String source) {
        try {
            JSONObject status = new JSONObject(source);
            String state = status.optString("BackendState", "");
            String authUrl = normalizeAuthUrl(status.optString("AuthURL", ""));
            return new TailnetStatus(state, authUrl);
        } catch (JSONException error) {
            throw new IllegalArgumentException("Tailnet 状态无效", error);
        }
    }

    boolean isRunning() {
        return RUNNING.equals(backendState);
    }

    boolean needsLogin() {
        return NEEDS_LOGIN.equals(backendState) || authUrl != null;
    }

    private static String normalizeAuthUrl(String source) {
        if (source == null || source.length() == 0) return null;
        try {
            URI uri = new URI(source);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawAuthority() == null ||
                    uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
                return null;
            return uri.toASCIIString();
        } catch (URISyntaxException error) {
            return null;
        }
    }
}