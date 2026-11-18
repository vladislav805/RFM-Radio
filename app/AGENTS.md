# app/ — Java/Android Auto-Context

Android application of RFM-Radio. Java root:
`app/src/main/java/com/vlad805/fmradio/` (~73 files). `FMService` runs in a
separate `:service` process; the app requires root and a Qualcomm tuner.
Debug application id: `com.vlad805.fmradio.dev`.

## Package Map

| Package | Role |
| --- | --- |
| (root) | `C.java` — all constants (commands, events, pref keys), `Storage` (Tray) |
| `activity/` | `MainActivity` (entry, gates), `FavoriteListsActivity`, `SettingsActivity`, `AboutActivity`, `RecordingDeleteActivity`, `StartupBlockedActivity` |
| `controller/` | `RadioController` (UI→service intents), `FavoriteController`, `RadioState`, `RadioStateUpdater`, `TunerStatus` |
| `service/` | `FMService` — the orchestrator |
| `service/audio/` | `AudioService` (abstract), `RoutingAudioService` (API ≥ 26), `LightAudioService` |
| `service/fm/` | Contract: `IFMController`, `IFMEventListener`, `IFMEventPoller`, `FMEventCallback`, `RadioStatePatch`, `Request`, `Empty`, `RecordError` |
| `service/fm/communication/` | `NativeProcessSession`, `NativeEventParser` |
| `service/fm/implementation/` | `AbstractQualcommNativeController`, `QualcommNative` |
| `service/recording/` | 11 files: WAV/MP3 recording, SRT subtitles, storage |
| `fragments/` | `PreferencesFragment` — settings built in code, no `res/xml` |
| `view/` | `RadioUIView`, `FrequencyBarView`, `FavoritesView`, `SignalStrength`, adapters |
| `models/`, `helper/`, `enums/`, `preferences/` | JSON models, utilities, `TunerDriver` enum, `BandUtils` |

`service/streaming/` is an empty leftover. `app/src/main/cpp/` is the LAME
MP3 encoder (JNI `libmp3encoder.so`), unrelated to the FM backend.

## FMService — Orchestrator

`service/FMService.java` (~940 lines). UI talks to it only via
`startService(Intent)` with `C.Command.*` actions; it replies only with
package-scoped app broadcasts (`C.Event.*`). No binder/AIDL. Returns
`START_NOT_STICKY`: a killed process must not resurrect the service.

Key flows:

- `getTunerDriver()`: `TunerDriverDetector` mirrors native detection — checks
  `/sys/class/video4linux/radio0/name` (`iris`/`tavarua` allowlist, or fallback
  to `/dev/radio0` when the sysfs node is absent/SELinux-unreadable) alongside
  `/dev/radio0` (`V4L2_QCOM`), and vendor paths for `fm_helium.so` (`HAL_HELIUM`);
  both map to `new QualcommNative(context, kind)`, unsupported devices get `Empty`.
- `getAudioService()`: API ≥ 26 → `RoutingAudioService`, else
  `LightAudioService`.
- `ENABLE` starts HAL audio before enabling the tuner; `DISABLE` stops audio,
  disables the tuner, and calls `stopSelf()`.
- `SPEAKER_STATE` toggles the speaker; for `QualcommNative` it also calls
  `refreshAudioRoute()` (Slimbus restart).
- `RECORD_START`/`RECORD_STOP` go through the audio service's
  `IAudioRecordable`.
- `NOTIFICATION_SEEK` seeks via the favorites list or hardware seek.
- Enable chain: `INSTALLED → LAUNCHED → ENABLED`; `ROOT_ACCESS_DENIED` and
  `LAUNCH_FAILED` stop the service.
- Unexpected native exit → `QualcommNative.onNativeProcessExit` →
  `context.stopService(FMService)`.
- `onDestroy()` → `kill()`: stop poll timer, audio, tuner; unregister
  receivers and the Tray preference listener.

## Controller Hierarchy

