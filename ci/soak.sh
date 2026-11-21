#!/bin/bash
# Long playback test: play one live channel full screen for SOAK_MIN minutes and record memory,
# stalls and crashes every 30 s.
set -u
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.dekstream.tv
ACT=$PKG/.ui.MainActivity
OUT=smoke
M3U=${TEST_M3U:-https://iptv-org.github.io/iptv/countries/us.m3u}
MIN=${SOAK_MIN:-12}
PLAY=${SOAK_PLAY:-1080}
mkdir -p $OUT
adb install -r $APK || exit 1
adb logcat -c
adb shell setprop persist.sys.timezone America/New_York >/dev/null 2>&1
EPG=${TEST_EPG:-https://epg.pw/xmltv/epg_US.xml}
adb shell am start -n $ACT --es test_m3u "$M3U" --es test_epg "$EPG" --es test_tab Home >/dev/null
sleep 30
# Load the full guide the way Home and Sports do (big guide = big memory), then start playing.
adb shell am start -n $ACT --es test_tab Sports >/dev/null; sleep 45
m=$(adb shell dumpsys meminfo $PKG 2>/dev/null); echo "after guide load: java $(echo "$m" | grep -E "^ +Java Heap:" | awk '{print int($3/1024)}') MB" | tee $OUT/soak-guide.txt
adb logcat -d | grep -iE "epg|guide" | grep -i dektv | tail -3 >> $OUT/soak-guide.txt
adb shell am start -n $ACT --es test_play "$PLAY" >/dev/null
sleep 10
{ cat $OUT/soak-guide.txt; adb logcat -d | grep "SOAK playing" | tail -1; } | tee $OUT/soak.txt
echo "min  javaMB  nativeMB  totalMB  alive" | tee -a $OUT/soak.txt
for i in $(seq 1 $((MIN * 2))); do
  m=$(adb shell dumpsys meminfo $PKG 2>/dev/null)
  java=$(echo "$m" | grep -E "^ +Java Heap:" | awk '{print int($3/1024)}')
  nat=$(echo "$m" | grep -E "^ +Native Heap:" | awk '{print int($3/1024)}')
  tot=$(echo "$m" | grep -E "^ +TOTAL( PSS)?:" | head -1 | awk '{print int($3/1024)}')
  alive=$(adb shell pidof $PKG >/dev/null 2>&1 && echo yes || echo NO)
  printf "%4.1f  %6s  %8s  %7s  %s\n" "$(echo "$i/2" | bc -l)" "${java:-?}" "${nat:-?}" "${tot:-?}" "$alive" | tee -a $OUT/soak.txt
  [ $((i % 6)) -eq 0 ] && adb exec-out screencap -p > "$OUT/soak-$(printf %02d $i).png"
  [ "$alive" = "NO" ] && break
  sleep 30
done
adb logcat -d > $OUT/logcat.txt
fail=0
{
  echo "DekTV soak test ($MIN min)"
  grep -q "FATAL EXCEPTION" $OUT/logcat.txt && { echo "CRASH:"; grep -A30 "FATAL EXCEPTION" $OUT/logcat.txt | head -50; fail=1; }
  grep -qE "ANR in $PKG" $OUT/logcat.txt && { echo "FREEZE (ANR)"; fail=1; }
  grep -c "OutOfMemory" $OUT/logcat.txt | sed 's/^/OutOfMemory lines: /'
  grep -E "DekTV|switched to|froze|stopped working" $OUT/logcat.txt | grep -iE "fail|froze|switch|SOAK" | tail -15
  adb shell pidof $PKG >/dev/null 2>&1 || { echo "App is not running at the end"; fail=1; }
  [ $fail -eq 0 ] && echo "RESULT: PASS" || echo "RESULT: FAIL"
} | tee $OUT/summary.txt
exit $fail
