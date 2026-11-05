// Generates the Juke Box block textures (16x16 PNG, no external deps).
// Run: bun run gen_textures.ts  (from the mod root)
import { deflateSync } from "node:zlib";
import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";

const SIZE = 16;
const OUT_DIR = join(import.meta.dir, "src/main/resources/assets/custommusic/textures/block");

// ---------------------------------------------------------------- tiny PNG encoder
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

function encodePng(pixels) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(SIZE, 0);
  ihdr.writeUInt32BE(SIZE, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // color type: truecolor RGB
  // scanlines with filter byte 0
  const raw = Buffer.alloc(SIZE * (SIZE * 3 + 1));
  let o = 0;
  for (let y = 0; y < SIZE; y++) {
    raw[o++] = 0;
    for (let x = 0; x < SIZE; x++) {
      const i = (y * SIZE + x) * 3;
      raw[o++] = pixels[i];
      raw[o++] = pixels[i + 1];
      raw[o++] = pixels[i + 2];
    }
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

// ---------------------------------------------------------------- pixel helpers
const px = () => new Array(SIZE * SIZE * 3).fill(0);
const set = (img, x, y, [r, g, b]) => {
  if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) return;
  const i = (y * SIZE + x) * 3;
  img[i] = r; img[i + 1] = g; img[i + 2] = b;
};
const fill = (img, [r, g, b]) => {
  for (let i = 0; i < img.length; i += 3) {
    img[i] = r; img[i + 1] = g; img[i + 2] = b;
  }
};

// Deterministic per-pixel noise so the wood does not look flat.
let seed = 1234567;
function rnd() {
  seed = (seed * 1103515245 + 12345) & 0x7fffffff;
  return seed / 0x7fffffff;
}

// Palette: dark walnut wood + teal accent (the mod's brand color).
const BORDER = [30, 20, 14];
const WOOD = [62, 45, 31];
const WOOD_DARK = [48, 34, 23];
const WOOD_LIGHT = [76, 56, 39];
const PLANK_LINE = [40, 28, 19];
const TEAL = [46, 211, 197];
const TEAL_DARK = [24, 122, 115];
const TEAL_LIGHT = [94, 235, 223];

function woodPixel(base) {
  const n = (rnd() - 0.5) * 14;
  return [clamp(base[0] + n), clamp(base[1] + n), clamp(base[2] + n)];
}
const clamp = (v) => Math.max(0, Math.min(255, Math.round(v)));

// ---------------------------------------------------------------- top: walnut + teal note
function topTexture() {
  const img = px();
  fill(img, WOOD);
  // subtle grain
  for (let y = 0; y < SIZE; y++) {
    for (let x = 0; x < SIZE; x++) {
      set(img, x, y, woodPixel((x + y) % 7 === 0 ? WOOD_DARK : WOOD));
    }
  }
  // frame
  for (let i = 0; i < SIZE; i++) {
    set(img, i, 0, BORDER); set(img, i, 15, BORDER);
    set(img, 0, i, BORDER); set(img, 15, i, BORDER);
  }
  for (let i = 1; i < 15; i++) {
    set(img, i, 1, WOOD_DARK); set(img, i, 14, WOOD_DARK);
    set(img, 1, i, WOOD_DARK); set(img, 14, i, WOOD_DARK);
  }
  // eighth-note glyph, centered
  const N = TEAL;
  const ND = TEAL_DARK;
  const glyph = [
    // [x, y]
    [8, 3], [9, 3],
    [8, 4], [9, 4],
    [8, 5], [9, 5],
    [8, 6], [9, 6],
    [8, 7], [9, 7],
    [8, 8], [9, 8],
    [4, 8], [5, 8], [6, 8], [7, 8], [8, 8], [9, 8],
    [4, 9], [5, 9], [6, 9], [7, 9], [8, 9], [9, 9],
    [4, 10], [5, 10], [6, 10], [7, 10],
    [5, 11], [6, 11],
  ];
  for (const [x, y] of glyph) set(img, x, y, N);
  // stem shading
  set(img, 9, 3, ND); set(img, 9, 4, ND); set(img, 9, 5, ND);
  set(img, 9, 6, ND); set(img, 9, 7, ND); set(img, 9, 8, ND);
  // note head highlight
  set(img, 4, 8, TEAL_LIGHT); set(img, 5, 9, TEAL_LIGHT);
  return img;
}

// ---------------------------------------------------------------- side: planks + teal stripe
function sideTexture() {
  const img = px();
  for (let y = 0; y < SIZE; y++) {
    for (let x = 0; x < SIZE; x++) {
      let base = WOOD;
      if (y === 4 || y === 9) base = PLANK_LINE;
      if (y === 5 || y === 10) base = WOOD_LIGHT;
      set(img, x, y, woodPixel(base));
    }
  }
  // top + bottom borders
  for (let i = 0; i < SIZE; i++) {
    set(img, i, 0, BORDER);
    set(img, i, 15, BORDER);
  }
  // teal accent stripe (rows 12-13) with a dark seam above it
  for (let x = 0; x < SIZE; x++) {
    set(img, x, 11, PLANK_LINE);
    set(img, x, 12, TEAL_DARK);
    set(img, x, 13, rnd() > 0.7 ? TEAL_LIGHT : TEAL);
  }
  return img;
}

// ---------------------------------------------------------------- bottom: plain planks
function bottomTexture() {
  const img = px();
  for (let y = 0; y < SIZE; y++) {
    for (let x = 0; x < SIZE; x++) {
      let base = WOOD;
      if (y === 4 || y === 9) base = PLANK_LINE;
      if (y === 5 || y === 10) base = WOOD_LIGHT;
      set(img, x, y, woodPixel(base));
    }
  }
  for (let i = 0; i < SIZE; i++) {
    set(img, i, 0, BORDER);
    set(img, i, 15, BORDER);
  }
  return img;
}

// ---------------------------------------------------------------- write
mkdirSync(OUT_DIR, { recursive: true });
const files = {
  "jukebox_top.png": topTexture(),
  "jukebox_side.png": sideTexture(),
  "jukebox_bottom.png": bottomTexture(),
};
for (const [name, pixels] of Object.entries(files)) {
  writeFileSync(join(OUT_DIR, name), encodePng(pixels));
  console.log("wrote", name);
}
