package com.xinyv.median;

import java.io.File;

/** JNI boundary for the optional libtailscale node in the isolated Tailnet process. */
final class TailnetNative {
    private static boolean loaded;

    private TailnetNative() {}

    static synchronized void load() {
        if (!loaded) {
            System.loadLibrary("median_tailnet");
            loaded = true;
        }
    }

    static int createNode(File stateDirectory) {
        if (stateDirectory == null) throw new NullPointerException("stateDirectory");
        if (!stateDirectory.exists() && !stateDirectory.mkdirs()) {
            throw new IllegalStateException("无法创建 Tailnet 状态目录");
        }
        int handle = nativeCreateNode(stateDirectory.getAbsolutePath());
        if (handle <= 0) throw new IllegalStateException("无法创建 Tailnet 节点");
        return handle;
    }

    static native int nativeCreateNode(String stateDirectory);
    static native int nativeCloseNode(int handle);
    static native String nativeLastError(int handle);
}