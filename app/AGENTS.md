# app/ — Java/Android Auto-Context

Android application of RFM-Radio. Java root:
`app/src/main/java/com/vlad805/fmradio/` (~75 files). `FMService` runs in the
main app process (single-process mode); the app requires root and a supported
FM tuner.
Debug application id: `com.vlad805.fmradio.dev`.

## Package Map

| Package | Role |
| --- | --- |
| (root) | `C.java` — `RecordFormat` and `Config` constants; `Storage` — typed `SharedPreferences` wrapper, `Storage.Key`, defaults |
| `activity/` | `MainActivity` (entry, gates), `SettingsActivity`, `AboutActivity`, `RecordingDeleteActivity`, `StartupBlockedActivity` |
| `controller/` | `RadioServiceController` (local binder contract), `RadioClient` (`ServiceConnection` wrapper + command queue), `StateListener`, `EventListener`, `FavoriteController` (in-process singleton), `RadioState` (pure reducers + `SET_*` masks), `TunerStatus` |
| `service/` | `FMService` — the orchestrator (`LocalBinder` + external `ACTION_*` entry points); `FMNotificationManager` — notification channels and playback/recording presentation |
| `service/audio/` | `AudioOutput`, `AudioSelection`, `AudioLifecycle`, `RoutedAudioPolicy`, `QualcommRoutedAudio`, `SamsungS610Audio`, `AudioService` (abstract), `RoutingAudioService` (API ≥ 26), `LightAudioService` |
| `service/fm/` | Contract: `FMEventCallback` (typed), `RadioStatePatch`, `RecordError` |
| `service/fm/communication/` | `NativeProcessSession`, `NativeEventParser`, `Request` |
| `service/fm/implementation/` | `NativeTunerController`, `TunerCommandFormat` |
| `service/recording/` | `PcmFormat`, `RecordingListener`, `RecordingResult`, WAV/MP3/Opus recording, SRT subtitles, storage |
| `fragments/` | `PreferencesFragment` — declarative `res/xml/preferences.xml` + backend capability gating |
| `view/` | `RadioUIView`, `FrequencyDialView`, `FavoritesView`, `SignalStrength`, adapters |
| `models/`, `helper/`, `enums/`, `preferences/` | JSON models, utilities, `TunerDriver` enum (with backend capability flags), `BandUtils` |

`service/streaming/` is an empty leftover. `app/src/main/cpp/` builds the JNI recording encoders `libmp3encoder.so` (LAME) and `libopusencoder.so` (libopus and libopusenc).

## FMService — Orchestrator

`service/FMService.java`. Runs in-process with hybrid `Started + Bound`
lifecycle (`START_NOT_STICKY`). UI talks to it directly via `FMService.LocalBinder`
(`RadioServiceController`) wrapped by `RadioClient`; external OS entry points
(`FMNotificationManager` `PendingIntent`s and `CallReceiver`) use
`FMService.ACTION_START`, `ACTION_STOP`, `ACTION_RECORD_START`,
`ACTION_RECORD_STOP`, and `ACTION_NOTIFICATION_SEEK` (`EXTRA_SEEK_DIRECTION`).
`FMService.isTunerActive()` exposes a `volatile boolean` reflecting an active
tuner status (not `IDLE`, `ROOT_ACCESS_DENIED`, or `FATAL_ERROR`) so
`CallReceiver` avoids starting an idle or failed service.

Key flows:

- **Single source of truth (`mState`)**: `FMService` owns the sole `RadioState`
  instance (initialized with `Storage.getLastFrequency()` in `onCreate()`),
  mutated strictly on the main looper (`runOnMainThread(...)`). All
  `FMEventCallback` and `RecordingListener` callbacks marshal onto the main
  thread before reducing `mState` via `applyStatus` / `applyFrequency` /
  `applyPatch` / `applyRecording*` / `applyForceSpeaker` and running side
  effects in `onInternalStateChanged(mode)`. `addStateListener()` delivers an
  immediate synchronous `RadioState.INITIAL_SNAPSHOT_MASK` (`SET_INITIAL`).
- **Enable chain**: `enable()` is idempotent (ignores `INSTALLING`..`ENABLED`),
  promotes the service via `startService(ACTION_START)`, and runs
  `prepareBinary() → INSTALLED → launch() → LAUNCHED → attachAudioService() +
  beforeEnable() + enable() → ENABLED → onEnabled() + startForeground()`.
  `ROOT_ACCESS_DENIED` and `FATAL_ERROR` clean up audio/tuner and call
  `stopSelf()`.
