# rcheevos (RetroAchievements client library) — static library.
#
# Vendored rcheevos 12.5.0 (see README.md in this directory).
# This module is built by the bridge's Android.mk, which includes
# this file; it is NOT built standalone. The bridge links it as a
# static library (LOCAL_STATIC_LIBRARIES := rcheevos).
#
# RC_CLIENT_SUPPORTS_HASH is required: it enables
# rc_client_begin_identify_and_load_game and the game hashing
# used to identify a loaded game. Without it, the client can
# only load a game from a precomputed hash.
#
# The file list mirrors upstream's test/Makefile (the c89 client
# build), minus rc_libretro.c — HowlingDog supplies its own
# read_memory callback (see bridge/jni/ra_client.c) — and minus
# the external/Raintegration sources, which this integration
# does not use.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := rcheevos
LOCAL_SRC_FILES := \
    src/rc_client.c \
    src/rc_compat.c \
    src/rc_util.c \
    src/rc_version.c \
    src/rcheevos/alloc.c \
    src/rcheevos/condition.c \
    src/rcheevos/condset.c \
    src/rcheevos/consoleinfo.c \
    src/rcheevos/format.c \
    src/rcheevos/lboard.c \
    src/rcheevos/memref.c \
    src/rcheevos/operand.c \
    src/rcheevos/rc_validate.c \
    src/rcheevos/richpresence.c \
    src/rcheevos/runtime.c \
    src/rcheevos/runtime_progress.c \
    src/rcheevos/trigger.c \
    src/rcheevos/value.c \
    src/rhash/aes.c \
    src/rhash/cdreader.c \
    src/rhash/hash.c \
    src/rhash/hash_disc.c \
    src/rhash/hash_encrypted.c \
    src/rhash/hash_rom.c \
    src/rhash/hash_zip.c \
    src/rhash/md5.c \
    src/rapi/rc_api_common.c \
    src/rapi/rc_api_editor.c \
    src/rapi/rc_api_info.c \
    src/rapi/rc_api_runtime.c \
    src/rapi/rc_api_user.c
LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/include \
    $(LOCAL_PATH)/src \
    $(LOCAL_PATH)/src/rcheevos \
    $(LOCAL_PATH)/src/rhash \
    $(LOCAL_PATH)/src/rapi
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
LOCAL_CFLAGS := -DRC_CLIENT_SUPPORTS_HASH -D_GNU_SOURCE -std=c99
include $(BUILD_STATIC_LIBRARY)
