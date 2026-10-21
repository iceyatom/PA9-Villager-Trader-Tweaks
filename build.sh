#!/usr/bin/env bash
# One-shot build helper for Trade Reorder (Minecraft 1.21–1.21.4 / Fabric).
# Requires: JDK 21+ on PATH, and a system Gradle >= 9.6 the FIRST time only
# (to generate the wrapper). After that, ./gradlew is self-contained.
set -e

if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  echo ">> Generating Gradle wrapper (needs system 'gradle' once)..."
  gradle wrapper --gradle-version 9.6.0
fi

echo ">> Building..."
./gradlew build

echo
echo ">> JAR(s) produced in build/libs/:"
ls -1 build/libs/*.jar
echo ">> Copy the NON -sources jar into your .minecraft/mods folder."
