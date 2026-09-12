package com.xinyv.median;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.Executor;

/** Dedicated process boundary for WebViews that will use the embedded Tailnet proxy. */
public final class TailnetActivity extends Activity {
    private static final String TAG = "TailnetActivity";
    private static final long STATUS_POLL_INTERVAL_MS = 1500L;
    private static final String START_URL = "http://nas.tailad3199.ts.net:4533/";
    private static boolean tailnetDataDirectoryConfigured;
    private final Object lifecycleLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int nativeNode;
    private TailnetNative.ConnectAdapter connectAdapter;
    private TailnetProxyController proxyController;
    private WebView webView;
    private boolean destroyed;
    private String pendingAuthUrl;

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
        TextView status = new TextView(this);
        status.setPadding(48, 48, 48, 48);
        status.setContentDescription("Tailnet 状态");
        setContentView(status);
        new Thread(new Runnable() {
            @Override public void run() {
                initializeTailnet(status);
            }
        }, "TailnetInit").start();
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
                if (!destroyed) status.setText("Tailnet 正在连接");
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
        status.setText("点按此处完成 Tailnet 设备授权");
        status.setContentDescription("Tailnet 设备等待授权");
        status.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View ignored) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(pendingAuthUrl)));
                } catch (RuntimeException error) {
                    Log.e(TAG, "Unable to open Tailnet authorization URL", error);
                }
            }
        });
    }

    private void showBrowser() {
        if (isFinishing() || isDestroyed()) return;
        pendingAuthUrl = null;
        View current = findViewById(android.R.id.content);
        if (current != null) current.setOnClickListener(null);
        if (webView == null) createWebView();
        webView.loadUrl(START_URL);
    }

    private void createWebView() {
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        WebViewPolicy.applySecureDefaults(settings, WebSettings.LOAD_DEFAULT);
        settings.setLoadsImagesAutomatically(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                Log.i(TAG, "Tailnet navigation started: " + url);
            }

            @Override public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "Tailnet navigation finished: " + url + ", title=" + view.getTitle());
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
        setContentView(webView);
    }

    private final class ProxyCallback implements TailnetProxyController.Callback {
        private final TextView status;

        ProxyCallback(TextView status) {
            this.status = status;
        }

        @Override public void onApplied() {
            status.setText("Tailnet 代理已就绪");
            showBrowser();
        }

        @Override public void onFailure(String message) {
            Log.e(TAG, "Tailnet proxy setup failed: " + message);
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