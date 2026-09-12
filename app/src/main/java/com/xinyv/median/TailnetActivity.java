package com.xinyv.median;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebChromeClient;
import android.webkit.WebViewClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.Executor;

/** Dedicated process boundary for WebViews that will use the embedded Tailnet proxy. */
public final class TailnetActivity extends Activity {
    private static final String TAG = "TailnetActivity";
    private static final long STATUS_POLL_INTERVAL_MS = 1500L;
    private static final long NETWORK_UPDATE_DEBOUNCE_MS = 500L;
    private static final int WHITE = Color.rgb(255, 255, 255);
    private static final int TEXT = Color.rgb(32, 33, 36);
    private static final int SURFACE = Color.rgb(241, 243, 244);
    private static final int BLUE = Color.rgb(26, 115, 232);
    private static boolean tailnetDataDirectoryConfigured;
    private final Object lifecycleLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int nativeNode;
    private TailnetNative.ConnectAdapter connectAdapter;
    private TailnetProxyController proxyController;
    private WebView webView;
    private EditText addressBar;
    private ProgressBar pageProgress;
    private FrameLayout webContainer;
    private ProgressBar statusProgress;
    private AlertDialog authorizationDialog;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean destroyed;
    private String pendingAuthUrl;
    private String promptedAuthUrl;
    private String lastNetworkInterfacesJson;
    private String lastNetworkDefaultInterface;
    private final Runnable networkUpdate = new Runnable() {
        @Override public void run() {
            new Thread(new Runnable() {
                @Override public void run() {
                    int node;
                    synchronized (lifecycleLock) { node = nativeNode; }
                    if (node <= 0 || destroyed) return;
                    try {
                        TailnetNetworkSnapshot.Snapshot network =
                                TailnetNetworkSnapshot.capture(TailnetActivity.this);
                        synchronized (lifecycleLock) {
                            if (network.interfacesJson.equals(lastNetworkInterfacesJson)
                                    && network.defaultInterfaceName.equals(
                                            lastNetworkDefaultInterface)) return;
                        }
                        TailnetNative.updateNetwork(node, network);
                        synchronized (lifecycleLock) {
                            lastNetworkInterfacesJson = network.interfacesJson;
                            lastNetworkDefaultInterface = network.defaultInterfaceName;
                        }
                        Log.i(TAG, "Tailnet network snapshot updated");
                    } catch (RuntimeException error) {
                        Log.e(TAG, "Tailnet network snapshot update failed", error);
                    }
                }
            }, "TailnetNetwork").start();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!TailnetPolicy.PROCESS_SUFFIX.equals(processSuffix())) {
            throw new IllegalStateException("TailnetActivity must run in the Tailnet process");
        }
        if (!ensureTailnetDataDirectory()) {
            TextView unavailable = new TextView(this);
            unavailable.setPadding(48, 48, 48, 48);
            unavailable.setText("Tailnet 浏览器暂不可用");
            setContentView(unavailable);
            return;
        }
        TextView status = createStatusView();
        registerNetworkCallback();
        new Thread(new Runnable() {
            @Override public void run() {
                initializeTailnet(status);
            }
        }, "TailnetInit").start();
    }

