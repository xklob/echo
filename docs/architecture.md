# Implemented architecture

Release shrinking preserves the ONNX Java/JNI boundary with the runtime's [required Android keep rule](https://onnxruntime.ai/docs/build/android.html#note-proguard-rules-for-r8-minimization-android-app-builds-to-work). CI builds the minified APK and compares class names embedded in ONNX JNI libraries against actual DEX class definitions using `tools/verify_native_bindings.py`. Native speech processing is also checked in the signed build; debug-only tests cannot catch shrinker failures.

`RecordingService` owns one active AudioRecord, one capture HandlerThread, one history, one settings store and one export worker. The activity binds for controls/state and starts the foreground service from visible user interaction. Capture does not start in `onCreate` or from boot. Foreground promotion precedes microphone use. STOP persists disabled state and waits for an in-flight save/final continuous tail before removing foreground visibility.

## Capture and time

Audio is 48 kHz mono PCM16. A reusable 50 ms read buffer feeds the Opus history, waveform and optional spoken-camera recognizer. Nonblocking reads are polled; repeated negative/empty reads and dead audio objects trigger cleanup/retry. Android recording callbacks distinguish client silencing from genuine quiet audio. Silenced/failed input never increases usable history. Permission errors remain visible.

The capture thread serializes commands and sample counters. Each capture session anchors sample zero to wall time at AudioRecord start. Export boundaries use captured sample counts, including encoder delay and final-frame padding. A recovery or source switch creates a new session/history. An export pins its original session; old export completion cannot advance a new session's autosave cursor. Separate GPS/trace files use their own timestamps and do not imply perfect sample synchronization.

Microphone and Android playback capture are alternative inputs to this same pipeline. Playback uses user-consented MediaProjection and permitted media/game/unknown usages. There is no silent fallback from device audio to microphone. Stopping projection stops listening. Screenshots and playback require separate projection lifetimes and are mutually exclusive here.

## History and limits

Concentus encodes 20 ms Opus packets at the chosen bitrate. Ten-second blocks group immutable packet arrays. Packet length, conservative object/reference allowance and block/index overhead are accounted, rather than assuming every frame is 60 bytes. RAM settings are capped by the app heap, currently min(64 MiB, one quarter of heap).

RAM mode evicts oldest whole blocks when its active history budget is exceeded. One exporter may retain an additional history-budget reserve, plus bounded codec/selection working storage. The selected MB value describes history accounting, not all app/native heap. Actual history duration is measured from available samples; no fixed-hours claim is made.

Disk mode seals blocks into private `files/history` packet files, rounded to the filesystem allocation unit for budget accounting (filesystem metadata is excluded), keeps bounded block metadata in RAM and evicts deterministically. Half the configured total disk limit is active history; half is reserved for pinned segments while a save proceeds. A snapshot freezes the mutable tail's references and pins older blocks. Eviction never overwrites those bytes. Pins release on success, failure or cancellation, before slow external copies. Saved clips live in a different root and cannot be removed by history eviction. Stale history/partial files are removed at service initialization after process death.

## Export and storage

One export is admitted at a time. Opus passes stored packets through VorbisJava's low-level streaming API. The high-level OpusFile API is avoided because it buffers whole clips. Ogg pre-skip includes decoder pre-roll at cuts; final granules trim precisely to selected sample count. Library framing supplies CRC, BOS/EOS, serials and 255-byte lacing.

WAV decodes packets into a streaming RIFF writer, rejecting the classic RIFF size limit. FLAC and AAC/M4A decode the same history into Android MediaCodec. Input is split to actual codec buffer capacity; PTS derives from cumulative samples. EOS drains with a deadline, and descriptors/codecs/muxers release on all paths. FLAC STREAMINFO receives the exact total-samples field. AAC priming/padding is preserved as a documented platform limitation; captured duration is recorded in the JSON sidecar.

`ClipStore` writes unique timestamp-based `.part` files under `files/clips/<kind>`, then atomically renames completed local files. It excludes partials from the library, preserves valid local output after an external-copy failure, and rotates only the requested automatic category plus its metadata. SAF grants are persisted. Copies are verified by length and SHA-256 readback; providers supporting rename receive a temporary name until completion. Other providers may expose a file during writing. Cancellation/failure attempts to delete partial provider output and never reports it as saved.

