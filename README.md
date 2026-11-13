# echo

Save audio from the past. This fork consolidates the recorder and useful additions from the echo fork network into one Android app.

Android 11 or newer. Application ID: `com.klob.echo`, so it can coexist with original echo. Current release: [2.4.1](https://github.com/xklob/echo/releases/tag/v2.4.1), versionCode `22`.

## Roadmap

- [x] A comprehensive UI/UX overhaul, delivered in 2.2.0.
- [ ] Wearable integration, such as recording from a watch.
- [ ] A web dashboard and control panel.
- [ ] Semantic search through recordings.
- [ ] Optional server transcription.

These are separate from unfinished source integration. See the [custom-feature backlog](docs/custom-feature-backlog.md).

## Use

echo checks for another active microphone recording before you start or switch to the microphone. A warning offers **CANCEL** or **CONTINUE** without probing or taking over the input first. Android hides app identities and normally hides system-audio capture from this check, so screen sharing and device-audio capture always show a session-replacement warning before Android’s permission screen. These interactive checks cannot prevent another app taking priority later; runtime interruption handling remains active.

Open **Record**, tap **START RECORDING**, allow microphone access, then choose how far back to keep and tap **SAVE CLIP**. **STOP RECORDING** stays stopped. After a reboot or process recreation, open echo and tap **RESUME RECORDING**; Android does not permit unrestricted microphone startup from a boot receiver.

Use **Library** to browse audio clips. Cards show duration, source and available context. Tap a clip for a detailed waveform, playback, speech analysis, transcription, GPS locations and related captures; its overflow menu has share, rename and delete. **Saved captures** in the Library menu or Tools opens the separate files. **Settings** holds recording preferences. Bottom navigation becomes a side rail in wider windows.

**Custom duration** remembers your entered value and whether it is selected, including after reopening the app. Selecting a preset keeps that custom value available. Saving uses as much of the selected duration as is actually present in history.

Clip context is matched by time. GPS fixes and screenshots inside the captured interval appear with that clip; videos may overlap the interval. Overlapping audio clips can share the same context. GPS gaps remain separate route segments. Files without reliable recording timestamps remain playable but are not given guessed context. Renaming preserves associations; deleting audio keeps the separate captures and GPS files.

Maps use MapLibre with OpenFreeMap basemaps. The route is drawn from local GPS files. Online map requests disclose the viewed area to the tile provider; echo does not upload the GPX file or audio to it. Without a basemap connection, saved coordinates and the route remain available. Maps do not request live location permission to display an existing clip.

Settings control history budgets, quality, format, automatic saves and listening rules. Each switch saves immediately; value dialogs validate before saving. There is no global Apply button. Changes are saved while recording; history and quality changes take effect when recording next starts. Invalid values are highlighted instead of silently ignored. Equal start/end hours mean all day; overnight windows are supported. Android can delay schedule transitions when exact-alarm access is unavailable.

Saved files stay in private app storage. Share recordings before uninstalling. An optional folder picker creates verified additional copies; library rename/delete affects local files. Folder copies remain independent and do not follow automatic local retention. Revoked grants and provider errors are reported while the local recording is kept. No all-files permission is requested.

The Record screen shows the amplitude history of the audio that **SAVE CLIP** would keep. It expands as history fills, then rolls with the selected duration, fitting the available screen width. Only available audio is shown.

**Rolling history is temporary:** the oldest audio is replaced as the RAM/storage budget fills. **SAVE CLIP** copies the selected recent audio into Library. It does not start a recording of that length. Unsaved history is cleared by a new recording session or process restart.

**Keep this session in clips** saves new audio from the moment you enable it, using the interval in **Settings → Saving clips**. Turning it off or stopping recording saves the final short clip. **Save clips automatically in every session** is the persistent alternative. Retention applies separately to those two groups; manual clips stay until deleted. Choose an interval shorter than available history, or enable early saving as history fills. Only one audio export runs at once.

History is compressed before saving. WAV and FLAC decode that Opus history; they cannot restore discarded quality. Opus preserves compressed packets without re-encoding. AAC can include encoder priming/padding; the clip JSON records captured duration separately. Approximate file sizes are labelled as estimates.

Microphone interruptions freeze usable history. Recovery starts a new history and reports the reset, so files do not pretend gaps were captured. Switching audio source, history storage or quality also starts new history. Saved clips remain.

## Optional tools

Tools are off until enabled. Location and spoken-camera choices persist across recording sessions. Stopping recording pauses them without clearing their choices. Camera, location and screen capture request their permissions or native Android consent separately. Capture state remains visible in the foreground notification.

- **Spoken camera commands:** enable in **Tools → Spoken camera commands**. Say **echo back echo** for the back camera or **echo front echo** for the front camera. Both default to **30 seconds of video with sound**. Edit each phrase and choose 5–600 seconds. Buttons can record either camera manually. Full phrases trigger after recognition completes; commands during a camera recording/save are ignored. Requires microphone recording, camera permission and the optional English model. No second microphone is opened. Missing-camera controls are disabled; a front-camera-only device never substitutes its front camera for a back-camera command. Camera failures leave audio recording running.
- **Speech and transcripts:** each audio clip has a waveform you can scrub and zoom. **ANALYZE SPEECH** marks speech regions in green using the bundled, offline Silero detector. **TRANSCRIBE** produces English text with tappable word timestamps and a shareable text file, and also marks speech. Install the ~40 MB Vosk English model under **Settings → Speech and transcripts**. The same model serves camera commands. Processing stays on the device, uses additional memory/battery, and can make mistakes; silence or an empty transcript is not proof that nobody spoke.
- Separate **automatic speech detection** and **automatic transcription** switches apply to newly saved audio clips and default off. Existing clips use the manual controls. Work is queued with visible, cancelable progress. Renaming and deleting clips includes their analysis and transcript files. Automatic audio-folder exports happen before analysis; later analysis is retained locally and included in configured server backups, and transcripts can be shared separately.
- GPS writes timestamped GPX traces. Audio sample times and GPS fixes share wall-clock associations, not sample-perfect synchronization.

- Screenshots require native screen-capture consent and stop with that consent/session. Device-audio capture needs a separate consent session and works only with permitted playback usages/apps. It cannot run alongside screenshots in this build. Microphone/device source changes explicitly reset history.
- Another app starting screen sharing can end echo’s projection session. If echo was recording device audio, recording stops; microphone recording does not depend on a projection session. Two apps competing for the microphone may be silenced by Android’s priority rules.
- Server sync is opt-in; its token is protected by Android Keystore. See [server](server/README.md). No upload occurs with default settings.

Shake detection, general keyword alerts, loudness-triggered audio saves, notes and surveys were removed in 2.4.0. Older note/survey files are left intact but no longer appear as clip context or tools. Existing saved clips and events remain accessible.

Simultaneous microphone + device-audio synchronization and automatic multi-quality history remain blocked source experiments. No placeholder controls are shipped. See the [integration ledger](docs/integration-ledger.md).

## Build and validation

Install ffmpeg for the independent export tests. JDK 17, Gradle 9.6.0, Android Gradle Plugin 9.5.0, compile/target API 37. Build Tools 36.0.0. Install SDK `platforms;android-37.0` and accept its license, then set `ANDROID_HOME` or an untracked `local.properties`.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Debug and release builds use different certificates. Export any wanted data from an earlier development build before replacing it with the production app; Android will reject an in-place update across those certificates. Future production updates retain the dedicated release certificate and increase versionCode.

For a signed minified release, set `ECHO_SIGNING_PROPERTIES` to a private properties file containing `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Resolve the keystore path relative to that file. Keep the key and credentials outside Git:

```sh
ECHO_SIGNING_PROPERTIES=/private/path/signing.properties ./gradlew assembleRelease lintRelease
```

Without signing configuration, release builds remain unsigned. klobstore tracks the standalone signed APK from GitHub Releases.

See [testing](docs/testing.md), [architecture](docs/architecture.md), [automation](docs/automation.md), [changelog](CHANGELOG.md), and the separate [custom-feature backlog](docs/custom-feature-backlog.md). Device coverage and physical-device gaps are recorded in testing, rather than inferred from a successful build.

## Attribution

Fork of [mafik/echo](https://github.com/mafik/echo), preserving upstream history and [GPL-3.0](LICENSE.txt). Adapted work and dependencies are listed in [third-party notices](docs/third-party-notices.md) and the source ledger. Contributor repositories are read-only integration sources.
