# native/ — C++ FM Backend Auto-Context

This directory builds one root executable `fmbin` (C++20, CMake, Android NDK,
minimum platform 21). It is NOT a JNI library: the app copies an ABI-specific
binary (`fmbin-armv7a` / `fmbin-aarch64`) from assets into its private files
directory, starts it through `su`, and owns it over stdin/stdout. `app/src/
main/cpp` is a different, unrelated native component (LAME MP3 encoder).

## Layout

| Path | Role |
| --- | --- |
| `main.cpp` | Entrypoint, command router, HAL `_exit()` bypass |
| `backend.h` | Common `Backend` base interface for all implementations |
| `backend_log.h` | Centralized logging utility `backend_log()` |
| `backend_factory.cpp` | Runtime backend detection and process-global singleton |
| `backend/v4l2_base/` | Abstract V4L2 base class (`V4l2BackendBase`) |
| `backend/v4l2_qcom/` | Qualcomm V4L2 backend (`v4l2_qcom_backend.cpp`, `v4l2_scan_state.cpp`, `v4l2_qcom_types.h`) |
| `backend/hal_helium/` | Qualcomm Helium backend (`hal_helium_backend.cpp`, `hal_helium_types.h`) |
| `backend/qcom_fm_controls.h` | Shared Qualcomm V4L2 control IDs |
| `hex_dump.cpp` / `.h` | Shared hex dump string formatting utility |
| `ctl_server.cpp` | stdio command loop, async event writer, state cache |
| `ctl_protocol.cpp` | Strict request-id frame parser |
| `ctl_json.cpp` | JSON serialization and state-patch deduplication |
| `startup_config.cpp` | Strict parser for the complete `enable` command |
| `region_profile.cpp` | Region limits and vendor band mappings |
| `rds_parser.cpp` | Shared PS/RT/AF/ECC/station-list payload parsers |
| `scan_result.cpp` | Bounded, deduplicated, sorted scan results |
| `rmssi.h` | `decode_rmssi()` — signed-low-byte RMSSI decoding, shared |
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

Selection is process-global, lazy (first command wins), with no fallback:

1. `/sys/class/video4linux/radio0/name` contains `iris` or `tavarua` (case-insensitive) with `/dev/radio0` present, or sysfs name node is absent while `/dev/radio0` exists → `kV4l2Qcom`; other names (such as `s610` or `si4709`) or empty name fail V4L2 matching and fall through
2. else `fm_helium.so` in known vendor/system/odm locations → `kHelium` (`dlopen`)
3. else no backend → `ERR_UNSUPPORTED`

Regions: `eu`/`us` 87500–108000 kHz, `jp` 76000–95000, `jp_wide`
76000–108000. V4L2 frequency units are 62.5 Hz; the protocol carries kHz.

## Protocol

Transport: stdin frames `<request-id>\t<command>\n` (UTF-8, trailing `\r`
tolerated, frames over 4096 bytes terminate the loop); stdout carries one
JSON object per line; stderr is diagnostics → logcat tag `RFM-QCOM`. Ready
handshake: `{"type":"ready","protocol":1,"pid":N,"backend":"v4l2_qcom"|"hal_helium"|"none"}`. Responses:
`{"type":"response","id":N,"ok":true,"data":"ok"}`. `make_ok()` delays the
success response by 300 ms intentionally — do not remove it. Java matches
success by a `"ok"` prefix on `data`, not by the `ok` field.

Commands: `init`, `enable`, `disable`, `setfreq`, `jump`, `seekhw`,
`power_mode`, `set_stereo`, `set_soft_mute`, `set_notch_filter`, `set_antenna`, `set_region`,
`set_spacing`, `searchhw`, `search_cancel`, `auto_af`, `slimbus`, plus
`shutdown` (alias `exit`) for process teardown.

`enable` is strict — exactly seven unique named parameters, any order:

```text
enable freq=<kHz> region=<eu|us|jp|jp_wide> spacing=<50|100|200> \
       stereo=<0|1> soft_mute=<0|1> antenna=<0..255> af=<0|1>
```

