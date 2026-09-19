#!/usr/bin/env bash
#
# 在隔离环境里把项目编译成可安装的 APK。
#
# 依赖全部来自 $COUNTDAY_ENV（默认 $HOME/.countday-env）：
#   $COUNTDAY_ENV/jdk                    JDK 17
#   $COUNTDAY_ENV/sdk/build-tools/34.0.0 aapt2 / d8 / zipalign / apksigner
#   $COUNTDAY_ENV/sdk/platforms/android-34/android.jar
#
# 不会写入 ~/.gradle、~/.android、系统目录，也不需要 root。
#
# 用法： ./build.sh [--clean]
#
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_DIR="${COUNTDAY_ENV:-$HOME/.countday-env}"
JAVA_HOME="${JAVA_HOME:-$ENV_DIR/jdk}"
SDK_ROOT="${ANDROID_SDK_ROOT:-$ENV_DIR/sdk}"

API_LEVEL="${API_LEVEL:-34}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-34.0.0}"
MIN_SDK="${MIN_SDK:-26}"
VERSION_CODE="${VERSION_CODE:-5}"
VERSION_NAME="${VERSION_NAME:-1.4}"
APP_ID="${APP_ID:-io.giasinton.countday}"

BUILD_TOOLS="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK_ROOT/platforms/android-$API_LEVEL/android.jar"
KEYSTORE="$ENV_DIR/debug.keystore"

BUILD_DIR="$PROJECT_DIR/build"
DIST_DIR="$PROJECT_DIR/dist"
MANIFEST_SRC="$PROJECT_DIR/app/src/main/AndroidManifest.xml"
JAVA_SRC="$PROJECT_DIR/app/src/main/java"
RES_DIR="$PROJECT_DIR/app/src/main/res"

die() { echo "错误：$*" >&2; exit 1; }

[ -x "$JAVA_HOME/bin/javac" ] || die "找不到 JDK：$JAVA_HOME（可设置 JAVA_HOME 或 COUNTDAY_ENV）"
[ -x "$BUILD_TOOLS/aapt2" ] || die "找不到 build-tools：$BUILD_TOOLS（运行 $ENV_DIR/setup-sdk.sh）"
[ -f "$ANDROID_JAR" ] || die "找不到 android.jar：$ANDROID_JAR"

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if [ "${1:-}" = "--clean" ]; then
  rm -rf "$BUILD_DIR" "$DIST_DIR"
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$DIST_DIR"

echo "==> 1/7 编译资源 (aapt2 compile)"
"$BUILD_TOOLS/aapt2" compile --dir "$RES_DIR" -o "$BUILD_DIR/res.zip"

echo "==> 2/7 生成清单 + 资源表 (aapt2 link)"
# Gradle/AGP 8 不允许在清单里写 package 属性（用 namespace 代替），
# 而 aapt2 link 又必须有 package，所以这里只在构建产物里临时注入。
sed "s|<manifest |<manifest package=\"$APP_ID\" |" "$MANIFEST_SRC" > "$BUILD_DIR/AndroidManifest.xml"

"$BUILD_TOOLS/aapt2" link \
  -o "$BUILD_DIR/app-unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$BUILD_DIR/AndroidManifest.xml" \
  --java "$BUILD_DIR/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$API_LEVEL" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  "$BUILD_DIR/res.zip"

echo "==> 3/7 编译 Java (javac)"
find "$JAVA_SRC" "$BUILD_DIR/gen" -name '*.java' > "$BUILD_DIR/sources.txt"
"$JAVA_HOME/bin/javac" \
  -encoding UTF-8 \
  -source 8 -target 8 \
  -Xlint:-options \
  -bootclasspath "$ANDROID_JAR:$BUILD_TOOLS/core-lambda-stubs.jar" \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD_DIR/classes" \
  @"$BUILD_DIR/sources.txt"

echo "==> 4/7 转换 dex (d8)"
find "$BUILD_DIR/classes" -name '*.class' > "$BUILD_DIR/classes.txt"
"$BUILD_TOOLS/d8" \
  --release \
  --min-api "$MIN_SDK" \
  --lib "$ANDROID_JAR" \
  --output "$BUILD_DIR/dex" \
  @"$BUILD_DIR/classes.txt"

echo "==> 5/7 打包 APK"
# resources.arsc 必须不压缩，否则 Android 11+ (targetSdk 30+) 拒绝安装
python3 - "$BUILD_DIR/app-unsigned.apk" "$BUILD_DIR/dex/classes.dex" "$BUILD_DIR/app-packaged.apk" <<'PY'
import sys, zipfile

src, dex_path, dst = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w') as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == 'resources.arsc':
            stored = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            stored.compress_type = zipfile.ZIP_STORED
            zout.writestr(stored, data)
        else:
            zout.writestr(item, data)
    with open(dex_path, 'rb') as fh:
        entry = zipfile.ZipInfo('classes.dex', date_time=(2026, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_STORED
        zout.writestr(entry, fh.read())
PY

echo "==> 6/7 对齐 (zipalign)"
"$BUILD_TOOLS/zipalign" -f -p 4 "$BUILD_DIR/app-packaged.apk" "$BUILD_DIR/app-aligned.apk"

echo "==> 7/7 签名 (apksigner)"
if [ ! -f "$KEYSTORE" ]; then
  echo "    生成调试用签名证书：$KEYSTORE"
  "$JAVA_HOME/bin/keytool" -genkeypair -v \
    -keystore "$KEYSTORE" \
    -storepass android -keypass android \
    -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=CountDay Debug, O=CountDay, C=CN" >/dev/null 2>&1
fi

OUT_APK="$DIST_DIR/countday-$VERSION_NAME.apk"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --v4-signing-enabled false \
  --out "$OUT_APK" \
  "$BUILD_DIR/app-aligned.apk"

"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$OUT_APK" | head -8
echo
echo "完成： $OUT_APK  ($(du -h "$OUT_APK" | cut -f1))"
echo "安装： adb install -r \"$OUT_APK\""
