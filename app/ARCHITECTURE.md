# App Architecture: AI Reference

Deep second-level reference for the Java/Android side of RFM-Radio. The
dense auto-loaded summary lives in `app/AGENTS.md`; this document holds the
per-class detail, flows, notification ids, settings inventory, and dead-code
inventory. Implementation is the source of truth — update this document when
architecture changes.

Paths below are relative to
`app/src/main/java/com/vlad805/fmradio/` unless stated otherwise.

## Overview

The app is a controller over a native root process (`fmbin`, see
`native/ARCHITECTURE.md`). Android-side responsibilities:

1. Detect the tuner driver and construct the controller (`FMService`).
2. Own the native process lifecycle and coordinate commands via Local Binder.
3. Route FM audio (device-dependent) and expose recording.
4. Render state (frequency, RDS, signal) and manage favorites.

Communication model, end to end:

```text
UI (MainActivity / SettingsActivity)
  -> RadioClient (bindService BIND_AUTO_CREATE + pending command queue)
  -> FMService.LocalBinder -> RadioServiceController (in-process main thread)
  -> NativeTunerController
  -> Request -> NativeProcessSession -> su -> fmbin stdin
  -> fmbin stdout JSON -> NativeEventParser
  -> FMEventCallback (marshaled to main looper in FMService)
  -> RadioState reducers (single RadioState instance in FMService)
  -> StateListener (persistent state + SET_INITIAL snapshot) / EventListener (one-shot events)
```

External OS entry points (`FMNotificationManager` `PendingIntent`s and
`CallReceiver`) still deliver `Intent` actions (`FMService.ACTION_*`) to
`FMService.onStartCommand(...)`. `C.java` holds only `C.RecordFormat` and
`C.Config`; preference keys (`Storage.Key.*` + internal keys) and defaults
live in `Storage.java`.

## Package Inventory

| Package | Files | Notes |
| --- | --- | --- |
| (root) | 4 | `C` (`RecordFormat`, `Config`), `Storage` (`SharedPreferences` wrapper), `Utils`, `UtilsLocalization` |
| `activity/` | 6 | see UI section |
| `controller/` | 7 | `RadioServiceController`, `RadioClient`, `StateListener`, `EventListener`, `FavoriteController`, `RadioState`, `TunerStatus` |
| `enums/` | 3 | `Direction`, `PowerMode`, `TunerDriver` (with backend capability flags) |
| `fragments/` | 1 | `PreferencesFragment` (declarative `res/xml/preferences.xml`) |
| `helper/` | 7 + `helper/json/` 2 | `Audio`, `TunerDriverDetector`, `RecordSchemaHelper`, `JSONFile`, … |
| `models/` | 3 | `FavoriteStation`, `FavoriteFile`, `RtPlusInfo` |
| `preferences/` | 1 | `BandUtils` |
| `receivers/` | 1 | `CallReceiver` |
| `service/` | 2 | `FMService`, `FMNotificationManager` |
| `service/audio/` | 9 | `AudioOutput`, `AudioSelection`, `AudioLifecycle`, `RoutedAudioPolicy`, `QualcommRoutedAudio`, `SamsungS610Audio`, `AudioService`, `RoutingAudioService`, `LightAudioService` |
| `service/fm/` | 3 | `FMEventCallback`, `RadioStatePatch`, `RecordError` |
| `service/fm/communication/` | 3 | `NativeProcessSession`, `NativeEventParser`, `Request` |
| `service/fm/implementation/` | 2 | `NativeTunerController`, `TunerCommandFormat` |
| `service/recording/` | 14 | see Recording section |
| `service/streaming/` | 0 | empty leftover directory |
| `view/` | 10 + `view/adapter/` 1 | see UI section |

`app/src/main/cpp/` builds `libmp3encoder.so` from the LAME 4.0 submodule
(`lame_encoder_jni.c`); it is unrelated to the FM backend in `native/`.

## FMService

`service/FMService.java`. Manifest: runs in the main app process (single-process),
`foregroundServiceType="mediaPlayback"`, not exported. Implements
`RadioServiceController` (exposed via `FMService.LocalBinder`) and
`FMEventCallback`, and registers strong-referenced listeners on
`Storage.get(this)` and `FavoriteController.get(this)`.

- **Thread safety**: all `FMEventCallback`, `RecordingListener`, and
  `OnFavoritesChangeListener` callbacks marshal onto `Looper.getMainLooper()`
  via `runOnMainThread(...)`. `mState`, `mAudioLifecycle`, `mNotifications`,
  `mStateListeners`, and `mEventListeners` are accessed exclusively on the main
  thread.
- `FMService.isTunerActive()`: `volatile boolean` updated on the main thread
  reflecting an active tuner status (excluding `IDLE`, `ROOT_ACCESS_DENIED`,
  and `FATAL_ERROR`). `CallReceiver` checks this before sending `ACTION_STOP`
  so calls never start an idle or failed service.
