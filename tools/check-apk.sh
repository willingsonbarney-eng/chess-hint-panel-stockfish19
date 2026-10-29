#!/usr/bin/env bash
# Static checks of a built APK.  This inspects the FILE only - it does not install or run anything,
# so it cannot prove that the app works on a phone (see docs/TESTING.md).
#   tools/check-apk.sh build/out/ChessHintPanel-v2.3.apk
set -u
APK="${1:?usage: check-apk.sh <apk>}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/34.0.0}"
FAIL=0
ok()   { echo "  PASS  $1"; }
bad()  { echo "  FAIL  $1"; FAIL=1; }
chk()  { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

echo "APK: $APK ($(stat -c %s "$APK") bytes)"
LIST="$(unzip -Z "$APK")"
chk "contains classes.dex"                        'echo "$LIST" | grep -q " classes.dex$"'
chk "contains lib/arm64-v8a/libstockfish.so"      'echo "$LIST" | grep -q " lib/arm64-v8a/libstockfish.so$"'
chk "ships ONLY the arm64-v8a ABI"                '[ "$(echo "$LIST" | grep -c " lib/")" = "1" ]'
chk "libstockfish.so is STORED (not compressed): required by extractNativeLibs=false" \
    'unzip -Z -v "$APK" lib/arm64-v8a/libstockfish.so | grep -q "compression method:  *none"'
chk "engine-build-info.txt packaged"              'echo "$LIST" | grep -q " assets/engine-build-info.txt$"'
chk "Stockfish GPLv3 licence packaged"            'echo "$LIST" | grep -q " assets/LICENSE-STOCKFISH-GPLv3.txt$"'
chk "Stockfish AUTHORS packaged"                  'echo "$LIST" | grep -q " assets/STOCKFISH-AUTHORS.txt$"'
chk "source offer packaged"                       'echo "$LIST" | grep -q " assets/SOURCE-OFFER.txt$"'

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
unzip -q -o "$APK" assets/engine-build-info.txt lib/arm64-v8a/libstockfish.so -d "$TMP" 2>/dev/null
chk "engine-build-info says Stockfish 19 / tag sf_19" 'grep -q "Stockfish 19" "$TMP/assets/engine-build-info.txt" && grep -q "sf_19" "$TMP/assets/engine-build-info.txt"'
want="$(grep '^Library SHA-256' "$TMP/assets/engine-build-info.txt" | awk '{print $3}')"
got="$(sha256sum "$TMP/lib/arm64-v8a/libstockfish.so" | cut -d' ' -f1)"
chk "packaged library is the one described in engine-build-info.txt" '[ -n "$want" ] && [ "$want" = "$got" ]'
if command -v file >/dev/null; then chk "library is an ARM aarch64 ELF shared object" 'file "$TMP/lib/arm64-v8a/libstockfish.so" | grep -q "ARM aarch64"'; fi
if command -v readelf >/dev/null; then
  chk "library load segments are 16 KB aligned (Android 15 / 16 KB pages)" \
      '[ -n "$(readelf -lW "$TMP/lib/arm64-v8a/libstockfish.so" | awk "/LOAD/ {print \$NF}")" ] && ! readelf -lW "$TMP/lib/arm64-v8a/libstockfish.so" | awk "/LOAD/ {print \$NF}" | grep -vqE "^0x(4000|8000|10000)$"'
fi

BADGING="$("$BT/aapt2" dump badging "$APK" 2>&1)"
chk "package com.chesshint.panel"                 'echo "$BADGING" | grep -q "^package: name=.com.chesshint.panel."'
chk "minSdk 26"                                   'echo "$BADGING" | grep -qE "^(min)?[sS]dkVersion:.26."'
echo "$BADGING" | grep -E "^(package:|sdkVersion|targetSdkVersion|native-code)" | sed 's/^/        /'
echo "  permissions declared:"; echo "$BADGING" | grep "^uses-permission" | sed 's/^/        /'
for perm in SYSTEM_ALERT_WINDOW FOREGROUND_SERVICE FOREGROUND_SERVICE_MEDIA_PROJECTION POST_NOTIFICATIONS; do
  chk "permission $perm"                          'echo "$BADGING" | grep -q "name=.android.permission.'$perm'."'
done
chk "no network permission (everything stays on the phone)" '! echo "$BADGING" | grep -q "android.permission.INTERNET"'
chk "native-code is arm64-v8a only"               'echo "$BADGING" | grep "^native-code" | grep -q "arm64-v8a" && ! echo "$BADGING" | grep "^native-code" | grep -q "armeabi\|x86"'

if [ -x "$BT/zipalign" ]; then chk "zipalign (4 byte, page aligned stored libs)" '"$BT/zipalign" -c -p 4 "$APK"'; fi
if [ -x "$BT/apksigner" ]; then chk "signature verifies (apksigner)" '"$BT/apksigner" verify "$APK" >/dev/null 2>&1'; fi
echo
if [ $FAIL = 0 ]; then echo "APK static check: all passed"; else echo "APK static check: FAILURES"; fi
exit $FAIL
