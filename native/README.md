# Native FM Layer

This directory contains the native FM control layer used by the Android app.

The current design exposes a single native executable per ABI:

- `app/src/main/assets/fmbin-armv7a`
- `app/src/main/assets/fmbin-aarch64`

Internally, that executable contains two backend implementations:

- `hal`: talks to Qualcomm's newer FM stack through `fm_helium.so`
- `legacy`: talks directly to `/dev/radio0` through Qualcomm's older V4L2-based FM driver

At runtime the executable auto-detects which backend to use:

1. If `/dev/radio0` exists, it selects the legacy backend.
2. Otherwise, if `fm_helium.so` is present in one of the known vendor/system locations, it selects the HAL backend.
3. Otherwise, initialization fails and the Java layer treats the device as unsupported.

## Directory Layout

- [`backend.h`](backend.h): common C++ backend interface
- [`backend_factory.h`](backend_factory.h): runtime backend selection API
- [`backend_factory.cpp`](backend_factory.cpp): backend detection and factory implementation
- [`main.cpp`](main.cpp): command router and process entrypoint
- [`ctl_server.h`](ctl_server.h): shared state-patch and command response types
- [`ctl_server.cpp`](ctl_server.cpp): standard-I/O command loop and event writer
- [`ctl_protocol.cpp`](ctl_protocol.cpp): request-frame parsing
- [`ctl_json.cpp`](ctl_json.cpp): response and event JSON serialization
- [`types.h`](types.h): shared primitive typedefs and boolean compatibility helpers
- [`legacy_backend.cpp`](legacy_backend.cpp): C++ adapter over the legacy backend
- [`hal_backend.cpp`](hal_backend.cpp): C++ adapter over the HAL backend
- [`legacy`](legacy): legacy Qualcomm FM implementation details
- [`hal`](hal): Qualcomm FM HAL / `fm_helium.so` implementation details

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

[`main.cpp`](main.cpp) is intentionally thin. It:

- parses incoming text commands
- lazily creates the detected backend
- routes commands to the selected backend
- returns a request-ID-matched JSON response over stdout

It does not contain backend detection tables or backend construction logic anymore; those live in [`backend_factory.cpp`](backend_factory.cpp).

### 4. Event Delivery

Both backends send partial state patches and lifecycle/search events through the
shared JSON writer in [`ctl_server.cpp`](ctl_server.cpp). Writes are serialized
because vendor callback threads can emit events while the command thread emits
a response. Native diagnostics go to stderr so stdout remains protocol-only.

## Supported Commands

The unified binary accepts the same external command API used before the merge:

- `init`
- `enable freq=<khz> region=<eu|us|jp|jp_wide> spacing=<50|100|200> stereo=<0|1> antenna=<value> af=<0|1>`
- `disable`
- `shutdown`
- `setfreq <khz>`
- `jump <direction>`
- `seekhw <direction>`
- `power_mode <low|normal>`
- `set_stereo <0|1>`
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
