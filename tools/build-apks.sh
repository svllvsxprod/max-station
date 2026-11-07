#!/usr/bin/env bash
set -eo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
: "${sdk:?Set ANDROID_SDK_ROOT or ANDROID_HOME}"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 installation}"
: "${MAXSTATION_SIGN_KEYSTORE:?Set MAXSTATION_SIGN_KEYSTORE to your keystore path}"
: "${MAXSTATION_SIGN_PASSWORD_FILE:?Set MAXSTATION_SIGN_PASSWORD_FILE to a private password file}"
alias=${MAXSTATION_SIGN_ALIAS:-max-station}
key_password_args=()
if [ -n "${MAXSTATION_SIGN_KEY_PASSWORD_FILE:-}" ]; then
  key_password_args=(--key-pass "file:$MAXSTATION_SIGN_KEY_PASSWORD_FILE")
fi
tools="$sdk/build-tools/34.0.0"
platform="$sdk/platforms/android-28/android.jar"
out="$repo/out"
cache="$repo/.cache/maven"
mkdir -p "$out" "$cache"

fetch() {
  local file="$1" path="$2" sha="$3"
  if [ ! -f "$cache/$file" ]; then
    curl -fsSL "https://repo.maven.apache.org/maven2/$path/$file" -o "$cache/$file.tmp"
    mv "$cache/$file.tmp" "$cache/$file"
  fi
  python3 - "$cache/$file" "$sha" <<'PY'
import hashlib
import pathlib
import sys
if hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest() != sys.argv[2]:
    raise SystemExit("Dependency checksum mismatch")
PY
}

fetch okhttp-4.12.0.jar com/squareup/okhttp3/okhttp/4.12.0 b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0
fetch okio-jvm-3.6.0.jar com/squareup/okio/okio-jvm/3.6.0 67543f0736fc422ae927ed0e504b98bc5e269fda0d3500579337cb713da28412
fetch kotlin-stdlib-1.9.10.jar org/jetbrains/kotlin/kotlin-stdlib/1.9.10 55e989c512b80907799f854309f3bc7782c5b3d13932442d0379d5c472711504

temp=$(mktemp -d)
trap 'rm -rf -- "$temp"' EXIT
build() {
  local module="$1" artifact="$2" id="$3" work="$temp/$2"
  local -a sources=() resources=() deps=() cp=()
  local source
  mkdir -p "$work"/{classes,dex,gen}
  while IFS= read -r -d '' source; do sources+=("$source"); done < <(find "$repo/$module/src" -name '*.java' -print0)
  if [ -d "$repo/$module/res" ]; then
    resources+=(-S "$repo/$module/res")
  fi
  case "$artifact" in
    max-station-voice)
      deps=("$cache"/{okhttp-4.12.0,okio-jvm-3.6.0,kotlin-stdlib-1.9.10}.jar)
      cp=(-cp "$(IFS=:; echo "${deps[*]}")")
      mkdir -p "$work/assets/licenses"
      cp "$repo/third-party/"*.txt "$work/assets/licenses/"
      resources+=(-A "$work/assets")
      ;;
    max-station-local-backend)
      sources+=("$repo/local-backend/src/org/yanalice/backend/LocalBackend.java")
      ;;
  esac
  "$tools/aapt" package --no-crunch -f -m -J "$work/gen" -M "$repo/$module/AndroidManifest.xml" \
    -I "$platform" "${resources[@]}" -F "$work/unsigned.apk"
  if [ -f "$work/gen/$id/R.java" ]; then sources+=("$work/gen/$id/R.java"); fi
  "$JAVA_HOME/bin/javac" -source 8 -target 8 -bootclasspath "$platform:$tools/core-lambda-stubs.jar" \
    "${cp[@]}" -d "$work/classes" "${sources[@]}"
  "$JAVA_HOME/bin/jar" --create --file "$work/classes.jar" -C "$work/classes" .
  "$JAVA_HOME/bin/java" -cp "$tools/lib/d8.jar" com.android.tools.r8.D8 --min-api 28 --lib "$platform" \
    --output "$work/dex" "$work/classes.jar" "${deps[@]}"
  (cd "$work/dex"; "$tools/aapt" add "$work/unsigned.apk" classes*.dex)
  "$tools/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
  "$tools/apksigner" sign --ks "$MAXSTATION_SIGN_KEYSTORE" --ks-key-alias "$alias" \
    --ks-pass "file:$MAXSTATION_SIGN_PASSWORD_FILE" "${key_password_args[@]}" \
    --out "$out/$artifact.apk" "$work/aligned.apk"
  "$tools/apksigner" verify --min-sdk-version 28 "$out/$artifact.apk"
}

build voice-input max-station-voice org/yanalice/voice
build settings-shortcuts max-station-settings org/yanalice/settings/shortcuts
build local-backend/android-service max-station-local-backend org/yanalice/backend/service
(cd "$out"; shasum -a 256 max-station-*.apk > SHA256SUMS.txt)
