# Native FM Layer

This directory contains the native FM control layer used by the Android app.

> For AI agents: the canonical references are [`AGENTS.md`](AGENTS.md)
> (auto-loaded summary) and [`ARCHITECTURE.md`](ARCHITECTURE.md) (full
> specification). This README is a shorter human-oriented overview.

The current design exposes a single native executable per ABI:

- `app/src/main/assets/fmbin-armv7a`
- `app/src/main/assets/fmbin-aarch64`

Internally, that executable contains three backend implementations:

- `hal_helium`: talks to Qualcomm's newer FM stack through `fm_helium.so`
- `v4l2_qcom`: talks directly to `/dev/radio0` through Qualcomm's V4L2-based FM driver (`iris` / `tavarua`)
- `v4l2_exynos`: talks directly to `/dev/radio0` through Samsung's Exynos `s610-radio` V4L2 driver

At runtime the executable auto-detects which backend to use:

1. If `/sys/class/video4linux/radio0/name` contains `iris` or `tavarua` (case-insensitive) alongside `/dev/radio0` (or the sysfs name node is absent while `/dev/radio0` exists), it selects `v4l2_qcom`.
2. Otherwise, if the sysfs name contains `s610` alongside `/dev/radio0`, it selects `v4l2_exynos`.
3. Otherwise, if `fm_helium.so` is present in one of the known vendor/system locations, it selects `hal_helium`.
4. Otherwise, initialization fails and the Java layer treats the device as unsupported.

## Directory Layout

- [`backend.h`](backend.h): common C++ backend interface
- [`backend_factory.h`](backend_factory.h): runtime backend selection API
- [`backend_factory.cpp`](backend_factory.cpp): backend detection and factory implementation
- Process entrypoint and backend lifetime — [`main.cpp`](main.cpp)
- Command dispatch and response timing — [`command_router.cpp`](command_router.cpp)
- [`ctl_server.h`](ctl_server.h): shared state-patch and command response types
- [`ctl_server.cpp`](ctl_server.cpp): standard-I/O command loop and event writer
- [`ctl_protocol.cpp`](ctl_protocol.cpp): request-frame parsing
- [`ctl_json.cpp`](ctl_json.cpp): response and event JSON serialization
- [`types.h`](types.h): shared primitive typedefs and boolean compatibility helpers
- [`backend/v4l2_base`](backend/v4l2_base): shared V4L2 base class (`V4l2BackendBase`)
- [`backend/v4l2_qcom`](backend/v4l2_qcom): Qualcomm V4L2 backend (`V4l2QcomBackend`)
- [`backend/v4l2_exynos`](backend/v4l2_exynos): Samsung Exynos S610 V4L2 backend (`V4l2ExynosBackend`)
- [`backend/hal_helium`](backend/hal_helium): Qualcomm FM HAL / `fm_helium.so` backend (`HeliumBackend`)

## Architecture

### 1. Unified Process

The Android app owns one root native process for the lifetime of a radio
session. Commands arrive on stdin and responses/events leave on stdout as
newline-delimited JSON. Closing stdin tells the process that its Android owner
has died; it then shuts down the selected backend and exits.

### 2. Backend Interface

[`backend.h`](backend.h) defines the common operations required by the app:

- initialization
- enable / disable
- tune and seek
- power mode
- RDS toggle
- stereo mode
- region / spacing / antenna
- hardware search
- AF toggle
- Slimbus toggle for HAL devices

Each backend implements that interface while keeping its own low-level logic isolated.

### 3. Command Routing

`main()` detects the backend kind, supplies the command and signal-poll callbacks
to the server, and shuts down the backend when the server returns. The first
command that needs the backend creates it through the factory. [Process entrypoint](main.cpp)

For each request the server passes the command text to the router. The router
parses arguments and returns a result, such as the new frequency for `jump`. The
server writes the result to stdout as JSON with the original request ID.
[Command handlers](command_router.cpp) · [Server loop](ctl_server.cpp)

### 4. Event Delivery

Both backends send partial state patches and lifecycle/search events through the
shared JSON writer in [`ctl_server.cpp`](ctl_server.cpp). Writes are serialized
because vendor callback threads can emit events while the command thread emits
a response. Native diagnostics go to stderr so stdout remains protocol-only.

The ready event reports the process ID and the detected backend. In a state event,
`"rds":{"rt_plus":{"ITEM.TITLE":"Song","INFO.WEATHER":"Rain"}}` updates the title and weather.
Every key in `rt_plus` is an RT+ content type name. An empty string clears that
field and omitted fields keep their previous values.

## Supported Commands

The unified binary accepts the same external command API used before the merge:

- `init`
- `enable freq=<khz> region=<eu|us|jp|jp_wide> spacing=<50|100|200> stereo=<0|1> soft_mute=<0|1> antenna=<value> af=<0|1>`
- `disable`
- `shutdown`
- `setfreq <khz>`
- `jump <direction>`
- `seekhw <direction>`
- `power_mode <low|normal>`
- `set_stereo <0|1>`
- `set_soft_mute <0|1>`
- `set_notch_filter <0|1|2>`
- `set_volume <0..15>`
- `set_force_use <usage> <config>`
- `set_antenna <value>`
- `set_region <eu|us|jp|jp_wide>`
- `set_spacing <50|100|200>`
- `searchhw`
- `search_cancel`
- `auto_af <0|1>`
- `slimbus <0|1>`

Not every command has identical meaning on both backends:

- `slimbus` is meaningful only on HAL devices
- some setup commands may be ignored or mapped differently depending on backend capabilities

The external protocol still stays uniform.

The named `enable` parameters are mandatory and may appear in any order. The
command applies the complete tuner configuration and initial frequency before
returning. The individual setup commands remain available for runtime changes.
Emphasis and RDS standard are derived from `region`: Europe uses 50 us/RDS,
United States uses 75 us/RBDS, and both Japanese profiles use 50 us/RDS.

## Build

```bash
bash ./native/build.sh
```

The build script:

- resolves a compatible Android NDK automatically
- builds both supported ABIs
- copies the resulting binaries into `app/src/main/assets`

The `legacy` and `hal` subdirectories are now implementation modules, not standalone applications.

## Verification

Typical verification after native changes:

```bash
./native/build.sh
./gradlew --no-daemon :app:assembleDebug
```

If both pass, the native asset packaging path is intact.
