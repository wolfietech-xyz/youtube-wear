---
name: watch-test
description: Build, install and test the youtube-wear app on the paired Samsung Galaxy Watch Ultra over wireless adb - open Reels or Videos, tap, swipe, screenshot and read filtered logs with one short command each. Use whenever a change needs checking on the watch.
---

# Testing youtube-wear on the watch

Everything goes through `.claude/skills/watch-test/watch.sh` (run with bash from any
directory). It keeps output short, waits for the watch to reconnect, and redacts the API key.

| Command | Does |
|---|---|
| `watch.sh reels` / `watch.sh videos` | Build, install, launch, open that mode, wait for the first video frame, print the last log lines, save a screenshot |
| `watch.sh build` | Gradle `assembleDebug`, prints only compile errors and the result |
| `watch.sh install`, `watch.sh launch` | Install the debug APK; force-stop, clear logcat and start the app |
| `watch.sh tap "Reels"` / `watch.sh tapxy 249 249` | Tap an element by its text, or a point (screen is 498x498) |
| `watch.sh swipe up` / `swipe down` | Next / previous reel |
| `watch.sh wait "First video frame" 60` | Wait for an app log line |
| `watch.sh shot name` | Screenshot to `build/watch-test/name.png`; then view it with Read |
| `watch.sh logs 40` | Last app and yt-dlp log lines |
| `watch.sh ui` | Texts on screen (Compose buttons, status) |

Useful app log lines (tag `YouTubeWear`): `Resolving <id>`, `Got N reels from feed|subscriptions|search`,
`Playing '<title>': <formats>`, `First video frame rendered`, `Player ready|ended`,
`Couldn't load <id>`, `Playback failed`. yt-dlp's own output is tag `python.stderr`.

## Facts that save rediscovery

- Watch: Galaxy Watch Ultra SM-L715F, Android 17 (API 37), 32-bit `armeabi-v7a` only,
  Qualcomm H.264/HEVC/VP9 hardware decoders, 498x498 round screen.
- adb is at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`, not on PATH. The watch
  is already paired; it drops off whenever it sleeps and returns by mDNS when woken. If
  `watch.sh` says "watch not connected", ask the user to wake the watch.
- In Git Bash set `MSYS_NO_PATHCONV=1` for raw adb calls, or device paths like `/sdcard`
  get rewritten to Windows paths (the script does this).
- Signed-in YouTube cookies live in the app's private `files/cookies.txt`; the user installs
  them with `adb push` + `adb shell run-as ca.wolfietech.dev.android.ytwear cp ...`. Never read, copy or
  print their contents.
- A signed-in resolve takes ~15 s on the watch (QuickJS solving YouTube's challenge), so
  wait up to 150 s for the first frame after a cold start.
- Commits are GPG-signed; if signing is cancelled the user has to be at the PC to enter
  the passphrase.
