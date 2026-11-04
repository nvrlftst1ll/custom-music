#!/usr/bin/env python3
"""Generates item textures (16x16 pixel-art discs) and example audio files."""
import math
import random
import struct
import wave
from pathlib import Path

MOD = Path("/home/z/my-project/custommusic-mod")
TEX = MOD / "src/main/resources/assets/custommusic/textures/item"
EX = MOD / "example-music"
PUB_DL = Path("/home/z/my-project/public/downloads")
PUB_DL.mkdir(parents=True, exist_ok=True)
TEX.mkdir(parents=True, exist_ok=True)
EX.mkdir(parents=True, exist_ok=True)

random.seed(42)


# ---------------------------------------------------------------- textures ---
def disc_texture(path: Path, label: tuple, label2: tuple, hole=(18, 18, 20), body=(28, 28, 34)):
    px = [[(0, 0, 0, 0)] * 16 for _ in range(16)]
    cx = cy = 7.5
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - cx, y - cy)
            if d <= 7.6:
                c = body
                # subtle grooves / shading
                if (d * 2) % 2 < 0.6:
                    c = (max(c[0] - 6, 0), max(c[1] - 6, 0), max(c[2] - 6, 0))
                if x - y > 4 or y - x > 4:  # slight top-left sheen
                    c = tuple(min(v + 5, 255) for v in c)
                if d <= 3.4:  # label
                    c = label if (x + y) % 4 else label2
                if d <= 0.9:  # spindle hole
                    c = hole
                px[y][x] = (*c, 255)
    # highlight pixel
    px[4][5] = (max(px[4][5][0], 70), max(px[4][5][1], 70), max(px[4][5][2], 78), 255)
    write_png(path, px)


def write_png(path: Path, px):
    from PIL import Image
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), px[y][x])
    img.save(path)


disc_texture(TEX / "music_disc.png", label=(16, 122, 87), label2=(5, 150, 105))     # emerald
disc_texture(TEX / "blank_disc.png", label=(113, 113, 122), label2=(140, 140, 148)) # zinc/gray
# hero copies for the landing page
from PIL import Image
for name in ("music_disc", "blank_disc"):
    img = Image.open(TEX / f"{name}.png")
    img.save(Path("/home/z/my-project/public") / f"disc-{name.replace('_disc','')}.png")
print("textures done")


# ------------------------------------------------------------------ audio ----
SR = 44100

def note(freq, dur, sr=SR, vol=0.5, wave_kind="square"):
    n = int(dur * sr)
    out = []
    for i in range(n):
        t = i / sr
        if wave_kind == "square":
            v = 1.0 if math.sin(2 * math.pi * freq * t) >= 0 else -1.0
        elif wave_kind == "tri":
            v = 2 / math.pi * math.asin(math.sin(2 * math.pi * freq * t))
        else:
            v = math.sin(2 * math.pi * freq * t)
        env = min(1.0, i / (0.01 * sr), (n - i) / (0.03 * sr))
        out.append(v * env * vol)
    return out


def mix(*tracks):
    n = max(len(t) for t in tracks)
    return [sum(t[i] if i < len(t) else 0 for t in tracks) for i in range(n)]


def concat(*parts):
    out = []
    for p in parts:
        out.extend(p)
    return out


def write_wav(path, samples, channels=1, sr=SR):
    data = bytearray()
    peak = max(1e-9, max(abs(s) for s in samples))
    for s in samples:
        s = s / peak * 0.82
        data += struct.pack("<h", int(s * 32767))
    with wave.open(str(path), "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(sr)
        if channels == 1:
            w.writeframes(bytes(data))
        else:
            inter = bytearray()
            # samples here are mono; duplicate with a small delay for stereo feel
            half = len(samples) // 2
            left = samples
            right = [0.0] * 60 + samples
            right = right[:len(samples)]
            peak2 = max(1e-9, max(abs(s) for s in right))
            for i in range(len(samples)):
                l = left[i] / peak * 0.82
                r = right[i] / peak2 * 0.82
                inter += struct.pack("<hh", int(l * 32767), int(r * 32767))
            w.writeframes(bytes(inter))


C, D, E, F, G, A, B, C5, D5, E5, G5, A5 = 261.63, 293.66, 329.63, 349.23, 392.0, 440.0, 493.88, 523.25, 587.33, 659.26, 783.99, 880.0

# 1) Chiptune menu theme (mono, square lead + triangle bass)
lead = concat(*[note(f, 0.16, vol=0.42, wave_kind="square")
                for f in [E, G, A, C5, A, G, E, D, E, G, E, D, C, D, E, G,
                          A, C5, D5, E5, D5, C5, A, G, A, C5, E5, D5, C5, A, G, E]])
bass = concat(*[note(f / 2, 0.64, vol=0.30, wave_kind="tri")
                for f in [C, G, A, F, C, G, F, C]])
bass = bass + note(C / 2, 0.16, vol=0.3, wave_kind="tri")
theme = mix(lead[:len(bass)], bass)
theme = theme + lead[len(bass):]
write_wav(EX / "Example - Chiptune Menu Theme.wav", theme[:SR * 20])

# 2) Stereo demo (stereo, sine pads with delay between channels)
pad = concat(*[note(f, 0.5, vol=0.4, wave_kind="sine")
               for f in [A, C5, E5, G5, E5, C5, A, F, A, C5, F, A, G, B, D5, G]])
write_wav(EX / "Track-01 final (stereo).wav", pad[:SR * 15], channels=2)

# 3) Night Owl (Demo).ogg — mono 44.1k vorbis via ffmpeg
owl = concat(*[note(f, 0.24, vol=0.4, wave_kind="tri")
               for f in [D, F, A, D5, A, F, E, G, B, E5, B, G,
                         D, F, A, D5, F, A, C5, E5, C5, A, F, D]])
tmp = EX / "_tmp_owl.wav"
write_wav(tmp, owl[:SR * 18])
import subprocess
subprocess.run(["ffmpeg", "-y", "-i", str(tmp), "-vn", "-ac", "1", "-ar", "44100",
                "-c:a", "libvorbis", "-q:a", "4", str(EX / "Night Owl (Demo).ogg")],
               check=True, capture_output=True)
tmp.unlink()

# URL-safe copies for the site downloads
import shutil
shutil.copy(EX / "Example - Chiptune Menu Theme.wav", PUB_DL / "example-chiptune-menu-theme.wav")
shutil.copy(EX / "Track-01 final (stereo).wav", PUB_DL / "example-track-01-final-stereo.wav")
shutil.copy(EX / "Night Owl (Demo).ogg", PUB_DL / "example-night-owl-demo.ogg")
print("audio done")
