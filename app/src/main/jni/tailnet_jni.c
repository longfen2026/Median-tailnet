#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "tailscale.h"
#include "tailnet_connect_adapter.h"

#define TAILNET_LOG_TAG "MedianTailnet"

typedef struct tailnet_log_bridge {
    tailscale node;
    int read_fd;
    pthread_t thread;
    struct tailnet_log_bridge *next;
} tailnet_log_bridge;

static pthread_mutex_t log_bridges_mutex = PTHREAD_MUTEX_INITIALIZER;
static tailnet_log_bridge *log_bridges;

static void log_tailnet_line(const char *line, size_t length) {
    while (length > 0 && (line[length - 1] == '\n' || line[length - 1] == '\r')) --length;
    if (length > 0) __android_log_print(ANDROID_LOG_INFO, TAILNET_LOG_TAG,
            "%.*s", (int) length, line);
}

static void *tailnet_log_main(void *argument) {
    tailnet_log_bridge *bridge = argument;
    char incoming[2048];
    char line[4096];
    size_t line_length = 0;
    for (;;) {
        ssize_t length = read(bridge->read_fd, incoming, sizeof(incoming));
        if (length < 0 && errno == EINTR) continue;
        if (length <= 0) break;
        for (ssize_t index = 0; index < length; ++index) {
            char value = incoming[index];
            if (value == '\n') {
                log_tailnet_line(line, line_length);
                line_length = 0;
            } else if (line_length < sizeof(line) - 1) {
                line[line_length++] = value;
            } else {
                log_tailnet_line(line, line_length);
                line_length = 0;
                line[line_length++] = value;
            }
        }
    }
    log_tailnet_line(line, line_length);
    close(bridge->read_fd);
    return NULL;
}

static int start_tailnet_log_bridge(tailscale node) {
    int descriptors[2] = {-1, -1};
    int log_fd = -1;
    tailnet_log_bridge *bridge = calloc(1, sizeof(*bridge));
    if (bridge == NULL || pipe(descriptors) != 0) {
        free(bridge);
        return -1;
    }
    bridge->node = node;
    bridge->read_fd = descriptors[0];
    log_fd = dup(descriptors[1]);
    if (log_fd < 0 || tailscale_set_logfd(node, log_fd) != 0
            || pthread_create(&bridge->thread, NULL, tailnet_log_main, bridge) != 0) {
        tailscale_set_logfd(node, -1);
        close(descriptors[0]);
        close(descriptors[1]);
        if (log_fd >= 0) close(log_fd);
        free(bridge);
        return -1;
    }
    close(descriptors[1]);
    pthread_mutex_lock(&log_bridges_mutex);
    bridge->next = log_bridges;
    log_bridges = bridge;
    pthread_mutex_unlock(&log_bridges_mutex);
    return 0;
}

static void stop_tailnet_log_bridge(tailscale node) {
    tailnet_log_bridge **current;
    tailnet_log_bridge *bridge = NULL;
    pthread_mutex_lock(&log_bridges_mutex);
    current = &log_bridges;
    while (*current != NULL && (*current)->node != node) current = &(*current)->next;
    if (*current != NULL) {
        bridge = *current;
        *current = bridge->next;
    }
    pthread_mutex_unlock(&log_bridges_mutex);
    if (bridge == NULL) return;
    tailscale_set_logfd(node, -1);
    pthread_join(bridge->thread, NULL);
    free(bridge);
}

static int close_tailnet_node(tailscale node) {
    int result = tailscale_close(node);
    stop_tailnet_log_bridge(node);
    return result;
}

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
    if (start_tailnet_log_bridge(node) != 0)
        __android_log_print(ANDROID_LOG_WARN, TAILNET_LOG_TAG,
                "Unable to attach libtailscale log bridge");
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
    return close_tailnet_node(handle);
}

JNIEXPORT jint JNICALL
Java_com_xinyv_median_TailnetNative_nativeStartNode(
        JNIEnv *env, jclass clazz, jint handle) {
    (void) env;
    (void) clazz;
    return tailscale_start(handle);
}

JNIEXPORT jint JNICALL
Java_com_xinyv_median_TailnetNative_nativeUpdateNetwork(
        JNIEnv *env, jclass clazz, jint handle,
        jstring interfaces_json, jstring default_interface) {
    (void) clazz;
    if (handle <= 0 || interfaces_json == NULL || default_interface == NULL) return -1;
    const char *interfaces = (*env)->GetStringUTFChars(env, interfaces_json, NULL);
    if (interfaces == NULL) return -1;
    const char *default_name = (*env)->GetStringUTFChars(env, default_interface, NULL);
    if (default_name == NULL) {
        (*env)->ReleaseStringUTFChars(env, interfaces_json, interfaces);
        return -1;
    }
    int result = tailscale_update_android_network(handle, interfaces, default_name);
    (*env)->ReleaseStringUTFChars(env, default_interface, default_name);
    (*env)->ReleaseStringUTFChars(env, interfaces_json, interfaces);
    return result;
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
        JNIEnv *env, jclass clazz, jint handle, jobjectArray domain_rules) {
    (void) clazz;
    if (handle <= 0) return 0;
    jsize rule_count = domain_rules == NULL ? 0 : (*env)->GetArrayLength(env, domain_rules);
    const char **rules = calloc((size_t) rule_count, sizeof(*rules));
    jstring *values = calloc((size_t) rule_count, sizeof(*values));
    tailnet_domain_policy *policy = NULL;
    tailnet_connect_adapter *adapter = NULL;
    jsize index;
    if (rule_count > 0 && (rules == NULL || values == NULL)) goto done;
    for (index = 0; index < rule_count; ++index) {
        values[index] = (jstring) (*env)->GetObjectArrayElement(env, domain_rules, index);
        if (values[index] == NULL) goto done;
        rules[index] = (*env)->GetStringUTFChars(env, values[index], NULL);
        if (rules[index] == NULL) goto done;
    }
    policy = tailnet_domain_policy_create(rules, (size_t) rule_count);
    if (policy != NULL) adapter = tailnet_connect_adapter_start(handle, policy);
done:
    for (index = 0; index < rule_count; ++index) {
        if (rules != NULL && rules[index] != NULL)
            (*env)->ReleaseStringUTFChars(env, values[index], rules[index]);
        if (values != NULL && values[index] != NULL)
            (*env)->DeleteLocalRef(env, values[index]);
    }
    free(values);
    free(rules);
    if (adapter == NULL) tailnet_domain_policy_destroy(policy);
    return (jlong) (intptr_t) adapter;
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
        tailnet_connect_adapter *adapter =
            (tailnet_connect_adapter *) (intptr_t) adapter_handle;
        tailscale node = tailnet_connect_adapter_node(adapter);
        tailnet_connect_adapter_stop(adapter);
        stop_tailnet_log_bridge(node);
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