#!/usr/bin/env python3
"""The measurements behind scripts/measure. Runs inside the emulator's container, where
adb is; scripts/measure starts it and saves what it prints.

    measure.py <serial> <apk path or "-"> <package> <user> <options as JSON>

Prints a report for people on stderr and the same numbers as JSON on stdout.
"""
import json
import re
import statistics
import subprocess
import sys
import time


def adb(*args, check=False):
    r = subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        sys.exit("adb %s failed: %s" % (" ".join(args), (r.stderr or r.stdout).strip()[-400:]))
    return r.stdout.replace("\r", "")


def sh(command):
    return adb("shell", command)


def say(line=""):
    print(line, file=sys.stderr, flush=True)


def kb(value):
    if value is None:
        return "n/a"
    return "%.1f MB" % (value / 1024.0) if value >= 1024 else "%d kB" % value


def meminfo(package):
    """Memory in use, in kB, from `dumpsys meminfo`: the total and its biggest parts."""
    out = sh("dumpsys meminfo " + package)
    found = {}
    for key, pattern in (("pss_total", r"TOTAL PSS:\s+(\d+)"), ("rss_total", r"TOTAL RSS:\s+(\d+)"),
                         ("java_heap", r"Java Heap:\s+(\d+)"), ("native_heap", r"Native Heap:\s+(\d+)"),
                         ("code", r"Code:\s+(\d+)"), ("graphics", r"Graphics:\s+(\d+)")):
        m = re.search(pattern, out)
        found[key] = int(m.group(1)) if m else None
    return found


def playing(package):
    """Does the system's list of media sessions show this app playing?"""
    out = sh("dumpsys media_session")
    for block in re.split(r"\n(?=\s{4}\S.* " + re.escape(package) + r"/)", out):
        if package in block.split("\n", 1)[0] and re.search(r"state=PlaybackState \{state=(PLAYING\()?3", block):
            return True
    return False