- `onCreate`: initializes `Storage`, the sole `RadioState` instance (seeded
  with `mStorage.getLastFrequency()`), `FavoriteController.get(this)` +
  `mFavoritesListener`, `FMNotificationManager` (`createChannels()` for
  `playback_channel_v3`, `record_channel`, `recording_channel_v2`),
  `mPreferenceChangeListener`, and the provisional `NativeTunerController` (or
  `null` on `NONE`). Audio is not instantiated in `onCreate`.
- `onBind`: returns `LocalBinder`, giving bound UI clients direct access to
  `RadioServiceController`.
- `onStartCommand`: returns `START_NOT_STICKY`. Handles only external actions:
  - `ACTION_START` — promotion to `Started` service triggered inside `enable()`.
  - `ACTION_STOP` — delegates to `disable()`.
  - `ACTION_RECORD_START` / `ACTION_RECORD_STOP` — delegates to
    `startRecord()` / `stopRecord()`.
  - `ACTION_NOTIFICATION_SEEK` (`EXTRA_SEEK_DIRECTION`) — calls
    `navigateThroughFavorite(direction)` when `notification_seek_by_favorites`
    is enabled, otherwise `hwSeek(...)`.
- `enable()`: idempotent (no-op if status is already `INSTALLING`, `INSTALLED`,
  `LAUNCHING`, `LAUNCHED`, `ENABLING`, or `ENABLED`). Promotes the service via
  `startService(ACTION_START)` and drives `prepareBinary()` → `INSTALLED` →
  `launch()` → `LAUNCHED` → `attachAudioService()` + `beforeEnable()` +
  `enable()` → `ENABLED` → `onEnabled()` + `startForeground()`.
- `stopPlaybackSession(boolean gracefulTunerDisable)`: separated from
  `onDestroy()` so `disable()` works cleanly even while `MainActivity` remains
  bound (`BIND_AUTO_CREATE`). Stops active recording, cancels recording
  progress notification, stops audio and clears `mAudioService`/`mAudioLifecycle`,
  calls `mTunerDriver.disable()` (which sends `disable` then stops/kills `fmbin`)
  or `mTunerDriver.kill()` so no root `fmbin` lingers in `IDLE`, resets `mState`
  to `TunerStatus.IDLE` (clearing RDS/signal/speaker/recording while preserving
  last frequency), and calls `stopForeground(true)` + `stopSelf()`. Keeps
  `Storage` and `FavoriteController` listeners registered while bound.
- `onDestroy()`: tears down any active playback session, ensures
  `mTunerDriver.kill()`, unregisters `Storage` and `FavoriteController`
  listeners, clears listener sets, and resets `sTunerActive = false`.
- `onInternalStateChanged(int mode)`: centralized side effects on every
  `mState` mutation — updates `sTunerActive`, persists `frequency_last` on
  `SET_FREQUENCY`, pushes `RdsSnapshot` to `IAudioRecordable.updateRds(...)`
  on frequency/RDS changes, refreshes the foreground playback notification
  when `ENABLED` (avoiding redundant `startForeground()` calls on periodic
  recording progress ticks), and notifies all `StateListener`s.
- `addStateListener(StateListener)`: adds listener and immediately invokes
  `onStateUpdated(mState, RadioState.INITIAL_SNAPSHOT_MASK)`.
- `toggleSpeaker()`: switches `AudioOutput` (`HEADPHONES`/`SPEAKER`) via
  `mAudioService.getOutput()` / `setOutput(...)` and applies
  `mState.applyForceSpeaker(...)`.
- `onPreferenceChanged`: updates pre-roll on `RECORDING_SAVE_PAST`, refreshes
  playback notification on `NOTIFICATION_SHOW_RDS`, and forwards tuner
  preference changes (`NativeTunerController.isTunerPreferenceKey`) to
  `setupTunerByPreferences` only when `mState.getStatus() == TunerStatus.ENABLED`.
- Backend resolution: `TunerDriverDetector.getTunerDriver()` gates launching
  `fmbin` (`/sys/class/video4linux/radio0/name` allowlist `iris`/`tavarua` or
  `/dev/radio0` fallback → `V4L2_QCOM`; `s610` → `V4L2_EXYNOS`;
  `fm_helium.so` → `HAL_HELIUM`; else `NONE`). On ready handshake,
  `TunerDriverDetector.resolveNativeBackend()` overrides the guess when a
  recognized backend is reported; explicit `"none"` fails launch without
  creating hardware audio.
- Audio selection (`AudioSelection` + `AudioLifecycle`): API ≥ 26 →
  `QUALCOMM_ROUTED` / `SAMSUNG_ROUTED` (`RoutingAudioService`), API 23–25 →
  `PCM_LOOPBACK` (`LightAudioService`), `NONE` → no hardware audio.
- `getPreferredRecorder()`: `recording_mode` → WAV (`RecordRawService`) /
  MP3 192 or 128 (`RecordLameService`), injecting `mRecordingListener`; sample
  rate comes from `mAudioService.getPcmFormat().sampleRateHz()` (48000 on
  routed capture, 44100 on loopback).
