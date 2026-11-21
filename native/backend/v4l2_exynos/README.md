# Samsung Exynos S610 V4L2 FM Backend

This directory contains the Samsung Exynos FM implementation (`s610-radio`) used
on Exynos SoCs (tested on Galaxy A51 / Exynos 9611) that expose `/dev/radio0`
driven by Samsung's `s610` kernel driver (`drivers/media/radio/s610/`).

It is compiled into the unified `fmbin` executable from [`native`](../..) and
selected through [`backend_factory.cpp`](../../backend_factory.cpp)
(`V4l2ExynosBackend` via `create_v4l2_exynos_backend()`) when
`/sys/class/video4linux/radio0/name` contains `"s610"`.

## What This Backend Talks To

The Exynos backend communicates directly with:

- `/dev/radio0` (standard V4L2 radio node registered by `s610-radio`)
- Standard V4L2 controls (`V4L2_CID_AUDIO_VOLUME`, `V4L2_CID_AUDIO_MUTE`)
- S610 custom V4L2 controls (`V4L2_CID_S610_*` starting at `0x00981970`)
- Raw 3-byte RDS blocks read via `poll()` + `read()` on `/dev/radio0`

Audio routing and capture run separately in Android's audio HAL through
`RoutingAudioService` (`g_fmradio_mute`, `g_fmradio_enable`, `fmradio_recoding`).

## Important Files

- [`s610_fm_controls.h`](s610_fm_controls.h): S610 V4L2 control IDs,
  frequency/mute constants, and raw 3-byte RDS status bitmasks derived from
  Samsung's `radio-s610.h` / `radio-s610.c`
- [`v4l2_exynos_backend.h`](v4l2_exynos_backend.h) /
  [`v4l2_exynos_backend.cpp`](v4l2_exynos_backend.cpp): `V4l2ExynosBackend`
  implementation (lifecycle, tuning, `O_NONBLOCK`-safe hardware seek/scan,
  hardware volume control, RSSI/SNR polling, raw RDS + RT+ decoding)
- [`../v4l2_base/v4l2_backend_base.h`](../v4l2_base/v4l2_backend_base.h):
  shared V4L2 helpers (`open_device`, `close_device`, `set_v4l2_ctrl`,
  `get_v4l2_ctrl`)

## Driver Invariants and Quirk Workarounds

- **Frequency units (`FAC_VALUE = 16000`)**: `s610` expects
  `v4l2_frequency.frequency = freq_khz * 16000`, not `62.5 Hz` (`kHz * 16`)
  used by Qualcomm's V4L2 driver.
- **Mute polarity**: `V4L2_CID_AUDIO_MUTE` uses `0 = mute` and `1 = unmute`.
- **Hardware volume (`0..15`)**: `V4L2_CID_AUDIO_VOLUME` indexes the driver's
  16-step `vol_level_init[0..15]` curve (`{0, 16, 23, 32, 45, 64, 90, 128,
  181, 256, 362, 512, 724, 1024, 1447, 2047}` in `radio-s610.c`), driven from
  `RoutingAudioService` via `set_volume` (fast-path command without the 300 ms
  `make_ok()` pacing delay). Because chip attenuation happens *before* `ABOX`
  PCM capture, `RoutingAudioService.compensateExynosVolume()` applies the
  inverse scale factor during recording.
- **Blocking `VIDIOC_S_HW_FREQ_SEEK`**: `s610_radio_s_hw_freq_seek()` returns
  `-EAGAIN` if `O_NONBLOCK` is set on the fd; `perform_hw_seek()` temporarily
  clears `O_NONBLOCK` for each seek and restores it afterwards. `cancel_search()`
  writes `V4L2_CID_S610_SEEK_CANCEL`, which the kernel checks without taking
  `s610_core_lock`.
- **RDS state resets and stabilization**:
  - `V4L2_CID_S610_RDS_ON` is a bitmask in `radio-s610.c` (`FM_RDS_ENABLE = 1`,
    `RDS_PARSER_ENABLE = 4`): `fmbin` writes `1` (raw 3-byte block stream,
    in-kernel parser off) and disables with `0`. Stock `com.sec.android.app.fm`
    writes `5` (`1 | 4`) for the in-kernel parser, which breaks raw reads.
  - `drain_rds_fifo()` flushes stale kernel FIFO blocks on enable/tune/seek
    (checking `poll(0)` first so `read()` never blocks).
  - Any 3-byte block with `errors != 0` aborts the in-flight group.
  - Group 0 PS and Group 2 RT clear on tune/seek/A-B flag toggle and commit
    only after full segment sets; `PI`, `PTY`, `PS`, and `country` (ECC) require
    two consecutive matching readings (`*_hits >= 2`) so stale/stray blocks and
    mid-cycle dynamic PS splices (e.g. `"106.RD"` before `"106.3"`) are suppressed.
  - Group 3A (`AID = 0x4BD7`) + ODA group decoding extracts RT+ tags via the
    shared `evaluate_rds_rt_plus_slices()` helper in `rds_parser.cpp`.

## Logs

Internal backend logs use the `exynos/` prefix under logcat tag `RFM-QCOM`.
