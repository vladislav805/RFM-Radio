# Qualcomm Native FM Backend: AI Context

## Purpose and Scope

This document is a memory snapshot for agents working on the custom Qualcomm
FM radio backend in `native/`. It describes the architecture, Android contract,
backend lifecycles, state and threading model, build and test paths, known
constraints, and risky implementation details.

Read this before modifying any of these areas:

- `native/`
- Qualcomm controller classes under
  `app/src/main/java/com/vlad805/fmradio/service/fm/`
- native FM assets or build integration in `app/build.gradle`

The native component controls device-specific radio hardware and is difficult
to exercise on a host. Preserve observed vendor ordering unless hardware logs
or a device test demonstrate that it can change safely.

## Executive Model

`native/` builds one executable named `fmbin`, not a JNI library. The Android
application copies an ABI-specific executable from assets into its private
files directory, starts it as root, and communicates with it over loopback UDP.

The executable contains two implementations behind the `Backend` interface:

| Backend | Selection condition | Hardware interface |
| --- | --- | --- |
| Legacy | `/dev/radio0` exists | Direct V4L2 and Qualcomm private controls |
| HAL/FM2 | No `/dev/radio0`, but `fm_helium.so` exists | Dynamically loaded Qualcomm vendor HAL |

Selection is process-global and lazy. The first command requiring a backend
calls `create_detected_backend()`, and the selected singleton is retained for
the life of the process. There is currently no fallback from a failed legacy
initialization to HAL.

## Hardware Verification

Live device verification has been performed on:

| Device | Backend | Platform context |
| --- | --- | --- |
| Xiaomi Mi A1 | Legacy V4L2 | Qualcomm Snapdragon 625 (MSM8953), AOSP Android 7.1.2 |
| Xiaomi Poco X3 Pro | HAL/FM2 | Qualcomm Snapdragon 860 (SM8150-AD), cdDroid 11.2 |

Treat behavior observed on these devices as evidence for the corresponding
backend, not as a universal Qualcomm contract. Re-test hardware-sensitive
changes on the matching backend whenever possible.

High-level data flow:

```text
Android QualcommNative
  -> root-launched fmbin
  -> UDP text command on 127.0.0.1:2112
  -> main.cpp command router
  -> Backend interface
  -> Legacy V4L2 or Qualcomm fm_helium.so
  -> asynchronous hardware event/callback
  -> JSON UDP message to 127.0.0.1:2113
  -> Android DatagramServer
  -> RadioStatePatch / application event
```

## Source Map

### Shared Native Layer

| Path | Responsibility |
| --- | --- |
| `native/main.cpp` | Process entrypoint, text command parsing and routing |
| `native/backend.h` | Common C++ interface implemented by both backends |
| `native/backend_factory.cpp` | Runtime detection and singleton backend creation |
| `native/legacy_backend.cpp` | Adapter from `Backend` to legacy functions |
| `native/hal_backend.cpp` | Adapter from `Backend` to FM2/HAL functions |
| `native/ctl_server.cpp` | UDP command server and asynchronous event sender |
| `native/ctl_json.cpp` | JSON serialization and state-patch deduplication |
| `native/startup_config.cpp` | Strict parser for the complete `enable` command |
| `native/region_profile.cpp` | Region limits and vendor mappings |
| `native/rds_parser.cpp` | Shared parsers for PS, RT, AF, and station lists |
| `native/fm_v4l2_controls.h` | Qualcomm private V4L2 control identifiers |
| `native/frequency_format.h` | Frequency-list formatting used by logging/dedup |
| `native/types.h` | Primitive compatibility types and booleans |

### HAL/FM2 Layer

| Path | Responsibility |
| --- | --- |
| `native/hal/fm2_backend.cpp` | HAL loading, state, controls, callbacks, scan, RDS |
| `native/hal/fm2_vendor_iface.h` | Reconstructed binary ABI for `fm_helium.so` |
| `native/hal/utils.cpp` | HAL logging and hex dumps |

