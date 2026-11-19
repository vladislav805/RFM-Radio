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
| `service/` | `FMService` — the orchestrator; `FMNotificationManager` — notification channels and playback/recording presentation |
| `service/audio/` | `AudioOutput`, `AudioSelection`, `AudioLifecycle`, `RoutedAudioPolicy`, `QualcommRoutedAudio`, `AudioService` (abstract), `RoutingAudioService` (API ≥ 26), `LightAudioService` |
| `service/fm/` | Contract: `FMEventCallback`, `RadioStatePatch`, `Request`, `RecordError` |
| `service/fm/communication/` | `NativeProcessSession`, `NativeEventParser` |
| `service/fm/implementation/` | `NativeTunerController`, `TunerCommandFormat` |
| `service/recording/` | 12 files: `PcmFormat`, WAV/MP3 recording, SRT subtitles, storage |
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
  both map to `new NativeTunerController(context, kind)`, unsupported devices get `null` (and fail launch).
- Audio selection: resolved once per session on `LAUNCHED` via
  `AudioSelection.resolve(resolvedDriver, SDK_INT)` and coordinated by
  `AudioLifecycle` (`HAL_HELIUM` starts audio before `ENABLE` and stops before
  `DISABLE`; `V4L2_QCOM` starts on `ENABLED` and stops on `DISABLED`/teardown;
  `NONE` creates no hardware audio). Pre-roll preference and current RDS
  snapshot are applied at attachment.
- `ENABLE`/`DISABLE` delegate audio ordering to `AudioLifecycle`; `DISABLE`
  disables the tuner and calls `stopSelf()`.
- `SPEAKER_STATE` toggles `AudioOutput` (`HEADPHONES`/`SPEAKER`) through
  `AudioService` (`getOutput()` / `setOutput()`); route-specific side effects
  (`FOR_MEDIA` on V4L2, `refreshAudioRoute()` Slimbus restart on Helium) live
  in the selected audio path/policy.
- `RECORD_START`/`RECORD_STOP` go through the audio service's
  `IAudioRecordable`.
- `NOTIFICATION_SEEK` seeks via the favorites list or hardware seek.
- Enable chain: `INSTALLED → LAUNCHED → ENABLED`; `ROOT_ACCESS_DENIED` and
  `LAUNCH_FAILED` stop the service.
- Unexpected native exit → `NativeTunerController` exit handler →
  `context.stopService(FMService)`.
- `onDestroy()` → `kill()`: stop audio and tuner; unregister
  receivers and the Tray preference listener.

## Controller Hierarchy

- `NativeTunerController` (`final`) — the only tuner controller. Owns binary install
  (asset → `/data/data/<pkg>/files/fmbin-<arch>`, chmod 0755, version in
  Tray; debug builds always reinstall) and `sendCommand` via the session.
  `launch()`: root probe, `killall fmbin-*`, 5 s ready handshake, `init` with
  10 s timeout. `enable()` sends one atomic `enable` command formatted by
  `TunerCommandFormat` from preferences (frequency clamped to `BandUtils` band
  limits) plus any non-default `set_notch_filter`.
- `TunerCommandFormat` — package-private command formatter for `regionName`,
  `spacingKhz`, and the atomic `enable` command.
- `Request` — command object; default timeout 1000 ms, per-command overrides
  (init 10 s, enable/disable 5 s, seekhw 4 s, jump 1.5 s, searchhw 60 s,
  slimbus 2 s).

## Communication Layer

`NativeProcessSession` owns the root process: `su` + `exec fmbin`, ready
handshake `{"type":"ready","protocol":1,"pid":N,"backend":"..."}` (updating
`NativeTunerController`/`FMService` driver kind when sysfs is hidden by SELinux), serial command queue
(`<id>\t<command>\n`; `\t`, `\r`, `\n` are forbidden inside a command),
timeout scheduler, and stop escalation (`shutdown` → stdin close → TERM →
KILL by handshake PID). Native stderr is drained to logcat tag `RFM-QCOM`;
stdout is protocol JSON only. `NativeEventParser` validates `state` patches
(frequency > 0, rmssi in [-128, 127], country `[A-Z]{2}`, pty ≥ 0, AF ≥ 0,
PS/RT trimmed, newlines → spaces) and dispatches `search_done`; `enabled` is
parsed, `disabled` is deliberately ignored (Java lifecycle is authoritative).

## Audio

- `RoutingAudioService` (API ≥ 26): owns audio focus, volume broadcast
  listener, and FM PCM capture (`AudioRecord` at 48 kHz stereo with
  `setPreferredDevice(TYPE_FM_TUNER)`). Delegates vendor output routing to
  `RoutedAudioPolicy` (`QualcommRoutedAudio`: `fm_status`, `fm_mute`,
  `fm_routing`, `handle_fm`, `fm_volume`, hidden `AudioSystem` masks,
  V4L2 `FOR_MEDIA`, Helium-only Slimbus refresh).
- `LightAudioService` (API < 26, legacy): `AudioRecord` (source 1998) →
  `AudioTrack` bridge loop at 44.1 kHz, retaining `FOR_MEDIA` speaker routing.
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
  FMNotificationManager, TunerCommandFormat, TunerDriverDetector,
  AudioSelection, AudioLifecycle, QualcommRoutedAudio, PcmFormat,
  PcmRecorderSession, RdsSubtitleWriter, RecordingStorage.
- Dead code: `service/streaming/` (empty), `enums/MuteState`,
  `view/IntEditTextPreference`, `C.RecordFormat.AAC_128` (selecting it
  returns a null recorder — unreachable from UI),
  `C.Event.UPDATE_AF` (never sent).
- `RecordService.record()` on IOException silently goes IDLE, leaving the
  file unfinished. `FavoriteStation`'s JSON constructor defaults the
  frequency to 108100 (outside the EU band).

## Critical Android Bridge Traps

- **Success is a string-prefix `"ok"` match on `data`** — the JSON `ok` field
  is ignored (`NativeTunerController.isOkResponse`). Do not rely on `ok`.
- **Duplicate-tune suppression**: HAL `setFrequency` drops repeated tunes
  within 1500 ms (`DUPLICATE_TUNE_WINDOW_MS`).
- **Recorder sample rate comes from the PCM source**: `AudioService.getPcmFormat()`
  returns `PcmFormat.PCM16_STEREO_48000` on `RoutingAudioService` (including
  routed V4L2 on API ≥ 26) and `PcmFormat.PCM16_STEREO_44100` on
  `LightAudioService`; it drives capture validation, pre-roll, WAV headers,
  MP3 encoding, and recorded durations.
- **Legacy RMSSI settle delay**: the native legacy backend waits 750 ms
  (`kMetricSettleMs`) after tune/jump before sampling RMSSI; do not "fix"
  Java-side timing to compensate.

Deep reference: `ARCHITECTURE.md` (same directory) — full package map,
per-class details, notification ids, SRT data flow, settings table,
dead-code inventory.
