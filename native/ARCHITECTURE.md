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
files directory, starts it as root, and owns it through redirected standard
input, output, and error streams.

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

| Device | Platform context | OS | Backend |
| --- | --- | --- | --- |
| Xiaomi Redmi 4A | Qualcomm Snapdragon 425 (MSM8937) | Android 10 (Resurrection Remix 8.6.5) | Legacy V4L2, direct no-firmware bootstrap |
| Xiaomi Mi A1 | Qualcomm Snapdragon 625 (MSM8953) | Android 7.1.2 (AOSP) | Legacy V4L2 |
| Xiaomi Poco X3 Pro | Qualcomm Snapdragon 860 (SM8150-AD) | Android 15 (crDroid 11.12) | HAL/FM2 |

Treat behavior observed on these devices as evidence for the corresponding
backend, not as a universal Qualcomm contract. Re-test hardware-sensitive
changes on the matching backend whenever possible.

High-level data flow:

```text
Android QualcommNative
  -> NativeProcessSession -> su -> exec fmbin
  -> request-id + tab + text command on stdin
  -> main.cpp command router
  -> Backend interface
  -> Legacy V4L2 or Qualcomm fm_helium.so
  -> asynchronous hardware event/callback or periodic RMSSI sample
  -> newline-delimited JSON response/event on stdout
  -> Android NativeEventParser
  -> RadioStatePatch / application event
```

## Source Map

### Shared Native Layer

| Path | Responsibility |
| --- | --- |
| `native/main.cpp` | Process entrypoint, text command parsing and routing |
| `native/backend.h` | Common C++ base interface implemented by all backends |
| `native/backend_log.h` | Centralized logging utility (`backend_log()`) |
| `native/backend_factory.cpp` | Runtime detection and singleton backend creation |
| `native/backend/v4l2_base/` | Abstract V4L2 base class (`V4l2BackendBase`) |
| `native/backend/v4l2_qcom/` | Qualcomm V4L2 backend (`v4l2_qcom_backend.cpp`, `v4l2_scan_state.cpp`, `v4l2_qcom_types.h`) |
| `native/backend/hal_helium/` | Qualcomm Helium backend (`hal_helium_backend.cpp`, `hal_helium_types.h`) |
| `native/ctl_server.cpp` | Standard-I/O command loop, process-liveness handling, and asynchronous event writer |
| `native/ctl_json.cpp` | JSON serialization and state-patch deduplication |
| `native/ctl_protocol.cpp` | Strict request-ID and command-line frame parser |
| `native/startup_config.cpp` | Strict parser for the complete `enable` command |
| `native/region_profile.cpp` | Region limits and vendor mappings |
| `native/rds_parser.cpp` | Shared parsers for PS, RT, AF, and station lists |
| `native/scan_result.cpp` | Shared bounded, deduplicated, sorted sequential-scan results |
| `native/periodic_deadline.cpp` | Testable monotonic deadline arithmetic for native RMSSI polling |
| `native/rmssi.h` | Shared signed-low-byte RMSSI decoding used by all backends |
| `native/monotonic_clock.h` | Shared monotonic millisecond clock for native timing |
| `native/backend/qcom_fm_controls.h` | Qualcomm private V4L2 control identifiers |
| `native/frequency_format.h` | Frequency-list formatting used by logging/dedup |
| `native/hex_dump.h` / `.cpp` | Shared hex dump string formatting utility |
| `native/types.h` | Primitive compatibility types and booleans |

### Qualcomm Helium Layer

| Path | Responsibility |
| --- | --- |
| `native/backend/hal_helium/hal_helium_backend.cpp` | `HalHeliumBackend` `Backend` implementation, HAL loading, state, controls, callbacks, scan, RDS |
| `native/backend/hal_helium/hal_helium_types.h` | Reconstructed binary ABI for `fm_helium.so` and Helium runtime state types |

### Qualcomm V4L2 Layer