### Legacy V4L2 Layer

| Path | Responsibility |
| --- | --- |
| `native/legacy/fm_wrap.cpp` | Lifecycle, Android properties, event thread, event translation |
| `native/legacy/fm_ctl.cpp` | Device open, ioctl controls, tuning, search, RDS buffers |
| `native/legacy/fmcommon.h` | Legacy constants, state, structs, and event enums |
| `native/legacy/detector.cpp` | Platform detection through Android properties |
| `native/legacy/utils.cpp` | Frequency conversion, band limits, filesystem helpers |

### Android Bridge

| Path | Responsibility |
| --- | --- |
| `app/src/main/java/com/vlad805/fmradio/service/fm/implementation/QualcommNative.java` | Unified native controller, launch and complete startup command |
| `app/src/main/java/com/vlad805/fmradio/service/fm/implementation/AbstractQualcommNativeController.java` | Binary installation, command queue, lifecycle, public controller behavior |
| `app/src/main/java/com/vlad805/fmradio/service/fm/communication/Poll.java` | Serialized UDP command requests |
| `app/src/main/java/com/vlad805/fmradio/service/fm/DatagramServer.java` | JSON event receiver and parser |
| `app/src/main/java/com/vlad805/fmradio/service/fm/RadioStatePatch.java` | Java representation of partial radio state |

## Build and Packaging

`native/CMakeLists.txt` uses C++20 and C11 and builds all shared, HAL, and
legacy sources into `fmbin`. The executable links `dl` for runtime HAL loading.

`native/build.sh` cross-compiles with Android NDK for:

| Android ABI | Asset name |
| --- | --- |
| `armeabi-v7a` | `app/src/main/assets/fmbin-armv7a` |
| `arm64-v8a` | `app/src/main/assets/fmbin-aarch64` |

The minimum native platform is Android 21. Build output is staged under
`BUILD_ROOT`, defaulting to `/tmp/rfm-radio-native`. Asset binaries are ignored
by Git and must be regenerated.

Useful commands from the repository root:

```sh
make test-native
make build-native
make build-app
```

Equivalent direct commands:

```sh
./native/build.sh
./gradlew --no-daemon :app:assembleDebug
```

Gradle invokes `native/build.sh` before application `preBuild`. The separate
`externalNativeBuild` configuration in `app/build.gradle` refers to
`app/src/main/cpp`, not this backend.

## Process and Android Lifecycle

`QualcommNative.getBinaryName()` selects `fmbin-<arch>`. The controller copies
that asset to `/data/data/<application-id>/files/`, applies mode `755`, kills an
old process, and launches the executable through `su`. Native stdout and stderr
are piped to Android logcat with tag `RFM-QCOM`.

Normal launch sequence:

```text
copy/install asset if needed
kill old fmbin process
start fmbin as root
enable Java command Poll
start Java event DatagramServer
send "init" with a 5 second timeout
```

Normal enable sequence sends one complete command assembled from preferences:

```text
enable freq=<kHz> region=<eu|us|jp|jp_wide> spacing=<50|100|200> \
       stereo=<0|1> soft_mute=<0|1> antenna=<0..255> af=<0|1>
```

`QualcommNative.shouldApplyStartupPreferences()` returns false because this
complete command applies all startup configuration atomically from the app's
point of view.

Normal shutdown is application-orchestrated:

```text
send "disable"
disable command Poll
close event DatagramServer
kill native process
```

The native-only `exit` command merely breaks the UDP command loop. It does not
disable radio hardware, join the legacy thread, or unload the HAL, so it is not
a complete shutdown operation.

Root access is effectively required for device nodes, system properties,
module setup, and vendor controls.

## UDP Protocol

### Transport

| Direction | Address | Payload |
| --- | --- | --- |
| Android to native | `127.0.0.1:2112` | Plain UTF-8 command text |
| Native to Android | `127.0.0.1:2113` | JSON event, maximum 511 bytes in practice |

