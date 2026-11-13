# Echo

Save audio from the past. This fork consolidates the recorder and useful additions from the Echo fork network into one Android app.

Android 11 or newer. Application ID: `com.klob.echo`, so it can coexist with original Echo. Current release: [2.1.0](https://github.com/xklob/echo/releases/tag/v2.1.0), versionCode `17`.

## Base features

The original Echo workflow: keep rolling microphone history and save audio from the past. Listening continues in the background until you stop it.

<img src="docs/screenshots/base-recorder.png" width="300" alt="Echo listening with 90 seconds of available history and a one-minute lookback selected" />

The screenshots show the current 2.1.0 interface on an Android 17 emulator, using generated test recordings.

## Features integrated from other forks

- Compressed Opus RAM history, bounded disk storage, memory budgets and direct packet exports.
- Material 3 controls, waveform, custom lookback, timestamp filenames, save progress and cancellation.
- Opus/Ogg, WAV, FLAC and AAC/M4A exports, scoped folder copies, and a recordings/trace library with playback, sharing, rename and delete.
- Autosave, rotating continuous clips, activity saves, active hours, battery threshold, notification actions, Quick Settings and opt-in automation.
- Optional offline keyword alerts, GPS/GPX, notes, surveys, shake camera clips, consented screenshots and permitted device-audio capture.
- Optional authenticated HTTPS sync with verified acknowledgements and retained local originals.

Integrated from Toldya, good-echo, saidIT, mikenrafter, ElliotBadinger and copa-sh. Exact source revisions, replacements and remaining blockers are in the [integration ledger](docs/integration-ledger.md).

**History, exports, activity and listening rules**

<p>
  <img src="docs/screenshots/integrated-history-export.png" width="300" alt="RAM and disk history budgets, Opus bitrate, export format and automatic-save settings" />
  <img src="docs/screenshots/integrated-rules-activity.png" width="300" alt="Amplitude activity settings, active hours, minimum battery, folder copies and external automation" />
</p>

**Recordings and optional tools**

<p>
  <img src="docs/screenshots/integrated-library.png" width="300" alt="Shared library containing a saved recording and timestamped note" />
  <img src="docs/screenshots/integrated-capture-tools.png" width="300" alt="Optional GPS, shake camera, screenshots, device-audio capture and offline keyword controls" />
  <img src="docs/screenshots/integrated-notes-sync.png" width="300" alt="Notes, survey import and optional HTTPS server-sync settings with no credentials entered" />
</p>

## Our features

Reserved for future features developed specifically for this fork. No custom features have been added yet; the [custom-feature backlog](docs/custom-feature-backlog.md) is separate from consolidation work.

## Use

Tap **LISTEN**, allow microphone access, then choose how far back to save. **STOP** stays stopped. After a reboot or process recreation, open Echo and tap **RESUME**; Android does not permit unrestricted microphone startup from a boot receiver.

Settings control history budgets, quality, format, automatic saves and listening rules. Equal start/end hours mean all day; overnight windows are supported. Android can delay schedule transitions when exact-alarm access is unavailable.

Saved files stay in private app storage. Share recordings before uninstalling. An optional folder picker creates verified additional copies; library rename/delete affects local files. Folder copies remain independent and do not follow automatic local retention. Revoked grants and provider errors are reported while the local recording is kept. No all-files permission is requested.

Automatic retention applies separately to automatic, continuous and activity clips. Manual clips remain until deleted. A continuous segment uses the configured autosave interval; stopping flushes its remaining tail. Only one export runs at once.

History is compressed before saving. WAV and FLAC decode that Opus history; they cannot restore discarded quality. Opus preserves compressed packets without re-encoding. AAC can include encoder priming/padding; the clip JSON records captured duration separately. Approximate file sizes are labelled as estimates.

Microphone interruptions freeze usable history. Recovery starts a new history and reports the reset, so files do not pretend gaps were captured. Switching audio source, history storage or quality also starts new history. Saved clips remain.

## Optional tools

Tools are off until explicitly enabled. STOP turns capture tools off. Camera, location and screen capture request their permissions or native Android consent separately. Capture state remains visible in the foreground notification.

- Activity saves detect loudness, including non-speech. Threshold and pre/post-roll are configurable. Ordinary quiet audio remains saveable.
- Keyword recognition uses the same captured samples. Install the optional US-English Vosk model from **Optional tools**; recognition stays offline after installation. The approximately 40 MB download and substantial native model RAM are additional to the history budget. Both library and supplied model use Apache-2.0.
- GPS writes timestamped GPX traces. Audio sample times and GPS fixes share wall-clock associations, not sample-perfect synchronization.
- Shake capture uses four swings followed by a return to the prior level of motion. The current configuration records silent five-second rear-camera clips. A capture-now button is also available. Camera/microphone concurrency depends on the device.
- Screenshots require native screen-capture consent and stop with that consent/session. Device-audio capture needs a separate consent session and works only with permitted playback usages/apps. It cannot run alongside screenshots in this build. Microphone/device source changes explicitly reset history.
- Notes and imported branching surveys are saved as separate traces. See [survey format](docs/survey-format.md).
- Server sync is opt-in; its token is protected by Android Keystore. See [server](server/README.md). No upload occurs with default settings.

Simultaneous microphone + device-audio synchronization and automatic multi-quality history remain blocked source experiments. No placeholder controls are shipped. See the [integration ledger](docs/integration-ledger.md).

## Build and validation

Install ffmpeg for the independent export tests. JDK 17, Gradle 9.6.0, Android Gradle Plugin 9.5.0, compile/target API 37. Build Tools 36.0.0. Install SDK `platforms;android-37.0` and accept its license, then set `ANDROID_HOME` or an untracked `local.properties`.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

Debug APK: `SaidIt/build/outputs/apk/debug/SaidIt-debug.apk`. Debug and release builds use different certificates. Export any wanted data from an earlier development build before replacing it with the production app; Android will reject an in-place update across those certificates. Future production updates retain the dedicated release certificate and increase versionCode.

For a signed minified release, set `ECHO_SIGNING_PROPERTIES` to a private properties file containing `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Resolve the keystore path relative to that file. Keep the key and credentials outside Git:

```sh
ECHO_SIGNING_PROPERTIES=/private/path/signing.properties ./gradlew assembleRelease lintRelease
```

Without signing configuration, release builds remain unsigned. Klobstore tracks the standalone signed APK from GitHub Releases.

See [testing](docs/testing.md), [architecture](docs/architecture.md), [automation](docs/automation.md), [changelog](CHANGELOG.md), and the separate [custom-feature backlog](docs/custom-feature-backlog.md). Device coverage and physical-device gaps are recorded in testing, rather than inferred from a successful build.

## Attribution

Fork of [mafik/echo](https://github.com/mafik/echo), preserving upstream history and [GPL-3.0](LICENSE.txt). Adapted work and dependencies are listed in [third-party notices](docs/third-party-notices.md) and the source ledger. Contributor repositories are read-only integration sources.
