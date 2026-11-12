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
