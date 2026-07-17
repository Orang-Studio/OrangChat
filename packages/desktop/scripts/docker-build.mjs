import { spawnSync } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

// electron-builder needs wine to stamp the .exe icon and run makensis. This box
// has no wine, so the Windows targets are built inside electronuserland/builder:wine.
const IMAGE = "electronuserland/builder:wine";
const pkgDir = resolve(join(dirname(fileURLToPath(import.meta.url)), ".."));
const repoRoot = resolve(pkgDir, "..", "..");

const dockerOk = spawnSync("docker", ["info"], { stdio: "ignore" }).status === 0;
const docker = dockerOk ? ["docker"] : ["sudo", "docker"];

if (!dockerOk && spawnSync("sudo", ["-n", "docker", "info"], { stdio: "ignore" }).status !== 0) {
  console.error("Cannot reach the Docker daemon (tried direct and `sudo -n docker`).");
  console.error("Run `sudo -v` first, or use `pnpm dist:win` for a wine-free portable zip.");
  process.exit(1);
}

const uid = process.getuid?.() ?? 0;
const gid = process.getgid?.() ?? 0;

const script = [
  "set -e",
  "cd /project/packages/desktop",
  // --publish never: the generic provider is a plain nginx path, so nothing is
  // uploaded, but latest.yml is still emitted for electron-updater to poll.
  "./node_modules/.bin/electron-builder --win nsis zip --publish never --config electron-builder.yml",
  `chown -R ${uid}:${gid} /project/packages/desktop/release`,
].join(" && ");

const args = [
  ...docker.slice(1),
  "run",
  "--rm",
  "-v",
  `${repoRoot}:/project`,
  "-v",
  `${process.env.HOME}/.cache/electron:/root/.cache/electron`,
  "-v",
  `${process.env.HOME}/.cache/electron-builder:/root/.cache/electron-builder`,
  "-e",
  "USE_HARD_LINKS=false",
  IMAGE,
  "/bin/bash",
  "-c",
  script,
];

console.log(`Building Windows targets in ${IMAGE} ...`);
const result = spawnSync(docker[0], args, { stdio: "inherit" });
process.exit(result.status ?? 1);