Unknown/duplicate keys, bad booleans/spacing/antenna/region, or an initial
frequency outside the region are rejected (`ERR_IVD_CFG`). Other numeric
commands go through `atoi` — invalid text silently becomes zero.

Events: partial `state` patches (frequency, stereo, rmssi, sinr, ps, rt, pi,
country, pty, af, rt_plus — only changed fields, deduplicated in `ctl_server`),
`seek_done` (single hardware seek completion, emitted even when landing on the
same frequency), `search_done` (sorted kHz array, at most 64 stations), and
`disabled`. No `enabled` event is sent; Java treats the `enable` response as completion.
PS/RT text is UTF-8: `rds_parser.cpp` converts raw EBU Latin G0
(EN 50067:1998 Annex E) bytes to UTF-8 before they reach JSON; raw
pre-conversion bytes are kept in `RdsTextPayload::raw_text` because RT+ tag
indices refer to raw characters.

## Vendor Ordering Invariants (do not reorder without device evidence)

- Qualcomm V4L2 enable order: prepare (emphasis/spacing/antenna) → RDS config →
  band → stereo → AF → initial tune → unmute last. The driver rejects
  `RDS_ON` if band is configured first.
- Qualcomm V4L2 has two bootstrap modes: firmware path (`fm_dl`, `hw.fm.init`) and,
  when both `init.svc.fm_dl` and `fm_qsoc_patches` are absent, a direct
  no-firmware V4L2 path.
- Helium normal-power startup deliberately writes low power, then normal power
  — this arms vendor asynchronous event masks (stereo events). Best-effort:
  failure does not abort enable.
- Helium shutdown waits up to 2 s for `disabled_cb`; `main()` then calls
  `_exit()` for Helium because the vendor `fm_hci_t` global destructor would
  cause `std::terminate()`.
- `shutdown`/`exit`, EOF on stdin, and TERM/HUP all reach backend cleanup —
  the process fails closed when the Java owner dies.
- Helium `vendor_set()` holds `state_.lock` across vendor calls and
  `hal_init()` is called under the same non-recursive mutex: a synchronous
  vendor callback that locks state would deadlock. Do not expand this.
- Qualcomm V4L2 globals (`fd_radio`, `fm_storage`, …) are shared between the
  command thread and the event thread without a mutex — known race; the
  event thread closes the fd on `RADIO_DISABLED`.
- Both full-search implementations are sequential scans (tune low → seek up
  → collect); they have no timeout and cap at 64 unique stations.
- Qualcomm V4L2 (`iris`) does not emit ECC (`country`) on verified devices
  (Xiaomi Mi A1, Xiaomi Redmi 4A): `TAVARUA_EVT_NEW_RAW_RDS` never fires for 1A/0A in
  `kV4l2CtrlRdsGroupMask`, and the kernel drops `HCI_EV_EXT_COUNTRY_CODE`.

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
(`rds_parser_test`), JSON serialization and state cache (`ctl_json_test`),
request frames (`ctl_protocol_test`), startup config (`startup_config_test`),
backend allowlist classification (`backend_factory_test`), region profiles, frequency formatting, shared and V4L2 scan state (`v4l2_scan_state_test`),
V4L2 Qcom lifecycle and metric switching (`v4l2_qcom_lifecycle_test`),
Helium shutdown state machine (`hal_shutdown_test`) and metric switching
(`hal_metric_test`) with injected vendor interfaces, RMSSI decoding
(`rmssi_test.cpp`), and polling deadlines (`periodic_deadline_test.cpp`).

Not covered: `main.cpp` routing, process transport,
`dlopen`/ABI compatibility, ioctls, event threads, device end-to-end.

## Full Specification

The complete reference — backend internals, locking, RMSSI/SINR
notes, region reverse-engineering, known risks, safe-change guidance — is
`ARCHITECTURE.md` in this directory. Open it only when doing deep protocol or
vendor-logic rework; this file should be sufficient for routine changes.
`README.md` here is a shorter human-oriented overview.