- Notifications (`service/FMNotificationManager.java`): playback id 1027
  (MediaStyle, seek/stop/record/seek actions using `FMService.ACTION_*`;
  `FMService` calls `startForeground(1027, notification)`), recording progress
  id 1029, recorded-item (built from `RecordingResult`, with delete intent into
  `RecordingDeleteActivity`) id 1030 under tag `"recording:<uri|path>"`. Title:
  frequency | PS (if `notification_show_ps`) or favorite name (via
  `FavoriteController.getFavoriteTitle(frequency)`); text: RT.

## Controller Layer

- `controller/RadioServiceController.java` — interface exposed by
  `FMService.LocalBinder`: `getState()`, `enable()`, `disable()`,
  `setFrequency()`, `jump()`, `hwSeek()`, `hwSearch()`, `cancelHwSearch()`,
  `setPowerMode()`, `toggleSpeaker()`, `startRecord()`, `stopRecord()`,
  `addStateListener()`/`removeStateListener()`,
  `addEventListener()`/`removeEventListener()`.
- `controller/RadioClient.java` — Activity-scoped `ServiceConnection` wrapper
  (`connect()`/`disconnect()`). Binds with `BIND_AUTO_CREATE`, holds a
  `mFallbackState` seeded with saved frequency prior to connection, queues
  commands issued before `onServiceConnected()` in FIFO `mPendingCommands`
  (cleared on `disconnect()`), and unregisters listeners on `disconnect()`.
  Does not call `startService()` (`FMService.enable()` owns `Started` promotion).
- `controller/StateListener.java` — persistent state observer
  (`onStateUpdated(RadioState, int changeMask)`).
- `controller/EventListener.java` — one-shot transient event observer with
  default no-op methods (`onSearchProgress`, `onSearchDone`, `onSearchFailed`,
  `onInvalidAntennaError`, `onRecordingFinished`, `onRecordingFailed`,
  `onTunerError`).
- `controller/RadioState.java` — plain in-process state model (no `Parcelable`)
  holding `SET_*` change-mask constants, `INITIAL_SNAPSHOT_MASK`, and pure
  reducers (`applyStatus`, `applyFrequency`, `applyPatch` with partial RT+
  merge and signal/AF reset on frequency change, `applyRecordingStarted`,
  `applyRecordingProgress`, `applyRecordingStopped`, `applyForceSpeaker`).
- `controller/FavoriteController.java` (extends `JSONFile<FavoriteFile>`) —
  thread-safe in-process singleton (`FavoriteController.get(Context)`,
  package-private constructors for JVM tests), `OnFavoritesChangeListener`
  (`CopyOnWriteArraySet`), cached `getFavoriteTitle(int kHz)`,
  `replaceCurrentListStations(List<FavoriteStation>)`, favorites CRUD,
  rename/remove (blocked for `default`), `importList` (JSON validation, 1 MB
  `MAX_IMPORT_BYTES`, name sanitizing, `_N` uniquification),
  `exportList`/`exportAllLists` (ZIP), `migrateLegacyFavorites()` from
  `/sdcard/RFM/favorites`.
- `enums/TunerDriver.java` — `V4L2_QCOM` / `V4L2_EXYNOS` / `HAL_HELIUM` / `NONE`,
  carrying backend capability flags (`hasKnownCapabilities`, `supportsSoftMute`,
  `supportsNotchFilter`, `supportsAntenna`, `supportsAutoAf`,
  `supportsJapanWide`, `supportsStereoDetection`, `getJapanStandardUpperKhz`).

## service/fm/ Contract

- `FMEventCallback` — typed callbacks (`onStatusChanged`, `onFrequencyChanged`,
  `onStatePatch`, `onSearchProgress`, `onSearchDone`, `onSearchFailed`,
  `onInvalidAntennaError`, `onError`, `onUnexpectedExit`); implemented by
  `FMService`.
- `RadioStatePatch.java` — nullable fields (frequency, ps, rt, pi, country,
  pty, af, stereo, rmssi, sinr, and RT+ fields indexed by content type
  with per-field update and deletion); `isEmpty()`. `NativeEventParser` reads
  each RT+ field directly under its content type name, such as `ITEM.TITLE`.
  The parser, state, overlay and recording snapshots use the same string keys.
- `RecordError.java` — simple `Throwable` subtype for recording errors.

## service/fm/implementation/

`NativeTunerController.java` (`final`) — the only real driver:

- Binary management: asset `fmbin-<arch>` (`Utils.determineArch()`) →
  `/data/data/<appId>/files/`, `Os.chmod 0755`, installed version in
  `SharedPreferences` key `bin_version_native` and APK `lastUpdateTime` in
  `bin_apk_update_time`.
  `isObsolete()` = `BuildConfig.DEBUG || version != VERSION_CODE ||
  apkUpdateTime != packageInfo.lastUpdateTime` — debug builds and updated
  APKs reinstall. `prepareBinary()` repairs a lost execute bit in place.
