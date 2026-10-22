#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
set -a
. ./.env
set +a
exec java -jar backend/target/bass-transcription-0.0.1-SNAPSHOT.jar
