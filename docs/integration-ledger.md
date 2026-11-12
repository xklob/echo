# Integration ledger

Base: mafik/echo master `007fdf30250a11e8432dd6b9edeeff7497cb9aad`.
Destination: xklob/echo, branch `integration/consolidate-forks`.
Source refs were fetched on 2026-11-12. Exact tips: [source-refs.json](source-refs.json).
Contributor remotes are read-only inputs. Upstream history and GPL-3.0 are retained.

| Source | Borrowed behavior / assessment | Decision | Status / validation |
| --- | --- | --- | --- |
| mafik/echo | Rolling recorder, WAV, original resources | Keep history and baseline behavior | Untouched base builds with JDK 17, Gradle 8.11.1, AGP 8.10.1, SDK 34 |
| ArturLinnik/toldya | Material 3, waveform, duration, SAF, schedule, exports | Port cohesive UI; replace service races and incomplete encoder EOS draining | Source reviewed; pending integration |
| shkarlsson/good-echo | Compressed Opus RAM history, MB control | Keep packet-history design, use measured byte accounting | Source reviewed; pending integration |
| IbrahimF1/good-echo | Ogg flags/CRC correction | Replace defective writer with validated container handling; test lacing and termination too | Source reviewed; pending integration |
| spidey000/saidIT | Autosave, library, AAC, PCM chunking, save without speech | Share one pipeline; preserve sample-based PTS and input-capacity handling | Source reviewed; pending integration |
| mikenrafter/echo | Disk buffer, automation, amplitude activity | Bounded history with pinned snapshots; finish activity separately | DiskAudioBuffer deletes by file, can exceed bound; cannot borrow unchanged |
| ElliotBadinger/echo | Autosave, library, performance/threading | Evaluate overlapping PRs #28/#29 once, omit agent/health churn | Source fetched; detailed audit pending |
| copa-sh/echo | Recovery, traces, Vosk, camera/screenshots, uploader/server | Optional tools after core; no broad storage or secret-in-URL upload | Source reviewed; detailed audit pending |

## Stage 1: fork build and identity

- Original `./gradlew` was not executable; `bash gradlew assembleDebug` passed before edits.
- JDK: OpenJDK 17.0.21; original SDK 34 installed by Gradle. Gradle 8.11.1 and AGP 8.10.1 retained.
- Fork identity and intent/package namespace: `com.klob.echo`, versionCode 16.
- Target/compile SDK 36; minimum SDK 30 retained.
- Remove obsolete license/coupon links, unused license classes/key and release signing placeholders.
- Remove all-files permission and its launch gate. App-specific storage is the initial destination; SAF follows with the shared exporter.
- Notification denial does not gate microphone access.
- Capture lifecycle, snapshots, naming, library and compatibility checks remain active consolidation work, not completed claims.

## Stage 2: shared pipeline, UI and controls

Implemented compressed mono 48 kHz Opus history and direct packet export. Concentus 1.0.2 supplies the codec; VorbisJava 0.8 supplies Ogg framing. The original good-echo writer is not imported. Its CRC/flags problem is superseded, including lacing/stream termination regression checks. Streaming low-level VorbisJava avoids its high-level OpusFile's whole-clip buffering. Android's Ogg MediaMuxer was considered; exact sample trimming and JVM-independent validation favor the portable packet API here.

History uses accounted ten-second blocks, heap-based RAM limits, optional private disk segments and deterministic eviction. An exporter pins immutable blocks while capture continues. RAM has one additional snapshot reserve; disk's total budget reserves half for pins. Counters are updated incrementally, avoiding scans of disk history on every audio frame. Temporary history is separate from saved files.

Replaced competing service/UI/export paths with one capture thread, one export worker, one local library/player, one settings store. Toldya's waveform is adapted with attribution; Material 3 controls and flexible seconds/presets share one selection. Save without speech/classifier state is unconditional. WAV/FLAC decode the lossy history, rather than pretending to retain original PCM quality.

- Scoped private recordings; optional persistent SAF folder copy, verified by readback length, with provider failure reported and local copy kept.
- Timestamp/unique names, partial-file exclusion, atomic local completion, bounded autosave/continuous categories. Manual clips never rotate.
- Microphone callback silencing, dead-object/read watchdog, cleanup/retry. Recovery starts a fresh history with visible notice; it does not count inaccessible samples as usable audio or fabricate a continuous timeline across gaps.
- Quiet foreground status, notification save/stop, opt-in v1 automation, Quick Settings through a visible activity, overnight schedules and battery threshold. Reboot prompts user interaction instead of unsupported microphone autostart. Exact alarm denial uses inexact fallback.
- No classifier, alternate microphone service, broad storage grant, payment flow, agent framework or automated dashboard imported.

Validation so far: 8 JVM tests; 2 Android 16/API 36 Pixel 9 Pro XL emulator instrumented tests; real debug/lint builds. Independent ffmpeg decodes Android-produced Opus, WAV, FLAC and AAC/M4A. PCM samples are exact for WAV/Opus-decoded output; AAC has codec priming/padding (measured container duration +64 ms on this emulator). No physical-phone validation is claimed. FLAC STREAMINFO duration repair is undergoing revalidation.

Larger optional features remain active work. See the source-feature matrix as it is completed; stage 2 does not mark them finished.