`CS_BUF` and the Java event buffer are both 512 bytes. The native command
server handles commands serially. Java `Poll` also serializes command requests.

Native successful responses are delayed by 300 ms in `make_ok()`. This delay is
intentional and documented as preserving Java/native timing relative to
asynchronous hardware events. Do not remove it casually.

The native server sends command responses with the terminating NUL byte
(`strlen(data) + 1`). Java constructs a string from the full datagram. Callers
usually use `startsWith("ok")`, but numeric parsing may be affected by this
contract.

### Commands

| Command | Meaning | Response on success |
| --- | --- | --- |
| `init` | Detect and initialize backend | `ok` |
| `enable ...` | Complete startup and initial tune | `ok` |
| `disable` | Turn receiver off | `ok` |
| `setfreq <kHz>` | Tune to absolute frequency | `ok` |
| `jump <direction>` | Move one configured spacing step | Numeric frequency |
| `seekhw <direction>` | Begin hardware seek | `ok` |
| `power_mode <low|other>` | Set low or normal power | `ok` |
| `set_stereo <0|1>` | Select mono/stereo mode | `ok` |
| `set_soft_mute <0|1>` | Enable or disable weak-signal attenuation | `ok` |
| `set_antenna <value>` | Select antenna | `ok` |
| `set_region <name>` | Change region and clamp current tune if needed | `ok` |
| `set_spacing <50|100|200>` | Change channel spacing | `ok` |
| `searchhw` | Begin full station search | `ok` |
| `search_cancel` | Cancel active search | `ok` |
| `auto_af <0|1>` | Toggle automatic AF jump | `ok` |
| `slimbus <0|1>` | Toggle HAL audio transport; no-op success on legacy | `ok` |

The `enable` parser is strict. It requires exactly six unique named parameters,
rejects unknown or duplicate keys, validates booleans, spacing, antenna, and
region, and requires the initial frequency to be inside the selected region.

Most other numeric commands use `atoi` through `parse_int_arg()`. Invalid text
therefore becomes zero and partial numeric strings may be accepted. Runtime
`setfreq` does not validate against the current band, and runtime antenna does
not enforce the startup parser's `0..255` range.

Common textual errors include:

| Error | Meaning |
| --- | --- |
| `ERR_IVD_FRQ` | Missing command argument |
| `ERR_IVD_CFG` | Invalid complete config, region, or spacing |
| `ERR_UNK_CMD` | Empty or unknown command |
| `ERR_UNSUPPORTED` | No backend or unsupported device |
| `ERR_FAILED` | Generic backend operation failure |
| `ERR_UNV_ANT` | Legacy antenna value rejected |
| `ERR_CNS_REG` | Legacy region/band could not be set |

`response_t.code` is not transmitted. Only its `data` string is sent over UDP.

### Asynchronous Events

The actual event protocol is JSON. Ignore the old `EVT_*` list in
`native/README.md`.

State is sent as partial patches:

```json
{
  "type": "state",
  "frequency": 101700,
  "stereo": true,
  "rds": {
    "ps": "STATION",
    "rt": "Radio text",
    "pi": "7756",
    "country": "UA",
    "pty": 7,
    "af": [99500, 103200]
  }
}
```

Only changed/present fields are included. Supported state fields are frequency,
stereo, PS, RT, PI, country, PTY, and AF frequencies. Country is a two-letter
code decoded from the registered ECC and PI country-code combination.

Search completion:

```json
{"type":"search_done","stations":[87500,101700]}
```

Disable notification:

```json
{"type":"disabled"}
```

Java can parse an `enabled` event, but current native code does not send one.
The Java controller considers a successful `enable` command response to be the
enable completion.

`ctl_server.cpp` maintains a mutex-protected state cache and suppresses patches
that do not change cached values. The cache is updated after successful
`sendto()`, not after application acknowledgement. UDP loss after kernel
acceptance can therefore cause a particular state change to remain absent on
the Java side until a later, different patch is emitted.

## Backend Detection

`backend_factory.cpp` checks in this order:

