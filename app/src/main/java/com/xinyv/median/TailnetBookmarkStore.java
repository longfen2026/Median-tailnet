package com.xinyv.median;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Bookmarks shown on the Tailnet entry page. Kept in its own preferences file so the
 * Tailnet process never contends with the main browser library's atomic file writes.
 */
final class TailnetBookmarkStore {
    static final class Bookmark {
        final String title;
        final String url;
        final long createdAt;

        Bookmark(String title, String url, long createdAt) {
            this.title = title;
            this.url = url;
            this.createdAt = createdAt;
        }
    }

    private static final String PREFS = "median_tailnet_bookmarks_v1";
    private static final String KEY = "bookmarks";
    private static final int MAX_BOOKMARKS = 24;
    private static final int MAX_TITLE_LENGTH = 48;

    private final SharedPreferences prefs;

    TailnetBookmarkStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized List<Bookmark> list() {
        ArrayList<Bookmark> result = new ArrayList<Bookmark>();
        JSONArray raw = readArray();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            String url = item.optString("url").trim();
            if (url.length() == 0) continue;
            String title = item.optString("title").trim();
            result.add(new Bookmark(title.length() == 0 ? url : title, url,
                    item.optLong("createdAt", 0L)));
        }
        return result;
    }

    synchronized Bookmark add(String title, String url) {
        String cleanUrl = cleanUrl(url);
        if (cleanUrl == null) return null;
        ArrayList<Bookmark> next = new ArrayList<Bookmark>(list());
        for (Bookmark item : next) if (item.url.equals(cleanUrl)) return null;
        Bookmark added = new Bookmark(cleanTitle(title, cleanUrl), cleanUrl, System.currentTimeMillis());
        next.add(0, added);
        persist(next);
        return added;
    }

    synchronized boolean update(String originalUrl, String title, String url) {
        String original = cleanUrl(originalUrl);
        String cleanUrl = cleanUrl(url);
        if (original == null || cleanUrl == null) return false;
        ArrayList<Bookmark> current = new ArrayList<Bookmark>(list());
        for (Bookmark item : current)
            if (!item.url.equals(original) && item.url.equals(cleanUrl)) return false;
        boolean changed = false;
        for (int i = 0; i < current.size(); i++) {
            Bookmark item = current.get(i);
            if (!item.url.equals(original)) continue;
            String nextTitle = cleanTitle(title, cleanUrl);
            if (!item.title.equals(nextTitle) || !item.url.equals(cleanUrl)) {
                current.set(i, new Bookmark(nextTitle, cleanUrl, item.createdAt));
                changed = true;
            }
        }
        if (changed) persist(current);
        return true;
    }

    synchronized void remove(String url) {
        String target = cleanUrl(url);
        if (target == null) return;
        ArrayList<Bookmark> current = new ArrayList<Bookmark>(list());
        boolean changed = false;
        for (int i = current.size() - 1; i >= 0; i--) {
            if (current.get(i).url.equals(target)) {
                current.remove(i);
                changed = true;
            }
        }
        if (changed) persist(current);
    }

    private JSONArray readArray() {
        try {
            return new JSONArray(prefs.getString(KEY, "[]"));
        } catch (Exception invalid) {
            return new JSONArray();
        }
    }

    private void persist(List<Bookmark> items) {
        JSONArray raw = new JSONArray();
        int count = 0;
        for (Bookmark item : items) {
            if (count >= MAX_BOOKMARKS) break;
            JSONObject entry = new JSONObject();
            try {
                entry.put("title", item.title);
                entry.put("url", item.url);
                entry.put("createdAt", item.createdAt);
                raw.put(entry);
            } catch (Exception ignored) {
            }
            count++;
        }
        prefs.edit().putString(KEY, raw.toString()).apply();
    }

    private static String cleanUrl(String url) {
        if (url == null) return null;
        String value = url.trim();
        return value.length() == 0 ? null : value;
    }

    private static String cleanTitle(String title, String fallbackUrl) {
        String value = title == null ? "" : title.trim();
        if (value.length() == 0) value = fallbackUrl;
        if (value.codePointCount(0, value.length()) > MAX_TITLE_LENGTH)
            value = value.substring(0, value.offsetByCodePoints(0, MAX_TITLE_LENGTH));
        return value;
    }
}
