# HowlingDog bridge — arm64-v8a only, matching the app's target
# (Robert's Notscape ABI rule) and the core build (APP_PLATFORM
# android-21, as invoked by CI).
APP_ABI := arm64-v8a
APP_PLATFORM := android-21
APP_STL := c++_static
