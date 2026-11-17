# Qualcomm FM HAL Backend

This directory contains the Qualcomm FM HAL-based implementation used on
devices that do not expose `/dev/radio0` directly but ship `fm_helium.so`.

It is no longer built as a standalone executable. Instead, it is compiled into
the unified native binary from [`native`](../..) and is selected through
[`backend_factory.cpp`](../../backend_factory.cpp) (`HalHeliumBackend` via `create_hal_helium_backend()`).

## What This Backend Talks To

The HAL backend works through Qualcomm's newer FM userspace interface:

1. `dlopen()` of `fm_helium.so`
2. lookup of `FM_HELIUM_LIB_INTERFACE`
3. `hal_init()`
4. FM control via vendor callbacks and `set_fm_ctrl()` / `get_fm_ctrl()`
5. forwarding HAL callbacks to the app through the shared standard-output event layer

This is the path used by devices that ship Qualcomm's FM2-style stack.

## Important Files

- [`hal_helium_backend.h`](hal_helium_backend.h) / [`hal_helium_backend.cpp`](hal_helium_backend.cpp): `HalHeliumBackend` implementation (HAL loading, callback wiring, Qualcomm control translation, scan, RDS)
- [`hal_helium_types.h`](hal_helium_types.h): vendor FM control IDs, `fm_helium.so` ABI declarations, and Helium runtime state types

## Internal Structure

### `hal_helium_backend.*`

`HalHeliumBackend` implements the unified `Backend` interface and encapsulates
all Helium runtime behavior:

- loading `fm_helium.so`
- resolving the vendor interface
- initializing the HAL
- enabling/disabling FM
- mapping high-level app commands to Qualcomm FM controls
- tracking runtime state
- translating HAL callbacks into app-facing JSON events

That includes:

- tune notifications
- stereo status
- PS / RT / PI / PTY
- AF updates
- ext country code events
- search results

### `hal_helium_types.h`

This file defines the Qualcomm-specific FM control IDs, callback interface, and
runtime state structs needed to talk to the vendor HAL cleanly.

## Runtime Behavior

When the unified binary selects the HAL backend:

1. `init` initializes the backend and loads `fm_helium.so`
2. `enable` brings FM up through Qualcomm HAL calls
3. callbacks from the vendor FM stack are translated into JSON app events on stdout
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

Run via:

```sh
./logcat.sh # clear terminal, clear logcat, run logcat watching by filter RFM-QCOM
```

If you want to focus on the HAL-specific internal logs emitted from
`hal_helium_backend.cpp`, they use the `hel/` prefix inside the emitted lines.
