# Legacy Qualcomm FM Backend

This directory contains the legacy Qualcomm FM implementation used on devices
that expose `/dev/radio0` and the older V4L2-based FM control path.

It is no longer built as a standalone executable. Instead, it is compiled into
the unified native binary from the parent [`native`](..) directory and is
accessed through [`legacy_backend.cpp`](../legacy_backend.cpp).

## What This Backend Talks To

The legacy backend works directly with:

- `/dev/radio0`
- Qualcomm private V4L2 controls
- FM event buffers exposed by the older driver

This is the classic "direct driver access" path.

## Important Files

- [`fmcommon.h`](fmcommon.h): legacy FM enums, structs, constants, and helper macros
- [`fm_ctl.h`](fm_ctl.h): low-level driver access API
- [`fm_ctl.c`](fm_ctl.c): V4L2 control handling, tuning, search, RDS extraction
- [`fm_wrap.h`](fm_wrap.h): higher-level FM command wrapper API
- [`fm_wrap.c`](fm_wrap.c): receiver lifecycle, interrupt thread, event processing, RDS forwarding
- [`utils.h`](utils.h): utility helpers
- [`utils.c`](utils.c): utility helper implementations
- [`detector.h`](detector.h): Qualcomm chip/platform probes
- [`detector.c`](detector.c): platform detection implementation

## Internal Structure

### `fm_ctl.*`

This layer is the lowest-level code in the legacy backend.

It contains:

- V4L2 control IDs
- `VIDIOC_*` ioctl usage
- frequency conversion
- band / spacing / power / mute / stereo control
- search operations
- reading PS/RT/AF/search buffers from the driver

This file should stay focused on direct driver interaction.

### `fm_wrap.*`

This layer sits above `fm_ctl.*` and implements higher-level behavior:

- radio open / prepare / enable / disable
- tuning helpers
- setup of legacy RDS behavior
- interrupt thread
- translation of driver events into app-facing UDP events

This is where most of the legacy-specific runtime behavior lives.

### `detector.*`

This code contains heuristics used by the legacy path to distinguish platform
details such as transport layer or ROMe chip behavior.

## Runtime Behavior

When the unified binary selects the legacy backend:

1. the Java side sends `init`
2. the backend opens `/dev/radio0`
3. FM driver setup is performed
4. an interrupt thread is created
5. driver events are translated into app events over UDP

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
