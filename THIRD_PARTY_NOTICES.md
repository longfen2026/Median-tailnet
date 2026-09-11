# Third-party notices

Median Browser 1.4.0 的生产 APK 不再打包 AndroidX、Kotlin 或第三方运行时库。

项目内包含 AndroidX WebKit document-start API 的聚焦兼容源码，以及 Android System WebView support-library boundary 协议所需接口。AndroidX 部分适用 Apache License 2.0；Chromium boundary 部分适用 Chromium BSD-style license。

测试和构建工具：

- JUnit 4.13.2（仅测试）— Eclipse Public License 1.0。
- Android Gradle Plugin 与 Gradle（仅构建工具）— 各自许可证适用。
- Android SDK Build Tools / R8 / AAPT2（仅构建工具）— Android SDK 相关许可证适用。

应用运行时依赖设备提供的 Android framework 和 Android System WebView。正式发布时应从最终 AAB 重新生成依赖与许可证报告。

## Focused AndroidX WebKit compatibility slice

Median vendors only the source-compatible document-start WebKit facade and the
Chromium support-library boundary interfaces required by that API. The code is
based on AndroidX WebKit (Apache License 2.0) and Chromium support-library
boundary sources (BSD-style license). Unrelated AndroidX APIs and transitive
runtime libraries are intentionally not packaged.

## Tailnet native component

When the optional Tailnet browsing component is built, it packages a pinned
source revision of `tailscale/libtailscale` under the BSD 3-Clause License.
The exact revision, Go toolchain, Android NDK, ABI list, and SHA-256 output
hashes are recorded by `tools/build_libtailscale_android.ps1` during release
assembly. Android builds use Go's shared-library mode because Go does not
support its archive mode for Android targets. This component is not yet
included in the standard APK.