- **Playback session teardown (`stopPlaybackSession()`)**: separated from
  `onDestroy()` so calling `disable()` while `MainActivity` stays bound does
  not leave a zombie `fmbin`. Stops recording and audio (clearing
  `mAudioService`/`mAudioLifecycle`), disables and terminates `fmbin`
  (`mTunerDriver.disable()` / `kill()`), resets `mState` to `TunerStatus.IDLE`
  (clearing RDS/signal while keeping last frequency), calls
  `stopForeground(true)` and `stopSelf()`, while keeping `Storage` and
  `FavoriteController` listeners registered until `onDestroy()`.
- `getTunerDriver()`: `TunerDriverDetector` mirrors native detection — checks
  `/sys/class/video4linux/radio0/name` (`iris`/`tavarua` → `V4L2_QCOM`,
  `s610` → `V4L2_EXYNOS`, or fallback to `/dev/radio0` when sysfs is
  absent/SELinux-unreadable) alongside vendor paths for `fm_helium.so`
  (`HAL_HELIUM`); supported drivers map to `new NativeTunerController(context, kind)`.
- Audio selection: resolved once per session on `LAUNCHED` via
  `AudioSelection.resolve(resolvedDriver, SDK_INT)` and coordinated by
  `AudioLifecycle` (`HAL_HELIUM` starts audio before `ENABLE` and stops before
  `DISABLE`; `V4L2_QCOM` starts on `ENABLED` and stops on `DISABLED`/teardown;
  `NONE` creates no hardware audio). Pre-roll preference and current RDS
  snapshot are applied at attachment.
- `toggleSpeaker()` switches `AudioOutput` (`HEADPHONES`/`SPEAKER`) through
  `AudioService` (`getOutput()` / `setOutput()`) and updates `mState`
  (`SET_SPEAKER`); route-specific side effects (`FOR_MEDIA` on V4L2,
  `refreshAudioRoute()` Slimbus restart on Helium) live in the selected audio
  path/policy.
- Unexpected native exit → `FMEventCallback.onUnexpectedExit(exitCode)` →
  marshaled to main thread in `FMService` → `EventListener.onTunerError(...)` +
  `stopPlaybackSession(false)`.
- `onDestroy()`: tears down any active playback session, ensures
  `mTunerDriver.kill()`, unregisters `Storage` and `FavoriteController`
  listeners, clears `StateListener`/`EventListener` sets.

## Controller Hierarchy

- `NativeTunerController` (`final`) — the only tuner controller. Owns binary
  install (asset → `/data/data/<pkg>/files/fmbin-<arch>`, chmod 0755, version
  and APK `lastUpdateTime` in `SharedPreferences`; debug builds and mismatched
  APK update times reinstall) and `sendCommand` via the session. Emits all
  lifecycle, frequency, patch, search, antenna, error, and exit notifications
  strictly through typed `FMEventCallback` (no broadcasts, no `stopService`).
  `launch()`: root probe, stop any previous session, `killall fmbin-*`, 5 s
  ready handshake, `init` with 10 s timeout. `enable()` sends one atomic
  `enable` command formatted by `TunerCommandFormat` from preferences
  (frequency clamped to `BandUtils` band limits) plus any non-default
  `set_notch_filter`. `disable()` sends `disable` (5 s timeout) and then stops
  the `NativeProcessSession` so no root `fmbin` lingers.
- `TunerCommandFormat` — package-private command formatter for `regionName`,
  `spacingKhz`, and the atomic `enable` command.
- `Request` — command object; default timeout 1000 ms, per-command overrides
  (init 10 s, enable/disable 5 s, seekhw 4 s, jump 1.5 s, searchhw 60 s,
  slimbus 2 s).

## Communication Layer

`NativeProcessSession` owns the root process: `su` + `exec fmbin`, ready
handshake `{"type":"ready","pid":N,"backend":"..."}` (updating
`NativeTunerController`/`FMService` driver kind when sysfs is hidden by SELinux),
serial command queue (`<id>\t<command>\n`; `\t`, `\r`, `\n` are forbidden inside
a command), timeout scheduler, and stop escalation (`shutdown` → stdin close →
TERM → KILL by handshake PID). Native stderr is drained to logcat tag `RFM-QCOM`;
stdout is protocol JSON only. `NativeEventParser` validates `state` patches
(frequency > 0, rmssi in [-128, 127], country `[A-Z]{2}`, pty ≥ 0, AF ≥ 0,
PS/RT trimmed, newlines → spaces) and dispatches `onStatePatch`,
`onSearchProgress`, `onSearchDone`, and `onSearchFailed`; `enabled`, `disabled`,
and `seek_done` are ignored (Java command completion is authoritative for
enable/disable and seek frequencies arrive via `state` patches).
`RtPlusInfo` stores a `Map<String, String>` keyed by RT+ content type names.
`NativeEventParser` preserves the keys in `rds.rt_plus`, such as `ITEM.TITLE`
and `INFO.WEATHER`. `RtPlusFields` associates these keys with label resources.
Omitted keys retain
their values. Empty strings clear individual fields and an empty object clears
the RT+ state. The overlay displays populated fields with localized English,
Russian and Polish labels.
`RadioState` stores TP as a boolean, initially false. An omitted `rds.tp`
keeps the current value. A station reset or stopping the tuner clears it.
`RadioUIView` shows TP beside PS when the flag is true. When the station supplies
a country code, the PI line includes its ISO code and flag (e.g. `DE 🇩🇪 · D210`).
For Qualcomm V4L2, `jump` also updates the displayed frequency through the
`state` patch from the tune event. Its delayed numeric reply acknowledges the target.