1. Existence of `/dev/radio0` selects legacy.
2. Existence of `fm_helium.so` in known vendor, system vendor, system_ext, or
   ODM 32/64-bit locations selects HAL.
3. Otherwise no backend is available.

Detection tests file existence only. It does not prove that `/dev/radio0` can
be opened or that the HAL ABI is compatible.

The detector checks absolute paths for `fm_helium.so`, while the HAL backend
calls `dlopen("fm_helium.so", RTLD_NOW)` by soname. Successful detection does
not guarantee successful loading under Android linker namespace rules.

## Region Model

| Region | Range kHz | Emphasis | RDS mode | Legacy band | HAL region |
| --- | ---: | --- | --- | ---: | ---: |
| `eu` | 87500-108000 | 50 us | RDS | 1 | 1 |
| `us` | 87500-108000 | 75 us | RBDS | 1 | 0 |
| `jp` | 76000-95000 | 50 us | RDS | 2 | 2 |
| `jp_wide` | 76000-108000 | 50 us | RDS | 3 | 3 |

Changing region at runtime also updates emphasis and RDS standard. Both
adapters clamp the current frequency to the new limits when necessary.

Reverse-engineering notes in `native/.ideas/region.md` report that a stock FM2
application was observed writing vendor region `4` while controlling actual
limits through explicit lower/upper controls. Current code intentionally uses
the table above. Treat changes to this mapping as hardware-sensitive.

## HAL/FM2 Backend

### ABI and Initialization

`native/hal/fm2_vendor_iface.h` manually reconstructs Qualcomm's binary ABI:

- callback function signatures and callback-table order
- `fm_interface_t`
- vendor payload structs
- IRIS frequency, seek, band, audio-mode, RMSSI, and Slimbus controls

Initialization in `fm2_backend_init()` performs:

```text
dlopen("fm_helium.so", RTLD_NOW)
dlsym("FM_HELIUM_LIB_INTERFACE")
vendor->hal_init(&g_callbacks)
mark runtime initialized
```

The ABI must match the device's vendor library exactly. Callback order, C++
`bool` representation, struct packing, and symbol interpretation are all
hardware compatibility boundaries. A mismatch can call an incorrect function
pointer, not merely return an error.

### Runtime State and Synchronization

`RuntimeState` in `fm2_backend.cpp` owns:

- dynamic library and vendor interface handles
- initialized/enabled flags
- configured region, range, spacing, stereo, antenna, and Slimbus state
- current frequency
- scan progress and collected stations
- RDS/RT+ deduplication state
- last error text
- a pthread mutex and condition variable

Vendor callbacks may run on vendor-managed threads. Most runtime fields are
protected by `g_state.lock`. The condition variable is used to wait up to two
seconds for `enabled_cb`.

Important locking caveat: `vendor_set()` holds `g_state.lock` while calling
vendor `set_fm_ctrl()`, and initialization calls vendor `hal_init()` while
holding the same non-recursive mutex. If a vendor implementation invokes a
callback synchronously from either call, and that callback also locks state,
the process can deadlock. Do not expand this locking pattern without examining
callback behavior.

### Enable and Disable

The adapter's complete enable sequence is:

```text
fm2_backend_configure_startup(config)
fm2_backend_enable()
fm2_backend_set_frequency(initial frequency)
rollback with disable on failure
```

The low-level enable sequence is approximately:

```text
initialize HAL if needed
reset enabled state
attempt Slimbus on (failure is logged but is not fatal)
set receiver STATE to RX
wait up to 2 seconds for enabled_cb
apply runtime controls
read diagnostic control snapshot
apply post-enable RDS and power controls
```

Runtime controls include region, explicit lower/upper bounds, spacing, stereo,
antenna, emphasis, RDS/RBDS standard, signal threshold, and soft mute.
Post-enable setup configures raw and processed RDS masks, low-power state, and
AF jump.

