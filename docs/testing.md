# Validation

Date: 2026-11-12. Emulator results are not physical Pixel or battery certification.

## Build

Untouched upstream `007fdf30250a11e8432dd6b9edeeff7497cb9aad` built before edits using JDK 17.0.21, Gradle 8.11.1, AGP 8.10.1 and SDK 34 (`bash gradlew assembleDebug`; original wrapper lacked execute permission).

Final development toolchain: OpenJDK 17.0.21, Gradle 9.6.0 with distribution SHA-256 pinned, AGP 9.5.0, compile/target API 37, min API 30, SDK package `platforms;android-37.0`, Build Tools 36.0.0. Android SDK/emulator images are installed outside the repo. Dependency versions are explicit. No private signing key is committed.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease
(cd server && go test ./... && go build .)
python3 -m unittest discover -s server -p 'test_*.py'
```

All commands passed locally. [GitHub Android and server validation](https://github.com/xklob/echo/actions/runs/43329148011) also passed for the consolidated implementation. Release is minified and unsigned. JNA's unreachable desktop AWT references have specific R8 warning exclusions; Vosk/JNA native entry points are retained. Gradle reports future Gradle 10 deprecations. Lint has no fatal errors. Server: Go 1.26.8, standard library only; restore uses Python 3.11+.

16 JVM tests pass: 8 history/container/export, 3 scheduling/DST, 2 activity boundaries and 3 relative-motion gestures. Go has 2 auth/hash/durable/idempotency tests; Python has 2 restore/safe-path/idempotency tests.

GitHub Actions builds the app/test APK, runs JVM tests/lint and tests the server/restore utility. It installs ffmpeg explicitly for independent decoding. It does not claim to run a phone emulator. An intermediate run confirmed the runner image no longer supplied ffmpeg; the workflow now installs it. The earlier stage-2 workflow failed during SDK setup because the default obsolete `tools` package disappeared; the workflow now pins a current command-line tools archive and valid SDK package paths.

## Original review artifact

The local review APK is `dist/echo-2.1.0-dev-debug.apk`, approximately 52 MiB, application ID `com.klob.echo`, version `2.1.0-dev` / 16. SHA-256:

```text
0a2432cecb5521f599fadf0e7969e16d2a8be81517f5c4e3d395917a6b205aa4
```

APK Signature Scheme v2 verification and `zipalign -c -P 16 4` passed. An actual `install -r` retained the complete generated recording library, verified by hashing its archive before and after installation. Native main/recordings screens were inspected. The APK contains neither test fixtures/components nor model weights. The private development signing key remains outside Git; that review build predates production signing. Follow-up validation commits before release preparation changed CI, tests and documentation rather than the app implementation.

## Android matrix

| Target | Actual coverage |
| --- | --- |
| Android 11 / API 30, Google APIs x86_64 emulator | All 15 functional device tests pass, plus actual URI-grant revocation and competing-private-microphone checks. |
| Android 16 / API 36, Google APIs x86_64 emulator, Pixel 9 Pro XL profile | 13 functional tests pass before final quiet-speech/autosave regressions and API-37 upgrade. Capture/save/platform formats, consented screen/playback, camera+mic, schedules, provider faults and sync covered. |
| Android 17 / API 37, Google APIs x86_64 emulator, Pixel 9 Pro XL profile | All 15 functional tests pass, plus actual URI-grant revocation and competing-private-microphone checks. Opt-in screen-off/resource soak runs separately below. |
| Physical Pixel 9 Pro XL / OEM devices | Unavailable. Neither Windows nor WSL ADB lists a physical target. No physical call/Bluetooth/Doze/battery/lens validation is claimed. |

The competing-private-microphone test uses a separate UID and Android privacy-sensitive AudioRecord: usable history freezes while Android silences Echo, then capture resumes with a new history and a visible gap notice. It passed on Android 11 and 17. This tests platform priority handling, not physical telephony or Bluetooth routing.

Device tests exercise platform Opus/WAV/FLAC/AAC output, collisions/invalid destination, repeated start/stop, screen-off sample advancement, continuous final tail, schedule pause/resume with denied exact alarms, manual-stop priority, automatic retention and failed-copy duplicate prevention. The test-only DocumentsProvider injects permission-style failure, ENOSPC/write failure, truncated readback and cancellation; actual Android URI grants are also revoked. Local originals survive failures. Manual files do not rotate.

Optional tests exercise GPX segments/XML/write failure, survey branches/answers, Keystore token encryption, missing model, installed offline keyword recognition, 50× attenuated speech, concurrent silent camera video + microphone, native screenshot consent/JPEG, native playback consent and a separate-UID capturable tone, and trusted test-localhost TLS upload with invalid acknowledgement followed by a verified retry. Generated test tokens/certificates are not production credentials and are absent from the review APK.

Explicit serials avoid ambiguity when multiple emulators are attached. Tests reset only this development package's generated preferences/files:

```sh
adb -s emulator-5558 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5558 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5558 shell am instrument -w com.klob.echo.test/androidx.test.runner.AndroidJUnitRunner
```

`PerformanceDeviceTest` is skipped by default and requires the explicit duration arguments below. The optional model tests require the publisher's HTTPS download on first run. No model weights are checked in.

## Independent media checks

`HistoryExportTest` independently parses Ogg CRC with polynomial `0x04c11db7`, page flags/serials, lacing boundaries and final granules. ffmpeg decodes output and checks exact sample counts for partial inputs of 7, 312, 960, 967, 48000 and 480123 samples, concurrent wrapped history, cancellation and disk-pinned eviction. Tone output is compared with a bounded lossy reconstruction error; the test does not just compare implementation metadata with itself. Tests require local ffmpeg for these independent checks.

Device-generated 98,917-sample input is 2.0607708 s at 48 kHz. WAV and repaired FLAC report 2.060771 s. Opus decoded PCM is sample-exact in length; Ogg duration may include its 312-sample pre-skip. AAC/M4A reports 2.124771 s on this encoder, +64 ms priming/padding. JSON records captured sample duration separately; AAC is not advertised as sample-exact. All four outputs decode successfully with independent ffmpeg. WAV/FLAC decode lossy Opus history and cannot restore original PCM quality.

```sh
ffprobe -v error -show_entries stream=codec_name,sample_rate,channels,duration -show_entries format=duration -of json clip.opus
ffmpeg -v error -i clip.opus -f s16le -acodec pcm_s16le -ac 1 -ar 48000 decoded.pcm
```

ARM64 Vosk/JNA ELF load segments are 16 KiB aligned. Verify final APK ZIP alignment with `zipalign -c -P 16 -v 4 APK`; native alignment and Android 17 emulator tests do not substitute for physical ARM64 testing.

## Resource soak

The opt-in test runs real foreground capture with screen off, a 1 MiB history budget, 96 kbps selected quality, 30-second autosave and retention of five automatic clips. It samples actual history, process PSS/Java heap, captured samples and process CPU time every 30 seconds, then optionally enables keyword recognition on the same stream. It asserts sample advancement and, for a six-minute run, actual old-history eviction.

```sh
adb -s emulator-5556 shell am instrument -w \
  -e class com.klob.echo.PerformanceDeviceTest \
  -e soak_seconds 360 -e keyword_seconds 120 \
  com.klob.echo.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 logcat -d -s EchoSoak:I EchoValidation:I '*:S'
