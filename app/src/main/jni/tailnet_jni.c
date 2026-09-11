#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "tailscale.h"

JNIEXPORT jint JNICALL
Java_com_xinyv_median_TailnetNative_nativeCreateNode(
        JNIEnv *env, jclass clazz, jstring state_directory) {
    (void) clazz;
    if (state_directory == NULL) return -1;

    const char *directory = (*env)->GetStringUTFChars(env, state_directory, NULL);
    if (directory == NULL) return -1;
    tailscale node = tailscale_new();
    if (node <= 0 || tailscale_set_dir(node, directory) != 0) {
        if (node > 0) tailscale_close(node);
        (*env)->ReleaseStringUTFChars(env, state_directory, directory);
        return -1;
    }
    tailscale_set_logfd(node, -1);
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

JNIEXPORT jstring JNICALL
Java_com_xinyv_median_TailnetNative_nativeLastError(
        JNIEnv *env, jclass clazz, jint handle) {
    (void) clazz;
    char message[512] = {0};
    tailscale_errmsg(handle, message, sizeof(message));
    return (*env)->NewStringUTF(env, message);
}