- `IFMController` — contract: install/launch/prepareBinary/kill/enable/
  disable/setFrequency/jump/hwSeek/hwSearch/cancelHwSearch/setPowerMode/
  applyPreference/setupTunerByPreferences.
- `AbstractQualcommNativeController` — template method: final public methods
  emit lifecycle events and call protected `*Impl` hooks. Owns binary install
  (asset → `/data/data/<pkg>/files/fmbin-<arch>`, chmod 0755, version in
  Tray; debug builds always reinstall) and `sendCommand` via the session.
- `QualcommNative` — the only real driver. `launchImpl`: root probe,
  `killall fmbin-*`, 5 s ready handshake, `init` with 10 s timeout.
  `enableImpl` sends one atomic `enable` command assembled from preferences
  (frequency clamped to `BandUtils` band limits).
  `shouldApplyStartupPreferences()` returns false (everything is in `enable`).
- `Empty` — no-op driver for unsupported devices; emits the full event chain.
- `Request` — command object; default timeout 1000 ms, per-command overrides
  (init 10 s, enable/disable 5 s, seekhw 4 s, jump 1.5 s, searchhw 60 s,
  slimbus 2 s).

## Communication Layer

`NativeProcessSession` owns the root process: `su` + `exec fmbin`, ready
handshake `{"type":"ready","protocol":1,"pid":N,"backend":"..."}` (updating
`QualcommNative`/`FMService` driver kind when sysfs is hidden by SELinux), serial command queue
(`<id>\t<command>\n`; `\t`, `\r`, `\n` are forbidden inside a command),
timeout scheduler, and stop escalation (`shutdown` → stdin close → TERM →
KILL by handshake PID). Native stderr is drained to logcat tag `RFM-QCOM`;
stdout is protocol JSON only. `NativeEventParser` validates `state` patches
(frequency > 0, rmssi in [-128, 127], country `[A-Z]{2}`, pty ≥ 0, AF ≥ 0,
PS/RT trimmed, newlines → spaces) and dispatches `search_done`; `enabled` is
parsed, `disabled` is deliberately ignored (Java lifecycle is authoritative).

## Audio

- `RoutingAudioService` (API ≥ 26, HAL devices): audio is routed by the audio
  HAL; device switching via `AudioManager.setParameters`
  (`fm_mute`, `fm_routing`, `fm_volume`) plus hidden `AudioSystem` constants
  through reflection. Recording opens a separate `AudioRecord` (48 kHz
  stereo) with `setPreferredDevice(TYPE_FM_TUNER)`.
- `LightAudioService` (API < 26, legacy): `AudioRecord` (source 1998) →
  `AudioTrack` bridge loop at 44.1 kHz.
- Both implement `IAudioRecordable` (start/stop record, `updateRds`) and feed
  the 5-second pre-roll ring buffer in `PcmRecorderSession`.
- `AudioService.requestForFocus` is the only AudioFocus point
  (`GAIN_TRANSIENT_MAY_DUCK` on `STREAM_MUSIC`).

## Recording

- `FMService.getPreferredRecorder()`: `recording_mode` pref →
  `RecordRawService` (WAV) or `RecordLameService` (MP3 192/128 via
  `LameMp3Encoder` JNI).
- `PcmRecorderSession`: PCM ring buffer (5 s pre-roll) plus an RDS-change
  history bound to PCM positions; writer thread with a bounded queue
  (backpressure).
- `RecordService` (abstract): IDLE/RECORDING/FINISHING/DONE state machine,
  temp file in `cacheDir/recordings`, publication via `RecordingStorage`
  (MediaStore on API ≥ 29, direct file below), final `RECORD_ENDED` broadcast.
- SRT subtitles (pref `recording_save_srt`, off by default): `RadioStateUpdater`
  → `updateRds(RdsSnapshot)` → `PcmRecorderSession` →
  `RecordService.recordRds` → `RdsSubtitleWriter` (one cue per snapshot
  change, PCM-relative times). `RdsSnapshot` is an immutable
  {frequencyKhz, ps, rt, pi, rtPlusTitle, rtPlusArtist} value; deletion via notification also removes
  the SRT file.
