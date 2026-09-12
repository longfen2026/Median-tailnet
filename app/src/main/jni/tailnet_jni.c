#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "tailscale.h"
#include "tailnet_connect_adapter.h"

JNIEXPORT jint JNICALL
Java_com_xinyv_median_TailnetNative_nativeCreateNode(
        JNIEnv *env, jclass clazz, jstring state_directory,
        jstring interfaces_json, jstring default_interface) {
    (void) clazz;
    if (state_directory == NULL || interfaces_json == NULL || default_interface == NULL) return -1;

    const char *directory = (*env)->GetStringUTFChars(env, state_directory, NULL);
    if (directory == NULL) return -1;
    const char *interfaces = (*env)->GetStringUTFChars(env, interfaces_json, NULL);
    if (interfaces == NULL) {
        (*env)->ReleaseStringUTFChars(env, state_directory, directory);
        return -1;
    }
    const char *default_name = (*env)->GetStringUTFChars(env, default_interface, NULL);
    if (default_name == NULL) {
        (*env)->ReleaseStringUTFChars(env, interfaces_json, interfaces);
        (*env)->ReleaseStringUTFChars(env, state_directory, directory);
        return -1;
    }
    tailscale node = tailscale_new();
    if (node <= 0
            || tailscale_set_android_network(node, interfaces, default_name) != 0
            || tailscale_set_dir(node, directory) != 0) {
        if (node > 0) tailscale_close(node);
        (*env)->ReleaseStringUTFChars(env, default_interface, default_name);
        (*env)->ReleaseStringUTFChars(env, interfaces_json, interfaces);
        (*env)->ReleaseStringUTFChars(env, state_directory, directory);
        return -1;
    }
    tailscale_set_logfd(node, -1);
    (*env)->ReleaseStringUTFChars(env, default_interface, default_name);
    (*env)->ReleaseStringUTFChars(env, interfaces_json, interfaces);
    (*env)->ReleaseStringUTFChars(env, state_directory, directory);
    return node;
}

JNIEXPORT jint JNICALL
Java_com_xinyv_median_TailnetNative_nativeCloseNode(
        JNIEnv *env, jclass clazz, jint handle) {
    (void) env;
    (void) clazz;
    return tailscale_close(handle);
}

JNIEXPORT jint JNICALL
Java_com_xinyv_median_TailnetNative_nativeStartNode(
        JNIEnv *env, jclass clazz, jint handle) {
    (void) env;
    (void) clazz;
    return tailscale_start(handle);
}

JNIEXPORT jstring JNICALL
Java_com_xinyv_median_TailnetNative_nativeCreateLoopbackSocksAddress(
        JNIEnv *env, jclass clazz, jint handle) {
    (void) clazz;
    char address[128] = {0};
    char proxy_credential[33] = {0};
    char local_api_credential[33] = {0};
    int result = tailscale_loopback(handle, address, sizeof(address),
            proxy_credential, local_api_credential);
    if (result != 0 || address[0] == '\0' || proxy_credential[0] == '\0') {
        memset(address, 0, sizeof(address));
        memset(proxy_credential, 0, sizeof(proxy_credential));
        memset(local_api_credential, 0, sizeof(local_api_credential));
        return NULL;
    }
    jstring value = (*env)->NewStringUTF(env, address);
    memset(address, 0, sizeof(address));
    memset(proxy_credential, 0, sizeof(proxy_credential));
    memset(local_api_credential, 0, sizeof(local_api_credential));
    return value;
}

JNIEXPORT jlong JNICALL
Java_com_xinyv_median_TailnetNative_nativeStartConnectAdapter(
        JNIEnv *env, jclass clazz, jint handle) {
    (void) env;
    (void) clazz;
    if (handle <= 0) return 0;
    return (jlong) (intptr_t) tailnet_connect_adapter_start(handle);
}

JNIEXPORT jstring JNICALL
Java_com_xinyv_median_TailnetNative_nativeConnectAdapterAddress(
        JNIEnv *env, jclass clazz, jlong adapter_handle) {
    (void) clazz;
    char address[64];
    tailnet_connect_adapter *adapter =
            (tailnet_connect_adapter *) (intptr_t) adapter_handle;
    if (tailnet_connect_adapter_address(adapter, address, sizeof(address)) != 0)
        return NULL;
    return (*env)->NewStringUTF(env, address);
}

JNIEXPORT void JNICALL
Java_com_xinyv_median_TailnetNative_nativeStopConnectAdapter(
        JNIEnv *env, jclass clazz, jlong adapter_handle) {
    (void) env;
    (void) clazz;
    tailnet_connect_adapter_stop(
            (tailnet_connect_adapter *) (intptr_t) adapter_handle);
}

JNIEXPORT jstring JNICALL
Java_com_xinyv_median_TailnetNative_nativeStatusJson(
        JNIEnv *env, jclass clazz, jint handle) {
    (void) clazz;
    char *status_json = NULL;
    if (handle <= 0 || tailscale_status_json(handle, &status_json) != 0 || status_json == NULL) {
        free(status_json);
        return NULL;
    }
    jstring value = (*env)->NewStringUTF(env, status_json);
    free(status_json);
    return value;
}

JNIEXPORT jstring JNICALL
Java_com_xinyv_median_TailnetNative_nativeLastError(
        JNIEnv *env, jclass clazz, jint handle) {
    (void) clazz;
    char message[512] = {0};
    tailscale_errmsg(handle, message, sizeof(message));
    return (*env)->NewStringUTF(env, message);
}