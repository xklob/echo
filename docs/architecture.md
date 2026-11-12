# Implemented architecture

`SaidItService` owns one active AudioRecord, one capture HandlerThread, one history, one settings store and one export worker. The activity binds for controls/state and starts the foreground service from visible user interaction. Capture does not start in `onCreate` or from boot. Foreground promotion precedes microphone use. STOP persists disabled state and waits for an in-flight save/final continuous tail before removing foreground visibility.

## Capture and time

Audio is 48 kHz mono PCM16. A reusable 50 ms read buffer feeds the Opus history, waveform, optional activity detector and optional keyword recognizer. Nonblocking reads are polled; repeated negative/empty reads and dead audio objects trigger cleanup/retry. Android recording callbacks distinguish client silencing from genuine quiet audio. Silenced/failed input never increases usable history. Permission errors remain visible.

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

Manual, automatic, continuous and activity categories share this exporter. Automatic cursors advance after local completion even if an additional folder copy fails. Fill triggers measure new data since that cursor; failed local exports back off. STOP/continuous-stop save a pending tail after an existing job, retaining foreground ownership until completion. Continuous/automatic limits never delete manual clips. Folder copies and server data have independent retention.

## Rules and controls

`ListeningRules` evaluates local active-hour windows, including overnight/all-day cases and timezone/DST resolution. A user-disabled session never restarts through rule checks. Exact alarms are used only when available; otherwise allow-while-idle inexact alarms provide a delayed fallback. Boot/process loss requires visible user resumption. A minimum battery threshold shares the same rule evaluation.

Notification actions, Quick Settings and opt-in versioned external broadcasts issue commands to the same service. Quick Settings starts through an activity so microphone access remains within Android's supported user interaction flow. Inputs and resource budgets are bounded. Background start rejection produces a resume notification.

## Optional tools

`OptionalTools` owns opt-in camera, screen, location and recognition lifetimes. Camera and screenshot implementations are adapted from copa-sh's Java revision, with cleanup fixes, bounded image size, supported foreground types and native consent. GPX points are bounded and acknowledged only after atomic trace completion; traces, notes and survey answers share one library. Survey nodes/answers/import size have explicit limits. Recognition shares PCM, uses a bounded queue with coalesced drain jobs, and does not gate quiet speech by amplitude.

`TraceSync` streams saved files and reconstruction manifests only after explicit configuration/use. Android Keystore encrypts the token. HTTPS, header authentication, checksummed acknowledgements, bounded response size and an idempotent content-hash API replace the source uploader's delete-on-2xx contract. Local files are never deleted by sync. The optional Go receiver syncs files and the parent directory before acknowledging. A restore script reconstructs original names from manifests.

One native library player owns playback and releases on activity stop. The remaining experimental dual-source mixer and multi-quality history are not included because their source implementations lack a valid synchronized/encoded end-to-end path; evidence is in the integration ledger.
