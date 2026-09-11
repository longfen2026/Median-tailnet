LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := tailscale
LOCAL_SRC_FILES := ../../../../build/libtailscale/$(TARGET_ARCH_ABI)/libtailscale.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := median_tailnet
LOCAL_SRC_FILES := tailnet_jni.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/../../../../build/libtailscale/$(TARGET_ARCH_ABI)
LOCAL_LDLIBS := -llog
LOCAL_SHARED_LIBRARIES := tailscale
include $(BUILD_SHARED_LIBRARY)