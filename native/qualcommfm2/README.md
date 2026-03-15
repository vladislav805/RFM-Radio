# Qualcomm FM2 HAL bridge

This binary is an experimental bridge between RFM Radio and Qualcomm's newer
FM stack used by FM2.

It keeps the same UDP protocol as the legacy `fmbin`, but instead of talking
to `/dev/radio0`, it:

1. `dlopen()`s `fm_helium.so`
2. obtains `FM_HELIUM_LIB_INTERFACE`
3. calls `hal_init()`
4. controls radio through `set_fm_ctrl()` / `get_fm_ctrl()`
5. forwards HAL callbacks to the app using the existing UDP events

## Runtime requirements

- device must ship `fm_helium.so`
- vendor FM HAL stack behind `fm_helium.so` must be working
- app still needs root to launch the userspace binary

## Build

```bash
export ANDROID_NDK_HOME=/path/to/android/ndk
./build.sh
```

Artifacts are copied to:

- `app/src/main/assets/fmbin-fm2-armv7a`
- `app/src/main/assets/fmbin-fm2-aarch64`


Логи бинаря:
```shell
adb logcat -s RFM-FM2 -v process
```

codex resume 019ced89-d8da-7953-84f4-95beb472e37d
