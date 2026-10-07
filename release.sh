#!/usr/bin/env bash
# Собирает APK, публикует как GitHub Release, шлёт через бота.
# Usage:
#   ./release.sh debug         — debug-ветка (prerelease, тег vN-debug)
#   ./release.sh release       — stable-ветка (release, тег vN)
#   ./release.sh notice        — информационный финальный release без APK
set -euo pipefail

cd "$(dirname "$0")"

MODE="${1:-debug}"
if [[ "$MODE" != "debug" && "$MODE" != "release" && "$MODE" != "notice" ]]; then
    echo "Usage: $0 {debug|release|notice}"; exit 1
fi

export ANDROID_HOME=/home/q/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

VERSION_CODE=$(grep -oP 'versionCode\s*=\s*\K\d+' app/build.gradle.kts)
VERSION_NAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts)
echo "Версия: $VERSION_NAME (code $VERSION_CODE)"

if [[ "$MODE" == "notice" ]]; then
    TAG="v${VERSION_CODE}"
    TITLE="${VERSION_NAME} — поддержка прекращена → Delta Chat"

    cat > /tmp/cheburmail-final-notice.md <<'EOF'
# CheburMail: поддержка прекращена

Разработка CheburMail остановлена. Проект остаётся открытым как proof of concept и часть портфолио.

Для практического использования рекомендуется перейти на **[Delta Chat](https://delta.chat/)** — зрелый open-source мессенджер, который также использует электронную почту как транспорт и лучше подходит для реального использования.

CheburMail создавался как аварийный канал связи поверх IMAP/SMTP в условиях ограниченного доступа к интернету.

**Это информационный релиз. APK не публикуется.**
EOF

    if gh release view "$TAG" >/dev/null 2>&1; then
        echo "ERROR: release $TAG уже существует" >&2
        exit 1
    fi

    gh release create "$TAG" \
        --title "$TITLE" \
        --notes-file /tmp/cheburmail-final-notice.md

    echo "✓ Финальное уведомление опубликовано: $TAG"
    exit 0
fi

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

if gh release view "$TAG" >/dev/null 2>&1; then
    echo "→ Релиз $TAG уже есть, удаляю и пересоздаю..."
    gh release delete "$TAG" --yes --cleanup-tag
fi

echo "→ gh release create $TAG..."
gh release create "$TAG" \
    --title "$TITLE" \
    --notes "Автосборка $(date -Iseconds)" \
    $PRERELEASE_FLAG \
    "/tmp/$APK_NAME"

TOKEN="${TELEGRAM_BOT_TOKEN:-}"
if [[ -z "$TOKEN" ]]; then
    echo "ERROR: TELEGRAM_BOT_TOKEN не задан в окружении" >&2
    exit 1
fi
CAPTION="🔧 CheburMail ${VERSION_NAME} ${MODE}"
for CID in 133154291 111000637; do
    curl -s -x http://127.0.0.1:7897 -F chat_id=$CID \
        -F document=@"/tmp/$APK_NAME" \
        -F "caption=$CAPTION" \
        "https://api.telegram.org/bot$TOKEN/sendDocument" >/dev/null
    echo "→ отправлено в Telegram: $CID"
done

echo "✓ Готово: $TAG"
