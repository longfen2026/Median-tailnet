package com.xinyv.median;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.view.View;
import android.widget.TextView;

import java.util.List;

/** Dedicated process boundary for WebViews that will use the embedded Tailnet proxy. */
public final class TailnetActivity extends Activity {
    private int nativeNode;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!TailnetPolicy.PROCESS_SUFFIX.equals(processSuffix())) {
            throw new IllegalStateException("TailnetActivity must run in the Tailnet process");
        }
        TextView status = new TextView(this);
        status.setPadding(48, 48, 48, 48);
        try {
            TailnetNative.load();
            nativeNode = TailnetNative.createNode(new java.io.File(getFilesDir(), "tailnet/node-v1"));
            status.setText("Tailnet 本地节点已准备就绪");
        } catch (RuntimeException error) {
            status.setText("Tailnet 本地节点不可用");
        }
        status.setContentDescription("Tailnet 状态");
        setContentView(status);
    }

    @Override protected void onDestroy() {
        if (nativeNode > 0) {
            TailnetNative.nativeCloseNode(nativeNode);
            nativeNode = 0;
        }
        super.onDestroy();
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