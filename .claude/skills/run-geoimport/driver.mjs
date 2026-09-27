#!/usr/bin/env node
// Drives the Geoimport Android app on an emulator: boot, install, seed data,
// tap by on-screen text, screenshot. Run `node driver.mjs help` for commands.
//
// Written as Node rather than PowerShell so it behaves identically from the
// Bash tool and the PowerShell tool, and so host paths never pass through Git
// Bash's path mangling (see "adb push" in SKILL.md Gotchas).

import { execFileSync, spawn } from "node:child_process";
import { existsSync, mkdirSync } from "node:fs";
import { homedir } from "node:os";
import path from "node:path";

const PKG = "com.pbungert.geoimport";
const ACTIVITY = `${PKG}/.MainActivity`;
const TRACKS_DIR = "/sdcard/Documents/Geoimport";
const REPO = path.resolve(import.meta.dirname, "../../..");
const SHOTS = path.join(REPO, "build", "run-shots");

const SDK =
  process.env.ANDROID_SDK_ROOT ||
  process.env.ANDROID_HOME ||
  path.join(homedir(), "AppData", "Local", "Android", "Sdk");
const ADB = path.join(SDK, "platform-tools", "adb.exe");
const EMULATOR = path.join(SDK, "emulator", "emulator.exe");
// Gradle needs a real JDK; the one shipped with Android Studio is the only
// one this machine is known to have.
const JAVA_HOME =
  process.env.JAVA_HOME || "C:\\Program Files\\Android\\Android Studio1\\jbr";

const run = (file, args, opts = {}) =>
  execFileSync(file, args, { encoding: "utf8", maxBuffer: 64 << 20, ...opts });
const adb = (...args) => run(ADB, args);
/** adb shell, as one string, so quoting works the same from either host shell. */
const sh = (cmd) => adb("shell", cmd);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function deviceOnline() {
  try {
    return /\bdevice\b/.test(adb("devices"));
  } catch {
    return false;
  }
}

async function waitFor(label, predicate, timeoutMs = 180_000) {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    if (await predicate()) return true;
    await sleep(2000);
  }
  throw new Error(`timed out waiting for ${label}`);
}

async function boot() {
  if (!deviceOnline()) {
    const avds = run(EMULATOR, ["-list-avds"]).trim().split(/\r?\n/).filter(Boolean);
    if (!avds.length) throw new Error("no AVDs; create one in Android Studio");
    const avd = process.env.AVD || avds[0];
    console.log(`starting ${avd}`);
    // Detached: the emulator must outlive this process.
    spawn(EMULATOR, ["-avd", avd], { detached: true, stdio: "ignore" }).unref();
  }
  await waitFor("boot", () => {
    try {
      return sh("getprop sys.boot_completed").trim() === "1";
    } catch {
      return false;
    }
  });
  console.log("booted");
}

function install() {
  const gradlew = path.join(REPO, "gradlew.bat");
  // shell: true is required - since the CVE-2024-27980 fix, Node refuses to
  // spawn .bat/.cmd directly and fails with EINVAL.
  run(`"${gradlew}" :app:assembleDebug --console=plain`, [], {
    cwd: REPO,
    env: { ...process.env, JAVA_HOME },
    stdio: "inherit",
    shell: true,
  });
  const apk = path.join(REPO, "app", "build", "outputs", "apk", "debug", "app-debug.apk");
  // -g grants runtime permissions; MANAGE_EXTERNAL_STORAGE is not one of them.
  console.log(adb("install", "-r", "-g", apk).trim());
  grant();
}

function grant() {
  sh(`appops set ${PKG} MANAGE_EXTERNAL_STORAGE allow`);
  console.log("all-files access granted");
}