Manual, automatic and session categories share this exporter. Automatic cursors advance after local completion even if an additional folder copy fails. Fill triggers measure new data since that cursor; failed local exports back off. STOP/continuous-stop save a pending tail after an existing job, retaining foreground ownership until completion. Continuous/automatic limits never delete manual clips. Folder copies and server data have independent retention.

## Rules and controls

`ListeningRules` evaluates local active-hour windows, including overnight/all-day cases and timezone/DST resolution. A user-disabled session never restarts through rule checks. Exact alarms are used only when available; otherwise allow-while-idle inexact alarms provide a delayed fallback. Boot/process loss requires visible user resumption. A minimum battery threshold shares the same rule evaluation.

Notification actions, Quick Settings and opt-in versioned external broadcasts issue commands to the same service. Quick Settings starts through an activity so microphone access remains within Android's supported user interaction flow. Inputs and resource budgets are bounded. Background start rejection produces a resume notification.

## Optional tools

`CaptureTools` owns camera, screen, location and recognition lifetimes. GPS and spoken-camera choices are durable preferences; runtime tools resume when recording starts and pause when recording stops. Android screen-sharing consent is session-specific. Valid history/quality settings persist immediately while the active history retains its original configuration until the next capture start. Camera and screenshot implementations are adapted from copa-sh's Java revision, with cleanup fixes, bounded image size, supported foreground types and native consent. GPX points are bounded and acknowledged only after atomic trace completion; traces remain independent files. Pending GPS points are flushed on successful audio export before reporting its context as ready. Camera phrase recognition shares PCM, uses a bounded queue with coalesced drain jobs, and does not gate quiet speech by amplitude. It consumes final recognized utterances and matches whole, unambiguous phrases. Queue overflow resets recognition to avoid joining a command across missing audio.

`TraceSync` streams saved files and reconstruction manifests only after explicit configuration/use. Android Keystore encrypts the token. HTTPS, header authentication, checksummed acknowledgements, bounded response size and an idempotent content-hash API replace the source uploader's delete-on-2xx contract. Local files are never deleted by sync. The optional Go receiver syncs files and the parent directory before acknowledging. A restore script reconstructs original names from manifests.

One native player is owned by the visible clip detail and releases its player and audio focus on stop. The remaining experimental dual-source mixer and multi-quality history are not included because their source implementations lack a valid synchronized/encoded end-to-end path; evidence is in the integration ledger.

## Android module and interface

The conventional `app` module contains `ui`, `settings`, `recording`, `storage`, `library`, `tools` and portable `core` packages. `MainActivity` hosts Record, Library, Tools and Settings fragments. Focused settings/tool pages use `DetailActivity` with native back navigation. `ServiceFragment` binds while visible; MainActivity also keeps an existing stopped buffer alive while switching top-level destinations. Activity results defer projection setup until the recording service has rebound after Android’s app picker.

Material 3 components supply navigation, dialogs, text fields, switches, chips, time pickers, sliders and recording detail controls. Reusable spacing uses dp/sp, screen and keyboard insets are applied at the activity root, and windows at least 600 dp wide use a navigation rail. Text fields show validation in place. Dialogs preserve unfinished values across recreation. Library I/O and model jobs run off the UI thread with lifecycle-aware results.

`AppSettings` preserves the existing preference keys. Individual changes save immediately. History/quality changes mark the next recording start as pending instead of replacing current audio. The appearance preference defaults to `system`; explicit `light` and `dark` choices persist and apply through native day/night APIs. The palette uses klob.io’s #00FF00 green on black in dark mode and #007500 for readable light-mode accents. Dynamic wallpaper colors do not override it.

Application ID, private data paths, signing certificate, automation action names and the externally referenced automation/tile components remain stable. A launcher alias preserves the original `.SaidItActivity` component for existing shortcuts; there is no legacy screen implementation. Obsolete IDE module files, legacy bitmap icon variants and the unused bundled-survey loader were removed. GPL and dependency attribution remain intact.

## Recording context

`RecordingRepository` loads only completed audio as primary entries. `RecordingInfo` derives the captured interval from the existing JSON start time and sample count, with bounded duration validation. `TimeWindow` uses inclusive start/exclusive end boundaries. Missing metadata falls back to native audio duration for playback display without inventing timestamp associations.

