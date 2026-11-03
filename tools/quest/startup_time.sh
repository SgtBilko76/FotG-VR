#!/usr/bin/env bash
# Time the game start on the headset: launch, then poll the FSO log for milestones.
#   tools/quest/startup_time.sh [max_seconds]
set -u
export LC_ALL=C
source "$(cd "$(dirname "$0")" && pwd)/game.sh"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
MAX="${1:-120}"
"$ADB" shell am broadcast -a com.oculus.vrpowermanager.prox_close >/dev/null
"$ADB" shell input keyevent KEYCODE_WAKEUP
"$ADB" shell am force-stop $PKG
"$ADB" shell run-as $PKG rm -f files/data/fs2_open.log
T0=$(date +%s.%N)
"$ADB" shell am start -n $ACTIVITY >/dev/null
declare -A seen
marks=("Initializing OpenGL" "TABLES: Starting parse of" "LUA: Opening LUA state" "GS_EVENT_GAME_INIT" "PLR => Verifying complete")
while :; do
  now=$(date +%s.%N); el=$(echo "$now - $T0" | bc)
  log=$("$ADB" shell run-as $PKG cat files/data/fs2_open.log 2>/dev/null)
  for m in "${marks[@]}"; do
    if [ -z "${seen[$m]:-}" ] && echo "$log" | grep -q "$m"; then seen[$m]=1; printf "%6.1f s  %s\n" "$el" "$m"; fi
  done
  [ -n "${seen[${marks[4]}]:-}" ] && break
  (( $(echo "$el > $MAX" | bc) )) && { echo "timeout"; break; }
  sleep 0.5
done