/** uiautomator's XML arrives as one enormous line; split it into nodes. */
function dump() {
  // While the app is still drawing its first frame this fails with "null root
  // node" and leaves the previous dump on disk, so a caller that ignored the
  // failure would read a stale screen. Insist on the success line instead.
  const quiet = { stdio: ["ignore", "pipe", "ignore"] };
  const said = run(ADB, ["shell", "uiautomator dump /sdcard/ui.xml"], quiet);
  if (!/dumped to/i.test(said)) throw new Error(`uiautomator: ${said.trim()}`);
  const xml = run(ADB, ["shell", "cat /sdcard/ui.xml"], quiet);
  const nodes = [];
  for (const node of xml.split("<node ").slice(1)) {
    const text = /\btext="([^"]*)"/.exec(node)?.[1] ?? "";
    const desc = /content-desc="([^"]*)"/.exec(node)?.[1] ?? "";
    const b = /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(node);
    if (!b) continue;
    const [x1, y1, x2, y2] = b.slice(1, 5).map(Number);
    nodes.push({
      text,
      desc,
      bounds: [x1, y1, x2, y2],
      cx: Math.round((x1 + x2) / 2),
      cy: Math.round((y1 + y2) / 2),
    });
  }
  return nodes;
}

const matches = (needle) => (n) =>
  n.text.toLowerCase().includes(needle.toLowerCase()) ||
  n.desc.toLowerCase().includes(needle.toLowerCase());

function find(needle) {
  const hits = dump().filter(matches(needle));
  if (!hits.length) throw new Error(`no node matching ${JSON.stringify(needle)}`);
  return hits;
}

function tap(x, y) {
  sh(`input tap ${x} ${y}`);
}

/**
 * Taps the node whose text contains [needle]. `--x N` taps that column at the
 * node's row instead of its centre - the track list's checkboxes are separate
 * nodes from their labels and sit at x=94.
 */
function tapText(needle, opts) {
  const hit = find(needle)[0];
  const x = opts.x ?? hit.cx;
  tap(x, hit.cy);
  console.log(`tapped ${x},${hit.cy} (${JSON.stringify(hit.text || hit.desc)})`);
}

async function waitText(needle, seconds = 60) {
  await waitFor(
    `text ${JSON.stringify(needle)}`,
    () => {
      try {
        return dump().some(matches(needle));
      } catch {
        return false;
      }
    },
    seconds * 1000,
  );
  console.log(`saw ${JSON.stringify(needle)}`);
}

function shot(name = `shot-${Date.now()}`) {
  mkdirSync(SHOTS, { recursive: true });
  const local = path.join(SHOTS, `${name}.png`);
  sh("screencap -p /sdcard/shot.png");
  // Quiet: the path is the only thing worth printing, so `shot` is usable
  // inside a command substitution.
  run(ADB, ["pull", "/sdcard/shot.png", local], { stdio: ["ignore", "ignore", "ignore"] });
  console.log(local);
  return local;
}

function pushTrack(hostPath) {
  const abs = path.resolve(hostPath);
  if (!existsSync(abs)) throw new Error(`no such file: ${abs}`);
  sh(`mkdir -p ${TRACKS_DIR}`);
  adb("push", abs, `${TRACKS_DIR}/`);
  // The app lists files it can see through MediaStore as well as the folder.
  sh(`am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE ` +
    `-d file://${TRACKS_DIR}/${path.basename(abs)}`);
  console.log(`pushed ${path.basename(abs)}`);
}

/**
 * Makes an "Import NN" folder of dummy RAFs whose modification times are the
 * capture times the app will geotag against. Times are UTC: the device's
 * `touch -t` reads them as UTC even though `ls` prints local.
 */
function seedPhotos(folder, times) {
  if (!times.length) throw new Error("give at least one YYYYMMDDhhmm time (UTC)");
  const dir = `/sdcard/Pictures/${folder}`;
  sh(`mkdir -p "${dir}"`);
  times.forEach((t, i) => {
    const name = `DSCF${String(i + 1).padStart(4, "0")}.RAF`;
    run(ADB, ["shell", `dd if=/dev/zero of="${dir}/${name}" bs=1024 count=64`], {
      stdio: ["ignore", "ignore", "ignore"], // dd reports on stderr
    });
    sh(`touch -t ${t} "${dir}/${name}"`);
  });
  console.log(sh(`ls -l "${dir}"`).trim());
}