    private void registerNetworkCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { refreshTailnetNetwork(); }
            @Override public void onLost(Network network) { refreshTailnetNetwork(); }
            @Override public void onCapabilitiesChanged(Network network,
                    android.net.NetworkCapabilities capabilities) { refreshTailnetNetwork(); }
            @Override public void onLinkPropertiesChanged(Network network,
                    android.net.LinkProperties properties) { refreshTailnetNetwork(); }
        };
        connectivityManager.registerNetworkCallback(new NetworkRequest.Builder().build(),
                networkCallback);
    }

    private void refreshTailnetNetwork() {
        mainHandler.removeCallbacks(networkUpdate);
        mainHandler.postDelayed(networkUpdate, NETWORK_UPDATE_DEBOUNCE_MS);
    }

    private TextView createStatusView() {
        LinearLayout surface = new LinearLayout(this);
        surface.setGravity(Gravity.CENTER);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setPadding(48, 48, 48, 48);

        statusProgress = new ProgressBar(this);
        statusProgress.setIndeterminate(true);
        surface.addView(statusProgress);

        TextView status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 32, 0, 0);
        status.setText("正在获取 Tailnet 状态");
        status.setContentDescription("Tailnet 正在加载");
        surface.addView(status);
        setContentView(surface);
        return status;
    }

    private static synchronized boolean ensureTailnetDataDirectory() {
        if (tailnetDataDirectoryConfigured) return true;
        if (android.os.Build.VERSION.SDK_INT < 28) return false;
        try {
            WebView.setDataDirectorySuffix("median_tailnet");
            tailnetDataDirectoryConfigured = true;
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to configure Tailnet WebView data directory", error);
            return false;
        }
    }

    private void initializeTailnet(final TextView status) {
        int node = 0;
        TailnetNative.ConnectAdapter adapter = null;
        try {
            TailnetNative.load();
                node = TailnetNative.createNode(this,
                    new java.io.File(getFilesDir(), "tailnet/node-v1"));
            TailnetNative.startNode(node);
            synchronized (lifecycleLock) {
                if (destroyed) {
                    TailnetNative.nativeCloseNode(node);
                    return;
                }
                nativeNode = node;
            }
            handleTailnetStatus(status, TailnetNative.status(node));
        } catch (RuntimeException error) {
            if (adapter != null) adapter.close();
            else if (node > 0) {
                synchronized (lifecycleLock) {
                    if (nativeNode == node) nativeNode = 0;
                }
                TailnetNative.nativeCloseNode(node);
            }
            Log.e(TAG, "Tailnet initialization failed: " + error.getClass().getSimpleName(), error);
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (!destroyed) status.setText("Tailnet 本地节点不可用");
                }
            });
        }
    }

    private void handleTailnetStatus(final TextView status, TailnetStatus tailnetStatus) {
        if (tailnetStatus.isRunning()) {
            pendingAuthUrl = null;
            startTailnetBrowser(status);
            return;
        }
        if (tailnetStatus.needsLogin() && tailnetStatus.authUrl != null) {
            pendingAuthUrl = tailnetStatus.authUrl;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (!destroyed && !isFinishing() && !isDestroyed()) showAuthorizationBrowser(status);
                }
            });
            scheduleStatusPoll(status);
            return;
        }
        runOnUiThread(new Runnable() {
            @Override public void run() {
                    if (!destroyed) {
                        statusProgress.setVisibility(View.VISIBLE);
                        status.setText("Tailnet 正在连接");
                        status.setContentDescription("Tailnet 正在连接");
                    }
            }
        });
        scheduleStatusPoll(status);
    }

    private void scheduleStatusPoll(final TextView status) {
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                new Thread(new Runnable() {
                    @Override public void run() {
                        int node;
                        synchronized (lifecycleLock) { node = nativeNode; }
                        if (node <= 0) return;
                        try {
                            handleTailnetStatus(status, TailnetNative.status(node));
                        } catch (RuntimeException error) {
                            Log.e(TAG, "Tailnet status refresh failed", error);
                            scheduleStatusPoll(status);
                        }
                    }
                }, "TailnetStatus").start();
            }
        }, STATUS_POLL_INTERVAL_MS);
    }

    private void startTailnetBrowser(final TextView status) {
        final int node;
        synchronized (lifecycleLock) {
            if (destroyed || connectAdapter != null) return;
            node = nativeNode;
        }
        TailnetNative.ConnectAdapter adapter = TailnetNative.startConnectAdapter(node);
        TailnetProxyController controller = new TailnetProxyController(processSuffix(), mainExecutor());
        synchronized (lifecycleLock) {
            if (destroyed) {
                adapter.close();
                return;
            }
            connectAdapter = adapter;
            proxyController = controller;
        }
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (!destroyed && !isFinishing() && !isDestroyed())
                    proxyController.apply(connectAdapter.proxyUrl, new ProxyCallback(status));
            }
        });
    }

    @Override protected void onDestroy() {
        final int node;
        final TailnetNative.ConnectAdapter adapter;
        synchronized (lifecycleLock) {
            destroyed = true;
            node = nativeNode;
            adapter = connectAdapter;
            nativeNode = 0;
            connectAdapter = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
            }
            networkCallback = null;
        }
        if (authorizationDialog != null) {
            authorizationDialog.dismiss();
            authorizationDialog = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        Runnable closeNode = new Runnable() {
            @Override public void run() {
                Thread shutdown = new Thread(new Runnable() {
                    @Override public void run() {
                        boolean nodeClosedByAdapter = adapter != null && adapter.close();
                        if (node > 0 && !nodeClosedByAdapter) TailnetNative.nativeCloseNode(node);
                    }
                }, "TailnetShutdown");
                shutdown.start();
            }
        };
        if (proxyController != null) proxyController.clear(closeNode);
        else closeNode.run();
        super.onDestroy();
    }

    private Executor mainExecutor() {
        return new Executor() {
            @Override public void execute(Runnable command) {
                mainHandler.post(command);
            }
        };
    }

    private void showAuthorizationBrowser(TextView status) {
        if (pendingAuthUrl == null) return;
        Uri authorizationUri = Uri.parse(pendingAuthUrl);
        if (!"https".equalsIgnoreCase(authorizationUri.getScheme())
                || authorizationUri.getHost() == null) {
            Log.e(TAG, "Rejected invalid Tailnet authorization URL");
            statusProgress.setVisibility(View.GONE);
            status.setText("Tailnet 授权地址无效");
            status.setContentDescription("Tailnet 授权地址无效");
            return;
        }
        statusProgress.setVisibility(View.GONE);
        status.setText("Tailnet 等待设备授权");
        status.setContentDescription("Tailnet 设备等待授权");
        if (authorizationDialog != null && authorizationDialog.isShowing()) return;
        if (pendingAuthUrl.equals(promptedAuthUrl)) return;
        promptedAuthUrl = pendingAuthUrl;
        status.setOnClickListener(null);
        authorizationDialog = new AlertDialog.Builder(this)
            .setTitle("授权 Tailnet")
            .setMessage("此设备需要先完成 Tailnet 授权，授权后将自动进入 Tailnet 浏览器。")
            .setNegativeButton("取消", (dialog, which) -> {
                status.setText("Tailnet 等待设备授权，点此重试");
                status.setContentDescription("Tailnet 等待设备授权，点此重试");
                status.setOnClickListener(view -> {
                    promptedAuthUrl = null;
                    showAuthorizationBrowser(status);
                });
            })
            .setPositiveButton("前往授权", (dialog, which) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, authorizationUri));
                } catch (RuntimeException error) {
                    Log.e(TAG, "Unable to open Tailnet authorization URL", error);
                }
            })
            .create();
        authorizationDialog.setOnDismissListener(dialog -> authorizationDialog = null);
        authorizationDialog.show();
    }

    private void showBrowser() {
        if (isFinishing() || isDestroyed()) return;
        pendingAuthUrl = null;
        if (authorizationDialog != null) {
            authorizationDialog.dismiss();
            authorizationDialog = null;
        }
        if (webView == null) createWebView();
    }

    private void createWebView() {
        buildBrowserUi();
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        WebViewPolicy.applySecureDefaults(settings, WebSettings.LOAD_DEFAULT);
        settings.setLoadsImagesAutomatically(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                Log.i(TAG, "Tailnet navigation started: " + url);
                addressBar.setText(url);
            }

            @Override public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "Tailnet navigation finished: " + url + ", title=" + view.getTitle());
                addressBar.setText(url);
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                    WebResourceError error) {
                if (request.isForMainFrame()) {
                    Log.e(TAG, "Tailnet navigation failed: " + request.getUrl()
                        + ", code=" + error.getErrorCode() + ", description="
                        + error.getDescription());
                }
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                    WebResourceResponse response) {
                if (request.isForMainFrame()) {
                    Log.w(TAG, "Tailnet HTTP response: " + request.getUrl()
                        + ", status=" + response.getStatusCode());
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                if (destroyed || view != webView) return;
                pageProgress.setProgress(progress);
                pageProgress.setVisibility(progress < 100 ? View.VISIBLE : View.GONE);
            }
        });
        webContainer.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void buildBrowserUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(WHITE);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(10), dp(7), dp(10), dp(7));
        topBar.setBackgroundColor(WHITE);

        LinearLayout addressPill = new LinearLayout(this);
        addressPill.setGravity(Gravity.CENTER_VERTICAL);
        addressPill.setPadding(dp(3), 0, dp(3), 0);
        addressPill.setBackground(roundRect(SURFACE, 22));
        BrowserIconView tailnetIcon = iconButton(BrowserIconView.SHIELD, "Tailnet 连接");
        addressPill.addView(tailnetIcon, new LinearLayout.LayoutParams(dp(40), dp(42)));

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setTextSize(15f);
        addressBar.setTextColor(TEXT);
        addressBar.setHintTextColor(Color.rgb(128, 134, 139));
        addressBar.setHint("输入 Tailnet 地址");
        addressBar.setSelectAllOnFocus(true);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        addressBar.setBackgroundColor(Color.TRANSPARENT);
        addressBar.setPadding(dp(3), 0, dp(3), 0);
        addressPill.addView(addressBar, new LinearLayout.LayoutParams(0, dp(44), 1f));
        BrowserIconView refresh = iconButton(BrowserIconView.RELOAD, "刷新");
        addressPill.addView(refresh, new LinearLayout.LayoutParams(dp(40), dp(42)));
        topBar.addView(addressPill, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        pageProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pageProgress.setMax(100);
        pageProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
        pageProgress.setVisibility(View.GONE);
        root.addView(pageProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        webContainer = new FrameLayout(this);
        webContainer.setBackgroundColor(WHITE);
        LinearLayout entry = new LinearLayout(this);
        entry.setGravity(Gravity.CENTER);
        entry.setOrientation(LinearLayout.VERTICAL);
        entry.setPadding(dp(28), dp(28), dp(28), dp(28));
        TextView title = new TextView(this);
        title.setText("Tailnet 浏览");
        title.setTextColor(TEXT);
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER);
        entry.addView(title);
        TextView hint = new TextView(this);
        hint.setText("在地址栏输入 Tailnet 设备或服务地址");
        hint.setTextColor(Color.rgb(95, 99, 104));
        hint.setTextSize(14f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(10), 0, 0);
        entry.addView(hint);
        webContainer.addView(entry, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(webContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(8), dp(2), dp(8), dp(2));
        bottomBar.setBackgroundColor(WHITE);
        bottomBar.setElevation(dp(6));
        BrowserIconView back = iconButton(BrowserIconView.BACK, "后退");
        BrowserIconView forward = iconButton(BrowserIconView.FORWARD, "前进");
        BrowserIconView home = iconButton(BrowserIconView.HOME, "Tailnet 入口");
        BrowserIconView bottomRefresh = iconButton(BrowserIconView.RELOAD, "刷新");
        BrowserIconView close = iconButton(BrowserIconView.CLOSE, "关闭 Tailnet 浏览");
        BrowserIconView[] buttons = new BrowserIconView[] {
            back, forward, home, bottomRefresh, close
        };
        for (BrowserIconView button : buttons) {
            bottomBar.addView(button, new LinearLayout.LayoutParams(0, dp(52), 1f));
        }
        root.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        setContentView(root);

        addressBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    loadTailnetAddress(addressBar.getText().toString());
                    addressBar.clearFocus();
                    hideKeyboard();
                    return true;
                }
                return false;
            }
        });
        refresh.setOnClickListener(view -> { if (webView != null) webView.reload(); });
        bottomRefresh.setOnClickListener(view -> { if (webView != null) webView.reload(); });
        back.setOnClickListener(view -> { if (webView != null && webView.canGoBack()) webView.goBack(); });
        forward.setOnClickListener(view -> { if (webView != null && webView.canGoForward()) webView.goForward(); });
        home.setOnClickListener(view -> showTailnetEntry());
        close.setOnClickListener(view -> finish());
    }

    private void loadTailnetAddress(String input) {
        String value = input == null ? "" : input.trim();
        if (value.length() == 0 || webView == null) return;
        String candidate = OmniboxInput.isExplicitHttpUrl(value)
                ? value : OmniboxInput.withDefaultHttpsScheme(value);
        try {
            webView.loadUrl(NetworkSecurity.parseHttpUrl(candidate).toString());
        } catch (Exception invalid) {
            Toast.makeText(this, "请输入有效的 HTTP(S) 地址", Toast.LENGTH_SHORT).show();
        }
    }

    private void showTailnetEntry() {
        if (webView != null) {
            webContainer.removeView(webView);
            webView.destroy();
            webView = null;
        }
        createWebView();
        addressBar.requestFocus();
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null && addressBar != null)
            keyboard.hideSoftInputFromWindow(addressBar.getWindowToken(), 0);
    }

    private BrowserIconView iconButton(int icon, String description) {
        BrowserIconView button = new BrowserIconView(this, icon);
        button.setContentDescription(description);
        return button;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class ProxyCallback implements TailnetProxyController.Callback {
        private final TextView status;

        ProxyCallback(TextView status) {
            this.status = status;
        }

        @Override public void onApplied() {
            statusProgress.setVisibility(View.VISIBLE);
            status.setText("Tailnet 代理已就绪");
            showBrowser();
        }

        @Override public void onFailure(String message) {
            Log.e(TAG, "Tailnet proxy setup failed: " + message);
            statusProgress.setVisibility(View.GONE);
            status.setText("Tailnet 浏览器暂不可用");
            status.setContentDescription("Tailnet 浏览器暂不可用");
        }
    }

    private String processSuffix() {
        String processName = currentProcessName();
        int separator = processName.indexOf(':');
        return separator < 0 ? "" : processName.substring(separator);
    }

    private String currentProcessName() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes != null) {
                int pid = Process.myPid();
                for (ActivityManager.RunningAppProcessInfo process : processes) {
                    if (process.pid == pid) return process.processName;
                }
            }
        }
        return getApplicationInfo().processName;
    }
}