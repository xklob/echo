# Changelog

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
