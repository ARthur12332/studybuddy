#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────
# 本机一键构建 StudyBuddy 的 debug APK
#
# 完全不需要 GitHub、不需要 Android Studio、不需要联网安装 IDE：
#   JDK 17  +  Gradle 8.7  +  Android SDK（build-tools 34 / platform 34）
# 三样都在 D 盘，脚本直接指过去。
#
# 用法（Git Bash）：
#   ./build-local.sh
# 产物：
#   app/build/outputs/apk/debug/app-debug.apk
#
# 路径都能用环境变量覆盖，换机器只改这里即可。
# ─────────────────────────────────────────────────────────────────────────
set -euo pipefail

JAVA_HOME="${JAVA_HOME:-D:/DevTools/jdk-17.0.20.1+1}"
ANDROID_HOME="${ANDROID_HOME:-D:/DevTools/android-sdk}"
GRADLE_BIN="${GRADLE_BIN:-D:/DevTools/gradle-8.7/bin/gradle}"
GRADLE_HOME="${GRADLE_HOME:-D:/DevTools/gradle-home}"
# 依赖下载走国内镜像的 init script（可选，不存在就直连）
INIT_MIRROR="${INIT_MIRROR:-D:/DevTools/_dl/init-mirror.gradle}"

cd "$(dirname "$0")"

[ -x "$JAVA_HOME/bin/java" ] || { echo "找不到 JDK：$JAVA_HOME"; exit 1; }
[ -d "$ANDROID_HOME/platforms/android-34" ] || { echo "找不到 Android SDK：$ANDROID_HOME"; exit 1; }
[ -x "$GRADLE_BIN" ] || { echo "找不到 Gradle：$GRADLE_BIN"; exit 1; }

# local.properties 让 AGP 知道 SDK 在哪（已被 .gitignore 忽略）
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties

export JAVA_HOME ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

ARGS=(--console=plain -g "$GRADLE_HOME")
[ -f "$INIT_MIRROR" ] && ARGS+=(-I "$INIT_MIRROR")

echo "▶ JDK     : $JAVA_HOME"
echo "▶ SDK     : $ANDROID_HOME"
echo "▶ Gradle  : $GRADLE_BIN"
echo

"$GRADLE_BIN" "${ARGS[@]}" assembleDebug

APK=app/build/outputs/apk/debug/app-debug.apk
echo
echo "✅ 构建完成：$(pwd)/$APK"
ls -la "$APK"
