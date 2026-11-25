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
  `-EAGAIN` if `O_NONBLOCK` is set on the fd (and converts `seek->spacing`
  from Hz to kHz itself); `perform_hw_seek()` writes
  `V4L2_CID_S610_SEEK_MODE = 4` for stock-HAL parity — `s_ctrl` maps `4` to
  `FM_TUNER_AUTONOMOUS_SEARCH_MODE_NEXT` (= 10) but only records the state for
  G_CTRL readback; the tuner mode is programmed by the seek ioctl itself.
  `O_NONBLOCK` is temporarily cleared for each seek and restored afterwards.
  Both `seek()` and `search()` run on the cancellable worker thread
  (`search_thread_`) so long hardware seeks do not stall the command loop;
  `cancel_search()` writes `V4L2_CID_S610_SEEK_CANCEL`, which the kernel
  checks without taking `s610_core_lock`.
- **Soft mute is always enabled in hardware**: `fm_low.c` hardcodes
  `use_soft_mute = TRUE` (the toggle is commented out), and
  `V4L2_CID_S610_SOFT_MUTE_COEFF` writes `mute_coeffs_soft` (default `0x1B16`).
  `enable()` leaves the driver default untouched and `set_soft_mute()` is a
  no-op.
- **No live stereo detection (`VIDIOC_G_TUNER.audmode` echoes configuration)**:
  `s610_radio_g_tuner` hardcodes `tuner->rxsubchans = V4L2_TUNER_SUB_RDS` and
  never sets `V4L2_TUNER_SUB_STEREO`; `tuner->audmode` is computed as
  `force_mono ? 0 : MODE_MASK_MONO_STEREO` from the mode written via
  `VIDIOC_S_TUNER` — the pilot-detection call (`low_get_most_mode`) is
  commented out and undefined in this driver generation. `read_stereo_state()`
  therefore reports the configured mode; switching it to `rxsubchans` (the
  V4L2-spec-correct field) would report permanent mono. Reading the chip's
  status register directly would require `VIDIOC_DBG_G_REGISTER`
  (`CONFIG_VIDEO_ADV_DEBUG`).
- **Signed `V4L2_CID_S610_RSSI_CURR`**: G_CTRL returns the raw unsigned byte;
  `fm_low.c` interprets it as signed 8-bit internally
  (`(rssi & 0x80) ? rssi - 256 : rssi`), so `request_signal_metric()` must
  apply the signed-low-byte cast itself; the seek threshold default `156`
  (`kS610DefaultRssiThreshold`) reads as `-100` in that representation.
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
  - Group 3A (`AID = 0x4BD7`) identifies the ODA group carrying the two
    RT+ tags. The backend passes them and the last complete RadioText to
    `RtPlusContext::extract_slices()` to extract title, artist and album.

## Logs

Internal backend logs use the `exynos/` prefix under logcat tag `RFM-QCOM`.

## References

- [LineageOS/android_hardware_samsung_slsi_fm](https://github.com/LineageOS/android_hardware_samsung_slsi_fm) — Samsung SLSI FM HAL (`FmRadioController_slsi.cpp`)
