# Changelog

## 2.3.0 // 2026-11-12

- Make audio clips the primary Library entries, with duration, source, GPS coordinates and related-note/capture previews.
- Open each clip in a detail screen with playback, seeking, a native interactive map, timestamped context and recording metadata.
- Match saved GPS fixes, notes, keyword events, surveys and captures to the recording interval. Preserve location gaps and shared context across overlapping clips; keep notes and captures accessible separately.
- Flush pending GPS fixes when a clip is saved. Preserve millisecond GPS timestamps and stable UTC trace timestamps; renamed traces keep their original time and type in a sidecar.
- Persist custom duration and its selected state across navigation and app restarts. Switching to a preset no longer loses the remembered custom value, including values from older installations.
- Keep recording identity, signing, private files and settings compatible with earlier releases. Remove the replaced playback-sheet implementation and update the screenshot gallery.

## 2.2.0 // 2026-11-12

- Rebuild the interface around Record, Library, Tools and Settings, with adaptive bottom navigation / side rail, focused detail screens and native back navigation.
- Replace the global Apply form with persistent switches and validated Material dialogs; use native time selection for recording schedules.
- Add System, Light and Dark appearance choices. System is the default; dark mode uses black surfaces and paradigm green.
- Rewrite labels, instructions and status messages. Distinguish saved tool choices, active collection, missing permission and missing speech model.
- Add a filtered library with readable titles, clip durations, overflow actions and a playback sheet with pause/seeking. Preserve note, survey and dialog drafts across screen recreation.
- Rename the Android module to app, organize feature packages, adopt the standard Gradle plugins layout, replace legacy icons with vectors/adaptive icons and remove obsolete IDE metadata. Preserve application identity, recordings, preferences, release signing and external controls.
- Guard rapid recording restarts against stale stop callbacks and reset displayed history counters when a new capture opens.
- Refresh the README with one gallery of actual app screenshots and mark the UI overhaul complete in the roadmap.

## 2.1.1 // 2026-11-12

- Save valid settings while recording and preserve them when leaving and reopening Settings. History/quality changes apply at the next recording start without discarding current history.
- Highlight invalid values beside their fields and show visible save feedback.
- Persist GPS, shake-camera and offline keyword-alert preferences across stops and service restarts. Stopping recording pauses tools without clearing their saved choices.
- Resume saved keyword alerts after model installation. Screen-sharing consent remains session-specific, as Android requires.
- Consolidate README screenshots into one gallery and add the requested future-feature roadmap.

## 2.1.0 // 2026-11-12

- Separate `com.klob.echo` identity with original upstream history/GPL retained.
- One compressed capture/history/export pipeline, RAM and bounded disk history, pinned concurrent snapshots, waveform and flexible lookback.
- Validated streaming Ogg/Opus, decoded WAV/FLAC and platform AAC/M4A. Capacity-aware codec input, complete EOS draining, final-frame trimming and FLAC duration metadata.
- Atomic local saves, unique start-time names, cancellation, verified SAF copies and a shared recordings/trace library.
- Autosave/continuous retention, overnight/DST rules, battery threshold, quiet foreground status, notification actions, Quick Settings and opt-in automation.
- Optional activity saves, offline Vosk alerts/model installation, GPS/GPX, notes, branching surveys, rear-camera shake clips, screenshots and permitted device-audio capture.
- Optional HTTPS sync/client server with Keystore-protected credentials, durable checksum acknowledgements, local retention and a reconstruction script.
- Modern Android foreground/permission handling; supported user resumption after reboot/process loss; visible microphone recovery/history reset.
- Real compilation/tests/independent decoders and CI. Remove obsolete billing/license resources, unused jars/fonts and agent-dashboard churn.

Simultaneous dual-source synchronization and multi-quality ageing remain source experiments with concrete gaps recorded in the integration ledger. Physical Pixel testing remains outstanding; emulator coverage and measured limits are documented in `docs/testing.md`.
