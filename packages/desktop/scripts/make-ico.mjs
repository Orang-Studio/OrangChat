import { readFileSync, writeFileSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const SIZES = [16, 24, 32, 48, 64, 128, 256];
const build = join(dirname(fileURLToPath(import.meta.url)), "..", "build");

const images = SIZES.map((size) => ({ size, data: readFileSync(join(build, `icon-${size}.png`)) }));

const header = Buffer.alloc(6);
header.writeUInt16LE(0, 0);
header.writeUInt16LE(1, 2);
header.writeUInt16LE(images.length, 4);

let offset = 6 + images.length * 16;
const entries = [];
for (const { size, data } of images) {
  const entry = Buffer.alloc(16);
  entry.writeUInt8(size >= 256 ? 0 : size, 0);
  entry.writeUInt8(size >= 256 ? 0 : size, 1);
  entry.writeUInt8(0, 2);
  entry.writeUInt8(0, 3);
  entry.writeUInt16LE(1, 4);
  entry.writeUInt16LE(32, 6);
  entry.writeUInt32LE(data.length, 8);
  entry.writeUInt32LE(offset, 12);
  entries.push(entry);
  offset += data.length;
}

writeFileSync(join(build, "icon.ico"), Buffer.concat([header, ...entries, ...images.map((i) => i.data)]));
writeFileSync(join(build, "icon.png"), readFileSync(join(build, "icon-512.png")));

for (const { size } of images) rmSync(join(build, `icon-${size}.png`), { force: true });
rmSync(join(build, "icon-512.png"), { force: true });

console.log(`icon.ico written (${SIZES.join(", ")}) + icon.png (512x512)`);