| Path | Responsibility |
| --- | --- |
| `native/backend/v4l2_base/v4l2_backend_base.h` | Shared V4L2 device handle, control I/O, and frequency unit conversion |
| `native/backend/v4l2_qcom/v4l2_qcom_backend.cpp` | Qualcomm V4L2 `Backend`, lifecycle, controls, event thread, RDS extraction |
| `native/backend/v4l2_qcom/v4l2_scan_state.cpp` | Testable sequential-scan collection and cancellation state |
| `native/backend/v4l2_qcom/v4l2_qcom_types.h` | Qualcomm V4L2 constants, state, structs, and event enums |

### Android Bridge

| Path | Responsibility |
| --- | --- |
| `app/src/main/java/com/vlad805/fmradio/service/FMService.java` | Orchestrator: driver and audio construction, command intents, app broadcasts, notifications |
| `app/src/main/java/com/vlad805/fmradio/service/fm/IFMController.java` | Controller contract implemented by all drivers |
| `app/src/main/java/com/vlad805/fmradio/service/fm/IFMEventListener.java` | Event-listener registration contract |
| `app/src/main/java/com/vlad805/fmradio/service/fm/IFMEventPoller.java` | Poll contract; no current driver implements it (legacy of the old polled controller) |
| `app/src/main/java/com/vlad805/fmradio/service/fm/FMEventCallback.java` | Event callback surface implemented by `FMService` |
| `app/src/main/java/com/vlad805/fmradio/service/fm/communication/Request.java` | Command object with per-command timeouts (default 1000 ms) |
| `app/src/main/java/com/vlad805/fmradio/service/fm/implementation/Empty.java` | No-op driver for unsupported devices |
| `app/src/main/java/com/vlad805/fmradio/service/fm/implementation/QualcommNative.java` | Unified native controller, launch and complete startup command |
| `app/src/main/java/com/vlad805/fmradio/service/fm/implementation/AbstractQualcommNativeController.java` | Binary installation, owned process lifecycle, public controller behavior |
| `app/src/main/java/com/vlad805/fmradio/service/fm/communication/NativeProcessSession.java` | Root process session, command queue, timeouts, standard-I/O readers/writer, and exit monitoring |
| `app/src/main/java/com/vlad805/fmradio/service/fm/communication/NativeEventParser.java` | JSON event validation and dispatch |
| `app/src/main/java/com/vlad805/fmradio/service/fm/RadioStatePatch.java` | Java representation of partial radio state |

The wider app architecture (audio routing, recording, favorites, settings)
is documented in `app/ARCHITECTURE.md`.

## Build and Packaging

`native/CMakeLists.txt` uses C++20 and C11 and builds all shared, HAL, and
legacy sources into `fmbin`. The executable links `dl` for runtime HAL loading.

`native/build.sh` cross-compiles with Android NDK for:

| Android ABI | Asset name |
| --- | --- |
| `armeabi-v7a` | `app/src/main/assets/fmbin-armv7a` |
| `arm64-v8a` | `app/src/main/assets/fmbin-aarch64` |

Android builds pin NDK `29.0.14033849` and CMake `3.22.1` for both `fmbin` and
the app's JNI library. `native/build.sh` resolves those exact versions from the
Android SDK and fails when they are unavailable instead of selecting another
installed toolchain.

The minimum native platform is Android 21. Build output is staged under
`BUILD_ROOT`, defaulting to `/tmp/rfm-radio-native`. Asset binaries are ignored
by Git and must be regenerated.

Useful commands from the repository root:

```sh
make test-native
make build-native
make build-debug
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
that asset to `/data/data/<application-id>/files/` and applies mode `755` as the
application UID before recording the installed version. An up-to-date binary
which has lost its execute bits is repaired in place during preparation. The
controller then verifies root access, kills an old process, and launches the
executable through a long-lived `su` session. Java sends an `exec` shell command
and then retains the resulting process and all three standard streams. Native
stderr is drained into Android logcat with tag `RFM-QCOM`; stdout is reserved
exclusively for protocol JSON.

Normal launch sequence:

```text
copy/install asset if needed
repair and verify executable mode
verify root access with a bounded background request
kill old fmbin process
start su and exec fmbin without backgrounding
wait up to 5 seconds for the native ready handshake
send "init" with a 10 second timeout
```

Normal enable sequence sends one complete command assembled from preferences:

```text
enable freq=<kHz> region=<eu|us|jp|jp_wide> spacing=<50|100|200> \
       stereo=<0|1> soft_mute=<0|1> antenna=<0..255> af=<0|1>
