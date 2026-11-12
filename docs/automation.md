# Automation v1

Enable **Allow external automation** in Settings. It defaults off. Broadcast to component `com.klob.echo/.AutomationReceiver`; actions use the `com.klob.echo.automation.v1.` prefix.

| Action suffix | Extras | Behavior |
| --- | --- | --- |
| START | None | Request listening; Android may require opening Echo first. |
| STOP | None | Persist disabled state, stop capture/tools and finish pending saves. |
| SAVE | `seconds` as a long, 1–86400; default 60 | Save available lookback. Reject invalid values. Requires an active session/history. |
| CONTINUOUS_START | None | Rotate saved segments using the configured interval/retention. |
| CONTINUOUS_STOP | None | Finish the remaining continuous tail. |
| SET_LIMITS | `ram_mb` int, 1–heap-derived maximum; `disk_mb` int, 16–1024 | Update limits only while user-disabled. No live allocation or source switch. |

Example for adb (use a permitted foreground/user interaction for START):

```sh
adb shell am broadcast -n com.klob.echo/.AutomationReceiver \
  -a com.klob.echo.automation.v1.SAVE --el seconds 300
```

The receiver never returns file paths, grants or recordings to its caller. External control is intentionally available to other apps only after opt-in; disable it when unused. Unknown actions and malformed/out-of-range extras are ignored. Routine recording notifications remain quiet. The Quick Settings tile uses a visible activity for start/resume and the same STOP path for stopping.