def main():
    global SERIAL
    SERIAL, apk, package, user, raw = sys.argv[1:6]
    opt = json.loads(raw)
    result = {"package": package, "when": time.strftime("%Y-%m-%d %H:%M:%S"),
              "device": sh("getprop ro.product.model").strip() + ", Android " + sh("getprop ro.build.version.release").strip()}

    # --- size -------------------------------------------------------------------------
    if apk != "-":
        size = int(subprocess.run(["stat", "-c", "%s", apk], capture_output=True, text=True).stdout)
        result["apk_bytes"] = size
        if not opt.get("keep"):
            adb("uninstall", package)
        out = subprocess.run(["adb", "-s", SERIAL, "install", "-r", "-t", "-g", apk], capture_output=True, text=True)
        if "Success" not in out.stdout:
            sys.exit("could not install %s: %s" % (apk, (out.stdout + out.stderr).strip()[-300:]))
    if "package:" not in sh("pm path " + package):
        sys.exit("%s is not installed on the emulator" % package)
    version = re.search(r"versionName=(\S+)", sh("dumpsys package " + package))
    result["version"] = version.group(1) if version else None
    target = sh("cmd package resolve-activity --brief --user %s -c android.intent.category.LAUNCHER %s" % (user, package)).strip().split("\n")[-1]
    if "/" not in target:
        sys.exit("%s has no screen that opens from the launcher" % package)

    # Open it once so it has made its files, then see what it takes up. Needs adb as root
    # (the "Google APIs" emulator image allows it; a real phone or a Play image does not).
    sh("am start --user %s -W -n %s" % (user, target))
    time.sleep(3)
    sh("am force-stop " + package)
    rooted = "uid=0" in subprocess.run(["adb", "-s", SERIAL, "shell", "su 0 id 2>/dev/null || id"], capture_output=True, text=True).stdout

    def du(path):
        out = sh("su 0 du -sk %s 2>/dev/null" % path).strip().split()
        return int(out[0]) if out and out[0].isdigit() else None

    if rooted:
        code_path = re.search(r"codePath=(\S+)", sh("dumpsys package " + package))
        result["installed_code_kb"] = du(code_path.group(1)) if code_path else None
        result["installed_data_kb"] = du("/data/user/%s/%s" % (user, package))
    state = re.search(r"\[status=([\w-]+)\]", sh("dumpsys package " + package + " | grep -A4 'Dexopt state'"))
    result["compiled_as"] = state.group(1) if state else None

    say("Measurements for %s %s on the emulator (%s)" % (package, result["version"] or "", result["device"]))
    say()
    say("Size")
    if apk != "-":
        say("  APK file                 %s   (what a person downloads)" % kb(result["apk_bytes"] / 1024))
    if rooted:
        say("  installed: code          %s   (the APK plus what the system compiles from it; compiled as: %s)"
            % (kb(result["installed_code_kb"]), result["compiled_as"]))
        say("  installed: data          %s   (after opening once)" % kb(result["installed_data_kb"]))
    else:
        say("  installed size           n/a (adb cannot become root on this image)")

    # --- cold start -------------------------------------------------------------------
    runs = int(opt.get("runs", 5))
    totals, waits, drawn = [], [], []
    for i in range(runs + 1):
        sh("am force-stop " + package)
        time.sleep(1.5)
        adb("logcat", "-c")
        out = sh("am start --user %s -W -n %s" % (user, target))
        total = re.search(r"TotalTime: (\d+)", out)
        wait = re.search(r"WaitTime: (\d+)", out)
        if not total or "LaunchState: COLD" not in out:
            continue
        time.sleep(2.5)
        full = re.search(r"Fully drawn " + re.escape(package) + r"\S*: \+(?:(\d+)s)?(\d+)ms", adb("logcat", "-d", "-s", "ActivityTaskManager:I"))
        if i == 0:
            continue  # the first start after an install does extra work; it is not the usual case
        totals.append(int(total.group(1)))
        waits.append(int(wait.group(1)) if wait else None)
        if full:
            drawn.append(int(full.group(1) or 0) * 1000 + int(full.group(2)))
    if totals:
        result["cold_start_ms"] = {"median": int(statistics.median(totals)), "min": min(totals), "max": max(totals), "runs": totals}
        say()
        say("Cold start (process not running; %d starts after one discarded)" % len(totals))
        say("  to first frame           median %d ms   (fastest %d, slowest %d)" % (result["cold_start_ms"]["median"], min(totals), max(totals)))
        if drawn:
            result["fully_drawn_ms"] = {"median": int(statistics.median(drawn)), "runs": drawn}
            say("  to fully drawn           median %d ms   (the app says when: reportFullyDrawn)" % result["fully_drawn_ms"]["median"])
        else:
            say("  to fully drawn           n/a (the app does not call reportFullyDrawn)")
    else:
        say("\nCold start: could not be measured (am start -W gave no time)")

    # --- memory -----------------------------------------------------------------------
    settle = int(opt.get("settle", 10))
    time.sleep(settle)
    idle = meminfo(package)
    result["memory_idle_kb"] = idle
    say()
    say("Memory (PSS: the app's own share of what it uses)")
    say("  open, idle %2ds           %s   (Java heap %s, native %s, graphics %s, code %s)"
        % (settle, kb(idle["pss_total"]), kb(idle["java_heap"]), kb(idle["native_heap"]), kb(idle["graphics"]), kb(idle["code"])))
    probe = "am instrument --user %s -w -e target %s -e mode %%s org.medianexus.carprobe/.Probe" % (user, package)
    started = "PASS  play" in sh(probe % "play")
    time.sleep(2)
    if started or playing(package):
        time.sleep(settle)
        busy = meminfo(package)
        result["memory_playing_kb"] = busy
        say("  playing %2ds              %s   (Java heap %s, native %s, graphics %s, code %s)"
            % (settle, kb(busy["pss_total"]), kb(busy["java_heap"]), kb(busy["native_heap"]), kb(busy["graphics"]), kb(busy["code"])))
        sh("input keyevent KEYCODE_HOME")
        time.sleep(settle)
        back = meminfo(package)
        result["memory_playing_background_kb"] = back
        say("  playing, app in background %s" % kb(back["pss_total"]))
        sh(probe % "pause")
    else:
        result["memory_playing_kb"] = None
        say("  playing                  n/a (nothing started when asked to play: no media service yet, or nothing to resume)")

    # --- scrolling --------------------------------------------------------------------
    sh("am start --user %s -W -n %s" % (user, target))
    time.sleep(2)
    for words in opt.get("taps", []):
        tree = adb("exec-out", "uiautomator", "dump", "/dev/tty")
        found = subprocess.run(["python3", "/work/scripts/uitree.py", "--find", words], input=tree, capture_output=True, text=True)
        if found.returncode != 0:
            sys.exit("measure: nothing on screen says \"%s\" (given with --tap), so the long list was not reached" % words)
        sh("input tap " + found.stdout.strip())
        time.sleep(1.5)
    size = re.search(r"(\d+)x(\d+)", sh("wm size").strip().split("\n")[-1])
    w, h = int(size.group(1)), int(size.group(2))
    sh("dumpsys gfxinfo %s reset" % package)
    flings = int(opt.get("flings", 8))
    for i in range(flings):
        sh("input swipe %d %d %d %d 180" % (w // 2, h * 7 // 10, w // 2, h * 3 // 10))
        time.sleep(0.9)
    for i in range(flings // 2):
        sh("input swipe %d %d %d %d 180" % (w // 2, h * 3 // 10, w // 2, h * 7 // 10))
        time.sleep(0.9)
    gfx = sh("dumpsys gfxinfo " + package)

    def number(pattern, cast=int):
        m = re.search(pattern, gfx)
        return cast(m.group(1)) if m else None

    frames = number(r"Total frames rendered: (\d+)")
    scroll = {"frames": frames, "janky": number(r"Janky frames: (\d+)"),
              "janky_percent": number(r"Janky frames: \d+ \(([\d.]+)%\)", float),
              "p50_ms": number(r"50th percentile: (\d+)ms"), "p90_ms": number(r"90th percentile: (\d+)ms"),
              "p95_ms": number(r"95th percentile: (\d+)ms"), "p99_ms": number(r"99th percentile: (\d+)ms"),
              "slow_ui_thread": number(r"Number Slow UI thread: (\d+)"), "missed_deadline": number(r"Number Frame deadline missed: (\d+)")}
    result["scroll"] = scroll
    say()
    say("Scrolling (%d flings down, %d up%s)" % (flings, flings // 2, (", after tapping " + ", ".join('"%s"' % t for t in opt["taps"])) if opt.get("taps") else " on the first screen"))
    if frames and frames >= 30:
        say("  frames drawn             %d" % frames)
        say("  slow frames              %s (%s%%)" % (scroll["janky"], scroll["janky_percent"]))
        say("  frame time               half under %s ms, 90%% under %s ms, 95%% under %s ms, 99%% under %s ms"
            % (scroll["p50_ms"], scroll["p90_ms"], scroll["p95_ms"], scroll["p99_ms"]))
        say("  held up on the main thread %s frames; missed their deadline %s" % (scroll["slow_ui_thread"], scroll["missed_deadline"]))
    else:
        say("  only %s frames were drawn: the screen that was open does not scroll. Name the way to a long list with --tap." % frames)
    say()
    say("Read these against earlier builds on this emulator, not against a phone: the emulator")
    say("draws with the processor (no graphics chip) and shares the machine, so frame times and")
    say("start-up are slower and noisier than on real hardware. Sizes and memory carry over well.")
    sh("am force-stop " + package)
    print(json.dumps(result, indent=1))


if __name__ == "__main__":
    main()