```

Measured Android 17 run passed in 482.771 s: 360 s core capture followed by 120 s with keyword recognition. [Raw sampled metrics](validation/2026-11-12-soak.csv) are parsed from actual instrumentation log output, not simulated results.

| Measurement | Core + autosave, 360 s | Keywords added, 120 s |
| --- | --- | --- |
| Process CPU / wall time, percentage of one emulated CPU | 6.85% | 8.99% including model load |
| Sampled process PSS | 55.10–56.39 MiB | 181.13–188.87 MiB after model load |
| Maximum sampled Java used heap | 7.45 MiB | 5.69 MiB |
| Maximum sampled accounted history | 921,329 bytes | 922,854 bytes |
| Final captured / retained samples | 17,288,328 / 2,884,488 | 23,053,128 / 2,889,288 |

The one-MiB active history wrapped by 90 s and stayed bounded while capture and 30-second autosaves continued. Sample time advanced at approximately 48 kHz. PSS includes native/shared memory and test-process overhead; Java used heap and history accounting are different quantities. The keyword phase used emulator microphone input, not a continuous real-world speech corpus. Native model memory is visibly much larger than the history budget. This short run supports bounded operation under these conditions, not an hours-long reliability or power claim.

Fixture keyword latency was approximately 1.1 seconds from fixture start for both normal and 50× attenuated speech on Android 17. This is one fixture, not a general recognition accuracy or acoustic latency guarantee. Resource measurements are from x86_64 software-device emulation. They cannot establish battery life, thermal behavior or hours of physical capture. Optional model memory is additional to history limits.

## Executable physical-device checklist

Use the distinct development application ID. Preserve/share any desired recordings before uninstalling or changing signing keys. Keep passwords/server tokens in native fields.

- Install with `adb -s SERIAL install -r APK`; cold launch, deny then grant mic/notifications independently, start/stop/resume recording, custom lookback, save/play/seek/share/rename/delete. Check System/Light/Dark, larger text, rotation and wider-window navigation. Confirm notifications and OS microphone indicator match state.
- Run screen-off capture for at least an hour with wraparound, autosave and disk mode; inspect actual history, memory, storage and output decodes. Repeat with battery saver and `adb -s SERIAL shell dumpsys deviceidle force-idle`, then `... unforce`. Measure physical battery drain with and without each optional tool.
- Place a call and use a competing recorder; revoke mic permission and change Bluetooth/wired routes. Confirm silenced/failed input does not grow usable history, recovery reports a reset, saved clips remain and no dead AudioRecord persists.
- Kill/recreate the app process, then reboot while enabled and while manually stopped/scheduled off. Confirm no phantom capture or unsupported boot mic startup; resume notification/visible action behaves as documented.
- Test overnight schedule and timezone changes; repeat with exact-alarm access denied. Manual STOP must remain stopped. Evaluate local DST transitions against `ListeningRulesTest` fixtures.
- Select a real DocumentsProvider folder; restart app, revoke its grant, fill destination and cancel a long export. Local file must survive; incomplete external output must not be reported as saved. Verify SHA-256 on successful copies. Check slow-provider behavior while capture advances.
- Enable GPS, shake camera and screenshots separately, grant/deny permissions and stop each. Walk, knock, shake/settle, trigger haptics and test front/rear availability where exposed. Inspect five-second silent MP4, widest rear framing and microphone continuity. Confirm STOP releases sensors/projection/location. Test protected-screen capture rejection.
- Test device audio with an allowed app, prohibited app and stopped projection. Source metadata must be accurate and there must be no hidden mic fallback. Simultaneous mic/device mixing is not available.
- Test missing model/offline installed model, quiet speech and non-speech, notification denial and model-load failure. Inspect CPU/PSS/battery. Notes/survey imports should handle malformed/large input without disturbing capture.
- Configure your optional HTTPS receiver and enter token natively. Test unavailable network, wrong token, invalid ack, retry, repeated sync and `restore.py`; compare restored hashes. Originals must remain local. No deletion, secret in URL/log or success before durable ack.
- Add Quick Settings tile and use documented v1 automation after explicit opt-in. Test excessive/invalid values and Android background-start rejection/resume path.

These physical checks and the two source experiments in [the ledger](integration-ledger.md) are remaining validation/integration limits, not custom feature requests.

## Signed 2.1.0 release preparation

The production APK is `echo-2.1.0.apk`, versionCode 17, using a dedicated RSA-4096 release key kept outside Git. The release certificate SHA-256 is `9830b437d226aa247e46793d75dd12b6e927b1bccc448551958490bdbebd91bc`.

APK SHA-256: `fa430c96542216a065151dbe5a190f28727cd5679ca0666120df887cc16dba9e`. APK signature and 16 KiB ZIP alignment checks pass. The normal production build was rebuilt after the isolated upgrade fixture, and matched the prepared release APK byte for byte. Debug/release lint, 16 JVM tests and both build variants passed.

A disposable Android 17 emulator runs a lower-version verification fixture signed with this same production key. It uses the minified release implementation with test-only version metadata; it is not a historical public release. Native LISTEN/SAVE/STOP recorded a completed 53,968-byte Opus clip, independently decoded by ffmpeg (48 kHz mono, approximately 9.92 s). This generated recording is retained for the real Klobstore update-to-code-17 preservation check. No user's existing app or recordings are removed.

The production certificate differs from the earlier debug review APK. Android correctly disallows replacing that debug installation in place; users must export wanted data before switching certificates. Future production releases preserve the production key. Physical Pixel validation remains outstanding.

## 2.1.1 persistence regression release

JVM tests, debug/release lint and both APK builds pass. Android 17 instrumentation reports `OK (20 tests)`: 19 functional tests, with the opt-in resource soak skipped. Three new regressions cover saving history settings during recording and reopening them, visible invalid-memory errors without overwriting preferences, and persistent optional-tool choices across stop/service recreation. Activity settings are snapshotted at recording start along with history configuration.

The signed minified APK upgraded the existing production 2.1.0 installation in place. The first-install timestamp and SHA-256 hashes of the existing generated clip and its metadata were unchanged. Release signature verification and 16 KiB alignment pass with the same production certificate. APK SHA-256: `4466abc905e88670b667bcd9f5bf19c0fa642329b91edc9ae5b3e548abe809df`.

## 2.2.0 interface and repository refactor

16 JVM tests, debug/release lint and all APK builds pass. Full Android 11 and Android 17 suites each report `OK (23 tests)`: 22 functional checks, with the opt-in resource soak skipped. New coverage verifies navigation and theme persistence, following a changed system theme, note drafts and single saves across recreation, keyword preferences, and numeric-dialog drafts. The final tool-message presentation change also passed seven focused interface, preferences and native projection checks on Android 17.

Rapid-start/stop coverage now exercises eight cycles. The recorder resets published counters when opening fresh history and ignores a queued stop callback after a newer start. The screen-off regression waits for actual initial PCM before turning the screen off, then asserts that capture continues. This distinguishes emulator microphone startup latency from screen-off recording.

The signed minified production build upgrades 2.1.1 in place. Its original install timestamp and SHA-256 hashes of prior generated audio and metadata remain unchanged. The production signing certificate is unchanged, and signature verification and 16 KiB ZIP alignment pass. APK SHA-256: `1c05e2cae0c1369c14a905e753ecbc64ddb2039e8e2871dfdf24005d5de8147f`.

A native save from the signed app produced a 327,572-byte Opus clip; ffmpeg independently decoded it as 48 kHz mono, with approximately 60 seconds of audio. Native playback, pause and seeking were exercised.

Native visual checks cover portrait, landscape navigation rail, 160% text size with scrollable controls, and both color themes. The README gallery contains direct emulator screenshots of the app using generated test content. Physical Pixel, battery, Bluetooth and OEM validation remain outstanding; the earlier resource soak was not repeated for this UI release.
