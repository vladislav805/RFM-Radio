# native/ — C++ FM Backend Auto-Context

This directory builds one root executable `fmbin` (C++20, CMake, Android NDK,
minimum platform 21). It is NOT a JNI library: the app copies an ABI-specific
binary (`fmbin-armv7a` / `fmbin-aarch64`) from assets into its private files
directory, starts it through `su`, and owns it over stdin/stdout. `app/src/
main/cpp` is a different, unrelated native component (LAME MP3 encoder).

## Layout

| Path | Role |
| --- | --- |
| `main.cpp` | Process entrypoint, backend lifetime, HAL `_exit()` bypass |
| `command_router.cpp` | Text command handlers, argument validation and response timing |
| `backend.h` | Common `Backend` base interface for all implementations |
| `backend_log.h` | `Logger` writes backend diagnostics to stderr. Each backend shares its logger with its helpers. |
| `backend_factory.cpp` | Runtime backend detection and process-global singleton |
| `backend/v4l2_base/` | Abstract V4L2 base class (`V4l2BackendBase`) |
| `backend/v4l2_qcom/` | Qualcomm V4L2 backend. `v4l2_qcom_events.cpp` handles the Tavarua event queue and reads the associated driver buffers. |
| `backend/v4l2_exynos/` | Samsung Exynos S610 V4L2 backend, driver controls and raw RDS block decoder (`s610_rds_decoder.cpp`) |
| `backend/hal_helium/` | Qualcomm Helium backend (`hal_helium_backend.cpp`, `hal_helium_types.h`) |
| `backend/qcom_fm_controls.h` | Shared Qualcomm V4L2 control IDs |
| `hex_dump.cpp` / `.h` | Shared hex dump string formatting utility |
| `ctl_server.cpp` | stdio command loop, async event writer, state cache |
| `ctl_protocol.cpp` | Strict request-id frame parser |
| `ctl_json.cpp` | JSON serialization and state-patch deduplication |
| `startup_config.cpp` | Strict parser for the complete `enable` command |
| `region_profile.cpp` | Region limits and vendor band mappings |
| `rds_parser.cpp` | Shared PS/RT/AF/ECC/station-list payload parsers |
| `rt_plus_context.cpp` | Decodes each pair of RT+ tags against raw RadioText and keeps values by six-bit content type until a reset or update. |
| `scan_result.cpp` | Bounded, deduplicated, sorted scan results |
| `rmssi.h` | `decode_rmssi()` — signed-low-byte RMSSI decoding, shared |
| `system.cpp` / `.h` | Shared system property, file-existence, and shell command helpers |
| `periodic_deadline.cpp` | Testable monotonic deadline arithmetic for polling |
| `tests/` | Host GoogleTest suite |

Device log evidence lives in `tmp/` and `log.log`; `logcat.sh` captures more.

## Code Style (touched code only)

- Comments describe what the code does — present tense, positive statements.
  Avoid "is NOT part of…"-style negations; state where the value actually
  lives instead.
- Multi-line function calls put each argument on its own line, with the
  closing paren on its own line:

  ```cpp
  parse_rds_ecc_payload(
          reinterpret_cast<const unsigned char *>(payload),
          len,
          country
  );
  ```

  Apply this formatting to code you touch; leave surrounding untouched code
  as is.

## Backends

`main()` detects the backend kind for the ready message. The first command that
needs the backend creates its process-global instance. Detection follows this order:

1. `/sys/class/video4linux/radio0/name` contains `iris` or `tavarua` (case-insensitive) with `/dev/radio0` present, or sysfs name node is absent while `/dev/radio0` exists → `kV4l2Qcom`
2. name contains `s610` with `/dev/radio0` present → `kV4l2Exynos` (Samsung Exynos S610; protocol token `v4l2_exynos`)
3. other names (such as `si4709`) or empty name fail V4L2 matching and fall through to: `fm_helium.so` in known vendor/system/odm locations → `kHelium` (`dlopen`)
4. else no backend → `ERR_UNSUPPORTED`

