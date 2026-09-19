#!/usr/bin/env bash
# Compiles and runs the pipeline against fixtures/corpus-a.jsonl.
# Needs a JDK 21 and nothing else - no network, no database, no Gradle.
set -euo pipefail
cd "$(dirname "$0")"

echo "==> compiling"
rm -rf build/selfcheck && mkdir -p build/selfcheck
# Excludes store/mongo: it imports the MongoDB driver directly (there's no
# JDK-native SPI to hide a document store behind, unlike java.sql for H2), so
# it needs the driver on the classpath to compile, breaking this script's
# "no network, no database, no Gradle" promise. It is compiled and exercised
# separately - see README (docker compose up, then ./gradlew run).
javac -d build/selfcheck $(find src/main/java -name '*.java' -not -path '*/store/mongo/*')

echo
echo "==> running"
java -cp build/selfcheck in.simplifymoney.ledgersync.SelfCheck "$@"
