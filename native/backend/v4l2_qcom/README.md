# Legacy Qualcomm FM Backend

This directory contains the legacy Qualcomm FM implementation used on devices
that expose `/dev/radio0` and the older V4L2-based FM control path.

It is no longer built as a standalone executable. Instead, it is compiled into
the unified native binary from [`native`](../..) and is selected through
[`backend_factory.cpp`](../../backend_factory.cpp) (`V4l2QcomBackend`).

## What This Backend Talks To

The legacy backend works directly with:

- `/dev/radio0`
- Qualcomm private V4L2 controls
- FM event buffers exposed by the older driver

This is the classic "direct driver access" path.

## Important Files

- [`v4l2_qcom_types.h`](v4l2_qcom_types.h): legacy FM enums, structs, and constants
- [`v4l2_qcom_backend.h`](v4l2_qcom_backend.h) / [`v4l2_qcom_backend.cpp`](v4l2_qcom_backend.cpp): `V4l2QcomBackend` implementation (lifecycle, V4L2 controls, tuning, search, interrupt thread, RDS extraction, platform detection)
- [`v4l2_scan_state.h`](v4l2_scan_state.h) / [`v4l2_scan_state.cpp`](v4l2_scan_state.cpp): sequential hardware scan/seek state machine and station collection
- [`../v4l2_base/v4l2_backend_base.h`](../v4l2_base/v4l2_backend_base.h): shared V4L2 base class (`open_device`, `close_device`, `set_v4l2_ctrl`, `get_v4l2_ctrl`, frequency unit conversions)

## Internal Structure

`V4l2QcomBackend` inherits from `V4l2BackendBase` and organizes the legacy driver logic into three internal layers:

- **Backend interface (`Backend` overrides)**: `init`, `enable`, `disable`, `set_frequency`, `seek`, `search`, `cancel_search`, `set_region`, `request_signal_metric`, `shutdown`.
- **Command layer (`command_*`)**: receiver lifecycle, Android property/patch-downloader bootstrap (`fm_dl`, `fm_qsoc_patches`, `direct_v4l2_transport_`), RDS setup, and search state coordination.
- **Receiver / V4L2 layer (`receiver_*`, `extract_*`)**: `VIDIOC_*` ioctl calls, V4L2 private buffer reads (`VIDIOC_DQBUF`), PS/RT/RT+/AF/search-list parsing, and the `event_thread` loop.

## Runtime Behavior

When the unified binary selects the legacy backend:

1. the Java side sends `init`
2. the backend opens `/dev/radio0`
3. FM driver setup is performed
4. an interrupt thread is created
5. driver events are translated into JSON app events on stdout

Examples of translated events:

- tune complete
- seek complete
- stereo / mono changes
- RDS PS/RT/PI/PTY
- AF list updates
- hardware search list updates

## Why This Code Exists As A Separate Module

Even after unifying the executable, the legacy backend still contains very
different low-level behavior from the HAL path:

- direct V4L2 usage
- interrupt/event-thread based operation
- explicit RDS buffer extraction
- device-specific transport setup

Trying to merge that code directly with the HAL backend would make the code
harder to understand. Keeping it isolated behind the common backend interface is
the cleaner approach.
