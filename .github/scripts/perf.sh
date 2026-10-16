#!/usr/bin/env bash
# Koude start en geheugen van twee APK's (basis vs. nieuw) op dezelfde emulator.
# Blokken worden afgewisseld (A B A B) zodat opwarmen/drift van de emulator beide treft.
set -uo pipefail
PKG=nl.jan.airco
ACT=$PKG/.MainActivity
LAUNCHES=${LAUNCHES:-8}
OUT=perf-results.csv
echo "variant,round,total_ms,pss_kb,java_heap_kb" > "$OUT"

install() {
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb install "$1" >/dev/null
  # Eén start zodat profileinstaller het baseline profile neerzet, dan compileren
  # zoals Android dat na installatie in de achtergrond doet.
  adb shell am start-activity -W -n "$ACT" >/dev/null
  sleep 4
  adb shell am force-stop "$PKG"
  adb shell cmd package compile -f -m speed-profile "$PKG" >/dev/null
  sleep 2
}

measure() { # $1 variant
  for i in $(seq 1 "$LAUNCHES"); do
    adb shell am force-stop "$PKG"
    sleep 1.5
    total=$(adb shell am start-activity -W -S -n "$ACT" | tr -d '\r' | awk -F': ' '/TotalTime/ {print $2}')
    sleep 5 # scherm in rust (animaties uit), daarna geheugen
    mem=$(adb shell dumpsys meminfo "$PKG" | tr -d '\r')
    pss=$(echo "$mem" | awk '/TOTAL PSS:/ {print $3; exit}')
    [ -z "$pss" ] && pss=$(echo "$mem" | awk '/^ *TOTAL / {print $2; exit}')
    heap=$(echo "$mem" | awk '/Java Heap:/ {print $3; exit}')
    echo "$1,$i,${total:-},${pss:-},${heap:-}" >> "$OUT"
  done
}

for block in 1 2; do
  for v in base new; do
    install "apks/$v.apk"
    measure "$v"
  done
done
cat "$OUT"
