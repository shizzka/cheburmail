#!/usr/bin/env bash
# Собирает APK, публикует как GitHub Release, шлёт через бота.
# Usage:
#   ./release.sh debug         — debug-ветка (prerelease, тег vN-debug)
#   ./release.sh release       — stable-ветка (release, тег vN)
set -euo pipefail

cd "$(dirname "$0")"

MODE="${1:-debug}"
if [[ "$MODE" != "debug" && "$MODE" != "release" ]]; then
    echo "Usage: $0 {debug|release}"; exit 1
fi

export ANDROID_HOME=/home/q/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

VERSION_CODE=$(grep -oP 'versionCode\s*=\s*\K\d+' app/build.gradle.kts)
VERSION_NAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts)
echo "Версия: $VERSION_NAME (code $VERSION_CODE)"

if [[ "$MODE" == "debug" ]]; then
    TAG="v${VERSION_CODE}-debug"
    TITLE="v${VERSION_NAME} debug"
    PRERELEASE_FLAG="--prerelease"
    GRADLE_TASK="assembleDebug"
    APK_SRC="app/build/outputs/apk/debug/app-debug.apk"
    APK_NAME="CheburMail-${VERSION_NAME}-debug.apk"
else
    TAG="v${VERSION_CODE}"
    TITLE="v${VERSION_NAME}"
    PRERELEASE_FLAG=""
    GRADLE_TASK="assembleRelease"
    APK_SRC="app/build/outputs/apk/release/app-release.apk"
    APK_NAME="CheburMail-${VERSION_NAME}.apk"
fi

echo "→ Gradle $GRADLE_TASK..."
./gradlew "$GRADLE_TASK" --no-daemon

cp "$APK_SRC" "/tmp/$APK_NAME"

# Tags считаем immutable — один tag = один артефакт. Для повторной публикации
# бампни versionCode/versionName в app/build.gradle.kts. Опция FORCE=1 перебивает
# (пользоваться только для debug-сборок при сломанном CI).
if gh release view "$TAG" >/dev/null 2>&1; then
    if [[ "${FORCE:-0}" == "1" ]]; then
        echo "→ FORCE=1: релиз $TAG уже есть, удаляю и пересоздаю..."
        gh release delete "$TAG" --yes --cleanup-tag
    else
        echo "ERROR: релиз $TAG уже опубликован. Бампни versionCode в app/build.gradle.kts" >&2
        echo "  или запусти с FORCE=1 (только для debug)." >&2
        exit 1
    fi
fi

# SHA-256 для аудита: один tag = один артефакт с известным хешем
APK_SHA256=$(sha256sum "/tmp/$APK_NAME" | awk '{print $1}')
RELEASE_NOTES="Автосборка $(date -Iseconds)
SHA-256: \`$APK_SHA256\`"

echo "→ gh release create $TAG..."
gh release create "$TAG" \
    --title "$TITLE" \
    --notes "$RELEASE_NOTES" \
    $PRERELEASE_FLAG \
    "/tmp/$APK_NAME"

TOKEN="${TELEGRAM_BOT_TOKEN:-}"
if [[ -z "$TOKEN" ]]; then
    echo "ERROR: TELEGRAM_BOT_TOKEN не задан в окружении" >&2
    exit 1
fi
CAPTION="🔧 CheburMail ${VERSION_NAME} ${MODE}"
for CID in 133154291 111000637; do
    curl -s -x http://127.0.0.1:10809 -F chat_id=$CID \
        -F document=@"/tmp/$APK_NAME" \
        -F "caption=$CAPTION" \
        "https://api.telegram.org/bot$TOKEN/sendDocument" >/dev/null
    echo "→ отправлено в Telegram: $CID"
done

echo "✓ Готово: $TAG"
