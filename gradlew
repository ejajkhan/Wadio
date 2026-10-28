#!/usr/bin/env sh
# Lightweight Gradle bootstrapper for environments where the standard wrapper JAR
# is not bundled. Downloads the pinned Gradle distribution and executes it.
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION=8.9
GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$HOME/.gradle"}
GRADLE_HOME="$GRADLE_USER_HOME/wordsmart-gradle/gradle-$GRADLE_VERSION"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  mkdir -p "$GRADLE_USER_HOME/wordsmart-gradle"
  ZIP="$GRADLE_USER_HOME/wordsmart-gradle/gradle-$GRADLE_VERSION-bin.zip"
  if [ ! -f "$ZIP" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fL --retry 3 --connect-timeout 20 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    else
      echo "Error: curl or wget is required to download Gradle $GRADLE_VERSION." >&2
      exit 1
    fi
  fi
  unzip -q -o "$ZIP" -d "$GRADLE_USER_HOME/wordsmart-gradle"
fi
exec "$GRADLE_HOME/bin/gradle" -p "$APP_HOME" "$@"