- WAV: 44-byte header patched on stop. MP3: manual ID3v2.3 tag.

## Favorites

- JSON files at `getExternalFilesDir()/favorites/<name>.json`; the current
  list name lives in Tray. No SQLite.
- `FavoriteController` (extends `JSONFile<FavoriteFile>`): rename/remove
  (never for `default`), import (JSON/ZIP, 1 MB limit, name sanitizing),
  export (single list or ZIP).
- A full hardware search result (`HW_SEARCH_DONE`) replaces the current list.

## UI and Settings

- `MainActivity` gates startup: `su` present and tuner supported, else
  `StartupBlockedActivity`. UI↔service only via `RadioController` intents and
  app broadcasts wrapped by `RadioStateUpdater`.
- Settings (`PreferencesFragment`, programmatic): `tuner_region` (1=EU, 2=JP,
  3=JP wide, 4=US), `tuner_spacing` (1=50, 2=100, 3=200 kHz), `tuner_stereo`,
  `tuner_soft_mute`, `tuner_antenna`, `tuner_power_mode`, `rds_auto_af`,
  `recording_directory`/`recording_filename` (templates `$f $y $m $d $h $i $s
  $r`), `recording_mode`, `recording_save_srt`, `recording_save_past`,
  `recording_show_notify`, `app_auto_startup`, `notification_show_ps`,
  `notification_seek_by_favorites`. Preference store: Tray (`Storage.java`);
  changes reach `FMService` via the Tray listener → `setupTunerByPreferences`.

## Build, Tests, Dead Code

- `app/build.gradle`: minSdk 23, targetSdk 24, ABI filters armeabi-v7a and
  arm64-v8a, NDK 29.0.14033849. `preBuild` runs `native/build.sh` and then
  verifies the `fmbin-*` assets exist.
- JVM tests (`make test-java`): RadioState, SignalStrength, Request,
  PcmRecorderSession, RdsSubtitleWriter, RecordingStorage.
- Dead code: `service/streaming/` (empty), `enums/MuteState`,
  `view/IntEditTextPreference`, `C.RecordFormat.AAC_128` (selecting it
  returns a null recorder — unreachable from UI), the poll timer
  (`PollTunerHandler`/`IFMEventPoller` — no current driver implements it),
  `C.Event.UPDATE_AF` (never sent).
- `RecordService.record()` on IOException silently goes IDLE, leaving the
  file unfinished. `FavoriteStation`'s JSON constructor defaults the
  frequency to 108100 (outside the EU band).

## Critical Android Bridge Traps

- **Success is a string-prefix `"ok"` match on `data`** — the JSON `ok` field
  is ignored (`QualcommNative` line ~209). Do not rely on `ok`.
- **Base-class `enableImpl` is dead code**: `AbstractQualcommNativeController`
  sends a bare `enable` with no parameters, which the strict native parser
  always rejects. Only `QualcommNative.enableImpl` (the atomic
  seven-parameter command) is live.
- **Duplicate-tune suppression**: HAL `setFrequencyImpl` drops repeated tunes
  within 1500 ms (`DUPLICATE_TUNE_WINDOW_MS`).
- **Recorder sample rate is backend-dependent**: 48000 Hz on HAL, 44100 Hz on
  legacy (`QualcommNative.getRecorderSampleRate()`); it flows into WAV
  headers, MP3 encoding, and recorded durations.
- **Legacy RMSSI settle delay**: the native legacy backend waits 750 ms
  (`kMetricSettleMs`) after tune/jump before sampling RMSSI; do not "fix"
  Java-side timing to compensate.

Deep reference: `ARCHITECTURE.md` (same directory) — full package map,
per-class details, notification ids, SRT data flow, settings table,
dead-code inventory.