Disable writes receiver state off, then disables Slimbus. It does not wait for
`disabled_cb`. The callback clears enabled/mode state and sends the JSON
`disabled` event. The library is not unloaded during normal shutdown.

### Tune, Jump, and Seek

Tune writes the IRIS frequency control. The tune callback updates frequency,
clears station-specific RDS state, and emits a state patch.

HAL `jump` uses configured spacing and wraps at region limits. Direction is
normalized by sign.

Seek configures search mode and dwell, then writes the IRIS seek control. The
immediate `seekhw` response only acknowledges command acceptance. Java does not
parse it as a frequency. The asynchronous callback updates frequency, clears
RDS, and emits the authoritative state patch.

### Full Search

HAL full search currently uses a sequential scan state machine rather than the
defined strong-list search mode:

```text
tune to lower band
set scan search mode and dwell
seek upward
collect unique frequencies from scan_next_cb
detect wrap when reported frequency decreases
finish after returning to the starting area
sort frequencies
send search_done
```

At most 64 frequencies are retained. The scan has no timeout. If callback
ordering never satisfies wrap detection, search may remain active indefinitely.

A Qualcomm search-list callback is also registered and can independently parse
and emit station results. If a vendor emits both scan-next and search-list
callbacks, two different completion messages are possible. Deduplication is
local to repeated search-list payloads and does not unify both mechanisms.

### HAL RDS and Audio

Callbacks handle PS, RT, PI, PTY, AF, RT+, ECC, stereo, and RDS availability.
App-facing state includes PS, RT, PI, PTY, AF, stereo, and the country decoded
from ECC plus the PI country nibble. RT+ remains diagnostic/logging data.

PS/RT/AF/search callback signatures do not provide explicit buffer lengths.
Parsers use count/length fields inside payloads and must trust vendor memory.
ABI or payload incompatibility can therefore cause out-of-bounds reads.

Audio-related native behavior includes mono/stereo mode, soft mute during
configuration, and Slimbus control. `QualcommNative.refreshAudioRoute()` toggles
Slimbus off then on for HAL devices. Native code does not configure the wider
Android AudioPolicy/AudioManager route. `kV4l2CtrlAudioPath` exists but is not
currently used.

## Legacy V4L2 Backend

### Initialization and Open

`fm_command_open()` performs vendor-specific Android setup before opening the
device:

```text
set hw.fm.mode=normal
set hw.fm.version=0
start fm_dl service
optionally insmod radio-iris-transport.ko
poll hw.fm.init for readiness
wait for vendor stabilization
open /dev/radio0 with O_RDWR | O_NONBLOCK
wait again before use
```

The readiness poll can run for roughly six seconds, followed by fixed delays.

### Enable and Required Ordering

The adapter enable sequence is deliberately ordered:

```text
prepare receiver with emphasis, spacing, and antenna
configure RDS/RBDS
set region band
set stereo/mono
set AF behavior
tune initial frequency
unmute last
```

RDS setup must occur before band setup. Existing code records that the driver
rejects `RDS_ON` if band is configured first. Preserve this ordering unless
verified on target hardware.

Prepare queries V4L2 capabilities, updates Android FM properties, optionally
runs Qualcomm patch download/setup, enables RX, mutes, applies basic controls,
and creates the event thread.

### Driver Operations

The low-level layer uses:

| Operation | API |
| --- | --- |
| Private controls | `VIDIOC_S_CTRL` |
| Band | `VIDIOC_S_TUNER` |
| Tune/get frequency | `VIDIOC_S_FREQUENCY` / `VIDIOC_G_FREQUENCY` |
| Stereo mode | `VIDIOC_S_TUNER.audmode` |
| Seek/search | `VIDIOC_S_HW_FREQ_SEEK` |
| RDS and event buffers | `VIDIOC_DQBUF` |

Frequency conversion uses V4L2 units of 62.5 Hz.

### Event Thread and Concurrency

An event thread dequeues the Qualcomm Tavarua event buffer and interprets each
byte as an event ID. It handles radio ready/disabled, tune/seek, scan progress,
PS/RT/raw RDS, stereo/mono, station lists, AF, ODA, RT+, and ERT.

