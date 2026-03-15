# Qualcomm FM HAL Backend

This directory contains the Qualcomm FM HAL-based implementation used on
devices that do not expose `/dev/radio0` directly but ship `fm_helium.so`.

It is no longer built as a standalone executable. Instead, it is compiled into
the unified native binary from the parent [`native`](..) directory and is
accessed through [`hal_backend.cpp`](../hal_backend.cpp).

## What This Backend Talks To

The HAL backend works through Qualcomm's newer FM userspace interface:

1. `dlopen()` of `fm_helium.so`
2. lookup of `FM_HELIUM_LIB_INTERFACE`
3. `hal_init()`
4. FM control via vendor callbacks and `set_fm_ctrl()` / `get_fm_ctrl()`
5. forwarding HAL callbacks to the app through the shared UDP event layer

This is the path used by devices that ship Qualcomm's FM2-style stack.

## Important Files

- [`common.h`](common.h): HAL-local logging helpers and common includes
- [`fm2_backend.h`](fm2_backend.h): public API exposed by the HAL module to the unified backend adapter
- [`fm2_backend.cpp`](fm2_backend.cpp): runtime state, HAL callback wiring, Qualcomm control translation, RDS handling
- [`fm2_vendor_iface.h`](fm2_vendor_iface.h): vendor FM control IDs and interface declarations
- [`fm_helium.so`](fm_helium.so): local reference binary used during reverse engineering and integration work

## Internal Structure

### `fm2_backend.*`

This is the real implementation module of the HAL backend.

It is responsible for:

- loading `fm_helium.so`
- resolving the vendor interface
- initializing the HAL
- enabling/disabling FM
- mapping high-level app commands to Qualcomm FM controls
- tracking runtime state
- translating HAL callbacks into app-facing UDP events

That includes:

- tune notifications
- stereo status
- PS / RT / PI / PTY
- AF updates
- ext country code events
- search results

### `fm2_vendor_iface.h`

This file defines the Qualcomm-specific FM control IDs and callback interface
needed to talk to the vendor HAL cleanly.

### `common.h`

This is intentionally small. It now contains only HAL-specific logging and
utility helpers, while shared primitive typedefs live in the parent
[`types.h`](../types.h).

## Runtime Behavior

When the unified binary selects the HAL backend:

1. `init` initializes the backend and loads `fm_helium.so`
2. `enable` brings FM up through Qualcomm HAL calls
3. callbacks from the vendor FM stack are translated into UDP app events
4. HAL-specific commands like `slimbus` are supported through the same command
   protocol as the legacy backend

The external command API stays stable even though the implementation path is
very different from the legacy backend.

## Why This Code Is Kept Separate

The HAL path is fundamentally different from the legacy V4L2 path:

- dynamic library loading instead of direct device access
- callback-driven behavior instead of direct interrupt/event buffer reads
- Qualcomm-specific vendor control IDs and state machines
- audio path handling such as Slimbus toggling

Keeping it isolated behind the unified backend interface keeps the codebase much
easier to reason about.

## Logs

When launched by the app, the unified binary is piped into Android logcat.

Typical filter:

```sh
adb logcat -s RFM-QCOM -v process
```

If you want to focus on the HAL-specific internal logs emitted from
`fm2_backend.cpp`, they still use the `[fm2]` prefix inside the emitted lines.
