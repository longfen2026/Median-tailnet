package com.xinyv.median;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/** Owns the embedded Tailnet node and the process-wide split-routing proxy. */
final class TailnetManager implements AutoCloseable {
    interface Listener {
        void onStateChanged();
    }

    enum State {
        DISABLED, STARTING, NEEDS_LOGIN, CONNECTING, RUNNING, ERROR
    }

    private static final String TAG = "TailnetManager";
    private static final long STATUS_POLL_INTERVAL_MS = 1500L;
    private static final long NETWORK_UPDATE_DEBOUNCE_MS = 500L;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final TailnetProxyController proxyController;
    private final Listener listener;
    private final Runnable statusPoll = new Runnable() {
        @Override public void run() { pollStatusAsync(); }
    };
    private final Runnable networkUpdate = new Runnable() {
        @Override public void run() { updateNetworkAsync(); }
    };

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private int nativeNode;
    private TailnetNative.ConnectAdapter connectAdapter;
    private List<String> domainRules = Collections.emptyList();
    private State state = State.DISABLED;
    private String authUrl;
    private String errorMessage;
    private String lastNetworkInterfacesJson;
    private String lastNetworkDefaultInterface;
    private boolean enabled;
    private boolean closed;
    private long generation;

    TailnetManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        proxyController = new TailnetProxyController(new Executor() {
            @Override public void execute(Runnable command) { mainHandler.post(command); }
        });
    }

    void configure(boolean enabled, Iterable<String> customRules) {
        List<String> normalized = new TailnetDomainPolicy(customRules).rules();
        boolean restart;
        synchronized (lock) {
            if (closed) return;
            restart = this.enabled && enabled && !domainRules.equals(normalized) &&
                    (nativeNode > 0 || state == State.STARTING);
            this.enabled = enabled;
            domainRules = normalized;
            generation++;
        }
        if (!enabled) stop(false, null);
        else if (restart) stop(false, new Runnable() {
            @Override public void run() { startIfNeeded(); }
        });
        else startIfNeeded();
    }

    State state() {
        synchronized (lock) { return state; }
    }

    String authUrl() {
        synchronized (lock) { return authUrl; }
    }

    String errorMessage() {
        synchronized (lock) { return errorMessage; }
    }

    List<String> domainRules() {
        synchronized (lock) { return new ArrayList<>(domainRules); }
    }

    private void startIfNeeded() {
        final long startGeneration;
        synchronized (lock) {
            if (closed || !enabled || nativeNode > 0 || state == State.STARTING) return;
            state = State.STARTING;
            authUrl = null;
            errorMessage = null;
            startGeneration = generation;
        }
        notifyChanged();
        registerNetworkCallback();
        new Thread(new Runnable() {
            @Override public void run() {
                int node = 0;
                try {
                    TailnetNative.load();
                    node = TailnetNative.createNode(context,
                            new File(context.getFilesDir(), "tailnet/node-v1"));
                    TailnetNative.startNode(node);
                    synchronized (lock) {
                        if (closed || !enabled || generation != startGeneration) {
                            TailnetNative.nativeCloseNode(node);
                            return;
                        }
                        nativeNode = node;
                    }
                    handleStatus(node, TailnetNative.status(node));
                } catch (RuntimeException error) {
                    if (node > 0) {
                        synchronized (lock) { if (nativeNode == node) nativeNode = 0; }
                        TailnetNative.nativeCloseNode(node);
                    }
                    fail("Tailnet 本地节点不可用", error);
                }
            }
        }, "TailnetInit").start();
    }

    private void handleStatus(int node, TailnetStatus status) {
        synchronized (lock) {
            if (closed || !enabled || nativeNode != node) return;
        }
        if (status.isRunning()) {
            boolean changed;
            synchronized (lock) {
                State nextState = stateForStatus(state, status);
                changed = visibleStatusChanged(state, authUrl, errorMessage, status);
                state = nextState;
                authUrl = null;
                errorMessage = null;
            }
            if (changed) notifyChanged();
            startRoutingProxy();
            return;
        }
        boolean changed;
        synchronized (lock) {
            if (closed || !enabled) return;
            State nextState = stateForStatus(state, status);
            changed = visibleStatusChanged(state, authUrl, errorMessage, status);
            authUrl = status.authUrl;
            state = nextState;
            errorMessage = null;
        }
        if (changed) notifyChanged();
        mainHandler.removeCallbacks(statusPoll);
        mainHandler.postDelayed(statusPoll, STATUS_POLL_INTERVAL_MS);
    }

    static State stateForStatus(State currentState, TailnetStatus status) {
        if (status.isRunning())
            return currentState == State.RUNNING ? State.RUNNING : State.CONNECTING;
        return status.needsLogin() ? State.NEEDS_LOGIN : State.CONNECTING;
    }

    static boolean visibleStatusChanged(State currentState, String currentAuthUrl,
            String currentErrorMessage, TailnetStatus status) {
        State nextState = stateForStatus(currentState, status);
        String nextAuthUrl = status.isRunning() ? null : status.authUrl;
        return currentState != nextState || !same(currentAuthUrl, nextAuthUrl) ||
                currentErrorMessage != null;
    }

    private static boolean same(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private void pollStatusAsync() {
        final int node;
        synchronized (lock) {
            if (closed || !enabled) return;
            node = nativeNode;
        }
        if (node <= 0) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    handleStatus(node, TailnetNative.status(node));
                } catch (RuntimeException error) {
                    Log.e(TAG, "Tailnet status refresh failed", error);
                    mainHandler.postDelayed(statusPoll, STATUS_POLL_INTERVAL_MS);
                }
            }
        }, "TailnetStatus").start();
    }

    private void startRoutingProxy() {
        final int node;
        final String[] rules;
        final long proxyGeneration;
        synchronized (lock) {
            if (closed || !enabled || connectAdapter != null) return;
            node = nativeNode;
            rules = domainRules.toArray(new String[0]);
            proxyGeneration = generation;
        }
        try {
            final TailnetNative.ConnectAdapter adapter =
                    TailnetNative.startConnectAdapter(node, rules);
            synchronized (lock) {
                if (closed || !enabled) {
                    adapter.close();
                    return;
                }
                connectAdapter = adapter;
            }
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    proxyController.apply(adapter.proxyUrl, new TailnetProxyController.Callback() {
                        @Override public void onApplied() {
                            boolean stale;
                            synchronized (lock) {
                                stale = closed || !enabled || generation != proxyGeneration ||
                                        connectAdapter != adapter;
                                if (!stale) state = State.RUNNING;
                            }
                            if (stale) {
                                proxyController.clear(new Runnable() {
                                    @Override public void run() {}
                                });
                                return;
                            }
                            notifyChanged();
                        }

                        @Override public void onFailure(String message) {
                            synchronized (lock) {
                                if (closed || !enabled || generation != proxyGeneration ||
                                        connectAdapter != adapter) return;
                            }
                            fail(message, null);
                        }
                    });
                }
            });
        } catch (RuntimeException error) {
            fail("无法启动 Tailnet 路由代理", error);
        }
    }

    private void registerNetworkCallback() {
        if (connectivityManager != null) return;
        connectivityManager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { refreshNetwork(); }
            @Override public void onLost(Network network) { refreshNetwork(); }
            @Override public void onCapabilitiesChanged(Network network,
                    android.net.NetworkCapabilities capabilities) { refreshNetwork(); }
            @Override public void onLinkPropertiesChanged(Network network,
                    android.net.LinkProperties properties) { refreshNetwork(); }
        };
        try {
            connectivityManager.registerNetworkCallback(new NetworkRequest.Builder().build(),
                    networkCallback);
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to register Tailnet network callback", error);
            networkCallback = null;
        }
    }

    private void refreshNetwork() {
        mainHandler.removeCallbacks(networkUpdate);
        mainHandler.postDelayed(networkUpdate, NETWORK_UPDATE_DEBOUNCE_MS);
    }

    private void updateNetworkAsync() {
        final int node;
        synchronized (lock) { node = nativeNode; }
        if (node <= 0) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    TailnetNetworkSnapshot.Snapshot network =
                            TailnetNetworkSnapshot.capture(context);
                    synchronized (lock) {
                        if (network.interfacesJson.equals(lastNetworkInterfacesJson) &&
                                network.defaultInterfaceName.equals(lastNetworkDefaultInterface))
                            return;
                    }
                    TailnetNative.updateNetwork(node, network);
                    synchronized (lock) {
                        lastNetworkInterfacesJson = network.interfacesJson;
                        lastNetworkDefaultInterface = network.defaultInterfaceName;
                    }
                } catch (RuntimeException error) {
                    Log.e(TAG, "Tailnet network snapshot update failed", error);
                }
            }
        }, "TailnetNetwork").start();
    }

    private void fail(String message, RuntimeException error) {
        if (error != null) Log.e(TAG, message, error);
        synchronized (lock) {
            if (closed) return;
            state = State.ERROR;
            errorMessage = message;
        }
        notifyChanged();
    }

    private void notifyChanged() {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onStateChanged();
            }
        });
    }

    private void stop(boolean closing, final Runnable complete) {
        final TailnetNative.ConnectAdapter adapter;
        final int node;
        synchronized (lock) {
            if (closing) {
                closed = true;
                generation++;
            }
            adapter = connectAdapter;
            node = nativeNode;
            connectAdapter = null;
            nativeNode = 0;
            state = State.DISABLED;
            authUrl = null;
            errorMessage = null;
            lastNetworkInterfacesJson = null;
            lastNetworkDefaultInterface = null;
        }
        mainHandler.removeCallbacks(statusPoll);
        mainHandler.removeCallbacks(networkUpdate);
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); }
            catch (RuntimeException ignored) {}
            networkCallback = null;
            connectivityManager = null;
        }
        proxyController.clear(new Runnable() {
            @Override public void run() {
                new Thread(new Runnable() {
                    @Override public void run() {
                        boolean nodeClosedByAdapter = adapter != null && adapter.close();
                        if (node > 0 && !nodeClosedByAdapter) TailnetNative.nativeCloseNode(node);
                        if (complete != null) mainHandler.post(complete);
                    }
                }, "TailnetShutdown").start();
            }
        });
        if (!closing) notifyChanged();
    }

    @Override public void close() {
        stop(true, null);
    }
}