// Patches BlockEntityType$BlockEntitySupplier to be public in a compile-only jar copy.
// Rationale: NeoForge's accesstransformer widens this interface at runtime, so the canonical
// `./gradlew build` compiles `BlockEntityType.Builder.of(MyBE::new, ...)` just fine. Compiling
// against the pre-patch renamed Minecraft jar in this sandbox needs the same widening up front.
// The produced jar is ONLY a compile-time classpath entry (first on the list); it is never
// shipped or run.
//
// Run: bun run patch_access.ts
import { readFileSync, writeFileSync, mkdirSync, rmSync } from "node:fs";
import { join } from "node:path";
import { execSync } from "node:child_process";

const MC_JAR =
  "/home/z/my-project/custommusic-mod/build/neoForm/neoFormJoined1.21.1-20240808.144430/steps/rename/output.jar";
const WORK = "/tmp/modcp/patch-work";
const OUT_JAR = "/tmp/modcp/minecraft-access-patch.jar";

const ACC_PUBLIC = 0x0001;

interface CPInfo {
  tag: number;
  str?: string;
  wide?: boolean;
}

/** Parse a class file up to (and including) the constant pool; return flags offset. */
function parseClass(buf: Buffer): { cp: (CPInfo | null)[]; accessFlagsOffset: number } {
  if (buf.readUInt32BE(0) !== 0xcafebabe) throw new Error("not a class file");
  const cpCount = buf.readUInt16BE(8);
  let o = 10; // magic(4) + minor(2) + major(2) + cp_count(2)
  const cp: (CPInfo | null)[] = [null];
  let i = 1;
  while (i < cpCount) {
    const tag = buf.readUInt8(o);
    o += 1;
    switch (tag) {
      case 1: {
        const len = buf.readUInt16BE(o);
        cp[i] = { tag, str: buf.toString("utf8", o + 2, o + 2 + len) };
        o += 2 + len;
        break;
      }
      case 7:
      case 8:
      case 16:
      case 19:
      case 20:
        cp[i] = { tag };
        o += 2;
        break;
      case 15:
        cp[i] = { tag };
        o += 3;
        break;
      case 5:
      case 6:
        cp[i] = { tag, wide: true };
        o += 8;
        i++; // long/double take two slots
        break;
      case 9:
      case 10:
      case 11:
      case 3:
      case 4:
      case 12:
      case 17: // CONSTANT_Dynamic: u2 bootstrap index + u2 name-and-type
      case 18: // InvokeDynamic: same shape
        cp[i] = { tag };
        o += 4;
        break;
      default:
        throw new Error(`unknown cp tag ${tag} at offset ${o - 1}`);
    }
    i++;
  }
  return { cp, accessFlagsOffset: o };
}

const utf8 = (cp: (CPInfo | null)[], idx: number) => cp[idx]?.str ?? "";

function skipMembers(buf: Buffer, cp: (CPInfo | null)[], o: number): number {
  const count = buf.readUInt16BE(o);
  o += 2;
  for (let m = 0; m < count; m++) {
    o += 6; // access_flags, name_index, descriptor_index
    const attrs = buf.readUInt16BE(o);
    o += 2;
    for (let a = 0; a < attrs; a++) {
      o += 2; // name index
      const len = buf.readUInt32BE(o);
      o += 4 + len;
    }
  }
  return o;
}

// ---------------------------------------------------------------- 1. inner interface class
rmSync(WORK, { recursive: true, force: true });
mkdirSync(join(WORK, "net/minecraft/world/level/block/entity"), { recursive: true });
execSync(`unzip -p ${MC_JAR} 'net/minecraft/world/level/block/entity/BlockEntityType$BlockEntitySupplier.class' > ${WORK}/net/minecraft/world/level/block/entity/BlockEntityType\\$BlockEntitySupplier.class`);

const innerPath = join(WORK, "net/minecraft/world/level/block/entity/BlockEntityType$BlockEntitySupplier.class");
const inner = Buffer.from(readFileSync(innerPath));
{
  const { accessFlagsOffset } = parseClass(inner);
  const flags = inner.readUInt16BE(accessFlagsOffset);
  inner.writeUInt16BE(flags | ACC_PUBLIC, accessFlagsOffset);
  console.log(
    "inner class flags:",
    flags.toString(16),
    "->",
    (flags | ACC_PUBLIC).toString(16)
  );
  writeFileSync(innerPath, inner);
}

// ---------------------------------------------------------------- 2. InnerClasses attribute of the outer class
execSync(`unzip -p ${MC_JAR} 'net/minecraft/world/level/block/entity/BlockEntityType.class' > ${WORK}/net/minecraft/world/level/block/entity/BlockEntityType.class`);
const outerPath = join(WORK, "net/minecraft/world/level/block/entity/BlockEntityType.class");
const outer = Buffer.from(readFileSync(outerPath));
{
  const { cp, accessFlagsOffset } = parseClass(outer);
  let o = accessFlagsOffset + 6; // access_flags + this_class + super_class
  const ifaces = outer.readUInt16BE(o);
  o += 2 + ifaces * 2;
  o = skipMembers(outer, cp, o); // fields
  o = skipMembers(outer, cp, o); // methods
  const classAttrs = outer.readUInt16BE(o);
  o += 2;
  let patched = 0;
  for (let a = 0; a < classAttrs; a++) {
    const nameIdx = outer.readUInt16BE(o);
    const len = outer.readUInt32BE(o + 2);
    const attrStart = o;
    const dataStart = o + 6;
    if (utf8(cp, nameIdx) === "InnerClasses") {
      let p = dataStart;
      const count = outer.readUInt16BE(p);
      p += 2;
      for (let e = 0; e < count; e++) {
        const innerInfo = outer.readUInt16BE(p);
        const nameIndex = outer.readUInt16BE(p + 4);
        const flagsOffset = p + 6;
        const innerName = utf8(cp, nameIndex);
        // outer_class_info of the entry should be BlockEntityType and name BlockEntitySupplier
        if (innerName === "BlockEntitySupplier") {
          const flags = outer.readUInt16BE(flagsOffset);
          outer.writeUInt16BE(flags | ACC_PUBLIC, flagsOffset);
          patched++;
          console.log(
            `InnerClasses entry 'BlockEntitySupplier': flags ${flags.toString(16)} -> ${(flags | ACC_PUBLIC).toString(16)}`
          );
        }
        p += 8;
      }
    }
    o = attrStart + 6 + len;
  }
  if (patched !== 1) throw new Error(`expected to patch exactly 1 InnerClasses entry, patched ${patched}`);
  writeFileSync(outerPath, outer);
}

// ---------------------------------------------------------------- 3. pack the patch jar (compile-only classpath entry)
execSync(
  `cd ${WORK} && ~/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2/bin/jar --create --file ${OUT_JAR} net`
);
console.log("wrote", OUT_JAR);