Regions: `eu`/`us` 87500–108000 kHz, `jp` 76000–95000, `jp_wide`
76000–108000. V4L2 frequency units are 62.5 Hz; the protocol carries kHz.

## Protocol

Transport: stdin frames `<request-id>\t<command>\n` (UTF-8, trailing `\r`
tolerated, frames over 4096 bytes terminate the loop); stdout carries one
JSON object per line; stderr is diagnostics → logcat tag `RFM-QCOM`. Ready
handshake: `{"type":"ready","pid":N,"backend":"v4l2_qcom"|"v4l2_exynos"|"hal_helium"|"none"}`. Responses:
`{"type":"response","id":N,"ok":true,"data":"ok"}`. `make_ok()` delays the
success response by 300 ms intentionally — do not remove it. Java matches
success by a `"ok"` prefix on `data`, not by the `ok` field.

Commands: `init`, `enable`, `disable`, `setfreq`, `jump`, `seekhw`,
`power_mode`, `set_stereo`, `set_soft_mute`, `set_notch_filter`, `set_volume`, `set_force_use`, `set_antenna`, `set_region`,
`set_spacing`, `searchhw`, `search_cancel`, `auto_af`, `slimbus`, plus
`shutdown` (alias `exit`) for process teardown. `set_volume` and `set_force_use` return immediately
without the 300 ms `make_ok()` delay to avoid serial queue lag.

On Qualcomm V4L2, `jump` returns the target accepted by `VIDIOC_S_FREQUENCY`.
`TAVARUA_EVT_TUNE_SUCC` publishes the actual tuned frequency in a `state` patch.

`enable` is strict — exactly seven unique named parameters, any order:

```text
enable freq=<kHz> region=<eu|us|jp|jp_wide> spacing=<50|100|200> \
       stereo=<0|1> soft_mute=<0|1> antenna=<0..255> af=<0|1>
```

Unknown/duplicate keys, bad booleans/spacing/antenna/region, or an initial
frequency outside the region are rejected (`ERR_IVD_CFG`). Other numeric
commands go through `atoi` — invalid text silently becomes zero.

Events: partial `state` patches (frequency, stereo, rmssi, sinr, ps, rt, pi,
country, pty, tp, af, rt_plus — only changed fields, deduplicated in `ctl_server`),
`seek_done` (single hardware seek completion, emitted even when landing on the
same frequency), `search_progress` (`{"type":"search_progress","count":N}`
emitted as unique stations are found during `searchhw`), `search_done` (sorted
kHz array, at most 64 stations), and `disabled`. No `enabled` event is sent; Java treats the `enable` response as completion.
PS/RT text is UTF-8: `rds_parser.cpp` converts raw EBU Latin G0
(EN 50067:1998 Annex E) bytes to UTF-8 before they reach JSON; raw
pre-conversion bytes are kept in `RdsTextPayload::raw_text` because RT+ tag
indices refer to raw characters. Each changed field in `rds.rt_plus` uses its
RT+ content type name as the JSON key, such as `ITEM.TITLE` or `INFO.WEATHER`.
An empty string clears that field. Dummy 0 and reserved 54–55 have no value.
Private classes use distinct keys `PRIVATE.56`, `PRIVATE.57` and `PRIVATE.58`.

All three backends use `make_station_metadata_reset_patch()` to clear published
station metadata (PS, RT, PI, country, PTY, TP, AF and RT+). Helium ordinary seek
completion also sends this reset when the HAL emits only `seek_complete_cb`.

`rds.tp` defaults to false. A station reset clears an active flag with
`false`. S610 reads bit 10 of block B after PI confirmation. Helium publishes
TP directly from bit 0 of byte 4 in each new 12-byte ECC packet, even when
ECC arrives before PS. PS supplies the displayed PI independently. For ECC
variant 0 (byte 7), Helium decodes the country from byte 9 and the PI country
nibble. Qualcomm V4L2 leaves the TP indicator off.

S610 logs the first observed TP, TA and legacy M/S values for a confirmed PI
and subsequent changes with the source block B. Both Qualcomm backends log the
five original PS/RT header bytes for comparison. Helium logs the RT+ item
toggle from callback byte 6 and item running from byte 7. These diagnostics use stderr.