```

`QualcommNative.shouldApplyStartupPreferences()` returns false because this
complete command applies all startup configuration atomically from the app's
point of view.

Normal shutdown is owned by `NativeProcessSession`:

```text
send "disable"
send "shutdown"
native performs backend cleanup and acknowledges the request
close native stdin and wait for process exit
fall back to TERM, then KILL by the handshake PID on timeout
```

`shutdown` and its compatibility alias `exit` perform backend cleanup before
the command loop exits. EOF or HUP on stdin and TERM/HUP signals reach the same
cleanup path. This makes loss of the Java service process fail closed: the
kernel closes its pipe descriptors, native sees EOF, disables the receiver,
and exits. `FMService` returns `START_NOT_STICKY`; a process-death restart with
no command stops instead of silently recreating a disconnected service.

Root access is effectively required for device nodes, system properties,
module setup, and vendor controls.

Root denial or a timed-out root probe terminates launch with
`ROOT_ACCESS_DENIED`, which shows a root-specific error. A failed ready
handshake or `init` timeout terminates the owned process and reports
`LAUNCH_FAILED` instead of leaving the controller in `LAUNCHING`.

## Standard-I/O Protocol

### Transport

| Direction | Stream | Payload |
| --- | --- | --- |
| Android to native | stdin | `<request-id>\t<command>\n` encoded as UTF-8 |
| Native to Android | stdout | One UTF-8 JSON object per line |
| Native to Android | stderr | Diagnostic log lines forwarded to logcat |

The native loop handles commands serially. `NativeProcessSession` normally
keeps one request in flight, attaches a monotonically increasing ID, and uses
that ID to match JSON responses. `shutdown` may be written behind an in-flight
command so teardown does not wait for Java's regular command queue.

Native announces a successful exec with:

```json
{"type":"ready","protocol":1,"pid":1234}
```

Command responses use:

```json
{"type":"response","id":7,"ok":true,"data":"ok"}
```

Native successful responses are delayed by 300 ms in `make_ok()`. This delay is
intentional and documented as preserving Java/native timing relative to
asynchronous hardware events. Do not remove it casually.

The response `ok` field is derived from `response_t.code`; `data` retains the
existing textual success/error values. Newline framing removes the old trailing
NUL behavior.

### Commands

| Command | Meaning | Response on success |
| --- | --- | --- |
| `init` | Detect and initialize backend | `ok` |
| `enable ...` | Complete startup and initial tune | `ok` |
| `disable` | Turn receiver off | `ok` |
| `shutdown` | Clean up the backend and exit the command loop (alias: `exit`) | `ok` |
| `setfreq <kHz>` | Tune to absolute frequency | `ok` |
| `jump <direction>` | Move one configured spacing step | Numeric frequency |
| `seekhw <direction>` | Begin hardware seek | `ok` |
| `power_mode <low|other>` | Set low or normal power | `ok` |
| `set_stereo <0|1>` | Select mono/stereo mode | `ok` |
| `set_soft_mute <0|1>` | Enable or disable weak-signal attenuation | `ok` |
| `set_notch_filter <0|1|2>` | Set hardware notch filter (0=auto, 1=low, 2=high) | `ok` |
| `set_antenna <value>` | Select antenna | `ok` |
| `set_region <name>` | Change region and clamp current tune if needed | `ok` |
| `set_spacing <50|100|200>` | Change channel spacing | `ok` |
| `searchhw` | Begin full station search | `ok` |
| `search_cancel` | Cancel active search | `ok` |
| `auto_af <0|1>` | Toggle automatic AF jump | `ok` |
| `slimbus <0|1>` | Toggle HAL audio transport; no-op success on legacy | `ok` |

While the tuner is enabled, the native command loop requests RMSSI from the
selected backend every two seconds. Legacy reads `VIDIOC_G_TUNER.signal`; HAL
uses the asynchronous station-parameter callback. Polling is suppressed while
the receiver is disabled or a tune, seek, or full scan is in progress. Android
does not send a polling command.

The `enable` parser is strict. It requires exactly seven unique named parameters,
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
| `ERR_IVD_CFG` | Invalid complete config, region, or spacing; also an invalid `set_soft_mute` or `set_notch_filter` argument |
| `ERR_UNK_CMD` | Empty or unknown command |
| `ERR_UNSUPPORTED` | No backend or unsupported device |
| `ERR_FAILED` | Generic backend operation failure |
| `ERR_ALREADY_ENABLED` | Legacy `enable` rejected because receiver is already enabled |
| `ERR_UNV_ANT` | Legacy antenna value rejected |
| `ERR_CNS_REG` | Legacy region/band could not be set |

Both `response_t.code` (as `ok`) and its `data` string are transmitted.

Java-side response handling details:

- Success is matched by a string-prefix `ok` check on the response `data`
  field (`QualcommNative`); the JSON `ok` field is currently ignored by the
  Java bridge.
- The base controller's `enableImpl` in
  `AbstractQualcommNativeController` sends a bare `enable` with no
  parameters and is dead code: the strict parser always rejects it. Only
  `QualcommNative.enableImpl` sends the complete seven-parameter command.
- HAL `setFrequencyImpl` suppresses a repeated tune within 1500 ms
  (`DUPLICATE_TUNE_WINDOW_MS`).
- The recorder sample rate is selected by backend kind: 48000 Hz for HAL
  and 44100 Hz for legacy (`QualcommNative.getRecorderSampleRate()`).

### Asynchronous Events

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
stereo, RMSSI, PS, RT, PI, country, PTY, and AF frequencies. RMSSI is a signed
receiver value serialized as a top-level `rmssi` field. Country is a two-letter
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
writing of the complete JSON line. Protocol writes are mutex-serialized because
vendor callbacks may emit events concurrently with command responses.

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

`native/backend/hal_helium/hal_helium_types.h` manually reconstructs Qualcomm's binary ABI:

- callback function signatures and callback-table order
- `fm_interface_t`
- vendor payload structs
- IRIS frequency, seek, band, audio-mode, RMSSI, and Slimbus controls

Initialization in `HalHeliumBackend::init()` performs:

```text
dlopen("fm_helium.so", RTLD_NOW)
dlsym("FM_HELIUM_LIB_INTERFACE")
vendor->hal_init(&kCallbacks)
mark runtime initialized
```

The ABI must match the device's vendor library exactly. Callback order, C++
`bool` representation, struct packing, and symbol interpretation are all
hardware compatibility boundaries. A mismatch can call an incorrect function
pointer, not merely return an error.

### Runtime State and Synchronization

`HeliumRuntimeState` in `hal_helium_types.h` owns:

- dynamic library and vendor interface handles
- initialized/enabled flags
- configured region, range, spacing, stereo, antenna, and Slimbus state
- current frequency
- scan progress and collected stations
- RDS/RT+ deduplication state
- last error text
- a pthread mutex and condition variable

Vendor callbacks may run on vendor-managed threads. Most runtime fields are
protected by `state_.lock`. The condition variable is used to wait up to two
seconds for `enabled_cb`.

Important locking caveat: `vendor_set()` holds `state_.lock` while calling
vendor `set_fm_ctrl()`, and initialization calls vendor `hal_init()` while
holding the same non-recursive mutex. If a vendor implementation invokes a
callback synchronously from either call, and that callback also locks state,
the process can deadlock. Do not expand this locking pattern without examining
callback behavior.

### Enable and Disable

The adapter's complete enable sequence is:

```text
configure_startup(config)
enable_receiver()
set_frequency(initial frequency)
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

