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
2. Own the native process lifecycle and translate intents into commands.
3. Route FM audio (device-dependent) and expose recording.
4. Render state (frequency, RDS, signal) and manage favorites.

Communication model, end to end:

```text
UI (MainActivity / views)
  -> RadioController.startService(Intent with C.Command.*)
  -> FMService (":service" process, START_NOT_STICKY)
  -> IFMController (QualcommNative)
  -> Request -> NativeProcessSession -> su -> fmbin stdin
  -> fmbin stdout JSON -> NativeEventParser
  -> FMEventCallback (implemented by FMService)
  -> Utils.sendAppBroadcast (C.Event.*, package-scoped)
  -> RadioStateUpdater (UI side) -> RadioState -> views
```

There is no binder/AIDL and no shared memory; both directions are
fire-and-forget. `C.java` holds every action/extra/preference constant.

## Package Inventory

| Package | Files | Notes |
| --- | --- | --- |
| (root) | 4 | `C`, `Storage` (Tray wrapper), `Utils`, `UtilsLocalization` |
| `activity/` | 6 | see UI section |
| `controller/` | 5 | see Controller section |
| `enums/` | 4 | `Direction`, `MuteState` (dead), `PowerMode`, `TunerDriver` |
| `fragments/` | 1 | `PreferencesFragment` (programmatic settings) |
| `helper/` | 7 + `helper/json/` 2 | `Audio`, `TunerDriverDetector`, `RecordSchemaHelper`, `JSONFile`, … |
| `models/` | 2 | `FavoriteStation`, `FavoriteFile` |
| `preferences/` | 2 | `BandUtils`, `Vars` |
| `receivers/` | 1 | `CallReceiver` |
| `service/` | 2 | `FMService`, `FMNotificationManager` |
| `service/audio/` | 3 | see Audio section |
| `service/fm/` | 7 | contract + `RadioStatePatch`, `RecordError` |
| `service/fm/communication/` | 3 | see Communication section |
| `service/fm/implementation/` | 3 | see Controller section |
| `service/recording/` | 11 | see Recording section |
| `service/streaming/` | 0 | empty leftover directory |
| `view/` | 11 + `view/adapter/` 1 | see UI section |

`app/src/main/cpp/` builds `libmp3encoder.so` from the LAME 4.0 submodule
(`lame_encoder_jni.c`); it is unrelated to the FM backend in `native/`.

## FMService

`service/FMService.java`. Manifest: `android:process=":service"`,
`foregroundServiceType="mediaPlayback"`, not exported. Implements
`FMEventCallback` and the Tray `OnTrayPreferenceChangeListener`.

- `onCreate`: creates `RadioState`, `RadioController`,
  `FavoriteController`, `FMNotificationManager` (`createChannels()` for
  `playback_channel_v3`, `record_channel`, `recording_channel_v2`), `Storage`, the tuner driver,
  the audio service, and two receivers: `EventReaction` (inner class,
  reacts to `C.Event.*`) and `RadioStateUpdater` (keeps `mState`; its
  update callback also pushes `RdsSnapshot` into
  `IAudioRecordable.updateRds` for subtitles).
- `onStartCommand`: null intent/action → `stopSelf()`; returns
  `START_NOT_STICKY` (a process-death restart without a command stops the
  service instead of recreating a disconnected one — pairs with the native
  EOF-driven fail-closed shutdown).
- Handled commands (`C.Command.*`): `INSTALL`, `LAUNCH`, `ENABLE`,
  `DISABLE`, `SET_FREQUENCY`, `JUMP`, `HW_SEEK`, `POWER_MODE`, `HW_SEARCH`,
  `HW_SEARCH_CANCEL`, `RECORD_START`, `RECORD_STOP`, `NOTIFICATION_SEEK`,
  `KILL`, `REQUEST_CURRENT_STATE`, `SPEAKER_STATE`.
- `ENABLE` starts HAL audio before enabling the tuner.
- `SPEAKER_STATE` toggles the speaker and, for `QualcommNative`, calls
  `refreshAudioRoute()`; for non-HAL it additionally uses
  `Audio.toggleThroughSpeaker()` (hidden `AudioSystem.setForceUse` via
  reflection).
