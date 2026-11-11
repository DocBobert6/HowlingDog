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
LOCAL_SRC_FILES := bridge.cpp runahead.c ra_client.c ra_cdreader.c
LOCAL_SHARED_LIBRARIES := retro-prebuilt
LOCAL_STATIC_LIBRARIES := rcheevos libchdr
LOCAL_C_INCLUDES := $(ROOT_DIR)/libretro $(ROOT_DIR)/deps/rcheevos/include $(ROOT_DIR)/deps/libretro-common/include
# RC_CLIENT_SUPPORTS_HASH must also be defined here: bridge.cpp
# and ra_client.c include rc_client.h, and the identify-and-load
# API is guarded by it (see deps/rcheevos/Android.mk).
LOCAL_CFLAGS := -DANDROID -D__LIBRETRO__ -DRC_CLIENT_SUPPORTS_HASH
LOCAL_LDLIBS := -llog -landroid -lz
include $(BUILD_SHARED_LIBRARY)

# RetroAchievements hashing for .chd discs (v1.2.0, item 3):
# libchdr, the CHD reader, built as a static library and linked
# into the bridge. No new vendoring — libchdr was already in the
# tree (the core compiles it into libretro.so). The source list
# and defines mirror the core's build exactly
# (libretro/Makefile.common): the libchdr sources, the zstd
# decompress sources, the lzma sources, and miniz. -lz is on the
# bridge's LDLIBS above (libchdr_zlib.c needs zlib).
include $(CLEAR_VARS)
LOCAL_MODULE := libchdr
LOCAL_SRC_FILES := \
    $(ROOT_DIR)/deps/libretro-common/formats/libchdr/libchdr_bitstream.c \
    $(ROOT_DIR)/deps/libretro-common/formats/libchdr/libchdr_cdrom.c \
    $(ROOT_DIR)/deps/libretro-common/formats/libchdr/libchdr_chd.c \
    $(ROOT_DIR)/deps/libretro-common/formats/libchdr/libchdr_flac.c \
    $(ROOT_DIR)/deps/libretro-common/formats/libchdr/libchdr_flac_codec.c \
    $(ROOT_DIR)/deps/libretro-common/formats/libchdr/libchdr_huffman.c \
    $(ROOT_DIR)/deps/libretro-common/formats/libchdr/libchdr_lzma.c \
    $(ROOT_DIR)/deps/libretro-common/formats/libchdr/libchdr_zlib.c \
    $(ROOT_DIR)/deps/libretro-common/formats/libchdr/libchdr_zstd.c \
    $(ROOT_DIR)/deps/lzma/src/Alloc.c \
    $(ROOT_DIR)/deps/lzma/src/CpuArch.c \
    $(ROOT_DIR)/deps/lzma/src/LzFind.c \
    $(ROOT_DIR)/deps/lzma/src/LzmaDec.c \
    $(ROOT_DIR)/deps/lzma/src/LzmaEnc.c \
    $(ROOT_DIR)/deps/miniz/miniz.c \
    $(ROOT_DIR)/deps/zstd/lib/common/entropy_common.c \
    $(ROOT_DIR)/deps/zstd/lib/common/error_private.c \
    $(ROOT_DIR)/deps/zstd/lib/common/fse_decompress.c \
    $(ROOT_DIR)/deps/zstd/lib/common/zstd_common.c \
    $(ROOT_DIR)/deps/zstd/lib/common/xxhash.c \
    $(ROOT_DIR)/deps/zstd/lib/decompress/huf_decompress.c \
    $(ROOT_DIR)/deps/zstd/lib/decompress/zstd_ddict.c \
    $(ROOT_DIR)/deps/zstd/lib/decompress/zstd_decompress.c \
    $(ROOT_DIR)/deps/zstd/lib/decompress/zstd_decompress_block.c
LOCAL_C_INCLUDES := \
    $(ROOT_DIR)/deps \
    $(ROOT_DIR)/deps/libretro-common/include \
    $(ROOT_DIR)/deps/miniz \
    $(ROOT_DIR)/deps/lzma/include \
    $(ROOT_DIR)/deps/zstd/lib
LOCAL_CFLAGS := -DANDROID -DHAVE_ZLIB -DHAVE_7ZIP -DHAVE_DR_FLAC -DHAVE_FLAC -DHAVE_ZSTD -DZSTD_DISABLE_ASM -DHAVE_CHDR -DZ7_ST
include $(BUILD_STATIC_LIBRARY)

# RetroAchievements (Phase 10): the vendored rcheevos client
# library, built as a static library and linked into the bridge.
# This include is LAST on purpose: the rcheevos Android.mk sets
# LOCAL_PATH to its own directory, and the bridge module above
# resolves its sources against the LOCAL_PATH set at the top of
# this file. ndk-build resolves the LOCAL_STATIC_LIBRARIES
# reference by name after parsing, so the order is safe.
include $(ROOT_DIR)/deps/rcheevos/Android.mk