On `RADIO_DISABLED`, the thread marks state off, closes the radio file
descriptor, and exits itself.

Legacy globals such as `fd_radio`, `fm_storage`, the power-completion flag, and
RT+ tags are shared between the command thread and event thread without a
mutex. The event thread can close the descriptor while command handling issues
an ioctl. Any lifecycle or concurrency change must account for these existing
data races rather than assuming this layer is thread-safe.

The return value of `pthread_create` is not checked, and the event thread is
neither detached nor joined. The power-completion flag is set on enable but is
not consistently reset during disable, making repeated enable/disable cycles
within one process sensitive.

### Tune, Jump, Seek, and Search

Legacy tune writes `VIDIOC_S_FREQUENCY`.

Legacy `jump` applies one spacing delta to the current frequency but does not
wrap at regional boundaries, unlike HAL.

Legacy seek uses V4L2 hardware seek with mode `SEEK` and dwell value 7. On
verified legacy hardware, the seek-complete event arrives while
`VIDIOC_G_FREQUENCY` still returns the starting frequency. It is diagnostic
only; the subsequent tune event carries the final frequency and emits the
authoritative state patch.

Full search requests `SCAN_FOR_STRONG` with a maximum of 20 stations. Results
arrive in the `NEW_SRCH_LIST` event and are emitted as `search_done`. Absolute
legacy channel values retain their native 50 kHz precision.

### Legacy RDS and Disable

Legacy configures processed RDS/RBDS groups, raw group masks, data buffers, and
PS-all behavior. PS, RT, PI, PTY, and AF are forwarded. RT+ and ERT are parsed
or logged but are not represented in the Android state protocol.

ODA events adjust the raw RDS group mask so the driver includes the detected
carrier.

Disable writes receiver state off and stops `fm_dl`, then returns before the
hardware disabled event. The event thread closes the descriptor later. This
asynchronous ownership is important during rollback and process termination.

## Shared RDS and JSON Behavior

`rds_parser.cpp` centralizes payload interpretation used by both backends where
possible. It parses and sanitizes PS/RT, decodes HAL AF payloads, and converts
vendor station lists.

The Java receiver trims PS/RT and replaces newlines with spaces. It rejects
non-positive frequency and negative PTY/array entries.

`RadioStateJsonCache` limits AF output to 25 frequencies. JSON must remain under
the fixed UDP buffer limit. Any protocol extension must be implemented in all
of these places:

1. Native patch structure in `ctl_server.h`.
2. Native serialization/cache in `ctl_json.*`.
3. Backend event construction.
4. Java parsing in `DatagramServer` and state representation.
5. Native host tests for JSON and parsers.

## Error Handling and Logging

Native logs go to stdout/stderr and are forwarded to logcat under `RFM-QCOM`.
Shared server logs use `srv/<scope>`, HAL logs use `hal/<scope>`, and legacy
logs use `leg/<scope>`. All three prefixes reserve seven characters for scope.

Legacy and HAL backend logs use the same shape:

```text
<backend>/<scope>: <operation> key=value...
```

Common formatting rules:

- PTY is a decimal program-type number: `pty=%d`.
- PI is a four-digit hexadecimal identifier without a prefix: `pi=%04x`.
- Frequencies and band limits use explicit kHz field names such as
  `frequency_khz`, `lower_khz`, `upper_khz`, and `frequencies_khz`.
- Vendor spacing enums use `spacing_vendor`; 50/100/200 kHz values use
  `spacing_khz`.
- Booleans use `0` or `1`; callback result codes retain the name `status`.
- Control IDs, masks, flags, and raw bytes remain hexadecimal.
- Common asynchronous state logs use the `event` scope. Backend-specific API
  diagnostics remain under `v4l2`, `vendor`, `metric`, or `snap`.

Do not force identical messages for operations with different semantics, such
as legacy station-list search versus HAL sequential scan, or the different
legacy/HAL RT+, ERT, ODA, and ECC payloads.

