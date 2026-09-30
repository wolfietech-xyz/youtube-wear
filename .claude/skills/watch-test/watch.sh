#!/usr/bin/env bash
# Build, install and drive the youtube-wear app on the paired Galaxy Watch over wireless adb.
# Output is kept short on purpose: it goes straight into Claude's context.
#
#   watch.sh build              assembleDebug; prints only errors and the result
#   watch.sh install            waits for the watch, installs the debug APK
#   watch.sh launch             force-stops and starts the app, clears logcat
#   watch.sh tap "<text>"       taps the on-screen element with that text
#   watch.sh tapxy <x> <y>      taps a point (screen is 498x498)
#   watch.sh swipe up|down      swipes the middle of the screen
#   watch.sh wait "<regex>" [s] waits (default 120 s) for a YouTubeWear log line matching regex
#   watch.sh shot <name>        screenshot to build/watch-test/<name>.png (then Read it)
#   watch.sh logs [n]           last n (default 25) app + yt-dlp log lines, API key redacted
#   watch.sh ui                 texts currently on screen
#   watch.sh shorts             build, install, launch, open Shorts, wait for the first frame
#   watch.sh home               build, install, launch, open the Home feed, wait for its list
#   watch.sh popular            same for Popular (Home is only shown while the login isn't refused)
set -u
export MSYS_NO_PATHCONV=1
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
ADB="${LOCALAPPDATA:-C:/Users/$USERNAME/AppData/Local}/Android/Sdk/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB=adb
OUT="$REPO/build/watch-test"
APK="$REPO/app/build/outputs/apk/debug/app-debug.apk"
PKG=ca.wolfietech.dev.android.ytwear
mkdir -p "$OUT"

winpath() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

wait_watch() {
  # The watch drops wireless adb when it sleeps; it comes back by mDNS once awake.
  for _ in $(seq 1 100); do
    timeout 5 "$ADB" devices 2>/dev/null | grep -q 'device$' && { "$ADB" shell input keyevent KEYCODE_WAKEUP; return 0; }
    sleep 3
  done
  echo "watch not connected: wake it and open Developer options > Wireless debugging" >&2
  return 1
}

ui_dump() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$ADB" shell cat /sdcard/ui.xml | tr '>' '\n'
}

cmd_build() {
  (cd "$REPO" && ./gradlew :app:assembleDebug --console=plain 2>&1) \
    | grep -E '^e: |error:|BUILD (SUCCESSFUL|FAILED)' | sed "s#file:///.*/app/src/main/##"
}

cmd_install() { wait_watch && "$ADB" install -r "$(winpath "$APK")" 2>&1 | tail -1; }

cmd_launch() {
  wait_watch || return 1
  "$ADB" shell am force-stop $PKG
  "$ADB" logcat -c
  "$ADB" shell am start -W -n $PKG/.MainActivity | grep -E 'Status|Error'
}

cmd_tap() {
  wait_watch || return 1
  local b
  b=$(ui_dump | grep -m1 "text=\"$1\"" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')
  [ -z "$b" ] && { echo "no element with text '$1'"; return 1; }
  set -- $b
  "$ADB" shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

cmd_tapxy() { wait_watch && "$ADB" shell input tap "$1" "$2"; }

cmd_swipe() {
  wait_watch || return 1
  case "$1" in
    up) "$ADB" shell input swipe 249 380 249 110 250 ;;
    down) "$ADB" shell input swipe 249 110 249 380 250 ;;
  esac
}

cmd_wait() {
  local secs="${2:-120}"
  for _ in $(seq 1 "$secs"); do
    "$ADB" logcat -d -s YouTubeWear:* 2>/dev/null | grep -qE "$1" && { echo "matched: $1"; return 0; }
    sleep 1
  done
  echo "timed out waiting for: $1"
  return 1
}

cmd_shot() { "$ADB" exec-out screencap -p > "$OUT/$1.png" && echo "$(winpath "$OUT/$1.png")"; }

cmd_logs() {
  "$ADB" logcat -d -s YouTubeWear:* python.stderr:* python.stdout:* \
    | grep -vE '	at |\[debug\]|original url|^-----' \
    | sed -E 's/AIza[0-9A-Za-z_-]{35}/<key>/g; s/^[0-9-]+ ([0-9:.]+) +[0-9]+ +[0-9]+ ([A-Z]) /\1 \2 /' \
    | cut -c1-220 | tail -n "${1:-25}"
}

cmd_ui() { wait_watch && ui_dump | grep -oE 'text="[^"]+"' | sed 's/text=//'; }

open_mode() {
  cmd_build | tee /dev/stderr | grep -q 'BUILD SUCCESSFUL' || return 1
  cmd_install && cmd_launch && sleep 3 && cmd_tap "$1" \
    && cmd_wait 'First video frame|Couldn.t load|Playback failed|No Shorts' 150
  cmd_logs 12
  cmd_shot "$(echo "$1" | tr 'A-Z' 'a-z')"
}

# Opens a feed from the start screen and waits for its list. A feed plays nothing, so there is no
# first frame to wait for. Popular can sit below the fold, so scroll once if it isn't found.
open_menu() {
  cmd_build | tee /dev/stderr | grep -q 'BUILD SUCCESSFUL' || return 1
  cmd_install && cmd_launch && sleep 3
  cmd_tap "$1" || { cmd_swipe up; sleep 1; cmd_tap "$1"; }
  sleep 10
  cmd_ui | head -8
  cmd_shot "$(echo "$1" | tr 'A-Z' 'a-z')"
}

case "${1:-}" in
  build) cmd_build ;;
  install) cmd_install ;;
  launch) cmd_launch ;;
  tap) cmd_tap "$2" ;;
  tapxy) cmd_tapxy "$2" "$3" ;;
  swipe) cmd_swipe "$2" ;;
  wait) cmd_wait "$2" "${3:-120}" ;;
  shot) cmd_shot "$2" ;;
  logs) cmd_logs "${2:-25}" ;;
  ui) cmd_ui ;;
  shorts) open_mode Shorts ;;
  home) open_menu Home ;;
  popular) open_menu Popular ;;
  *) sed -n '2,21p' "$0" ;;
esac
