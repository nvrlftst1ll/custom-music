#!/bin/bash
# Assembles the distributable mod jar from the sandbox-compiled classes + resources,
# mirroring the canonical `./gradlew build` layout (see compile_sandbox.sh for why the
# canonical pipeline is not used here). CI still builds canonically from the same sources.
set -e
cd "$(dirname "$0")"

JAR_TOOL=~/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2/bin/jar
CLASSES=/tmp/mod-out/classes
BUILD=/tmp/jarbuild
VERSION=1.1.0
OUT_JAR=build/libs/custommusic-${VERSION}-all.jar

# --- workspace --------------------------------------------------------------
rm -rf "$BUILD"
mkdir -p "$BUILD/META-INF/jarjar"

# --- classes ----------------------------------------------------------------
cp -r "$CLASSES/dev" "$BUILD/dev"

# --- resources --------------------------------------------------------------
cp -r src/main/resources/assets "$BUILD/assets"
cp -r src/main/resources/data "$BUILD/data"
cp src/main/resources/custommusic.mixins.json "$BUILD/custommusic.mixins.json"
cp src/main/resources/pack.mcmeta "$BUILD/pack.mcmeta"

# --- neoforge.mods.toml with expanded placeholders --------------------------
sed -e "s/\${mod_id}/custommusic/g" \
    -e "s/\${mod_version}/${VERSION}/g" \
    -e "s/\${mod_license}/MIT/g" \
    -e "s/\${neo_version}/21.1.77/g" \
    -e "s/\${minecraft_version}/1.21.1/g" \
    src/main/resources/META-INF/neoforge.mods.toml > "$BUILD/META-INF/neoforge.mods.toml"

# --- manifest ---------------------------------------------------------------
printf 'Manifest-Version: 1.0\nCreated-By: 21.0.13 (Eclipse Adoptium)\n' > "$BUILD/META-INF/MANIFEST.MF"

# --- jar-in-jar audio codecs (identical to the canonical jarJar output) -----
cp repo/dev/custommusic/audio-codecs/1.0/audio-codecs-1.0.jar "$BUILD/META-INF/jarjar/"
cp repo/dev/custommusic/jvorbisenc-patched/0.0.3/jvorbisenc-patched-0.0.3.jar "$BUILD/META-INF/jarjar/"
unzip -p /home/z/my-project/public/downloads/custommusic-1.0.2-all.jar \
  META-INF/jarjar/metadata.json > "$BUILD/META-INF/jarjar/metadata.json"

# --- pack -------------------------------------------------------------------
mkdir -p build/libs
"$JAR_TOOL" --create --file "$OUT_JAR" -C "$BUILD" .
echo "=== built $OUT_JAR ==="
unzip -l "$OUT_JAR" | tail -3