- `sendCommand(Request)` under `mSessionLock`; no session → immediate
  `IOException("Native process is not running")`.
- `startNativeProcess(timeoutMs)` — creates `NativeProcessSession`, forwards
  events to `mEventCallback`, and wires `handleUnexpectedNativeExit` as the
  lifecycle listener (calls `mEventCallback.onUnexpectedExit(exitCode)`).
- `launch()`: background thread `"NativeTunerLaunch"` —
  `Utils.runAsRoot("true")` (root probe; denial → `RootAccessDeniedError` →
  `TunerStatus.ROOT_ACCESS_DENIED`), stops any lingering previous session,
  `killall fmbin-*`, 150 ms sleep, `startNativeProcess(5000)` (ready
  handshake), then `Request("init", 10000)`.
- Success test: `response.startsWith("ok")` — the JSON `ok` field is
  ignored throughout the Java bridge.
- `enable()`: one atomic
  `enable freq= region= spacing= stereo= soft_mute= antenna= af=` command
  formatted by `TunerCommandFormat` from preferences (saved frequency clamped
  to `BandUtils` band limits), followed by a non-default `set_notch_filter`
  when configured.
- `disable()`: sends `disable` (5 s timeout) and then detaches and stops the
  `NativeProcessSession` on `"NativeTunerStop"` so no root `fmbin` lingers.
- `applyPreference` maps preferences to `set_spacing`, `set_region`,
  `set_stereo`, `set_soft_mute`, `set_notch_filter`, `set_antenna` (emitting
  `onInvalidAntennaError()` on `ERR_UNV_ANT`), `power_mode`, `auto_af`.
  `setupTunerByPreferences(changed)` reads `SharedPreferences` via `Storage`
  and applies; a spacing change re-applies stereo afterwards (vendor quirk).
- `setFrequency`: HAL only — suppresses a repeated tune within
  `DUPLICATE_TUNE_WINDOW_MS` = 1500 ms.
- `refreshAudioRoute()`: HAL only — `slimbus 0` then `slimbus 1` (2000 ms
  timeouts each); restarts the audio transport when the output device
  changes. No-op on legacy.
- `kill()` → detaches session and runs background graceful stop escalation.

`TunerCommandFormat.java` — package-private command formatter:
- `regionName`: 1→eu, 2→jp, 3→jp_wide, 4→us (default `eu`).
- `spacingKhz`: 1→50, 2→100, 3→200 (default `100`).
- `enable(...)`: formats the atomic `enable` command string.

## service/fm/communication/

`NativeProcessSession.java` (~550 lines) — owns the root process:

- `start(timeoutMs)`: `ProcessBuilder("su")`, writes `exec '<binary>'`
  (shell-quoted), waits on a `CountDownLatch` for
  `{"type":"ready","pid":N,"backend":"..."}` (`backend` overrides
  local sysfs fallback detection in `NativeTunerController`/`FMService` when present).
  Protocol mismatch or pid ≤ 0 → launch failure.
- Threads: `NativeStdout` (parses lines; non-JSON → warn+skip),
  `NativeStderr` (→ logcat `RFM-QCOM`), `NativeProcessWaiter` (exit
  monitor, 50 ms liveness poll).
- Command queue: `ArrayDeque<Request>` + one `activeRequest`; monotonic
  request ids; frame `"<id>\t<command>\n"`; commands containing `\t`,
  `\r`, or `\n` are rejected as invalid framing. Timeouts run on a
  single-threaded scheduled executor.
- `stop(timeoutMs)`: sets `stopping`, fails the queue, writes `shutdown`
  outside the queue, awaits the response, closes stdin, then escalates:
  `kill -TERM <pid>` → `destroy()` → `kill -KILL`. A repeated stop waits
  for the in-flight one (+3500 ms).
- Unexpected exit (after handshake, not a planned stop) fails all pending
  and queued requests and notifies the lifecycle listener.

`NativeEventParser.java`: `state` → validated `RadioStatePatch`
(frequency ≤ 0 → drop; rmssi/sinr outside [-128, 127] → drop, `null` →
`RMSSI_UNKNOWN`/`SINR_UNKNOWN`; country must match `[A-Z]{2}`; pty < 0 → drop;
AF with negatives → drop; PS/RT/RT+ trimmed with newlines → spaces);
`enabled`, `disabled`, and `seek_done` → deliberately ignored (Java command
completion owns enable/disable lifecycle and seek frequency arrives via `state`
patches); `search_progress` → `onSearchProgress`; `search_done` →
`onSearchDone`; `search_failed` → `onSearchFailed`; anything else → warning.

For Qualcomm V4L2, `NativeTunerController.jump()` takes the displayed frequency
from the tune event's `state` patch. The numeric command reply acknowledges the
target and can arrive after the event. Helium and Exynos also apply the frequency
returned by `jump`.

`Request.java` — command object: `command` + `timeout` (default 1000 ms),
fluent `onResponse`/`onError`, `fire`/`fireError`. Per-command timeouts
used by `NativeTunerController`: `init` 10000, `enable`/`disable` 5000,
`seekhw` 4000, `jump` 1500, `searchhw` 60000, `slimbus` 2000 ms.