## Vendor Ordering Invariants (do not reorder without device evidence)

- Qualcomm V4L2 enable order: prepare (emphasis/spacing/antenna) → RDS config →
  band → stereo → AF → initial tune → unmute last. The driver rejects
  `RDS_ON` if band is configured first.
- Qualcomm V4L2 bootstrap (`select_v4l2_bootstrap_mode`) supports two modes
  (`V4l2BootstrapMode`): when both `init.svc.fm_dl` and `fm_qsoc_patches` are
  absent it uses `kDirect`; otherwise it uses `kFirmwareThenDirect`
  (pre-clears `hw.fm.init=0`, starts `fm_dl`, and falls back to the direct
  no-firmware V4L2 transport within the same `V4l2QcomBackend` instance if
  `hw.fm.init` times out or `fm_qsoc_patches` exits non-zero, still attempting
  `open("/dev/radio0")`). Legacy firmware setup also performs non-fatal
  post-`STATE=RX` `V4L2_CID_TUNE_POWER_LEVEL=7` and `VIDIOC_G_TUNER` probe steps
  and a bounded (1 s) non-fatal `RADIO_READY` wait after starting the event
  thread.
- Helium normal-power startup deliberately writes low power, then normal power
  — this arms vendor asynchronous event masks (stereo events). Best-effort:
  failure does not abort enable.
- Helium shutdown waits up to 2 s for `disabled_cb`; `main()` then calls
  `_exit()` for Helium because the vendor `fm_hci_t` global destructor would
  cause `std::terminate()`.
- `shutdown`/`exit`, EOF on stdin, and TERM/HUP all reach backend cleanup —
  the process fails closed when the Java owner dies.
- Helium `vendor_set()` holds `state_lock_` across vendor calls and
  `hal_init()` is called under the same non-recursive mutex: a synchronous
  vendor callback that locks state would deadlock. Do not expand this.
- Qualcomm V4L2 `fd_radio_` is atomic (`V4l2BackendBase::close_device()` uses
  atomic exchange so each descriptor is closed at most once). While the event
  thread is running, it closes `fd_radio_` on exit (including `RADIO_DISABLED`);
  `stop_event_thread()` interrupts in-flight `VIDIOC_DQBUF` via non-`SA_RESTART`
  `SIGUSR1` (`interrupt_event_read()`), waits within a bound before joining, and
  blocks reopening if a previous event thread has not stopped.
- Both full-search implementations are sequential scans (tune low → seek up
  → collect); they have no timeout and cap at 64 unique stations.
- Qualcomm V4L2 (`iris`) does not emit ECC (`country`) on verified devices
  (Xiaomi Mi A1, Xiaomi Redmi 4A): `TAVARUA_EVT_NEW_RAW_RDS` never fires for 1A/0A in
  `kV4l2CtrlRdsGroupMask`, and the kernel drops `HCI_EV_EXT_COUNTRY_CODE`.
- Samsung Exynos S610 (`s610-radio`) invariants: `v4l2_frequency.frequency` uses
  `kHz * 16000` (`FAC_VALUE`, not `62.5 Hz` units); `V4L2_CID_AUDIO_MUTE` is
  `0 = mute`, `1 = unmute`; `VIDIOC_S_HW_FREQ_SEEK` returns `-EAGAIN` if
  `O_NONBLOCK` is set (temporarily cleared around seek), runs asynchronously on a
  worker thread for `seekhw` (cancellable via `V4L2_CID_S610_SEEK_CANCEL`), and
  writes `V4L2_CID_S610_SEEK_MODE = 4` (`FM_TUNER_AUTONOMOUS_SEARCH_MODE_NEXT`)
  before each seek; `V4L2_CID_AUDIO_VOLUME`
  (`0..15`) drives hardware gain (`vol_level_init[0..15]` in `radio-s610.c`
  attenuates upstream of PCM capture); RDS uses raw 3-byte blocks
  (`V4L2_CID_S610_RDS_ON = 1` = `FM_RDS_ENABLE` without `RDS_PARSER_ENABLE` bit `0x04`; `5` from stock enables the in-kernel parser and breaks raw 3-byte reads), drops blocks with `errors != 0`, and requires
  2 consecutive hits for `PI`, `PTY`, `PS`, and `country` (suppressing mid-cycle
  dynamic PS splices); `kS610CidSnrCurr` is clamped to `[-128, 127]` before JSON emission.
