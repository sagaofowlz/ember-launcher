#!/usr/bin/env sh
# Small self-bootstrapping Gradle launcher, used because the binary wrapper JAR is not committed.
set -eu
GRADLE_VERSION="8.10.2"
GRADLE_HOME_BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/ember-distributions"
GRADLE_HOME="$GRADLE_HOME_BASE/gradle-$GRADLE_VERSION"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  mkdir -p "$GRADLE_HOME_BASE"
  ZIP="$GRADLE_HOME_BASE/gradle-$GRADLE_VERSION-bin.zip"
  if [ ! -f "$ZIP" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
    elif command -v wget >/dev/null 2>&1; then
      wget "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -O "$ZIP"
    else
      echo "curl or wget is required to download Gradle." >&2; exit 1
    fi
  fi
  unzip -q -o "$ZIP" -d "$GRADLE_HOME_BASE"
fi
exec "$GRADLE_HOME/bin/gradle" "$@"