## service/audio/

- `AudioOutput.java` — output device enum (`HEADPHONES`, `SPEAKER`).
- `AudioService.java` (abstract) — `AudioManager`, `mSampleRate` 44100,
  `mBufferSize` 16384, `mAudioSource` 1998 (the unofficial FM source);
  `getAudioRecorder()` (stereo PCM16, `STATE_INITIALIZED` check);
  `requestForFocus(boolean)` — the only AudioFocus point
  (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, `STREAM_MUSIC`); `getOutput()` /
  `setOutput(AudioOutput)` and `setPreRollEnabled` hooks.
- `RoutedAudioPolicy.java` — output-routing contract for `RoutingAudioService`:
  `applyRoute(enabled, output, updateVolume)`,
  `onMusicVolumeChanged(output)`, `getOutput(requestedOutput)`,
  `afterOutputChanged(output)`.
- `QualcommRoutedAudio.java` — Qualcomm `RoutedAudioPolicy` implementation:
  `AudioManager.setParameters` (`fm_status` reset check, `fm_volume`,
  `fm_mute=0`, `fm_routing=<DEVICE_OUT_FM | DEVICE_OUT_SPEAKER |
  DEVICE_OUT_WIRED_HEADSET>`, `handle_fm`), hidden `AudioSystem` device masks
  via reflection with fallback, `fm_volume` gain computation
  (`getStreamVolumeDb` on API ≥ 28, linear index below), framework route
  (`setCommunicationDevice`/`clearCommunicationDevice` on API ≥ 31 and
  `setSpeakerphoneOn`), V4L2 `FOR_MEDIA` force-use, and Helium-only Slimbus
  refresh callback.
- `SamsungS610Audio.java` — Samsung Exynos S610 `RoutedAudioPolicy`:
  `g_fmradio_mute`, `g_fmradio_enable`, `AudioSystem.setForceUse(FOR_FM=8)`
  speaker routing (direct call with root `set_force_use` fallback in `fmbin`
  when `MODIFY_AUDIO_ROUTING` is denied), and scaled 0..15 hardware volume
  updates via `HardwareVolumeController` (`NativeTunerController.setVolume`
  with completion callback). Distinguishes requested vs confirmed applied
  volume (`getAppliedVolume()`) using strict generation matching so
  failed/stale updates roll back deduplication without corrupting capture gain.
- `RoutingAudioService.java` (API ≥ 26) — owns audio focus, hidden
  `VOLUME_CHANGED_ACTION` listener, and FM capture (`AudioRecord` 48000 Hz
  stereo PCM16 with `setPreferredDevice(TYPE_FM_TUNER)` found by
  `findFmTunerInputDevice`; Samsung Exynos also tries source `11`,
  `fmradio_recoding=on/off`, `MediaSession` volume bypass, and
  `compensateExynosVolume` guarded by `ExynosGainCompensationTracker` across
  `recordBufferShorts` drain transitions); `Fm2RecordPcm` thread feeds
  `PcmRecorderSession`. Delegates output routing and speaker side effects to
  its `RoutedAudioPolicy`. Does not run a duplicate recording progress timer.