/**
 * Waits until the screen stops changing, so a screenshot taken next does not
 * catch a menu mid-fade. Waiting for the tapped label to disappear would be
 * neater but does not generalise: "Import log" is both a menu item and the
 * title of the screen it opens.
 */
async function settle(tries = 10) {
  const snapshot = () =>
    dump().map((n) => `${n.text}|${n.desc}|${n.bounds}`).join("\n");
  let previous = null;
  for (let i = 0; i < tries; i++) {
    let current;
    try {
      current = snapshot();
    } catch {
      current = null;
    }
    if (current && current === previous) return;
    previous = current;
    await sleep(400);
  }
}

/** Opens the overflow menu and taps an item by its label. */
async function menu(item) {
  tap(980, 240);
  await waitText(item, 10);
  tapText(item, {});
  await settle();
}

const SWIPES = {
  "sheet-up": [540, 1930, 540, 300],
  "sheet-down": [540, 400, 540, 2200],
};

const HELP = `usage: node driver.mjs <command> [args]

  boot                        start the AVD if needed, wait for boot
  install                     assembleDebug, install, grant all-files access
  grant                       grant all-files access only
  start | stop                launch or force-stop the app
  shot [name]                 screenshot -> build/run-shots/<name>.png
  ui                          print every on-screen text with tap coordinates
  find <text>                 print nodes whose text contains <text>
  tap <x> <y>                 tap a point
  tap-text <text> [--x N]     tap a node by text; --x taps that column instead
  wait-text <text> [secs]     poll until the text appears
  swipe <x1> <y1> <x2> <y2>   swipe
  sheet-up | sheet-down       expand or collapse the bottom sheet
  menu <item>                 open the overflow menu and tap an item
  settle                      wait until the screen stops changing
  push-track <file.gpx>       push a track to Documents/Geoimport
  seed-photos <folder> <YYYYMMDDhhmm...>
                              dummy RAFs with those UTC capture times`;

const [cmd, ...rest] = process.argv.slice(2);
const flags = {};
const args = [];
for (let i = 0; i < rest.length; i++) {
  if (rest[i] === "--x") flags.x = Number(rest[++i]);
  else args.push(rest[i]);
}

switch (cmd) {
  case "boot": await boot(); break;
  case "install": install(); break;
  case "grant": grant(); break;
  case "start": sh(`am start -n ${ACTIVITY}`); await waitText("Record", 60); break;
  case "stop": sh(`am force-stop ${PKG}`); break;
  case "shot": shot(args[0]); break;
  case "ui":
    for (const n of dump()) {
      if (n.text || n.desc) console.log(`${n.cx},${n.cy}\t${n.text || `[${n.desc}]`}`);
    }
    break;
  case "find":
    for (const n of find(args[0])) {
      console.log(`${n.cx},${n.cy}\tbounds=${JSON.stringify(n.bounds)}\t${n.text || n.desc}`);
    }
    break;
  case "tap": tap(Number(args[0]), Number(args[1])); break;
  case "tap-text": tapText(args[0], flags); break;
  case "wait-text": await waitText(args[0], Number(args[1] ?? 60)); break;
  case "swipe": sh(`input swipe ${args.slice(0, 4).join(" ")} 400`); break;
  case "sheet-up":
  case "sheet-down": sh(`input swipe ${SWIPES[cmd].join(" ")} 400`); break;
  case "menu": await menu(args.join(" ")); break;
  case "settle": await settle(); break;
  case "push-track": pushTrack(args[0]); break;
  case "seed-photos": seedPhotos(args[0], args.slice(1)); break;
  default: console.log(HELP); process.exit(cmd ? 1 : 0);
}
