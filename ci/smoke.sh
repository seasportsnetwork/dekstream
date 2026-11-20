#!/bin/bash
# Emulator smoke test: install the debug APK, set up a public test playlist, visit each tab,
# screenshot it, and fail if the app crashes or freezes (ANR).
set -u
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.dekstream.tv
ACT=$PKG/.ui.MainActivity
OUT=smoke
M3U=${TEST_M3U:-https://iptv-org.github.io/iptv/countries/us.m3u}
mkdir -p $OUT

shot()  { adb exec-out screencap -p > "$OUT/$1.png"; }
start() { adb shell am start -n $ACT "$@" >/dev/null; }
alive() { adb shell pidof $PKG >/dev/null 2>&1; }
key()   { adb shell input keyevent "$@"; }
step()  { echo "== $1"; echo "$1" >> $OUT/steps.txt; }

adb install -r $APK || exit 1
adb logcat -c
adb shell wm size 1920x1080 >/dev/null 2>&1
adb shell setprop persist.sys.timezone America/New_York >/dev/null 2>&1
adb shell cmd alarm set-timezone America/New_York >/dev/null 2>&1 || adb shell service call alarm 3 s16 America/New_York >/dev/null 2>&1

step "launch + test playlist"
start --es test_m3u "$M3U" --es test_tab Home
sleep 30; shot 01-home

n=2
for t in Live Favorites Guide Sports Movies Series Search Settings Home; do
  step "tab $t"
  start --es test_tab $t
  sleep 12
  shot "$(printf %02d $n)-$t"; n=$((n+1))
  alive || { echo "App died on $t"; break; }
done

step "live tv: move focus (no autoplay), then OK = preview"
start --es test_tab Live; sleep 8
key 22 22; sleep 1; key 20 20 20; sleep 4; shot "$(printf %02d $n)-live-focus"; n=$((n+1))
key 23; sleep 8; shot "$(printf %02d $n)-live-preview"; n=$((n+1))
key 4; sleep 2

step "home scroll"
start --es test_tab Home; sleep 6
for i in 1 2 3 4 5 6; do key 20; sleep 0.5; done; sleep 3
shot "$(printf %02d $n)-home-scrolled"; n=$((n+1))

step "light theme"
start --es test_tab Home --ei test_palette 4; sleep 8
shot "$(printf %02d $n)-home-light"; n=$((n+1))
start --ei test_palette 0

adb logcat -d > $OUT/logcat.txt

# ---- Verdict ----
fail=0
{
  echo "DekTV smoke test"
  if grep -q "FATAL EXCEPTION" $OUT/logcat.txt; then echo "CRASH:"; grep -A25 "FATAL EXCEPTION" $OUT/logcat.txt | head -60; fail=1; fi
  if grep -qE "ANR in $PKG|Application Not Responding: $PKG" $OUT/logcat.txt; then echo "FREEZE (ANR):"; grep -A8 -E "ANR in $PKG" $OUT/logcat.txt | head -30; fail=1; fi
  alive || { echo "App is not running at the end"; fail=1; }
  worst=$(grep -oE "Skipped [0-9]+ frames" $OUT/logcat.txt | grep -oE "[0-9]+" | sort -n | tail -1)
  echo "Worst UI stall: ${worst:-0} skipped frames (~$(( ${worst:-0} * 16 )) ms)"
  [ "${worst:-0}" -gt 300 ] && echo "WARNING: UI stalled more than ~5 s"
  [ $fail -eq 0 ] && echo "RESULT: PASS" || echo "RESULT: FAIL"
} | tee $OUT/summary.txt
exit $fail
