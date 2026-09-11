/* Copyright 2018 The Chromium Authors. BSD-style license. */
package org.chromium.support_lib_boundary;

import java.util.concurrent.Executor;

public interface ProxyControllerBoundaryInterface {
    void setProxyOverride(String[][] proxyRules, String[] bypassRules,
            Runnable listener, Executor executor);
    void clearProxyOverride(Runnable listener, Executor executor);
}