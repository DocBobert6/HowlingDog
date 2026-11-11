# HowlingDog Phase 2 — JNI bridge module.
#
# The bridge is a SEPARATE ndk-build module from the core: the core's
# own Android.mk (libretro/jni/) is untouched, and nothing under the
# core's src/ is modified. The bridge links against the prebuilt
# libretro.so the core build produces (libretro/libs/arm64-v8a/), so
# the CI order is: build core -> build bridge -> stage both .so files
# into app/src/main/jniLibs/arm64-v8a/ -> Gradle assembles the APK.

LOCAL_PATH := $(call my-dir)

ROOT_DIR := $(LOCAL_PATH)/../..

# The stock core, as a prebuilt shared library. Only the retro_*
# symbols are exported (libretro/link.T), which is all the bridge
# needs.
include $(CLEAR_VARS)
LOCAL_MODULE := retro-prebuilt
LOCAL_SRC_FILES := $(ROOT_DIR)/libretro/libs/arm64-v8a/libretro.so
LOCAL_EXPORT_C_INCLUDES := $(ROOT_DIR)/libretro
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := howlingdog-bridge
LOCAL_SRC_FILES := bridge.cpp runahead.c
LOCAL_SHARED_LIBRARIES := retro-prebuilt
LOCAL_C_INCLUDES := $(ROOT_DIR)/libretro
LOCAL_CFLAGS := -DANDROID -D__LIBRETRO__
LOCAL_LDLIBS := -llog -landroid
include $(BUILD_SHARED_LIBRARY)
