#!/usr/bin/env bash
set -e
APK_PATH=app/build/outputs/apk/debug/zomdroid-debug-1.4.apk
PATCH_DIR=app/build/outputs/apk/patch_work
echo APK_PATH=$APK_PATH
rm -rf "$PATCH_DIR" && mkdir -p "$PATCH_DIR" && cd "$PATCH_DIR"

if [ ! -f "$OLDPWD/$APK_PATH" ]; then
  echo "APK not found: $OLDPWD/$APK_PATH" >&2
  exit 1
fi
if [ ! -f "$OLDPWD/icon.png" ]; then
  echo "icon.png not found at repo root" >&2
  exit 2
fi

echo "Unpacking APK..."
unzip "$OLDPWD/$APK_PATH" -d unpacked

echo "Replacing launcher icon files with repo root icon.png"
find unpacked/res -type f \( -name 'ic_launcher_foreground.png' -o -name '*_monochrome_foreground.png' -o -name 'ic_launcher.png' \) -print -exec cp "$OLDPWD/icon.png" {} \;

echo "Repacking APK..."
cd unpacked
zip -r ../patched_unsigned.apk .
cd ..

if ! command -v zipalign >/dev/null 2>&1; then
  echo "zipalign not found in PATH" >&2
  exit 3
fi

echo "Zipaligning..."
zipalign -v -p 4 patched_unsigned.apk patched_aligned.apk

if ! command -v apksigner >/dev/null 2>&1; then
  echo "apksigner not found in PATH" >&2
  exit 4
fi

echo "Signing with debug keystore (~/.android/debug.keystore)..."
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android --out patched_signed.apk patched_aligned.apk

echo "Installing patched APK (may prompt on device)..."
adb install -r patched_signed.apk || true

echo "Launching app (best-effort)..."
adb shell am start -n com.zomdroid/.LauncherActivity || true

echo "Patched APK:" && ls -la patched_signed.apk

echo Done
