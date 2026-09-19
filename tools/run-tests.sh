#!/usr/bin/env bash
# 在电脑上用 JDK 直接跑 DayMath 的纯逻辑校验（不依赖 Android SDK，也不影响构建产物）。
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_DIR="${COUNTDAY_ENV:-$HOME/.countday-env}"
JAVA_HOME="${JAVA_HOME:-$ENV_DIR/jdk}"

WORK_DIR="$PROJECT_DIR/build/tests"
PKG_DIR="$WORK_DIR/src/io/giasinton/countday"

rm -rf "$WORK_DIR"
mkdir -p "$PKG_DIR" "$WORK_DIR/out"
cp "$PROJECT_DIR/app/src/main/java/io/giasinton/countday/DayMath.java" "$PKG_DIR/"
cp "$PROJECT_DIR/app/src/main/java/io/giasinton/countday/BoxMath.java" "$PKG_DIR/"
cp "$PROJECT_DIR/tools/DayMathCheck.java" "$PKG_DIR/"
cp "$PROJECT_DIR/tools/BoxMathCheck.java" "$PKG_DIR/"

"$JAVA_HOME/bin/javac" -d "$WORK_DIR/out" "$PKG_DIR"/*.java
"$JAVA_HOME/bin/java" -cp "$WORK_DIR/out" io.giasinton.countday.DayMathCheck
echo
"$JAVA_HOME/bin/java" -cp "$WORK_DIR/out" io.giasinton.countday.BoxMathCheck
