package com.xinyv.median;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class MainActivitySecuritySmokeTest {
    private static final String TAG = "TailnetSmokeTest";

    @Test
    public void webViewSecurityDefaultsRemainHardened() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            scenario.onActivity(activity -> {
                try {
                    WebView webView = webView(activity);
                    WebSettings settings = webView.getSettings();
                    assertFalse(settings.getAllowFileAccess());
                    assertFalse(settings.getAllowContentAccess());
                    assertFalse(settings.getSaveFormData());
                    assertTrue(settings.getMediaPlaybackRequiresUserGesture());
                    assertFalse(settings.getJavaScriptCanOpenWindowsAutomatically());
                    assertTrue(settings.getSafeBrowsingEnabled());
                    assertTrue(settings.getMixedContentMode() == WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }

    @Test
    public void exportedActivityRejectsNonHttpExplicitIntent() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Intent malicious = new Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/private.txt"));
        malicious.setClass(context, MainActivity.class);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(malicious)) {
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            scenario.onActivity(activity -> {
                try {
                    String current = currentPageUrl(activity);
                    assertNotNull(current);
                    String lower = current.toLowerCase();
                    assertFalse(lower.startsWith("file:"));
                    assertFalse(lower.startsWith("content:"));
                    assertFalse(lower.startsWith("data:"));
                    assertFalse(lower.startsWith("javascript:"));
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }

    @Test
    public void tailnetActivityWaitsForLoopbackProxyBeforeCreatingWebView() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, TailnetActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Log.i(TAG, "starting TailnetActivity");
        context.startActivity(intent);

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Log.i(TAG, "waiting for Tailnet proxy terminal state");
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (!hasTailnetTerminalState(device) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100);
        }
        UiObject2 browser = device.findObject(By.clazz(WebView.class));
        UiObject2 unavailable = device.findObject(By.text("Tailnet 浏览器暂不可用"));
        UiObject2 nodeUnavailable = device.findObject(By.text("Tailnet 本地节点不可用"));
        UiObject2 waitingAuthorization = device.findObject(
            By.desc("Tailnet 设备等待授权"));
        Log.i(TAG, "finished wait; browser=" + (browser != null)
            + ", unavailable=" + (unavailable != null) + ", nodeUnavailable="
            + (nodeUnavailable != null) + ", waitingAuthorization="
            + (waitingAuthorization != null));
        assertTrue("Tailnet Activity did not reach a proxy terminal state; foreground="
            + device.getCurrentPackageName(),
            browser != null || unavailable != null || nodeUnavailable != null
                || waitingAuthorization != null);
        if (waitingAuthorization != null) {
            assertTrue("Tailnet WebView must remain unavailable before device authorization",
                browser == null);
        }
    }

    private static boolean hasTailnetTerminalState(UiDevice device) {
        return device.hasObject(By.clazz(WebView.class))
            || device.hasObject(By.text("Tailnet 浏览器暂不可用"))
            || device.hasObject(By.text("Tailnet 本地节点不可用"))
            || device.hasObject(By.desc("Tailnet 设备等待授权"));
    }

    private static String currentPageUrl(MainActivity activity) throws Exception {
        Field field = MainActivity.class.getDeclaredField("currentPageUrl");
        field.setAccessible(true);
        Object value = field.get(activity);
        return value == null ? null : value.toString();
    }

    private static WebView webView(MainActivity activity) throws Exception {
        Field field = MainActivity.class.getDeclaredField("webView");
        field.setAccessible(true);
        WebView value = (WebView) field.get(activity);
        assertNotNull(value);
        return value;
    }
}
