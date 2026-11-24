#!/usr/bin/env python3
"""Crash hunt driver: walks with position feedback, attacks/jumps, opens menus.
usage: walkhunt.py [minutes=30] [--nolaunch]"""
import subprocess, time, re, random, sys, os, collections, math
os.environ["MSYS_NO_PATHCONV"] = "1"
PKG = "com.mina.port"
D = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(D, "walkhunt-logcat.txt")
MIN = float(sys.argv[1]) if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else 30
def adb(*a, t=60):
    try: return subprocess.run(["adb", *a], capture_output=True, text=True, timeout=t, errors="replace").stdout
    except Exception: return ""
def key(*names, long=False):
    cmd = "; ".join("input keyboard keyevent %s KEYCODE_%s" % ("--longpress" if long else "", n) for n in names)
    adb("shell", cmd)
def alive(): return bool(adb("shell", "pidof", PKG).strip())
logf = None; lp = None
def start_log():
    global logf, lp
    if lp: lp.kill()
    adb("logcat", "-c")
    logf = open(LOG, "ab"); lp = subprocess.Popen(["adb", "logcat", "-v", "time", "-s", "MinaPort:*", "DEBUG:*", "libc:*", "AndroidRuntime:*"], stdout=logf, stderr=subprocess.STDOUT)
def tail_lines(n=3000):
    logf.flush()
    with open(LOG, "rb") as f:
        return f.read().decode(errors="replace").splitlines()[-n:]
POS = re.compile(r"pos (-?[\d.]+) (-?[\d.]+)")
def last_pos():
    for l in reversed(tail_lines(300)):
        m = POS.search(l)
        if m: return float(m.group(1)), float(m.group(2))
    return None
CRASH = re.compile(r"signal:|SIGSEGV|SIGABRT|SIGILL|SIGBUS|FATAL|Fatal signal")
crashes = 0; regions = collections.OrderedDict(); dist = 0.0; lastp = None
stats = collections.Counter()
def launch():
    adb("shell", "am", "force-stop", PKG); start_log()
    adb("shell", "am", "start", "-n", PKG + "/.MinaActivity")
    print("launched, waiting 150s", flush=True)
    for _ in range(15):
        time.sleep(10)
        if not alive(): print("died during load"); break
    for _ in range(3): key("ENTER"); time.sleep(3)
def handle_crash(what):
    global crashes
    crashes += 1
    lines = tail_lines(3000)
    idx = [i for i, l in enumerate(lines) if CRASH.search(l)]
    s = max(0, (idx[0] if idx else len(lines)) - 150)
    p = os.path.join(D, "crash-%d.log" % crashes)
    with open(p, "w", encoding="utf-8") as f:
        f.write("# doing: %s\n# t=%s lastpos=%s\n" % (what, time.ctime(), lastp))
        f.write("\n".join(lines[s:]))
    print("CRASH #%d saved %s (doing %s)" % (crashes, p, what), flush=True)
    launch()
def check(what):
    if not alive():
        time.sleep(2); handle_crash(what); return False
    lines = tail_lines(400)
    if any(CRASH.search(l) for l in lines[-400:] if "walkhunt" not in l) and not alive():
        handle_crash(what); return False
    return True
def region(p): return (int(p[0] // 300), int(p[1] // 300))
def track():
    global lastp, dist
    p = last_pos()
    if p:
        if lastp: 
            d = math.hypot(p[0]-lastp[0], p[1]-lastp[1])
            if d < 500: dist += d
        lastp = p; r = region(p)
        regions.setdefault(r, [p, p]); regions[r][1] = p
    return p
DIRS = ["DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT"]
def walk(d, bursts=3):
    adb("shell", "for i in 1 2 3 4 5 6 7 8 9 10 11 12; do input keyboard keyevent --longpress KEYCODE_%s; done" % d, t=120)
def menus():
    seq = random.choice([["ENTER"], ["E"], ["ENTER", "DPAD_RIGHT", "DPAD_RIGHT"], ["ENTER", "DPAD_DOWN", "DPAD_RIGHT"]])
    stats["menu"] += 1
    key(*seq); time.sleep(2)
    for _ in range(random.randint(2, 5)):
        key(random.choice(["DPAD_LEFT", "DPAD_RIGHT", "DPAD_DOWN", "DPAD_UP"])); time.sleep(0.8)
    for _ in range(3): key("K"); time.sleep(0.6)
    key("ENTER") if False else None
def mem():
    o = adb("shell", "dumpsys", "meminfo", PKG)
    m = re.search(r"TOTAL PSS:\s+(\d+)", o) or re.search(r"TOTAL\s+(\d+)", o)
    return "pss_kb=%s" % (m and m.group(1))
def tap():
    t = os.environ.get("TAP")
    if t:
        x, y, disp = t.split(","); adb("shell", "input", "-d", disp, "tap", x, y); stats["paneltap"] += 1
def gfx():
    o = adb("shell", "dumpsys", "gfxinfo", PKG)
    m = re.search(r"Total frames rendered: (\d+)", o); j = re.search(r"Janky frames: (\d+) \(([\d.]+)%\)", o)
    p50 = re.search(r"50th percentile: (\d+)ms", o)
    return "frames=%s janky=%s p50=%s" % (m and m.group(1), j and j.group(0), p50 and p50.group(1))
def main():
    global lastp
    if "--nolaunch" in sys.argv: start_log()
    else: launch()
    end = time.time() + MIN * 60; nextchk = 0; nextgfx = 0; nextmenu = time.time() + 60
    d = random.choice(DIRS); good = {x: 1.0 for x in DIRS}
    legstart = time.time(); legpos = track()
    while time.time() < end:
        walk(d)
        if random.random() < 0.3: key("J"); stats["attack"] += 1
        if random.random() < 0.2: key("SPACE"); stats["jump"] += 1
        track()
        now = time.time()
        if now - legstart > 25:   # end of leg: judge progress, choose next dir
            p = lastp; mv = math.hypot(p[0]-legpos[0], p[1]-legpos[1]) if p and legpos else 0
            good[d] = 0.5*good[d] + mv
            stats["legs"] += 1
            w = [good[x] + 3 + (8 if (x == "DPAD_UP" and random.random() < .3) else 0) for x in DIRS]
            d = random.choices(DIRS, w)[0]; legstart = now; legpos = p
        if now >= nextmenu:
            menus(); nextmenu = now + random.randint(30, 60)
        if now >= nextchk:
            nextchk = now + 10
            if not check("walking %s near %s" % (d, lastp)): legpos = None
        if now >= nextgfx:
            nextgfx = now + 120; tap(); print("gfx", gfx(), mem(), "pos", lastp, "dist %.0f" % dist, flush=True)
    check("end")
    lines = tail_lines(100000)
    c = collections.Counter(re.sub(r"[-\d.x]+", "#", l.split("MinaPort:")[-1])[:60] for l in lines if "MinaPort" in l and " pos " not in l)
    print("REPORT crashes=%d dist=%.0f stats=%s" % (crashes, dist, dict(stats)))
    print("regions(300u cells):", len(regions), [(k, tuple(round(x) for x in v[0])) for k, v in regions.items()])
    print("top log msgs:", c.most_common(8)); print("gfx", gfx())
main()