## Audio

- `RoutingAudioService` (API ≥ 26): owns audio focus, volume broadcast
  listener, and FM PCM capture (`AudioRecord` at 48 kHz stereo with
  `setPreferredDevice(TYPE_FM_TUNER)`). Delegates vendor output routing to
  `RoutedAudioPolicy` (`QualcommRoutedAudio` or `SamsungS610Audio` for
  Exynos `g_fmradio_*` parameters, hardware volume `set_volume`, source 11
  capture, `fmradio_recoding`, `MediaSession` volume session, and software
  gain compensation). Does not poll recording progress (`RecordService` is the
  sole progress source).
- `LightAudioService` (API < 26, legacy): `AudioRecord` (source 1998) →
  `AudioTrack` bridge loop at 44.1 kHz, retaining `FOR_MEDIA` speaker routing.
- Both implement `IAudioRecordable` (start/stop record, `updateRds`) and feed
  the 5-second pre-roll ring buffer in `PcmRecorderSession`.
- `AudioService.requestForFocus` is the only AudioFocus point
  (`GAIN_TRANSIENT_MAY_DUCK` on `STREAM_MUSIC`).

## Recording

- `FMService.getPreferredRecorder()`: `recording_mode` pref →
  `RecordRawService` (WAV), `RecordLameService` (MP3 192/128), or
  `RecordOpusService` (Ogg Opus, 96 kbps stereo VBR), passing `FMService`'s `RecordingListener`.
- `PcmRecorderSession`: PCM ring buffer (5 s pre-roll) plus an RDS-change
  history bound to PCM positions; writer thread with a bounded queue
  (backpressure).
- `RecordService` (abstract): IDLE/RECORDING/FINISHING/DONE state machine,
  temp file in `cacheDir/recordings`, publication via `RecordingStorage`
  (MediaStore on API ≥ 29, direct file below), typed `RecordingListener`
  callbacks (`onRecordingStarted`, throttled 750 ms `onRecordingProgress`,
  `onRecordingCompleted(RecordingResult)`, `onRecordingFailed(Throwable)`).
- SRT subtitles (pref `recording_save_srt`, off by default): `FMService`
  `onInternalStateChanged` → `updateRds(RdsSnapshot)` → `PcmRecorderSession` →
  `RecordService.recordRds` → `RdsSubtitleWriter` (one cue per snapshot
  change, PCM-relative times). `RdsSnapshot` holds frequency, PS, RT, PI and
  the complete immutable `RtPlusInfo`. Changes to any RT+ field start a new
  cue. The compact track line is followed by localized rows for the other
  populated fields. Deletion via
  notification (`RecordingDeleteActivity`) also removes the SRT file.
- WAV: 44-byte header patched on stop. MP3: manual ID3v2.3 tag.

## Favorites

- JSON files at `getExternalFilesDir()/favorites/<name>.json`; the current
  list name lives in `SharedPreferences`. No SQLite.
- `FavoriteController` (in-process singleton via `FavoriteController.get(context)`,
  extends `JSONFile<FavoriteFile>`): `OnFavoritesChangeListener`
  (`CopyOnWriteArraySet`), cached `getFavoriteTitle(int kHz)`,
  `replaceCurrentListStations(List<FavoriteStation>)`, rename/remove (never for
  `default`), import (JSON/ZIP, 1 MB limit, name sanitizing), export (single
  list or ZIP).
- `MainActivity`, `FavoritesView`, `FavoriteListsBottomSheet`, and `FMService`
  all share `FavoriteController.get(...)` and subscribe to changes directly.
- `FavoritesRecyclerView` delegates station selection and add-current-station
  requests to `MainActivity` via `OnStationActionListener`.
- A full hardware search result (`EventListener.onSearchDone`) replaces the
  current list via `FavoriteController.replaceCurrentListStations(...)`.

## UI and Settings

