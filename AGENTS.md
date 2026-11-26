# RFM-Radio — Agent Guide

Real FM hardware radio app for rooted Android devices on Qualcomm Snapdragon
platforms. A single native root executable (`fmbin`) drives the receiver
through one of two backends — legacy V4L2 (`/dev/radio0`) or vendor HAL
(`fm_helium.so`) — and the Java app owns it over a stdin/stdout JSON protocol.

## Directory Map

| Path | Role |
| --- | --- |
| `app/` | Android application: UI, `FMService`, audio, recording, favorites |
| `native/` | C++ `fmbin` executable: both FM backends, protocol, host tests |
| `.githooks/` | Git hooks (documentation sync reminder) |
| `.github/` | CI: native tests, debug APK, unit tests, tagged releases |
| `fastlane/` | Store metadata and screenshots |
| `LINKS.md` | External reference material links |

`app/src/main/cpp/` builds the MP3 and Ogg Opus recording encoders and is
unrelated to the FM backend. `app/src/main/assets/fmbin-*` are git-ignored
artifacts produced by `native/build.sh` and must be regenerated locally.

## Subdirectory Guides

Nested `AGENTS.md` files are loaded automatically when working inside their
directory:

- `app/AGENTS.md` — Java/Android architecture, bridge traps, audio, recording
- `native/AGENTS.md` — native protocol summary, vendor invariants, tests

Deep references — open only for complex rework, they are long:

- `native/ARCHITECTURE.md` — full native specification (~900 lines)
- `app/ARCHITECTURE.md` — full app-side architecture map

## Build and Test Commands

| Command | Purpose |
| --- | --- |
| `make test-native` | Native host GoogleTest suite (no device needed) |
| `make test-java` | JVM unit tests (`:app:testDebugUnitTest`) |
| `make test` | Both test suites |
| `make build-native` | Cross-compile `fmbin` into `app/src/main/assets` |
| `make build-debug` | Debug APK; Gradle `preBuild` re-runs `native/build.sh` |
| `make build-release` | Release APK (unsigned without `ANDROID_RELEASE_*`) |

Native builds pin NDK `29.0.14033849` and CMake `3.22.1` and fail when they
are unavailable. The recording codec submodules require
`git submodule update --init --recursive` after cloning.

## Verification Rules of Thumb

- Parser, protocol, region, JSON, or config changes → `make test-native`.
- Native runtime changes → `make test-native` + `make build-native`.
- Java bridge, packaging, or protocol changes → add `make build-debug`.
- Hardware-sensitive changes (vendor ordering, callbacks, audio) cannot be
  validated on the host and need a real device.

## External Reference Repositories

Third-party reference sources used for reverse engineering are kept in a
separate local directory outside this repository:

- https://github.com/crdroidandroid/android_vendor_qcom_opensource_fm-commonsys —
  Qualcomm commonsys FM stack (`fm_hci`, Helium receiver)
- https://github.com/LineageOS/android_hardware_qcom_fm — LineageOS FM HAL
  (Helium)
- https://github.com/iusmac/RevampedFMRadio — third-party Qualcomm FM app
  (legacy V4L2 usage; a second local checkout is used for HAL work)
- https://github.com/sethsong90/vendor_qcom — Qualcomm vendor trees
- https://github.com/LineageOS/android_hardware_samsung_slsi_fm — LineageOS Samsung SLSI FM HAL (Exynos S610)

Do not scan, search, or read these repositories unless the user explicitly
requests it and points to their location. The deep references already cite
the exact files from them where needed.

## Documentation Sync Policy

Before finishing any task, check whether the change touched:

- the native command set, JSON protocol, or backend behavior;
- the native↔Java bridge, `Request` timeouts, or event parsing;
- `FMService` orchestration, audio routing, recording, or favorites;
- controller hierarchy, service architecture, or build integration.

If it did, update the matching documents within the same session:

1. `app/AGENTS.md` and/or `native/AGENTS.md` — keep the dense summaries true.
2. `app/ARCHITECTURE.md` and/or `native/ARCHITECTURE.md` —
   keep the deep references true.
3. `native/README.md` when the public command list changes.

Implementation is the source of truth; documentation follows it. The
pre-commit hook below prints a reminder but does not replace this check.

## Git Hooks

A non-blocking documentation reminder hook lives in `.githooks/pre-commit`.
Enable it once per clone:

```sh
git config core.hooksPath .githooks
```

It prints a warning when code under `app/` or `native/` is committed without
any change to `AGENTS.md` or `ARCHITECTURE.md`; it never blocks a commit.
