#!/usr/bin/env bash
# Set Key=Value lines in the game's settings ini on the headset (debug builds).
#   tools/quest/set_option.sh Lighting=2 Detail=2
set -eu
source "$(cd "$(dirname "$0")" && pwd)/game.sh"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
INI=files/data/FotG_R_settings.ini
T="${TMPDIR:-/tmp}/settings_$$.ini"
"$ADB" shell run-as $PKG cat $INI > "$T"
for kv in "$@"; do k=${kv%%=*}; sed -i "s|^$k=.*|$kv|" "$T"; done
"$ADB" push "$T" /data/local/tmp/settings.ini >/dev/null
"$ADB" shell "run-as $PKG sh -c 'cat /data/local/tmp/settings.ini > $INI'"
"$ADB" shell run-as $PKG head -11 $INI | tr '\n' ' '; echo
rm -f "$T"
