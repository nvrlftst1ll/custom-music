#!/bin/bash
# Compile the mod against the renamed Minecraft jar + NeoForge artifacts (see DEVNOTES:
# "Сборка без полного пайплайна"). The canonical build is `./gradlew build` (CI + release);
# this script exists to compile inside a constrained sandbox where the NeoForm decompile
# step cannot finish in the tool-call time window.
set -e
cd "$(dirname "$0")"

JAVAC=~/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2/bin/javac
NEOFORM=build/neoForm/neoFormJoined1.21.1-20240808.144430
OUT=/tmp/mod-out/classes

# --- classpath -------------------------------------------------------------
# The access-patch jar shadows BlockEntityType with a compile-only copy where the
# package-private BlockEntitySupplier interface is public (NeoForge's accesstransformer
# does the same widening in the canonical build). MUST be first on the classpath.
CP="/tmp/modcp/minecraft-access-patch.jar"
CP="$CP:$NEOFORM/steps/rename/output.jar"
CP="$CP:/home/z/.gradle/caches/modules-2/files-2.1/net.neoforged/neoforge/21.1.77/1a1a3ab373b60aeb7a1d460fa9d6557f6897503e/neoforge-21.1.77-universal.jar"
CP="$CP:/tmp/modcp/loader-4.0.31.jar"
CP="$CP:/tmp/modcp/bus-8.0.2.jar"
CP="$CP:/tmp/modcp/mergetool-2.0.3-api.jar"
CP="$CP:/tmp/modcp/sponge-mixin-0.15.2+mixin.0.8.7.jar"
CP="$CP:/home/z/.gradle/caches/modules-2/files-2.1/com.google.code.findbugs/jsr305/3.0.2/25ea2e8b0c338a877313bd4672d3fe056ea78f0d/jsr305-3.0.2.jar"
# Minecraft runtime libraries (gson, netty, brigadier, authlib, slf4j, datafixerupper, ...)
while read -r line; do
  jar="${line#-e=}"
  [ -f "$jar" ] && CP="$CP:$jar" || echo "WARN missing library: $jar" >&2
done < "$NEOFORM/steps/listLibraries/libraries.txt"
# bundled audio codecs (JLayer/JAAD/JOrbis imports in AudioConverter/JaadAudio)
for jar in repo/dev/custommusic/audio-codecs/1.0/audio-codecs-1.0.jar \
           repo/dev/custommusic/jvorbisenc-patched/0.0.3/jvorbisenc-patched-0.0.3.jar; do
  if [ -f "$jar" ]; then CP="$CP:$jar"; else echo "WARN missing codec: $jar" >&2; fi
done

echo "=== classpath: $(echo "$CP" | tr ':' '\n' | wc -l) entries ==="

mkdir -p "$OUT"
find src/main/java -name "*.java" > /tmp/mod-sources.txt
echo "=== compiling $(wc -l < /tmp/mod-sources.txt) sources ==="
"$JAVAC" --release 21 -proc:none -encoding UTF-8 -nowarn \
  -cp "$CP" \
  -d "$OUT" \
  @/tmp/mod-sources.txt
echo "=== compile OK ==="
