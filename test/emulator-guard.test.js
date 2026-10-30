// Tests for scripts/emulator-guard.js: may scripts/ci-local.sh start the local emulator now?
// Stefan's rules: never next to an iOS build or simulator (M1, 16 GB), never touch a physical phone.
import { test } from "node:test";
import assert from "node:assert/strict";
import { decide, physicalDevices } from "../scripts/emulator-guard.js";

const idle = { processes: ["zsh", "node", "java"], bootedSimulators: "", adbDevices: "List of devices attached\n\n" };

test("starts when nothing heavy runs and no phone is attached", () => {
  assert.deepEqual(decide(idle), { ok: true });
});

test("refuses while xcodebuild runs", () => {
  const result = decide({ ...idle, processes: [...idle.processes, "xcodebuild"] });
  assert.equal(result.ok, false);
  assert.match(result.reason, /xcodebuild/);
});

test("refuses while the iOS Simulator app runs or a simulator is booted", () => {
  assert.equal(decide({ ...idle, processes: [...idle.processes, "Simulator"] }).ok, false);
  const booted = "== Devices ==\n-- iOS 27.0 --\n    iPhone 17 (ABC-123) (Booted) \n";
  const result = decide({ ...idle, bootedSimulators: booted });
  assert.equal(result.ok, false);
  assert.match(result.reason, /simulator/i);
});

test("refuses when a physical phone is attached (USB or Wi-Fi)", () => {
  const adb = "List of devices attached\nR58N123ABC\tdevice\n192.168.1.20:5555\tdevice\nemulator-5584\tdevice\n";
  assert.deepEqual(physicalDevices(adb), ["R58N123ABC", "192.168.1.20:5555"]);
  const result = decide({ ...idle, adbDevices: adb });
  assert.equal(result.ok, false);
  assert.match(result.reason, /physical/);
});

test("an emulator alone is not a phone", () => {
  assert.deepEqual(physicalDevices("List of devices attached\nemulator-5584\tdevice\nemulator-5556\toffline\n"), []);
});
