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
import android.text.InputType;
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
    private LinearLayout topBar;
    private EditText addressBar;
    private ProgressBar pageProgress;
    private FrameLayout webContainer;
    private LinearLayout entryContainer;
    private LinearLayout bookmarkGrid;
    private TextView bookmarkHint;
    private TailnetBookmarkStore bookmarkStore;
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
        showTailnetEntry();
    }

    private void createWebView() {
        if (webView != null) return;
        if (webContainer == null) buildBrowserUi();
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
        webView.bringToFront();
        topBar.setVisibility(View.VISIBLE);
    }

    private void buildBrowserUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(WHITE);

        topBar = new LinearLayout(this);
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
        entryContainer = new LinearLayout(this);
        entryContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        entryContainer.setOrientation(LinearLayout.VERTICAL);
        entryContainer.setPadding(dp(24), 0, dp(24), 0);
        buildEntryContent();
        webContainer.addView(entryContainer, new FrameLayout.LayoutParams(
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
        topBar.setVisibility(View.GONE);

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

    /** Entry page: centered logo, address bar, and bookmark tiles. */
    private void buildEntryContent() {
        entryContainer.removeAllViews();

        // Vertical spacer pushes content toward the optical center of the page.
        View topSpacer = new View(this);
        entryContainer.addView(topSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.32f));

        TailnetLogoView logo = new TailnetLogoView(this);
        logo.setContentDescription("Tailnet");
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        entryContainer.addView(logo, logoParams);

        // Centered address bar styled like the top pill.
        LinearLayout entryPill = new LinearLayout(this);
        entryPill.setGravity(Gravity.CENTER_VERTICAL);
        entryPill.setPadding(dp(6), 0, dp(6), 0);
        entryPill.setBackground(roundRect(SURFACE, 24));
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        pillParams.gravity = Gravity.CENTER_HORIZONTAL;
        pillParams.setMargins(0, dp(18), 0, 0);
        entryContainer.addView(entryPill, pillParams);

        BrowserIconView searchIcon = iconButton(BrowserIconView.SEARCH, "搜索 Tailnet");
        entryPill.addView(searchIcon, new LinearLayout.LayoutParams(dp(42), dp(48)));

        final EditText entryAddress = new EditText(this);
        entryAddress.setSingleLine(true);
        entryAddress.setTextSize(15f);
        entryAddress.setTextColor(TEXT);
        entryAddress.setHintTextColor(Color.rgb(128, 134, 139));
        entryAddress.setHint("输入 Tailnet 地址");
        entryAddress.setSelectAllOnFocus(true);
        entryAddress.setImeOptions(EditorInfo.IME_ACTION_GO);
        entryAddress.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        entryAddress.setBackgroundColor(Color.TRANSPARENT);
        entryPill.addView(entryAddress, new LinearLayout.LayoutParams(0, dp(48), 1f));
        entryAddress.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    loadTailnetAddress(entryAddress.getText().toString());
                    entryAddress.clearFocus();
                    hideKeyboard();
                    return true;
                }
                return false;
            }
        });

        // Bookmark section header with add button.
        LinearLayout bookmarkHeader = new LinearLayout(this);
        bookmarkHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(0, dp(26), 0, dp(6));
        entryContainer.addView(bookmarkHeader, headerParams);

        TextView bookmarkTitle = new TextView(this);
        bookmarkTitle.setText("常用书签");
        bookmarkTitle.setTextColor(TEXT);
        bookmarkTitle.setTextSize(14f);
        bookmarkTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        bookmarkHeader.addView(bookmarkTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        BrowserIconView addBookmark = iconButton(BrowserIconView.PLUS, "添加常用书签");
        addBookmark.setTintColor(BLUE);
        bookmarkHeader.addView(addBookmark, new LinearLayout.LayoutParams(dp(36), dp(36)));
        addBookmark.setOnClickListener(view -> showBookmarkEditor(null, entryAddress.getText().toString()));

        bookmarkGrid = new LinearLayout(this);
        bookmarkGrid.setOrientation(LinearLayout.VERTICAL);
        entryContainer.addView(bookmarkGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bookmarkHint = new TextView(this);
        bookmarkHint.setText("暂无常用书签，点 + 添加");
        bookmarkHint.setTextColor(Color.rgb(95, 99, 104));
        bookmarkHint.setTextSize(13f);
        bookmarkHint.setGravity(Gravity.CENTER);
        bookmarkHint.setPadding(0, dp(10), 0, dp(10));
        entryContainer.addView(bookmarkHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Bottom spacer keeps the block visually centered.
        View bottomSpacer = new View(this);
        entryContainer.addView(bottomSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.68f));

        renderBookmarks();
    }

    private void renderBookmarks() {
        if (bookmarkGrid == null) return;
        if (bookmarkStore == null) bookmarkStore = new TailnetBookmarkStore(this);
        List<TailnetBookmarkStore.Bookmark> bookmarks = bookmarkStore.list();
        bookmarkGrid.removeAllViews();
        bookmarkHint.setVisibility(bookmarks.isEmpty() ? View.VISIBLE : View.GONE);

        LinearLayout row = null;
        for (int index = 0; index < bookmarks.size(); index++) {
            if (index % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowParams.setMargins(0, 0, 0, dp(8));
                bookmarkGrid.addView(row, rowParams);
            }
            row.addView(bookmarkTile(bookmarks.get(index)), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private View bookmarkTile(final TailnetBookmarkStore.Bookmark bookmark) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(4), dp(10), dp(4), dp(10));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setBackground(rippleBackground());

        View badge = new View(this);
        badge.setBackground(roundRect(SURFACE, 14));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(46), dp(46));
        tile.addView(badge, badgeParams);

        TextView label = new TextView(this);
        label.setText(bookmark.title);
        label.setTextColor(TEXT);
        label.setTextSize(12f);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(6), 0, 0);
        tile.addView(label, labelParams);

        tile.setOnClickListener(view -> loadTailnetAddress(bookmark.url));
        tile.setOnLongClickListener(view -> {
            showBookmarkActions(bookmark);
            return true;
        });
        return tile;
    }

    private void showBookmarkActions(final TailnetBookmarkStore.Bookmark bookmark) {
        new AlertDialog.Builder(this)
                .setTitle(bookmark.title)
                .setItems(new CharSequence[] { "编辑书签", "删除书签" }, (dialog, which) -> {
                    if (which == 0) showBookmarkEditor(bookmark, bookmark.url);
                    else {
                        bookmarkStore.remove(bookmark.url);
                        renderBookmarks();
                        Toast.makeText(this, "已删除书签", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showBookmarkEditor(final TailnetBookmarkStore.Bookmark existing, String initialUrl) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(4), dp(4), dp(4), dp(0));

        final EditText titleInput = new EditText(this);
        titleInput.setHint("名称");
        titleInput.setSingleLine(true);
        if (existing != null) titleInput.setText(bookmarkStore.list().isEmpty()
                ? "" : existing.title);
        form.addView(titleInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText urlInput = new EditText(this);
        urlInput.setHint("Tailnet 地址");
        urlInput.setSingleLine(true);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(initialUrl == null ? "" : initialUrl);
        form.addView(urlInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "添加常用书签" : "编辑常用书签")
                .setView(form)
                .setPositiveButton(existing == null ? "添加" : "保存", (dialog, which) -> {
                    String title = titleInput.getText().toString().trim();
                    String url = urlInput.getText().toString().trim();
                    if (url.length() == 0) {
                        Toast.makeText(this, "请输入 Tailnet 地址", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String normalized = OmniboxInput.isExplicitHttpUrl(url)
                            ? url : OmniboxInput.withDefaultHttpsScheme(url);
                    try {
                        normalized = NetworkSecurity.parseHttpUrl(normalized).toString();
                    } catch (Exception invalid) {
                        Toast.makeText(this, "请输入有效的 HTTP(S) 地址", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (existing == null) {
                        if (bookmarkStore.add(title, normalized) == null)
                            Toast.makeText(this, "该地址已在书签中", Toast.LENGTH_SHORT).show();
                    } else if (!bookmarkStore.update(existing.url, title, normalized)) {
                        Toast.makeText(this, "保存书签失败", Toast.LENGTH_SHORT).show();
                    }
                    renderBookmarks();
                })
                .setNegativeButton("取消", null)
                .show();
        urlInput.requestFocus();
    }

    private GradientDrawable rippleBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.TRANSPARENT);
        drawable.setCornerRadius(dp(12));
        return drawable;
    }

    private void loadTailnetAddress(String input) {
        String value = input == null ? "" : input.trim();
        if (value.length() == 0) return;
        String candidate = OmniboxInput.isExplicitHttpUrl(value)
                ? value : OmniboxInput.withDefaultHttpsScheme(value);
        try {
            createWebView();
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
        if (webContainer == null) buildBrowserUi();
        if (bookmarkStore == null) bookmarkStore = new TailnetBookmarkStore(this);
        renderBookmarks();
        topBar.setVisibility(View.GONE);
        pageProgress.setVisibility(View.GONE);
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