HAL stores a human-readable `last_error`. Legacy generally maps failures to
fixed public error strings. Legacy control failures often log only a generic
error rather than preserving `errno` details.

HAL configuration uses cumulative `ok &= vendor_set(...)`, so setup continues
after an individual failure. Later successful controls and diagnostic reads can
overwrite the original `last_error`; callers may receive only `ERR_FAILED`.

Several HAL setters update software state before hardware control success. A
failed stereo update is especially notable: cached mode can make a repeated
request appear already applied. When changing setters, update cached state only
at a point consistent with callback and failure semantics.

## Tests and Verification Boundaries

Host-side GoogleTest is enabled with `RFM_NATIVE_TESTS=ON`. Current tests cover:

- PS, RT, AF, and station-list parsing
- JSON serialization and state-cache behavior
- strict startup configuration parsing
- region profiles and frequency clamping
- frequency-list formatting

Current host tests do not cover:

- command routing in `main.cpp`
- UDP request/response transport
- backend detection or fallback
- `dlopen` and vendor ABI compatibility
- HAL callback lifecycle or scan state machine
- legacy ioctl and event-thread behavior
- synchronization, shutdown, or Android/native end-to-end behavior

After parser, config, region, or JSON changes, run:

```sh
make test-native
```

After native runtime changes, also run when an Android NDK is available:

```sh
make build-native
```

After bridge, packaging, or protocol changes, run:

```sh
make build-app
```

Hardware-sensitive changes still require testing on representative legacy and
HAL Qualcomm devices. A successful host test and cross-build cannot validate
vendor control ordering, callback ABI, audio routing, or radio behavior.

## Known Risks and Design Traps

Use this list as a pre-change checklist:

| Risk | Why it matters |
| --- | --- |
| HAL vendor calls under state mutex | Synchronous callbacks can deadlock |
| Reconstructed `fm_helium.so` ABI | Device variants can corrupt callback dispatch |
| Legacy globals without synchronization | Command/event races can close or reuse the fd |
| No legacy-to-HAL fallback | Mere existence of `/dev/radio0` fixes selection |
| Detection path differs from `dlopen` path | HAL detection can pass while loading fails |
| Weak runtime argument parsing | Invalid input silently becomes zero |
| HAL scan has no timeout | Search can remain active forever |
| Two HAL search completion paths | Duplicate/conflicting station lists are possible |
| UDP state dedup without acknowledgement | Lost packet may suppress later identical state |
| Command responses include NUL | Java numeric parsing can be inconsistent |
| Legacy jump lacks band wrap | Out-of-band tune can be requested |
| `exit` is not hardware shutdown | Radio/HAL/thread cleanup is skipped |

## Safe Change Guidance

- Preserve the common `Backend` contract unless Android and both adapters are
  updated together.
- Preserve legacy RDS-before-band and tune-before-unmute ordering.
- Avoid holding the HAL state mutex across vendor calls unless synchronous
  callback behavior is proven safe.
- Do not assume HAL and legacy frequency/search semantics are identical.
- Validate new protocol fields against the 512-byte datagram limit.
- Keep callbacks lightweight and make lock ownership explicit.
- Do not add compatibility behavior without a concrete target-device need.
- Add host tests for pure parsing, serialization, configuration, and state
  transitions whenever those can be isolated from hardware.
- For device failures, collect `RFM-QCOM` logs and identify backend kind before
  changing shared code.

## Documentation Caveats

Existing README files are partially stale:

- `native/README.md` refers to `ctl_server.c`; the implementation is
  `ctl_server.cpp`.
- It describes old `EVT_*` events; the current app-facing protocol is JSON.
- `native/legacy/README.md` names several `.c` files that are now `.cpp`.
- `native/hal/README.md` references absent `common.h` and a local
  `fm_helium.so` reference binary.

Use source code and this document for current navigation. Update this document
when architecture, protocol, lifecycle, or known invariants change.