- `NOTIFICATION_SEEK` uses `navigateThroughFavorite(direction)` with
  wrap-around when `notification_seek_by_favorites` is enabled, otherwise
  redirects to `HW_SEEK`.
- `REQUEST_CURRENT_STATE` ("ui_started") answers with a `CURRENT_STATE`
  broadcast carrying the `RadioState` parcel.
- Event chain: `INSTALLED` → launch; `LAUNCHED` → enable; `ENABLED` →
  (non-HAL only) start audio and restore frequency; `LAUNCH_FAILED` /
  `ROOT_ACCESS_DENIED` → `stopSelf()`. `FREQUENCY_SET`/`STATE_CHANGED` →
  persist `frequency_last`, update notification; `UPDATE_PS`/`UPDATE_RT` →
  notification; `FAVORITE_LIST_CHANGED` → reload favorites; `RECORD_*` →
  recording state, notifications, "subtitles failed" warning path.
- `getTunerDriver()` (~L427): `TunerDriverDetector` — `/sys/class/video4linux/radio0/name`
  allowlist (`iris`/`tavarua`, falling back only if sysfs is absent or
  unreadable) + `/dev/radio0` → `V4L2_QCOM`; `fm_helium.so` in eight vendor paths
  → `HAL_HELIUM`; otherwise `NONE`.
  `V4L2_QCOM`/`HAL_HELIUM` → `new QualcommNative(this, kind)`; `NONE` → `new Empty(this)`.
- `getAudioService()` (~L413): API ≥ 26 → `RoutingAudioService`, else
  `LightAudioService`.
- `getPreferredRecorder()` (~L447): `recording_mode` → WAV
  (`RecordRawService`) / MP3 192 or 128 (`RecordLameService`); sample rate
  from `QualcommNative.getRecorderSampleRate()` (48000 HAL / 44100
  legacy), otherwise 44100. `AAC_128` is unhandled → null (unreachable
  from the UI).
- `onDestroy` → `kill()`: `stopAudio`, `tuner kill`,
  unregister both receivers and the Tray listener, `stopForeground(true)`.
- Notifications (`service/FMNotificationManager.java`): playback id 1027 (MediaStyle, seek/stop/record/seek actions; `FMService` calls `startForeground(1027, notification)`),
  recording progress id 1029, recorded-item (with delete intent into
  `RecordingDeleteActivity`) id 1030 under tag `"recording:<uri>"`. Title:
  frequency | PS (if `notification_show_ps`) or favorite name; text: RT.

## Controller Layer

- `controller/RadioController.java` — UI facade; every method sends a
  `startService` intent with a `C.Command.*` action. Also the only consumer
  of `C.Event.CURRENT_STATE` (`requestForCurrentState` round trip).
- `controller/FavoriteController.java` (~450 lines, extends
  `JSONFile<FavoriteFile>`) — favorites CRUD, rename/remove (blocked for
  `default`), `importList` (JSON validation, 1 MB `MAX_IMPORT_BYTES`, name
  sanitizing, `_N` uniquification), `exportList`/`exportAllLists` (ZIP),
  `migrateLegacyFavorites()` from `/sdcard/RFM/favorites`.
- `controller/RadioState.java` — Parcelable UI state (frequency, RDS
  fields, rmssi, stereo). `clear()` resets RDS/rmssi/stereo.
- `controller/RadioStateUpdater.java` — registers `C.Event.*` receivers
  and folds broadcasts into `RadioState`, notifying a
  `TunerStateListener` with a change bitmask (`SET_*` flags).
- `enums/TunerDriver.java` — `V4L2_QCOM` / `HAL_HELIUM` / `NONE`.

## service/fm/ Contract

- `IFMController` — full driver contract (see `app/AGENTS.md`).
- `IFMEventListener` — `setEventListener(FMEventCallback)`.
- `FMEventCallback` — `onEvent(String)`, `onStatePatch(RadioStatePatch)`,
  `onSearchDone(int[])`; implemented by `FMService`.