For a normal-power startup, post-enable setup deliberately writes low power and
then normal power before reapplying processed RDS configuration. Reference
Helium initializes its cached power mode to normal and sends the asynchronous
signal, RDS-sync, and stereo event mask only when that cached value changes. A
direct initial normal request is therefore a no-op and leaves only tune-status
snapshots. Device testing confirmed that one low-to-normal transition enables
subsequent stereo-to-mono events when the headphone antenna is disconnected.
This workaround is best-effort: both writes are attempted, but failure does not
abort receiver enable because tuning and synchronous status snapshots still
work without the asynchronous event mask.

Disable writes receiver state off, then disables Slimbus. It does not wait for
`disabled_cb`. The callback clears enabled/mode state and sends the JSON
`disabled` event. The library is not unloaded during normal shutdown.

HAL shutdown waits up to 2000 ms for `disabled_cb`, including when an earlier
`disable` or enable rollback already requested OFF. Runtime state tracks cleanup
separately from enabled state: cleanup is required as soon as enable starts and
only the disabled callback confirms it. A pending OFF is not sent again; an OFF
that failed to submit can be retried. The adapter delegates shutdown to this
runtime state, including enable without an explicit `init`. Missing callbacks
produce a timeout log, after which process exit continues. EOF and TERM/HUP use
the same shutdown wait; only legacy retains the fixed EOF/signal settle delay.

