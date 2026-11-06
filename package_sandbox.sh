#!/bin/bash
# Assembles the distributable mod jar from the sandbox-compiled classes + resources,
# mirroring the canonical `./gradlew build` layout (see compile_sandbox.sh for why the
# canonical pipeline is not used here). CI still builds canonically from the same sources.
set -e
cd "$(dirname "$0")"

JAR_TOOL=~/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2/bin/jar
CLASSES=/tmp/mod-out/classes
BUILD=/tmp/jarbuild
VERSION=1.1.1
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
# The jarJar metadata format consumed by JarJarSelector (0.4.x, shipped with NeoForge
# 21.1) — written directly so the build never depends on a previously built jar.
cat > "$BUILD/META-INF/jarjar/metadata.json" <<'JSON'
{
  "jars": [
    {
      "identifier": {
        "group": "dev.custommusic",
        "artifact": "audio-codecs"
      },
      "version": {
        "range": "[1.0,)",
        "artifactVersion": "1.0"
      },
      "path": "META-INF/jarjar/audio-codecs-1.0.jar",
      "isObfuscated": false
    },
    {
      "identifier": {
        "group": "dev.custommusic",
        "artifact": "jvorbisenc-patched"
      },
      "version": {
        "range": "[0.0.3,)",
        "artifactVersion": "0.0.3"
      },
      "path": "META-INF/jarjar/jvorbisenc-patched-0.0.3.jar",
      "isObfuscated": false
    }
  ]
}
JSON

# --- pack -------------------------------------------------------------------
mkdir -p build/libs
"$JAR_TOOL" --create --file "$OUT_JAR" -C "$BUILD" .
echo "=== built $OUT_JAR ==="
unzip -l "$OUT_JAR" | tail -3

# --- verify: a broken jar must never ship (the 1.1.0 lesson) -----------------
echo "=== verifying jar ==="
FAIL=0
# 1+2. mods.toml byte-exact header check via python (shell quoting of '[[...' is fragile)
python3 - "$OUT_JAR" <<'PY' || FAIL=1
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    toml = z.read("META-INF/neoforge.mods.toml")
    assert b"[[mods]]" in toml, "[[mods]] header missing"
    assert b"[[mixins]]" in toml, "[[mixins]] header missing"
    assert b"modId = \"custommusic\"" in toml, "modId missing"
    assert b"1.1.1" in toml, "version missing"
print("  mods.toml headers: OK")
PY
# 3. jarjar metadata is valid JSON and lists both libraries
python3 - "$OUT_JAR" <<'PY' || FAIL=1
import json, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    meta = json.loads(z.read("META-INF/jarjar/metadata.json"))
    jars = {j["identifier"]["artifact"] for j in meta["jars"]}
    assert jars == {"audio-codecs", "jvorbisenc-patched"}, jars
    for j in meta["jars"]:
        assert j["path"] in z.namelist(), j["path"]
    # the AAC decoder must really be inside the nested jar
    with z.open("META-INF/jarjar/audio-codecs-1.0.jar") as nj:
        import io
        nested = zipfile.ZipFile(io.BytesIO(nj.read()))
        assert "net/sourceforge/jaad/mp4/MP4Container.class" in nested.namelist()
        assert "javazoom/spi/mpeg/sampled/file/MpegAudioFileReader.class" in nested.namelist()
print("  jarjar metadata + nested codecs: OK")
PY
# 4. key mod classes present
unzip -l "$OUT_JAR" | grep -q "dev/custommusic/server/Codecs.class" \
  && echo "  Codecs.class: OK" || { echo "  Codecs.class: MISSING"; FAIL=1; }
unzip -l "$OUT_JAR" | grep -q "dev/custommusic/server/CustomJukeboxBlockEntity.class" \
  && echo "  Juke Box classes: OK" || { echo "  Juke Box classes: MISSING"; FAIL=1; }
# 5. resources
for f in assets/custommusic/blockstates/jukebox.json assets/custommusic/lang/ru_ru.json \
         data/custommusic/recipe/jukebox.json pack.mcmeta custommusic.mixins.json; do
  unzip -l "$OUT_JAR" | grep -q "$f" || { echo "  $f: MISSING"; FAIL=1; }
done
[ -n "$(unzip -l "$OUT_JAR" | grep 'textures/block/jukebox_top.png')" ] \
  && echo "  jukebox textures: OK" || { echo "  jukebox textures: MISSING"; FAIL=1; }
if [ "$FAIL" != 0 ]; then echo "=== JAR VERIFICATION FAILED ==="; exit 1; fi
echo "=== jar verification OK ==="