- `MainActivity` gates startup: `su` present and tuner supported, else
  `StartupBlockedActivity`. Binds `RadioClient` in `onStart()` and unbinds in
  `onStop()`. On `SET_INITIAL`, applies `PowerMode.NORMAL` if `ENABLED` and
  runs `app_auto_startup` once only when `IDLE`. In `onStop()`, applies
  `PowerMode.LOW` if `isLowPowerMode()` and `ENABLED`, and cancels/dismisses
  active `HW_SEARCH` when finishing or recreating (also dismissing in
  `onDestroy()` to avoid `WindowLeaked`).
- `SettingsActivity` binds `RadioClient` in `onStart()`/`onStop()` to receive
  `EventListener.onInvalidAntennaError()`.
- Settings (`PreferencesFragment` + `res/xml/preferences.xml` + `res/values/arrays.xml`):
  `tuner_region` (1=EU, 2=JP, 3=JP wide, 4=US), `tuner_spacing` (1=50, 2=100,
  3=200 kHz), `tuner_stereo`, `tuner_soft_mute`, `tuner_notch_filter`,
  `tuner_antenna`, `tuner_power_mode`, `rds_auto_af`,
  `recording_directory`/`recording_filename` (templates `$f $y $m $d $h $i $s
  $r`), `recording_mode`, `recording_save_srt`, `recording_save_past`,
  `recording_show_notify`, `app_auto_startup`, `app_favorites_columns`, `app_theme`,
  `notification_show_ps`, `notification_seek_by_favorites`. Feature availability is driven by
  `TunerDriver` capability flags (`hasKnownCapabilities`, `supportsSoftMute`,
  `supportsNotchFilter`, `supportsAntenna`, `supportsAutoAf`,
  `supportsJapanWide`, `supportsStereoDetection`, `getJapanStandardUpperKhz`);
  unresolved `NONE` does not disable UI controls.
- Preference store: standard AndroidX `DefaultSharedPreferences` wrapped by
  typed getters/setters on `Storage.get(context)`; changes reach `FMService`
  via `OnSharedPreferenceChangeListener` (filtered by
  `NativeTunerController.isTunerPreferenceKey`, applied while `ENABLED`).

## Build, Tests, Dead Code

- `app/build.gradle`: minSdk 23, targetSdk 24, ABI filters armeabi-v7a and
  arm64-v8a, NDK 29.0.14033849. `preBuild` runs `native/build.sh` and then
  verifies the `fmbin-*` assets exist. The universal debug APK contains both ABIs.
  The `armv7Release` and `arm64Release` variants each package their own LAME,
  Opus and `fmbin` binaries. The app selects `fmbin` by process bitness.
- JVM tests (`make test-java`): `RadioStateTest`, `RadioClientTest`,
  `FavoriteControllerTest`, `NativeEventParserTest`, `SignalStrengthTest`,
  `RequestTest`, `FMNotificationManagerTest`, `TunerCommandFormatTest`,
  `TunerDriverDetectorTest`, `TunerDriverCapabilitiesTest`, `StorageTest`,
  `AudioSelectionTest`, `AudioLifecycleTest`, `QualcommRoutedAudioTest`,
  `RoutingAudioServiceTest`, `SamsungS610AudioTest`, `PcmFormatTest`,
  `PcmRecorderSessionTest`, `RdsSubtitleWriterTest`, `RecordingStorageTest`.
- Remaining leftovers: `service/streaming/` (empty) and `FavoriteStation`'s JSON constructor defaults frequency to 108100 (outside
  the EU band).

## Critical Android Bridge Traps

- **Success is a string-prefix `"ok"` match on `data`** — the JSON `ok` field
  is ignored (`NativeTunerController.isOkResponse`). Do not rely on `ok`.
- **Duplicate-tune suppression**: HAL `setFrequency` drops repeated tunes
  within 1500 ms (`DUPLICATE_TUNE_WINDOW_MS`).
- **Recorder sample rate comes from the PCM source**: `AudioService.getPcmFormat()`
  returns `PcmFormat.PCM16_STEREO_48000` on `RoutingAudioService` (including
  routed V4L2 on API ≥ 26) and `PcmFormat.PCM16_STEREO_44100` on
  `LightAudioService`; it drives capture validation, pre-roll, WAV headers,
  MP3 encoding, Opus input resampling, and recorded durations.
- **Legacy RMSSI settle delay**: the native legacy backend waits 750 ms
  (`kMetricSettleMs`) after tune/jump before sampling RMSSI; do not "fix"
  Java-side timing to compensate.

Deep reference: `ARCHITECTURE.md` (same directory) — full package map,
per-class details, notification ids, SRT data flow, settings table,
dead-code inventory.
