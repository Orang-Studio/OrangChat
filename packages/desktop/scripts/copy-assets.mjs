import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
mkdirSync(join(root, "dist"), { recursive: true });
copyFileSync(join(root, "src", "picker.html"), join(root, "dist", "picker.html"));
copyFileSync(join(root, "..", "shared", "games.json"), join(root, "dist", "games.json"));
