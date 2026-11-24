#!/bin/bash
# Automated crash hunt: skip intro, walk around, open menus. Needs emulator + installed APK.
export MSYS_NO_PATHCONV=1
OUT=/c/Claude/mina-port/shots/auto; mkdir -p $OUT
k(){ adb shell input keyboard keyevent "$@"; }
alive(){ adb shell pidof com.mina.port >/dev/null; }
shot(){ adb exec-out screencap -p > $OUT/$1.png; }
adb logcat -c; adb shell am force-stop com.mina.port; adb shell am start -n com.mina.port/.MinaActivity >/dev/null
sleep 150   # to main menu
for i in $(seq 1 12); do k KEYCODE_ENTER; sleep 3; done   # menu, profile, intro pages
k KEYCODE_DPAD_DOWN; k KEYCODE_DPAD_DOWN; k KEYCODE_DPAD_RIGHT; k KEYCODE_ENTER
for i in $(seq 1 25); do k KEYCODE_ENTER; sleep 3; alive || { echo "DIED during intro at $i"; break; }; done
shot after_intro
n=0
for round in $(seq 1 40); do
  for d in KEYCODE_DPAD_RIGHT KEYCODE_DPAD_LEFT KEYCODE_DPAD_UP KEYCODE_DPAD_DOWN; do
    adb shell "input keyboard keyevent --longpress $d" ; sleep 2
    k KEYCODE_Z; k KEYCODE_X; k KEYCODE_SPACE
  done
  case $((round % 5)) in 0) k KEYCODE_TAB; sleep 2; shot menu_$round; k KEYCODE_ESCAPE;; 3) k KEYCODE_M; sleep 2; shot map_$round; k KEYCODE_ESCAPE;; esac
  [ $((round % 4)) = 0 ] && shot r$round
  alive || { echo "DIED at round $round"; shot died; break; }
done
alive && echo "STILL ALIVE after loop"
adb logcat -d -s MinaPort:E | grep -E "signal:|FATAL|fault" | head -5
