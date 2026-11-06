# 直接挂上游 AAR 变体（PKGNAME/CLSNAME 在同目录 Application.mk 覆盖）
UPSTREAM := $(call my-dir)/../cpp/hev-socks5-tunnel
include $(UPSTREAM)/Android.mk

# 自研本地 relay：socks5 前端 + DNS-over-TCP 兜底（本地无鉴权）
# ndk-build 要求 EXECUTABLE 模块文件名不含扩展名
include $(CLEAR_VARS)
LOCAL_MODULE := carvpnrelay
LOCAL_MODULE_CLASS := EXECUTABLE
LOCAL_MODULE_FILENAME := carvpnrelay
LOCAL_PATH := $(CURDIR)/src/main/jni
LOCAL_SRC_FILES := relay/carvpn_relay.c
LOCAL_CFLAGS := -O2 -Wall
LOCAL_LDLIBS := -landroid
include $(BUILD_EXECUTABLE)