- On S610 a non-wrapping upward seek returns `EINVAL` at the band limit.
  A full scan completes on this error only when `VIDIOC_G_FREQUENCY` confirms
  the selected region's upper edge. Other `EINVAL` results emit `search_failed`.
- Samsung Exynos S610 verified driver semantics (checked against `radio-s610.c`
  / `fm_low.c` — do not "fix" these to match the V4L2 spec):
  `use_soft_mute` is hardcoded `TRUE` in `fm_low.c`, so `set_soft_mute()` is a
  no-op and `enable()` leaves `V4L2_CID_S610_SOFT_MUTE_COEFF` at its driver
  default (`0x1B16`);
  `s610_radio_g_tuner` hardcodes `rxsubchans = V4L2_TUNER_SUB_RDS` (never
  `SUB_STEREO`) and returns `audmode` as a pure echo of the configured mode
  (`force_mono ? 0 : MODE_MASK_MONO_STEREO`); the pilot-detection call
  (`low_get_most_mode`) is commented out and has no definition anywhere in the
  driver, so no live stereo detection exists through V4L2 —
  `read_stereo_state()` reporting the configured mode is the best available.
  `V4L2_CID_S610_RSSI_CURR` returns the raw unsigned byte and `fm_low.c`
  sign-extends it internally (`(rssi & 0x80) ? rssi - 256 : rssi`), so the
  backend must apply the signed-low-byte cast itself; the seek threshold
  default `156` reads as `-100` in that representation.

## RMSSI

The native command loop polls signal metrics every 2 s while enabled
(suppressed during tune/seek/scan). Both backends attempt SINR
(`kV4l2CtrlIrisSinr`) first and fall back to RMSSI if SINR is unsupported or
fails. Skipped/busy requests do not disable SINR capability or emit clears.
Both backends clear the inactive metric in both directions using
`RADIO_PATCH_CLEAR_METRIC` (`"sinr": null` / `"rmssi": null`), with duplicate
and already-absent clears deduplicated during JSON serialization (`ctl_json.cpp`).
Qualcomm V4L2 reads `VIDIOC_G_TUNER.signal` for RMSSI and waits 750 ms
(`kMetricSettleMs`) after tune/jump before sampling. Decoding is shared in
`rmssi.h` (`decode_rmssi`, signed low byte).

## Tests

`make test-native` (host GoogleTest, no device) covers: RDS parsing
(`rds_parser_test`), S610 group assembly, confirmation and RT+ updates
(`s610_rds_decoder_test`), JSON serialization and state cache (`ctl_json_test`),
request frames (`ctl_protocol_test`), command routing, validation and replies
(`command_router_test`), startup config (`startup_config_test`),
backend allowlist classification (`backend_factory_test`), region profiles, frequency formatting, shared and V4L2 scan state (`v4l2_scan_state_test`),
V4L2 Qcom lifecycle and metric switching (`v4l2_qcom_lifecycle_test`),
Helium shutdown state machine (`hal_shutdown_test`) and metric switching
(`hal_metric_test`) with injected vendor interfaces, RMSSI decoding
(`rmssi_test.cpp`), and polling deadlines (`periodic_deadline_test.cpp`).

Not covered: process transport,
`dlopen`/ABI compatibility, ioctls, event threads, device end-to-end.

## Full Specification

The complete reference — backend internals, locking, RMSSI/SINR
notes, region reverse-engineering, known risks, safe-change guidance — is
`ARCHITECTURE.md` in this directory. Open it only when doing deep protocol or
vendor-logic rework; this file should be sufficient for routine changes.
`README.md` here is a shorter human-oriented overview.
