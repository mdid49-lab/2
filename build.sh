#!/usr/bin/env bash
set -e
if [ ! -x "./gradlew" ]; then
  echo "gradlew missing. Please upload the official Gradle wrapper files or run: gradle wrapper --gradle-version 8.7"
  exit 1
fi
./gradlew "$@"
