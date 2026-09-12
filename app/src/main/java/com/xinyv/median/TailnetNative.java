package com.xinyv.median;

import android.content.Context;

import java.io.File;

/** JNI boundary for the optional libtailscale node used by Median's routing proxy. */
final class TailnetNative {
    private static boolean loaded;

    private TailnetNative() {}

    static synchronized void load() {
        if (!loaded) {
            System.loadLibrary("median_tailnet");
            loaded = true;
        }
    }

    static int createNode(Context context, File stateDirectory) {
        if (context == null) throw new NullPointerException("context");
        if (stateDirectory == null) throw new NullPointerException("stateDirectory");
        if (!stateDirectory.exists() && !stateDirectory.mkdirs()) {
            throw new IllegalStateException("无法创建 Tailnet 状态目录");
        }
        TailnetNetworkSnapshot.Snapshot network = TailnetNetworkSnapshot.capture(context);
        int handle = nativeCreateNode(stateDirectory.getAbsolutePath(), network.interfacesJson,
                network.defaultInterfaceName);
        if (handle <= 0) throw new IllegalStateException("无法创建 Tailnet 节点");
        return handle;
    }

    static void startNode(int handle) {
        if (handle <= 0) throw new IllegalArgumentException("Tailnet 节点无效");
        if (nativeStartNode(handle) == 0) return;
        String detail = nativeLastError(handle);
        throw new IllegalStateException("无法启动 Tailnet 节点: "
            + (detail == null || detail.length() == 0 ? "未知错误" : detail));
    }

    static void updateNetwork(int handle, TailnetNetworkSnapshot.Snapshot network) {
        if (handle <= 0) throw new IllegalArgumentException("Tailnet 节点无效");
        if (network == null) throw new NullPointerException("network");
        if (nativeUpdateNetwork(handle, network.interfacesJson, network.defaultInterfaceName) == 0)
            return;
        String detail = nativeLastError(handle);
        throw new IllegalStateException("无法更新 Tailnet 网络: "
            + (detail == null || detail.length() == 0 ? "未知错误" : detail));
    }

    static TailnetStatus status(int handle) {
        if (handle <= 0) throw new IllegalArgumentException("Tailnet 节点无效");
        String json = nativeStatusJson(handle);
        if (json == null) throw new IllegalStateException("无法读取 Tailnet 节点状态");
        return TailnetStatus.parse(json);
    }

    static LoopbackSocksEndpoint createLoopbackSocksEndpoint(int handle) {
        if (handle <= 0) throw new IllegalArgumentException("Tailnet 节点无效");
        String address = nativeCreateLoopbackSocksAddress(handle);
        if (address == null) throw new IllegalStateException("无法创建 Tailnet 回环代理");
        return new LoopbackSocksEndpoint(normalizeLoopbackAddress(address));
    }

    static ConnectAdapter startConnectAdapter(int handle, String[] domainRules) {
        if (handle <= 0) throw new IllegalArgumentException("Tailnet 节点无效");
        if (domainRules == null) throw new NullPointerException("domainRules");
        long adapterHandle = nativeStartConnectAdapter(handle, domainRules);
        if (adapterHandle == 0) throw new IllegalStateException("无法启动 Tailnet HTTP 代理");
        String proxyUrl = nativeConnectAdapterAddress(adapterHandle);
        if (proxyUrl == null) {
            nativeStopConnectAdapter(adapterHandle);
            throw new IllegalStateException("无法读取 Tailnet HTTP 代理地址");
        }
        return new ConnectAdapter(adapterHandle, TailnetPolicy.normalizeLoopbackHttpProxyUrl(proxyUrl));
    }

    private static String normalizeLoopbackAddress(String address) {
        if (address.startsWith("::1:")) {
            return TailnetPolicy.normalizeLoopbackSocksUrl(
                    "socks5://[::1]" + address.substring(3));
        }
        return TailnetPolicy.normalizeLoopbackSocksUrl("socks5://" + address);
    }

    static final class LoopbackSocksEndpoint {
        final String proxyUrl;

        LoopbackSocksEndpoint(String proxyUrl) {
            this.proxyUrl = proxyUrl;
        }
    }

    static final class ConnectAdapter {
        private long nativeHandle;
        final String proxyUrl;

        ConnectAdapter(long nativeHandle, String proxyUrl) {
            this.nativeHandle = nativeHandle;
            this.proxyUrl = proxyUrl;
        }

        synchronized boolean close() {
            if (nativeHandle != 0) {
                nativeStopConnectAdapter(nativeHandle);
                nativeHandle = 0;
                return true;
            }
            return false;
        }
    }

        static native int nativeCreateNode(String stateDirectory, String interfacesJson,
            String defaultInterfaceName);
    static native int nativeCloseNode(int handle);
    static native int nativeStartNode(int handle);
        static native int nativeUpdateNetwork(int handle, String interfacesJson,
            String defaultInterfaceName);
    static native String nativeCreateLoopbackSocksAddress(int handle);
    static native long nativeStartConnectAdapter(int handle, String[] domainRules);
    static native String nativeConnectAdapterAddress(long adapterHandle);
    static native void nativeStopConnectAdapter(long adapterHandle);
    static native String nativeStatusJson(int handle);
    static native String nativeLastError(int handle);
}