- `LightAudioService.java` (API < 26, legacy) — `AudioRecord` (source
  1998, 44100) → `AudioTrack` (`STREAM_MUSIC`) loop on a thread; the same
  `PcmRecorderSession.append()` sits on the capture path; same pre-roll;
  retains `FOR_MEDIA` speaker toggle (and Helium Slimbus callback if used).
  Note: writes into `PcmRecorderSession` happen synchronously on the audio
  thread (unlike HAL's queued writer).

## service/recording/

Data flow:

```text
AudioService capture thread
  -> PcmRecorderSession (ring buffer + rdsHistory)
  -> FmPcmWriter thread (bounded queue, backpressure)
  -> IFMRecorder.record(short[], int)          (PCM)
  -> RecordService.onReceivedData              (encode + write)
  -> RecordingListener (marshaled to main looper in FMService)
     -> RadioState.applyRecording* + FMNotificationManager + EventListener
RDS: FMService.onInternalStateChanged -> updateRds(RdsSnapshot)
  -> PcmRecorderSession.updateRds (PCM position)
  -> RecordService.recordRds -> RdsSubtitleWriter
```

- `PcmFormat.java` — `PCM16_STEREO_44100` and `PCM16_STEREO_48000` with
  `sampleRateHz()`, `channelCount()`, and `matches(sampleRateHz, channelCount)`
  used to configure and validate capture, pre-roll, and recorders.
- `IAudioRecordable` — `startRecord(IFMRecorder)`, `stopRecord()`,
  `updateRds(RdsSnapshot)`; implemented by both audio services.
- `IFMRecorder` — `startRecord(initialDurationMillis)`, `record(short[],
  int)`, `recordRds(positionMillis, RdsSnapshot)` (default no-op),
  `stopRecord()`.
- `RecordingListener.java` — typed recording lifecycle callbacks
  (`onRecordingStarted`, `onRecordingProgress`, `onRecordingCompleted`,
  `onRecordingFailed`).
- `RecordingResult.java` — immutable value object carrying size, duration,
  display path/name, audio URI/path, `subtitlesRequested`, subtitle URI/path,
  and `hasSubtitleFailure()`.
- `PcmRecorderSession.java` (~300 lines) — PCM ring buffer sized
  `sampleRate × channels × preRollSeconds` (pre-roll =
  `C.Config.RECORDING_PRE_ROLL_SECONDS` = 5 s, opt-in via
  `recording_save_past`); `rdsHistory` is a deque of
  `RdsChange{sample, snapshot}` pruned to one baseline before the oldest
  retained PCM. `start(recorder)` snapshots history, replays RDS relative
  to the recording start, then streams PCM through the `FmPcmWriter`
  thread (`ArrayBlockingQueue` of 256 chunks, FINISH marker). `stop()`
  drains the queue before `stopRecord()`. `clearHistory()` only when idle.
- `RecordService.java` (abstract) — state machine
  IDLE/RECORDING/FINISHING/DONE. `startRecord` creates the file via
  `RecordingStorage.create()`, reads `recording_save_srt` once per recording,
  and invokes `RecordingListener.onRecordingStarted(...)`; `record()` buffers
  (`7200 + len*2*1.75` bytes) → encode → write, throttled
  `onRecordingProgress(...)` (≥ 750 ms); `stopRecord()` flushes the encoder,
  closes streams, `RecordingTarget.commit()`/`abort()`, publishes subtitles,
  and notifies `onRecordingCompleted(RecordingResult)` or
  `onRecordingFailed(IOException)`. Duration =
  max(initialDuration, acceptedPcmSamples/sampleRate/2). On `IOException`
  in `record()` it silently returns to IDLE, leaving the file unfinished.
- `RecordRawService.java` — WAV: writes a 44-byte header at start, patches
  chunk sizes (offsets 4 and 40) at stop via `FileChannel`.
- `RecordLameService.java` — MP3 through `LameMp3Encoder` (CBR 192/128,
  quality 5, 2 channels) with a manual ID3v2.3 tag (TIT2 "XXX.X MHz", TDRC,
  TENC "RFM Radio", ISO-8859-1, synchsafe sizes); `flush()` with a 7200
  buffer on finalization.
- `LameMp3Encoder.java` — JNI wrapper for `libmp3encoder`
  (`nativeInit` / `nativeEncodeInterleaved` / `nativeFlush` /
  `nativeClose`).
- `RecordingStorage.java` — target factory: directory/filename templates
  from `recording_directory`/`recording_filename` with
  `RecordSchemaHelper.prepareString` (`$f` frequency×10, `$y $m $d $h $i
  $s`, `$r` random); temp files in `cacheDir/recordings`; API ≥ 29 →
  MediaStore (`Music/RFM-Recordings/…`, IS_PENDING, copy after recording),
  below → direct file. Sanitizing rejects `..`, `/`, `\`.
  `requiresStoragePermission()`: < Q always; == Q only with SRT enabled;
  > Q never. `deletePublished()` via ContentResolver or file (canonical
  path check).
- `RecordingTarget.java` — temp → published lifecycle; subtitle target is
  a sibling `.srt` (on Q through `MediaStore.Files` since SRT is not
  "media"); `commitToFile()` (rename with copy fallback),
  `commitToMediaStore()`.
- `RdsSnapshot.java` holds frequency, PS, RT, PI and the complete immutable
  `RtPlusInfo`. Snapshot equality includes every populated RT+ field.
  `toSubtitleText(rtPlusLabels)` groups the artist, title and album on one line.
  The remaining populated fields follow in content type order, each with its
  localized label from `RtPlusFields.localizedLabels()` selected at recording start.
  Broadcast text is whitespace-normalized and HTML-escaped for SRT playback.
- `RdsSubtitleWriter.java` — SRT cues: each snapshot change closes the
  previous cue (`HH:MM:SS,mmm --> …`); `finish(durationMillis)` closes the
  last. Times are PCM-relative, including pre-roll history.

## Favorites

Storage: JSON files in `getExternalFilesDir()/favorites/<name>.json`
(`{"items":[{"frequency":…,"title":…}]}`); the active list name is the
`SharedPreferences` key `favorites_list_current` (default `"default"`). No SQLite.

- In-process singleton: `FavoriteController.get(context)` maintains a
  `CopyOnWriteArraySet<OnFavoritesChangeListener>` and an in-memory frequency →
  title map rebuilt on `reload()` and `save()`.
- `MainActivity`, `FavoritesView`, `FavoriteListsBottomSheet`, and `FMService`
  share the singleton; all mutations (`save`, `setCurrentFavoriteList`,
  `addList`, `renameList`, `removeList`, `importList`,
  `replaceCurrentListStations`) automatically notify listeners.
- Import: `FavoriteController.importList` (JSON) or
  `FavoriteListTransferHelper` via `FavoriteListsBottomSheet` (ZIP via `ZipInputStream`, multiple lists).
- Export: single list or all lists as ZIP via SAF (`ACTION_CREATE_DOCUMENT`).
- A completed hardware search (`EventListener.onSearchDone`) replaces the
  current list via `FavoriteController.replaceCurrentListStations(...)`.
- `FavoriteStation(JSONObject)` defaults the frequency to **108100** —
  outside the EU band; malformed JSON silently produces it.

## UI

- `MainActivity` — startup gates: `Utils.isRootEnvironmentAvailable()` (checks
  for `su` only) and `TunerDriverDetector.isDeviceSupported()`; failure →
  `StartupBlockedActivity` (two reasons: root required / unsupported device).
  Uses `RadioClient` (bound in `onStart()`, unbound in `onStop()`) and
  `FavoriteController.get(this)`. On `SET_INITIAL` snapshot: applies
  `PowerMode.NORMAL` if `ENABLED` and evaluates `app_auto_startup` once when
  `IDLE` (preventing re-trigger on recreation). In `onStop()`: if
  `isLowPowerMode()` and `ENABLED`, applies `PowerMode.LOW` before
  `disconnect()`; if `HW_SEARCH` is active while finishing or changing
  configurations, cancels search and dismisses `mSearchProgress` (and
  `onDestroy()` also dismisses `mSearchProgress` to avoid `WindowLeaked`).
  Runtime permissions: recording (`RECORD_AUDIO`, storage when needed) with a
  "don't ask again → open settings" flow; playback `RECORD_AUDIO` only for
  legacy + API < 26. Menu: settings, speaker (`toggleSpeaker()`), about.
  Station scan (`hwSearch`) lives in the `FavoritesView` header bar.
- `FavoriteListsBottomSheet` — Material 3 `BottomSheetDialogFragment` list
  manager opened from `FavoritesView`, using `FavoriteController.get(this)` and
  `FavoriteListTransferHelper`: switch active list, create/rename/remove,
  export (single JSON or all as ZIP), and import (JSON or ZIP via SAF
  `ActivityResultLauncher`).
- `SettingsActivity` — hosts `PreferencesFragment`; binds `RadioClient` in
  `onStart()`/`onStop()` to observe `EventListener.onInvalidAntennaError()` →
  toast.
- `RecordingDeleteActivity` — dialog activity (noHistory, excludeFromRecents)
  with self-contained `EXTRA_*` keys (including `EXTRA_SUBTITLE_*`); deletes
  audio + subtitles via `RecordingStorage.deletePublished`, cancels the
  notification.
- Views: `RadioUIView` (Material 3 card with frequency, PS/RT/PTY/PI,
  RT+ toggle and scrollable overlay of populated fields with localized labels, statuses), `FrequencyDialView` (custom Canvas
  drum-style horizontal frequency dial themed via Material 3 `MaterialColors`
  with fling physics, haptic ticks, and favorite markers; band/spacing from
  `BandUtils`), `FavoritesView`/`FavoritesRecyclerView`/`FavoritePanelAdapter`
  (Material 3 2/3-column preset grid controlled by `app_favorites_columns`,
  observing `FavoriteController`
  on attach/detach and delegating station selection and add-current-station
  requests via `OnStationActionListener` to `MainActivity`),
  `SignalStrength.levelForRmssi` (thresholds −105/−91/−81/−71/−61 → five
  bars).

## Settings

`PreferencesFragment` inflates declarative XML preferences from
`res/xml/preferences.xml` (backed by `res/values/arrays.xml`) and applies icon
tinting, `TunerDriver` capability gating (disabling unsupported options and
filtering Exynos to narrow 76–90 MHz Japan band), antenna numeric validation,
pre-roll warning/permission prompt, and version check clicks in Java.
Categories: Tuner, RDS, Recording, App, Notifications, Info. Store: standard
AndroidX `DefaultSharedPreferences` wrapped by typed getters/setters on
`Storage.get(context)` (retaining `applicationContext`). Low-level storage
details (decimal strings for UI-bound `ListPreference`/`EditTextPreference`
numeric keys vs native primitives for internal non-UI keys) are encapsulated
inside `Storage.java`.

| Key | Default | Meaning |
| --- | --- | --- |
| `tuner_region` | `"1"` (1) | 1=EU, 2=JP std, 3=JP wide, 4=US |
| `tuner_spacing` | `"2"` (2) | 1=50, 2=100, 3=200 kHz |
| `tuner_stereo` | true | stereo mode |
| `tuner_soft_mute` | true | weak-signal attenuation |
| `tuner_notch_filter` | `"0"` (0) | 0=Auto, 1=Low, 2=High EMI notch filter |
| `tuner_antenna` | `"0"` (0) | 0 headphones, 1 internal |
| `tuner_power_mode` | false | false=normal, true=low |
| `rds_auto_af` | false | automatic AF jump |
| `recording_directory` | `$y-$m-$d` | directory template |
| `recording_filename` | `FM-$h$i$s-$f` | filename template |
| `recording_mode` | `"0"` (0) | 0=WAV, 1=MP3 192, 2=MP3 128 |
| `recording_save_srt` | false | SRT subtitles for recordings |
| `recording_save_past` | false | 5 s pre-roll (confirm + mic permission) |
| `recording_show_notify` | true | recording progress notification |
| `app_auto_startup` | false | start audio on app launch |
| `notification_show_ps` | true | PS in the notification title |
| `notification_seek_by_favorites` | false | notification seek steps through favorites |
| `frequency_last` | 87500 | internal native `int`, restored on enable |
| `detected_backend` | `""` | internal `String`, persisted `TunerDriver` name |
| `bin_version_native` | — | internal native `int`, installed fmbin version |
| `bin_apk_update_time` | — | internal native `long`, APK `lastUpdateTime` when fmbin was installed |

`SharedPreferences` changes reach `FMService` through its strong-referenced
`OnSharedPreferenceChangeListener` → `setupTunerByPreferences` (when `ENABLED`),
notification refresh (`notification_show_ps`), and pre-roll toggle.

## Build

`app/build.gradle`: `applicationId` `com.vlad805.fmradio` (debug suffix
`.dev`), minSdk 23, targetSdk 24, compileSdk 37, Java 11, ABI filters
`armeabi-v7a`/`arm64-v8a`, NDK `29.0.14033849`, CMake `3.22.1`.
`externalNativeBuild` points at `src/main/cpp` (LAME only). FM backend
integration: task `buildFmbinNative` (Exec → `native/build.sh`) →
`verifyNativeAssets` (asserts `fmbin-armv7a`/`fmbin-aarch64` in assets) →
`preBuild`. Release: minify + proguard, signing from `ANDROID_RELEASE_*`
env. Permissions: `INTERNET`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`,
`READ/WRITE_EXTERNAL_STORAGE`, `READ_PHONE_STATE`.

## JVM Unit Tests

`app/src/test/java/…` (JUnit4): `RadioStateTest` (clear semantics + pure
reducers `applyStatus`, `applyFrequency`, `applyPatch`, recording/speaker
masks), `RadioClientTest` (pending command FIFO queue, disconnect cleanup,
immediate `SET_INITIAL` snapshot), `FavoriteControllerTest` (listener
notifications, cached `getFavoriteTitle`, `replaceCurrentListStations`, list
switch/rename/remove), `NativeEventParserTest` (typed `FMEventCallback`
dispatch, validation, partial RT+, duplicate lifecycle suppression),
`SignalStrengthTest` (RMSSI thresholds → bars), `RequestTest` (dispatch),
`FMNotificationManagerTest` (playback title/body precedence, compact actions,
`RecordingResult` tags/subtitle warnings, channel ids),
`TunerCommandFormatTest` (atomic enable command, region/spacing mapping),
`TunerDriverDetectorTest` (allowlist + handshake backend resolution),
`TunerDriverCapabilitiesTest` (backend capability flags + `BandUtils`
limits/spacing), `StorageTest` (string-backed UI preference parsing, native
type round-trips, `ClassCastException` safety, and `preferences.xml` ↔
`Storage` key/default parity), `AudioSelectionTest` (API/backend matrix),
`AudioLifecycleTest` (start/stop ordering, idempotent teardown),
`QualcommRoutedAudioTest` (parameter order, `fm_status` reset, V4L2
`FOR_MEDIA` vs Helium Slimbus, loopback speaker toggle),
`RoutingAudioServiceTest`, `SamsungS610AudioTest`, `PcmFormatTest`
(stereo formats, mismatch rejection, routed V4L2 48 kHz),
`PcmRecorderSessionTest` (pre-roll wrap, partial history, live-only, RDS
ordering, backpressure), `RdsSubtitleWriterTest` (cue format, dedup,
zero-duration), `RecordingStorageTest` (subtitle naming, path containment).
No instrumented tests.

## Dead Code and Traps Inventory

1. `service/streaming/` — empty directory.
2. `FavoriteStation` JSON default 108100 kHz.
3. `AudioService.mAudioSource = 1998` — an unofficial FM source constant;
   device-dependent.
4. Hidden APIs via reflection (`AudioSystem.DEVICE_OUT_*`, `setForceUse`)
   and `AudioManager.setParameters("fm_…")` — vendor-dependent by design.
5. `RecordService.record()` swallows `IOException` → silent IDLE.
6. `LightAudioService` writes to `PcmRecorderSession` synchronously on the
   audio thread.
7. Debug builds always reinstall `fmbin` (`isObsolete()` includes
   `BuildConfig.DEBUG`) — frequent binary copies are expected during
   debugging.
8. `Utils.runAsRoot` has a 15-second shell timeout.
9. Bridge success matching is `data.startsWith("ok")`; the `ok` field is
   unused (see `NativeTunerController`).