`RecordingContext` joins screenshot/legacy-event instants and overlapping video intervals, filters GPX fixes to the clip interval and deduplicates repeated fixes. `GpxReader` bounds input size and fix count, rejects XML declarations of entities, validates coordinates/timestamps and preserves explicit, invalid-fix and over-one-minute gaps as separate segments. New GPX timestamps retain milliseconds. New trace names use UTC; old traces fall back to filesystem time. Trace rename writes a hidden `.meta.json` preserving time, duration and type; sync includes this sidecar. Audio deletion never removes shared context.

`LibraryModel` performs local reads and file operations on a single worker. The detail screen owns playback and MapLibre lifecycles and restores its path, seek position and map camera across recreation. Maps draw local GeoJSON over OpenFreeMap Dark/Positron basemaps; unavailable basemaps fall back to a plain local route style and coordinate readout. Tile requests reveal the viewport to the provider, but audio and GPX files are not uploaded. Route display may sample very large traces; original fixes are retained.

Custom duration stores the active duration, explicit custom selection and last custom value separately. Migration preserves an older non-preset duration even when the next action selects a preset.


## Waveform, speech and transcript processing

`AudioDecoder` streams platform-decoded PCM through mono 16 kHz resampling. `WaveformPeaks` retains min/max bins starting at 10 ms and compacts pairs beyond 262,144 bins, keeping long-clip memory bounded. Binary `.wave` caches retain width, sample count and source file identity. The detail waveform shows relative amplitude, time ticks, green speech bands and a seek cursor; direct touch, pinch/button zoom and accessible progress actions control the native player.

Silero VAD 6.2.3 runs bundled ONNX weights with one CPU thread and 512-sample frames, 64-sample context and recurrent state. Hysteresis (0.5/0.35), 200 ms minimum speech, 300 ms pauses and 100 ms padding produce speech ranges independently of recognized English words. Vosk uses the optional English model with unrestricted vocabulary and word timestamps for transcription. Camera and file recognizers share one reference-counted native model.

WorkManager owns durable per-clip jobs, foreground progress/cancellation and retries. Only one speech job executes at once; waveform decoding can proceed independently. Automatic flags are off initially and enqueue only after a local audio save. Transcription includes speech detection. Results are atomically published under a file-operation lock after checking source identity; renames/deletes cancel jobs and move/delete companion files. Analysis JSON, waveform caches and transcript text are included in configured backup. Transcript rendering is paginated to avoid building unbounded text spans on the UI thread.

## Spoken camera recording

`CameraRecorder` selects the requested front/back lens and records Camera2 H.264 video. `CameraAudio` receives the recorder's existing 48 kHz PCM through a bounded queue and encodes AAC on its own thread. No second AudioRecord or MediaRecorder microphone is opened. `VideoMuxer` combines the tracks into the final MP4 only after both complete. Startup/no-audio/queue/encoder failures never report a silent video as a successful video-with-sound capture. Captures stop with the service and valid shorter clips are finalized; temporary video/audio files stay outside the visible Library. The default duration is 30 seconds and the configurable range is 5–600 seconds. Busy recording/finalization rejects overlapping commands. Shake sensors, keyword notification/event generation and amplitude-triggered audio export are removed; old saved context remains readable.

`AmplitudeHistory` keeps a bounded live min/max envelope: 100 ms bins for ten minutes and one-second bins for 24 hours, approximately 361 KiB. It observes the existing PCM stream without retaining raw audio. The preview is clipped to the actual savable history and chosen lookback, and aggregated to the view width; starting fresh history resets it. Notes/surveys and their creation/import code are removed. Their old files remain in storage for compatibility and backup, but are excluded from the capture browser and clip associations.

`CaptureConflicts` reads `AudioManager.getActiveRecordingConfigurations()` before interactive microphone starts/source switches and excludes echo’s own audio session. It does not open an input to test availability or request privileged app-identity permissions. AOSP filters system-only sources such as remote submix from `RecordingActivityMonitor`, so device-audio/screen capture always has an honest projection-replacement warning. If a device exposes remote submix, it is classified separately. Call-mode and unavailable-query states have explicit wording. Confirmation uses a restorable dialog and fragment result, and cancellation performs no capture action.
