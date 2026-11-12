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

All commands passed locally. Release is minified and unsigned. JNA's unreachable desktop AWT references have specific R8 warning exclusions; Vosk/JNA native entry points are retained. Gradle reports future Gradle 10 deprecations. Lint has no fatal errors. Server: Go 1.26.8, standard library only; restore uses Python 3.11+.

16 JVM tests pass: 8 history/container/export, 3 scheduling/DST, 2 activity boundaries and 3 relative-motion gestures. Go has 2 auth/hash/durable/idempotency tests; Python has 2 restore/safe-path/idempotency tests.

GitHub Actions builds the app/test APK, runs JVM tests/lint and tests the server/restore utility. It verifies ffmpeg is installed. It does not claim to run a phone emulator. The earlier stage-2 workflow failed during SDK setup because the default obsolete `tools` package disappeared; the workflow now pins a current command-line tools archive and valid SDK package paths.

## Android matrix

| Target | Actual coverage |
| --- | --- |
| Android 11 / API 30, Google APIs x86_64 emulator | All 15 functional device tests pass, plus final actual URI-grant revocation rerun. |
| Android 16 / API 36, Google APIs x86_64 emulator, Pixel 9 Pro XL profile | 13 functional tests pass before final quiet-speech/autosave regressions and API-37 upgrade. Capture/save/platform formats, consented screen/playback, camera+mic, schedules, provider faults and sync covered. |
| Android 17 / API 37, Google APIs x86_64 emulator, Pixel 9 Pro XL profile | All 15 functional tests pass, plus final actual URI-grant revocation rerun. Opt-in screen-off/resource soak runs separately below. |
| Physical Pixel 9 Pro XL / OEM devices | Unavailable. Neither Windows nor WSL ADB lists a physical target. No physical call/Bluetooth/Doze/battery/lens validation is claimed. |

Device tests exercise platform Opus/WAV/FLAC/AAC output, collisions/invalid destination, repeated start/stop, screen-off sample advancement, continuous final tail, schedule pause/resume with denied exact alarms, manual-stop priority, automatic retention and failed-copy duplicate prevention. The test-only DocumentsProvider injects permission-style failure, ENOSPC/write failure, truncated readback and cancellation; actual Android URI grants are also revoked. Local originals survive failures. Manual files do not rotate.

Optional tests exercise GPX segments/XML/write failure, survey branches/answers, Keystore token encryption, missing model, installed offline keyword recognition, 50× attenuated speech, concurrent silent camera video + microphone, native screenshot consent/JPEG, native playback consent and a separate-UID capturable tone, and trusted test-localhost TLS upload with invalid acknowledgement followed by a verified retry. Generated test tokens/certificates are not production credentials and are absent from the review APK.

Explicit serials avoid ambiguity when multiple emulators are attached. Tests reset only this development package's generated preferences/files:

```sh
adb -s emulator-5558 install -r SaidIt/build/outputs/apk/debug/SaidIt-debug.apk
adb -s emulator-5558 install -r SaidIt/build/outputs/apk/androidTest/debug/SaidIt-debug-androidTest.apk
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

Fixture keyword latency was approximately 1.1 seconds from fixture start for both normal and 50× attenuated speech on Android 17. This is one fixture, not a general recognition accuracy or acoustic latency guarantee. Resource measurements are from x86_64 software-device emulation. They cannot establish battery life, thermal behavior or hours of physical capture. Optional model memory is additional to history limits.

## Executable physical-device checklist

Use the distinct development application ID. Preserve/share any desired recordings before uninstalling or changing signing keys. Keep passwords/server tokens in native fields.

- Install with `adb -s SERIAL install -r APK`; cold launch, deny then grant mic/notifications independently, LISTEN/STOP/re-enable, custom lookback, save/play/share/rename/delete, last clip. Confirm notifications and OS microphone indicator match state.
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
