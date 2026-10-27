// May the local emulator start now? Used by scripts/emulator.sh before every start.
// Never next to xcodebuild or an iOS simulator (one heavy job at a time on the 16 GB M1), and never with a physical
// phone attached (the UI tests install the app on every adb device).
// CLI: node scripts/emulator-guard.js   (exit 0 = go; exit 1 = no, with the reason on stderr)
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

/** adb serials that aren't emulators (USB serials, Wi-Fi host:port). */
export function physicalDevices(adbDevices) {
  return adbDevices
    .split("\n")
    .slice(1)
    .map((line) => line.trim().split(/\s+/))
    .filter(([serial, state]) => serial && state)
    .map(([serial]) => serial)
    .filter((serial) => !serial.startsWith("emulator-"));
}

export function decide({ processes, bootedSimulators, adbDevices }) {
  if (processes.includes("xcodebuild")) return { ok: false, reason: "xcodebuild is running" };
  if (processes.includes("Simulator") || /\(Booted\)/.test(bootedSimulators)) {
    return { ok: false, reason: "an iOS simulator is running" };
  }
  const phones = physicalDevices(adbDevices);
  if (phones.length > 0) return { ok: false, reason: `a physical device is attached (${phones.join(", ")})` };
  return { ok: true };
}

function run(cmd, args) {
  try {
    return execFileSync(cmd, args, { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] });
  } catch {
    return "";
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const result = decide({
    processes: run("ps", ["-axco", "comm"]).split("\n").map((p) => p.trim()),
    bootedSimulators: run("xcrun", ["simctl", "list", "devices", "booted"]),
    adbDevices: run("adb", ["devices"]),
  });
  if (!result.ok) {
    console.error(`Emulator not started: ${result.reason}. One heavy job at a time; no physical phones.`);
    process.exit(1);
  }
}