After explicit backend cleanup and the shutdown response (or the callback wait
on EOF/signal), `main()` uses `_exit()` for the HAL backend. On Poco X3 Pro with
crDroid 11.12, normal libc exit runs the system `libfm-hci.so` global
`fm_hci_t` destructor, whose joinable `std::thread` causes `std::terminate()`
even after FM OFF and the RX worker's exit. Bypassing static destructors avoids
this vendor crash; it does not replace receiver cleanup. Protocol messages use
direct writes, and stdout/stderr are flushed before `_exit()`.
The reference `fm_hci_close()` in the sibling
`rfm-radio-useful-repos/android_vendor_qcom_opensource_fm-commonsys/fm_hci/fm_hci.cpp`
joins TX but leaves RX joinable; Helium frees its receiver structures before
calling `disabled_cb`. Waiting for that callback does not fix the RX destructor.

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
finish when seek_complete_cb reports the vendor's terminal SEARCH_COMPLETE
retain wrap detection as a fallback for vendor variants
sort frequencies
send search_done
```

Both sequential backends use the shared `ScanResultCollector` for validation,
deduplication, the 64-station bound, and sorted result snapshots. HAL retains
its own callback lifecycle and wrap fallback. At most 64 frequencies are
retained. The scan has no timeout. If callback
ordering provides neither terminal completion nor a detectable wrap, search may
remain active indefinitely. Terminal completion sends an empty station list when
the scan found no stations. Device testing confirmed that the HAL may emit the
terminal seek-complete callback twice and then tune back to the lower band; only
the first callback completes the active scan and sends `search_done`.

A Qualcomm search-list callback is also registered and can independently parse
and emit station results. If a vendor emits both scan-next and search-list
callbacks, two different completion messages are possible. Deduplication is
local to repeated search-list payloads and does not unify both mechanisms.

### RMSSI and SINR

The app-facing signal indicator uses RMSSI only. HAL requests
`kV4l2CtrlIrisRmssi` (`0x00980934`) and receives the signed low byte through
`fm_get_station_param_cb`. `HeliumRmssiState` keeps the pending request, request
frequency, pause state, and timeout-log state together so late callbacks from a
previous frequency are not published for the current station.

RMSSI remains paused from the start of enable until `tune_cb`, and from the
start of disable through `disabled_cb`. The vendor callbacks are authoritative
for `HeliumRuntimeState::enabled`; a failed disable only resumes RMSSI when that state
still reports the receiver as enabled.

`tune_cb` requests one immediate RMSSI refresh after publishing the new
frequency. The two-second command-loop polling remains the periodic fallback.
If an older request is still pending, the immediate refresh is skipped and the
normal pending-request guard prevents overlap.

Qualcomm drivers also expose SINR through `kV4l2CtrlIrisSinr`
(`V4L2_PRIVATE_BASE + 0x2c`). Both backends attempt SINR first and fall
back to RMSSI if SINR is unsupported or fails (`HeliumMetricRequestResult::kUnsupported`
or V4L2 control failure). Busy, scan-active, or settle-window skipped requests
do not change capability or emit metric clears. Whenever the active metric
changes in either direction (SINR to RMSSI fallback, including synchronous HAL
rejection, or RMSSI to SINR recovery), the inactive metric is explicitly cleared
via `RADIO_PATCH_CLEAR_METRIC` (`"sinr": null` / `"rmssi": null`). Repeated or
already-absent metric clears are deduplicated at JSON serialization time in
`ctl_json.cpp`, where cleared fields normalize back to `RADIO_PATCH_ABSENT_METRIC`.

### HAL RDS and Audio

Callbacks handle PS, RT, PI, PTY, AF, RT+, ECC, stereo, and RDS synchronization.
App-facing state includes PS, RT, PI, PTY, AF, stereo, country decoded
from ECC plus the PI country nibble, and RT+ item title/artist (sliced against raw RT).

The ECC callback (`ext_country_code_cb`) receives the 12-byte RDS group 1A
payload where `[4]` is the firmware TA/TP header byte and `[5]` is the 1A
variant. The variant gate reads `[5]` only (via `parse_rds_ecc_payload()` in
`rds_parser.cpp`): folding the TA/TP header byte into the variant made
`variant` always non-zero on TP stations and silently dropped their country
(issue #102). The slow-labelling low byte `[9]` is ECC only at variant 0;
other variants are logged and discarded.

The `rds_avail_status_cb` boolean is a decoder sync/lock snapshot, not a gate
for processed RDS data. A tune-status callback commonly reports `rds_sync=0`
before separate PS or RT events arrive, sometimes without a later sync-true
callback. Both backends therefore log this state as `rds_sync` and continue to
accept valid RDS payloads regardless of the latest value. Station metadata is
cleared on tune/seek context changes, not on transient sync loss.

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

The legacy backend has two bootstrap modes. The firmware path above can spend
roughly six seconds waiting for `hw.fm.init`, followed by fixed delays, and may
run `fm_qsoc_patches` during receiver setup.

If both `init.svc.fm_dl` and an executable `fm_qsoc_patches` are absent, the
backend selects the no-firmware direct V4L2 path before inspecting
`hw.fm.init`, which may be stale from a killed process. During enable this path
sets `hw.fm.init=1`, waits 200 ms, starts the event listener before `STATE=RX`,
and waits for `RADIO_READY`. Disable clears `hw.fm.init` instead of stopping
`fm_dl`.

The downloader present on Xiaomi Mi A1 keeps it on the firmware path. Xiaomi
Redmi 4A Android 10 uses the direct path.

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
| RMSSI | `VIDIOC_G_TUNER.signal` |
| Stereo mode | `VIDIOC_S_TUNER.audmode` |
| Seek/search | `VIDIOC_S_HW_FREQ_SEEK` |
| RDS and event buffers | `VIDIOC_DQBUF` |

Frequency conversion uses V4L2 units of 62.5 Hz.

`TAVARUA_EVT_TUNE_SUCC` publishes the new frequency and immediately reads one
RMSSI sample. Sequential full scans skip this one-shot while their scan state
is busy; the native two-second polling supplies the next sample after scan
completion. Both the tune event and periodic adapter path use
`fm_command_publish_rmssi()` so decoding and state-patch behavior stay shared.
After a runtime tune or jump, the adapter pauses RMSSI sampling for a fixed
750 ms settle window (`kMetricSettleMs` in `v4l2_qcom_backend.cpp`) so the
metric can stabilize on the new frequency.

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

If `enable()` is called while the receiver is already enabled, it rejects fast
with `ERR_ALREADY_ENABLED` without disturbing the active session or event thread.
If `enable()` is called while disabled (including after a prior `disable()` or
failed enable rollback), it first calls `stop_event_thread()` to stop and join
any lingering event thread and reopens `/dev/radio0` via `command_open()` when
`fd_radio_ < 0` before preparing the receiver.

Legacy `pthread_create()` return codes are checked; on `command_setup_receiver()`
failures, `rollback_enable()`, or `command_shutdown()`, `stop_event_thread()`
clears `power_on_completed_` and `thread_running_`, closes the descriptor to
unblock any pending `VIDIOC_DQBUF`, and joins the event thread deterministically.

### Tune, Jump, Seek, and Search

Legacy tune writes `VIDIOC_S_FREQUENCY`.

Legacy `jump` applies one spacing delta to the current frequency but does not
wrap at regional boundaries, unlike HAL.

Legacy seek uses V4L2 hardware seek with mode `SEEK` and dwell value 7. On
verified legacy hardware, the seek-complete event arrives while
`VIDIOC_G_FREQUENCY` still returns the starting frequency. It is diagnostic
only; the subsequent tune event carries the final frequency and emits the
authoritative state patch.

Legacy full search uses ordinary `SCAN` mode so it is not limited to the 20
stations available from Qualcomm's `SCAN_FOR_STRONG` list payload. Native first
tunes to the current region's lower bound, then the driver traverses the band
after one hardware-seek request. `TUNE_SUCC` records a
pending frequency, `SCAN_NEXT` confirms and collects it, and `SEEK_COMPLETE`
sorts and emits the result as `search_done`. At most 64 unique stations are
retained to keep the event bounded and compact. Normal completion emits an
empty list when no stations were found. Device testing of
ordinary legacy scan returned 42 stations, confirming that this path exceeds
the former 20-station list limit.

The collector has explicit seeking, scanning, and cancelling states protected
by its own mutex. This prevents an ordinary seek callback from completing an
active full scan. Tune, jump, region, spacing, low-power, and overlapping search
commands are rejected while a search operation is unresolved. Cancelled scans consume
their terminal `SEEK_COMPLETE` without sending a result, and disable abandons
the operation immediately. The old `NEW_SRCH_LIST` payload is still drained and
logged if received, but no longer completes application full search.
Frequencies retain their native kHz precision.

### Legacy RDS and Disable

Legacy configures processed RDS/RBDS groups, raw group masks, data buffers, and
PS-all behavior. PS, RT, PI, PTY, AF, and RT+ item title/artist (sliced against raw RT)
are forwarded to the client. ERT is logged for diagnostics.

Verified `qcom_v4l2` IRIS kernel interfaces (tested on both `msm8953` Mi A1 and
Redmi 4A) do not expose ECC to userspace. The HCI dispatcher drops
`HCI_EV_EXT_COUNTRY_CODE` (`0x17`), and `TAVARUA_EVT_NEW_RAW_RDS` does not fire
even when group 1A (`0x04`) or 0A (`0x01`) is enabled in
`kV4l2CtrlRdsGroupMask` — raw RDS groups are consumed inside the driver for
ODA/RT+/ERT and are not written to the allocated raw-RDS FIFO. Consequently the
Qualcomm V4L2 backend cannot emit a reliable `country` without a kernel change.
The country nibble in PI alone is ambiguous across ECC regions and must not be
used as a substitute.

ODA events adjust the raw RDS group mask so the driver includes the detected
carrier. ODA and the following RT+ event are unrelated to ECC; ODA discovery
uses group 3A, while ECC would come from group 1A or the dropped HCI event.

Disable writes receiver state off and stops `fm_dl`, then returns before the
hardware disabled event. The event thread closes the descriptor later. This
asynchronous ownership is important during rollback and process termination.

## Shared RDS and JSON Behavior

`rds_parser.cpp` centralizes payload interpretation used by both backends where
possible. It parses and sanitizes PS/RT, decodes HAL AF payloads, and converts
vendor station lists.

PS/RT text is delivered by both backends as raw EBU Latin G0 bytes
(EN 50067:1998 / IEC 62106 Annex E). `parse_rds_text_payload()` converts them
to UTF-8 through `kEbuLatinG0CodePoints` — a 256-entry Unicode code-point
table (mirrored from redsea) with a runtime UTF-8 encoder:
0x80–0xFF are accented/special characters, 0x24/0x5E/0x60/0x7E are remapped
(`¤`, `―`, `‖`, `¯`), and 0x00–0x1F are mapped as low-byte aliases of
0x80–0x9F (high-bit-stripped encoders), except 0x0D which keeps its RT
terminator meaning. The conversion runs AFTER the raw-byte post-processing
(`\r` truncation and trailing padding trim: NUL for both PS and RT, spaces and
0xff vendor padding for RT only) because 0xff is vendor padding
that would otherwise look like a UTF-8 lead byte. `RdsTextPayload::text` is
UTF-8 with `text_len` in UTF-8 bytes; `raw_text` keeps the pre-conversion
bytes because RT+ tag start/length indices refer to raw EBU Latin G0 byte offsets.
Slicing MUST be performed in C++ on `raw_text` before UTF-8 conversion because
EBU Latin G0 byte indices do not align with UTF-8 / Java UTF-16 character indices
when non-ASCII characters or low-byte aliases are present. Both backends slice
the raw buffer and convert only the slice (`evaluate_rds_rt_plus_slices`).

The Java receiver trims PS/RT and replaces newlines with spaces. It rejects
non-positive frequency and negative PTY/array entries.

`RadioStateJsonCache` limits AF output to 50 frequencies. Each protocol message
must remain a single valid JSON line. Any protocol extension must be
implemented in all of these places:

1. Native patch structure in `ctl_server.h`.
2. Native serialization/cache in `ctl_json.*`.
3. Backend event construction.
4. Java parsing in `NativeEventParser` and state representation.
5. Native host tests for JSON and parsers.

## Error Handling and Logging

Native logs go to stderr and are forwarded to logcat under `RFM-QCOM`. Stdout
must contain protocol JSON only.
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
- standard-I/O request-frame parsing
- strict startup configuration parsing
- HAL shutdown callback waits, pending OFF/rollback cleanup, timeout, and retry
  using an injected vendor interface (not device HAL libraries)
- Legacy V4L2 Qcom lifecycle transitions (repeated `enable` rejection,
  `disable` -> `enable` recovery, setup/timeout rollback joining the event
  thread) and bidirectional metric switching using fake fd/ioctl/sleep
  overrides (`tests/v4l2_qcom_lifecycle_test.cpp`)
- HAL signal-metric switching, sync/async SINR fallback, recovery clears, and
  skipped-poll preservation (`tests/hal_metric_test.cpp`)
- region profiles and frequency clamping
- frequency-list formatting
- shared sequential-scan result collection
- legacy sequential-scan state transitions
- signed-low-byte RMSSI decoding (`tests/rmssi_test.cpp`)
- RMSSI polling deadline arithmetic with an injected clock
  (`tests/periodic_deadline_test.cpp`)

Current host tests do not cover:

- command routing in `main.cpp`
- standard-I/O process transport and EOF-driven shutdown
- backend detection or fallback
- `dlopen` and vendor ABI compatibility
- device HAL callback lifecycle or HAL scan state machine
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
make build-debug
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
| Sequential scans have no timeout | HAL or legacy search can remain active if terminal events are lost |
| Two HAL search completion paths | Duplicate/conflicting station lists are possible |
| Stdout shared by callback threads | All protocol writes must remain serialized and newline framed |
| Standard-output backpressure | A stalled Java reader can block a native callback writer |
| Legacy jump lacks band wrap | Out-of-band tune can be requested |
| Asynchronous vendor disable | HAL waits for disabled_cb with a timeout; legacy uses a settle delay, and both still need device verification |

## Safe Change Guidance

- Preserve the common `Backend` contract unless Android and both adapters are
  updated together.
- Preserve legacy RDS-before-band and tune-before-unmute ordering.
- Avoid holding the HAL state mutex across vendor calls unless synchronous
  callback behavior is proven safe.
- Do not assume HAL and legacy frequency/search semantics are identical.
- Keep stdout protocol-only and ensure every message is valid single-line JSON.
- Keep callbacks lightweight and make lock ownership explicit.
- Do not add compatibility behavior without a concrete target-device need.
- Add host tests for pure parsing, serialization, configuration, and state
  transitions whenever those can be isolated from hardware.
- For device failures, collect `RFM-QCOM` logs and identify backend kind before
  changing shared code.

## Documentation Caveats

Agent-facing documentation layers:

- `native/AGENTS.md` — dense auto-loaded summary for routine work in
  `native/`.
- this document — full specification for deep protocol or vendor rework.
- `app/AGENTS.md` and `app/ARCHITECTURE.md` — the Android side,
  including the bridge classes above.

- `native/README.md` is a shorter human-oriented overview; treat the command
  tables here as canonical when they disagree.
- `native/backend/v4l2_qcom/README.md` and `native/backend/hal_helium/README.md`
  summarize each backend directory.

Use source code and this document for current navigation. Update this document
when architecture, protocol, lifecycle, or known invariants change.