- `Request.java` — command object: `command` + `timeout` (default 1000 ms),
  fluent `onResponse`/`onError`, `fire`/`fireError`. Per-command timeouts
  used by the controllers: `init` 10000, `enable`/`disable` 5000, `seekhw`
  4000, `jump` 1500, `searchhw` 60000, `slimbus` 2000 ms.
- `RadioStatePatch.java` — nullable fields (frequency, ps, rt, pi, country,
  pty, af, stereo, rmssi); `isEmpty()`; `toIntent(action)` fills `C.Key.*`
  extras (AF → `FREQUENCIES`, stereo → `STEREO_MODE`).
- `Empty.java` — no-op driver for NONE: logs, `sleep()`s for realism,
  emits the full event chain including `HW_SEEK_COMPLETE` (which the real
  driver never sends — completion comes from state patches).
- `RecordError.java` — simple `Throwable` subtype for recording errors.

## service/fm/implementation/

`AbstractQualcommNativeController.java` (~570 lines) — template method.
Final public methods emit lifecycle events (`PREPARING`, `INSTALLING`,
`INSTALLED`, `LAUNCHING`, …) and call protected `*Impl` hooks with
`Callback<T>`:

- Binary management: asset `fmbin-<arch>` (`Utils.determineArch()`) →
  `/data/data/<appId>/files/`, `Os.chmod 0755`, installed version in Tray
  key `bin_version_native`. `isObsolete()` = `BuildConfig.DEBUG || version <
  VERSION_CODE` — debug builds always reinstall. `prepareBinary()` repairs a
  lost execute bit in place.
- `sendCommand(Request)` under `mSessionLock`; no session → immediate
  `IOException("Native process is not running")`.
- `startNativeProcess(timeoutMs)` — creates `NativeProcessSession`, adapts
  its events into `onServerEvent`/`onServerStatePatch`/`onServerSearchDone`
  plus the external `mEventCallback`, and wires
  `handleUnexpectedNativeExit` as the lifecycle listener.
- `applyPreferenceImpl` maps preferences to `set_spacing`, `set_region`,
  `set_stereo`, `set_soft_mute`, `set_notch_filter`, `set_antenna`, `power_mode`, `auto_af`.
  `setupTunerByPreferences(changed)` reads Tray and applies; a spacing
  change re-applies stereo afterwards (vendor quirk).
- `kill()` → background graceful stop → `DISABLED`, `KILLED` events.
- Internal `RootAccessDeniedError` → `ROOT_ACCESS_DENIED` event.
- **Trap**: the base `enableImpl` sends a bare `enable` with no parameters —
  dead code that the strict native parser always rejects. Only
  `QualcommNative.enableImpl` is live.

`QualcommNative.java` (~220 lines) — the only real driver:

- `launchImpl`: background thread "QualcommNativeLaunch":
  `Utils.runAsRoot("true")` (root probe; denial → `RootAccessDeniedError`),
  `killall fmbin-*`, 150 ms sleep, `startNativeProcess(5000)` (ready
  handshake), then `Request("init", 10000)`.
- Success test: `response.startsWith("ok")` — the JSON `ok` field is
  ignored throughout the Java bridge.
- `enableImpl`: one atomic
  `enable freq= region= spacing= stereo= soft_mute= antenna= af=` command
  from preferences; saved frequency clamped to `BandUtils` band limits.
  `shouldApplyStartupPreferences()` → false.
- `setFrequencyImpl`: HAL only — suppresses a repeated tune within
  `DUPLICATE_TUNE_WINDOW_MS` = 1500 ms.
- `getRecorderSampleRate()`: 48000 (HAL) / 44100 (legacy).
- `refreshAudioRoute()`: HAL only — `slimbus 0` then `slimbus 1` (2000 ms
  timeouts each); restarts the audio transport when the output device
  changes. No-op on legacy.
- `onNativeProcessExit`: error event + `DISABLED`, then
  `context.stopService(FMService)` — the service dies with the process.
- `regionName`: 1→eu, 2→jp, 3→jp_wide, 4→us; `spacingKhz`: 1→50, 2→100,
  3→200.

## service/fm/communication/

`NativeProcessSession.java` (~550 lines) — owns the root process:

- `start(timeoutMs)`: `ProcessBuilder("su")`, writes `exec '<binary>'`
  (shell-quoted), waits on a `CountDownLatch` for
  `{"type":"ready","protocol":1,"pid":N,"backend":"..."}` (`backend` overrides
  local sysfs fallback detection in `QualcommNative`/`FMService` when present).
  Protocol mismatch or pid ≤ 0 →
  launch failure.
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

`NativeEventParser.java` (~150 lines): `state` → validated `RadioStatePatch`
(frequency ≤ 0 → drop; rmssi outside [-128, 127] → drop; country must match
`[A-Z]{2}`; pty < 0 → drop; AF with negatives → drop; PS/RT trimmed with
newlines → spaces); `enabled` → `ENABLED` event; `disabled` → deliberately
ignored (Java owns the disable lifecycle); `search_done` → `onSearchDone`;
anything else → warning.

## service/audio/

- `AudioService.java` (abstract) — `AudioManager`, `mSampleRate` 44100,
  `mBufferSize` 16384, `mAudioSource` 1998 (the unofficial FM source);
  `getAudioRecorder()` (stereo PCM16, `STATE_INITIALIZED` check);
  `requestForFocus(boolean)` — the only AudioFocus point
  (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, `STREAM_MUSIC`); no-op
  `setPreRollEnabled` / `setSpeakerEnabled` hooks.
- `RoutingAudioService.java` (~625 lines, API ≥ 26) — for HAL/FM2 devices
  where the audio HAL carries FM audio: no record→track bridge. Routing via
  `AudioManager.setParameters`: `fm_mute=0`, `fm_routing=<DEVICE_OUT_FM |
  DEVICE_OUT_SPEAKER | DEVICE_OUT_WIRED_HEADSET>` (hidden `AudioSystem`
  constants via reflection with fallback), `handle_fm`, `fm_volume`
  (recomputed from `getStreamVolumeDb` on API ≥ 28, linear index below);
  framework route via `setCommunicationDevice`/`clearCommunicationDevice`
  (API ≥ 31) and `setSpeakerphoneOn`; hidden `VOLUME_CHANGED_ACTION`
  listener keeps `fm_volume` in sync. Recording: separate `AudioRecord`
  (48000 Hz stereo PCM16) with `setPreferredDevice(TYPE_FM_TUNER)` found by
  `findFmTunerInputDevice`; `Fm2RecordPcm` thread feeds
  `PcmRecorderSession`; pre-roll keeps capture alive during playback
  (`RECORDING_SAVE_PAST`), deferred while a recording is active; progress
  `Handler` (1000 ms) → `RECORD_TIME_UPDATE` broadcast; permission checks
  (`RECORD_AUDIO` + storage when `RecordingStorage` requires it).
