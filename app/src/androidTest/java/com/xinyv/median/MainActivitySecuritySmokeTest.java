package com.xinyv.median;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class MainActivitySecuritySmokeTest {
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
    public void mainActivityOwnsTailnetRoutingAndSharedWebView() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            scenario.onActivity(activity -> {
                try {
                    assertNotNull(webView(activity));
                    Field manager = MainActivity.class.getDeclaredField("tailnetManager");
                    manager.setAccessible(true);
                    assertNotNull(manager.get(activity));
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }

    @Test
    public void mainMenuExposesTailnetAboveCurrentPageAndOutsideBrowserSettings() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            scenario.onActivity(activity -> {
                try {
                    invoke(activity, "showMainMenu");
                    String mainMenu = overlayText(activity);
                    assertTrue(mainMenu.contains("Tailnet 分流"));
                    assertTrue(mainMenu.contains("普通网络直连"));
                    assertTrue(mainMenu.indexOf("Tailnet 分流") < mainMenu.indexOf("当前网站设置"));

                    invoke(activity, "showBrowserSettings");
                    assertFalse(overlayText(activity).contains("Tailnet 分流"));

                    invoke(activity, "showTailnetSettings");
                    String tailnetSettings = overlayText(activity);
                    assertTrue(tailnetSettings.contains("启用 Tailnet 分流"));
                    assertTrue(tailnetSettings.contains("自定义 Tailnet 域名"));
                    assertTrue(tailnetSettings.contains("默认 *.ts.net"));
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }

    @Test
    public void runningTailnetUpdatesMenuColorAndDefaultHomeLogoOnly() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            scenario.onActivity(activity -> {
                try {
                    invoke(activity, "showHome");
                    setTailnetState(activity, TailnetManager.State.RUNNING);
                    invoke(activity, "updateTailnetConnectionUi");
                    assertEquals(Color.rgb(52, 168, 83), menuTint(activity));
                    assertTrue(cachedHomeHtml(activity).contains("an-tailnet</div>"));

                    setTailnetState(activity, TailnetManager.State.DISABLED);
                    invoke(activity, "updateTailnetConnectionUi");
                    assertEquals(Color.rgb(32, 33, 36), menuTint(activity));
                    assertFalse(cachedHomeHtml(activity).contains("an-tailnet</div>"));
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }

    private static void setTailnetState(MainActivity activity, TailnetManager.State state) throws Exception {
        Field managerField = MainActivity.class.getDeclaredField("tailnetManager");
        managerField.setAccessible(true);
        Object manager = managerField.get(activity);
        Field stateField = TailnetManager.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(manager, state);
    }

    private static int menuTint(MainActivity activity) throws Exception {
        Field menuField = MainActivity.class.getDeclaredField("menuButton");
        menuField.setAccessible(true);
        Object menu = menuField.get(activity);
        Field tintField = BrowserIconView.class.getDeclaredField("tintColor");
        tintField.setAccessible(true);
        return tintField.getInt(menu);
    }

    private static String cachedHomeHtml(MainActivity activity) throws Exception {
        Field field = MainActivity.class.getDeclaredField("cachedHomeHtml");
        field.setAccessible(true);
        return String.valueOf(field.get(activity));
    }

    private static void invoke(MainActivity activity, String methodName) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(activity);
    }

    private static String overlayText(MainActivity activity) throws Exception {
        Field field = MainActivity.class.getDeclaredField("activeOverlay");
        field.setAccessible(true);
        return collectText((View) field.get(activity), new StringBuilder()).toString();
    }

    private static StringBuilder collectText(View view, StringBuilder result) {
        if (view instanceof TextView) result.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectText(group.getChildAt(i), result);
        }
        return result;
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