- `LightAudioService.java` (API < 26, legacy) — `AudioRecord` (source
  1998, 44100) → `AudioTrack` (`STREAM_MUSIC`) loop on a thread; the same
  `PcmRecorderSession.append()` sits on the capture path; same pre-roll.
  Note: writes into `PcmRecorderSession` happen synchronously on the audio
  thread (unlike HAL's queued writer).

## service/recording/

Data flow:

```text
AudioService capture thread
  -> PcmRecorderSession (ring buffer + rdsHistory)
  -> FmPcmWriter thread (bounded queue, backpressure)
  -> IFMRecorder.record(short[], int)         (PCM)
  -> RecordService.onReceivedData              (encode + write)
RDS: RadioStateUpdater -> updateRds(RdsSnapshot)
  -> PcmRecorderSession.updateRds (PCM position)
  -> RecordService.recordRds -> RdsSubtitleWriter
```

- `IAudioRecordable` — `startRecord(IFMRecorder)`, `stopRecord()`,
  `updateRds(RdsSnapshot)`; implemented by both audio services.
- `IFMRecorder` — `startRecord(initialDurationMillis)`, `record(short[],
  int)`, `recordRds(positionMillis, RdsSnapshot)` (default no-op),
  `stopRecord()`.
- `PcmRecorderSession.java` (~300 lines) — PCM ring buffer sized
  `sampleRate × channels × preRollSeconds` (pre-roll =
  `C.Config.RECORDING_PRE_ROLL_SECONDS` = 5 s, opt-in via
  `recording_save_past`); `rdsHistory` is a deque of
  `RdsChange{sample, snapshot}` pruned to one baseline before the oldest
  retained PCM. `start(recorder)` snapshots history, replays RDS relative
  to the recording start, then streams PCM through the `FmPcmWriter`
  thread (`ArrayBlockingQueue` of 256 chunks, FINISH marker). `stop()`
  drains the queue before `stopRecord()`. `clearHistory()` only when idle.
- `RecordService.java` (~420 lines, abstract) — state machine
  IDLE/RECORDING/FINISHING/DONE. `startRecord` creates the file via
  `RecordingStorage.create()` and reads `recording_save_srt` once per
  recording; `record()` buffers (`7200 + len*2*1.75` bytes) → encode →
  write, throttled `RECORD_TIME_UPDATE` (≥ 750 ms); `stopRecord()` flushes
  the encoder, closes streams, `RecordingTarget.commit()`/`abort()`,
  publishes subtitles, and broadcasts the final `RECORD_ENDED` with SIZE,
  DURATION, PATH, DISPLAY_NAME, RECORDING_URI|RECORDING_FILE_PATH,
  SUBTITLE_URI|SUBTITLE_FILE_PATH, SUBTITLES_REQUESTED. Duration =
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
- `RdsSnapshot.java` — immutable {frequencyKhz, ps, rt, pi, rtPlusTitle, rtPlusArtist};
  `toSubtitleText()` = "99.8 MHz [0432]\nPS: …\nRT: …\nRT+: …" with HTML escaping
  and control/space normalization.
- `RdsSubtitleWriter.java` — SRT cues: each snapshot change closes the
  previous cue (`HH:MM:SS,mmm --> …`); `finish(durationMillis)` closes the
  last. Times are PCM-relative, including pre-roll history.

## Favorites

Storage: JSON files in `getExternalFilesDir()/favorites/<name>.json`
(`{"items":[{"frequency":…,"title":…}]}`); the active list name is the Tray
key `favorites_list_current` (default `"default"`). No SQLite.

- Import: `FavoriteController.importList` (JSON) or
  `FavoriteListsActivity` (ZIP via `ZipInputStream`, multiple lists).
- Export: single list, selected lists, or all lists as ZIP.
- A completed hardware search (`HW_SEARCH_DONE`) replaces the current
  list's content (MainActivity `mSearchDone`).
- `FavoriteStation(JSONObject)` defaults the frequency to **108100** —
  outside the EU band; malformed JSON silently produces it.

## UI

- `MainActivity` (~774 lines) — startup gates: `Utils
  .isRootEnvironmentAvailable()` (checks for `su` only) and
  `TunerDriverDetector.isDeviceSupported()`; failure →
  `StartupBlockedActivity` (two reasons: root required / unsupported
  device). Owns `RadioController` + `FavoriteController`;
  `registerForUpdates(this)`; `requestForCurrentState` broadcast round
  trip. Runtime permissions: recording (`RECORD_AUDIO`, storage when
  needed) with a "don't ask again → open settings" flow; playback
  `RECORD_AUDIO` only for legacy + API < 26. Menu: about, settings, scan,
  record, speaker. `HW_SEARCH` shows a confirm dialog, then progress and a
  `HW_SEARCH_DONE` receiver. `onDestroy`: if `tuner_power_mode` is enabled,
  `setPowerMode(LOW)` while the service keeps running.
- `FavoriteListsActivity` (~719 lines) — list manager: RecyclerView with
  selection mode, rename/remove, export (single/selected ZIP/all), import
  (JSON or ZIP via SAF `ACTION_*_DOCUMENT`).
- `SettingsActivity` — hosts `PreferencesFragment`; listens for
  `ERROR_INVALID_ANTENNA` → toast.
- `RecordingDeleteActivity` — dialog activity (noHistory,
  excludeFromRecents); deletes audio + subtitles via
  `RecordingStorage.deletePublished`, cancels the notification.
- Views: `RadioUIView` (frequency, PS/RT/PTY/PI, statuses),
  `FrequencyBarView` (custom seekbar with favorite markers; band/spacing
  from `BandUtils`), `FavoritesView`/`FavoritesRecyclerView`/
  `FavoritePanelAdapter` (drag & drop via `SimpleItemTouchHelperCallback`),
  `SignalStrength.levelForRmssi` (thresholds −105/−91/−81/−71/−61 → five
  bars), `TextViewWithReflection`.

## Settings

`PreferencesFragment` builds everything in code (no `res/xml`).
Categories: Tuner, RDS, Recording, App, Notifications, Info. Store: Tray.

| Key | Default | Meaning |
| --- | --- | --- |
| `tuner_region` | 1 | 1=EU, 2=JP std, 3=JP wide, 4=US |
| `tuner_spacing` | 2 | 1=50, 2=100, 3=200 kHz |
| `tuner_stereo` | true | stereo mode |
| `tuner_soft_mute` | true | weak-signal attenuation |
| `tuner_antenna` | 0 | 0 headphones, 1 internal |
| `tuner_power_mode` | false | false=normal, true=low |
| `rds_auto_af` | false | automatic AF jump |
| `recording_directory` | `$y-$m-$d` | directory template |
| `recording_filename` | `FM-$h$i$s-$f` | filename template |
| `recording_mode` | 0 | 0=WAV, 1=MP3 192, 2=MP3 128 |
| `recording_save_srt` | false | SRT subtitles for recordings |
| `recording_save_past` | false | 5 s pre-roll (confirm + mic permission) |
| `recording_show_notify` | true | recording progress notification |
| `app_auto_startup` | false | start audio on app launch |
| `notification_show_ps` | true | PS in the notification title |
| `notification_seek_by_favorites` | false | notification seek steps through favorites |
| `frequency_last` | 87500 | Tray-only, restored on enable |
| `bin_version_native` | — | Tray-only, installed fmbin version |

Tray changes reach `FMService` through the listener →
`setupTunerByPreferences` + pre-roll toggle.

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

`app/src/test/java/…` (JUnit4): `RadioStateTest` (clear semantics),
`SignalStrengthTest` (RMSSI thresholds → bars), `RequestTest` (dispatch),
`FMNotificationManagerTest` (playback title/body precedence, compact actions,
recording tags/ids), `PcmRecorderSessionTest` (pre-roll wrap, partial history, live-only, RDS
ordering, backpressure), `RdsSubtitleWriterTest` (cue format, dedup,
zero-duration), `RecordingStorageTest` (subtitle naming, path containment).
No instrumented tests.

## Dead Code and Traps Inventory

1. `service/streaming/` — empty directory.
2. `enums/MuteState` — unreferenced.
3. `view/IntEditTextPreference` — unreferenced (settings are programmatic).
4. `C.RecordFormat.AAC_128` (=3) — `getPreferredRecorder()` returns null
   for it; unreachable from the UI but an NPE trap if set manually.
5. `C.Event.HW_SEEK_COMPLETE` — emitted only by `Empty`; the real driver
   completes seeks via state patches.
6. `C.Event.UPDATE_AF` — declared, never sent (AF arrives in state patches).
7. `FavoriteStation` JSON default 108100 kHz.
8. `AudioService.mAudioSource = 1998` — an unofficial FM source constant;
   device-dependent.
9. Hidden APIs via reflection (`AudioSystem.DEVICE_OUT_*`, `setForceUse`)
   and `AudioManager.setParameters("fm_…")` — vendor-dependent by design.
10. `RecordService.record()` swallows `IOException` → silent IDLE.
11. `LightAudioService` writes to `PcmRecorderSession` synchronously on the
    audio thread.
12. Debug builds always reinstall `fmbin` (`isObsolete()` includes
    `BuildConfig.DEBUG`) — frequent binary copies are expected during
    debugging.
13. `Utils.runAsRoot` has a 15-second shell timeout.
14. Bridge success matching is `data.startsWith("ok")`; the `ok` field is
    unused (see `QualcommNative`).
15. Base `AbstractQualcommNativeController.enableImpl` is dead code that
    would always fail the strict native parser.
