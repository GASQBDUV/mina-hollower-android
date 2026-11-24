#!/usr/bin/env python3
"""Verify that every undefined symbol of the glibc libs is provided by
(Android system stubs + bundled glibc libs + libglibc_compat.so).
Run inside WSL:  python3 verify.py [--libdir libs] [--compat out/libglibc_compat.so]
Exit code 0 = nothing unresolved (weak undefined are reported separately)."""
import argparse, os, subprocess, sys, glob
NDK = os.path.expanduser("~/android-sdk/ndk/27.3.13750724")
BIN = NDK + "/toolchains/llvm/prebuilt/linux-x86_64/bin"
SYSROOT = NDK + "/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/29"
SYSLIBS = ["libc.so", "libm.so", "libdl.so", "liblog.so", "libEGL.so", "libGLESv2.so", "libGLESv3.so",
           "libandroid.so", "libz.so"]
CHAIN = ["libgcc_s.so.1", "libstdc++.so.6", "libc++abi.so.1", "libc++.so.1", "libatomic.so.1", "libgothic_patches.so"]

def nm(path, mode):
    out = subprocess.run([BIN + "/llvm-nm", "-D", mode, path], capture_output=True, text=True).stdout
    res = []
    for l in out.splitlines():
        p = l.split()
        if len(p) >= 2:
            res.append((p[0] if len(p) == 2 else p[1], p[-1].split("@")[0]))  # (type, name)
    return res

def main():
    ap = argparse.ArgumentParser()
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--libdir", default=here + "/libs")
    ap.add_argument("--compat", default=here + "/out/libglibc_compat.so")
    ap.add_argument("--with-android-libcxx-shared", action="store_true")
    a = ap.parse_args()
    sys_exp = set()
    for l in SYSLIBS:
        p = f"{SYSROOT}/{l}"
        if os.path.exists(p):
            sys_exp |= {n for t, n in nm(p, "--defined-only")}
    if a.with_android_libcxx_shared:
        sys_exp |= {n for t, n in nm(f"{SYSROOT}/libc++_shared.so", "--defined-only")}
    exp = {l: {n for t, n in nm(f"{a.libdir}/{l}", "--defined-only")} for l in CHAIN}
    compat = {n for t, n in nm(a.compat, "--defined-only")} if os.path.exists(a.compat) else set()
    print(f"system exports: {len(sys_exp)}  compat exports: {len(compat)}")
    bad = 0
    for l in CHAIN:
        und = nm(f"{a.libdir}/{l}", "--undefined-only")
        strong = {n for t, n in und if t != "w" and t != "v"}
        weak = {n for t, n in und if t in ("w", "v")}
        others = set().union(*[exp[o] for o in CHAIN if o != l])
        miss_wo = strong - sys_exp - others
        miss = miss_wo - compat
        wmiss = weak - sys_exp - others - compat
        print(f"== {l}: undef strong={len(strong)} weak={len(weak)}  "
              f"needing compat={len(miss_wo)}  UNRESOLVED={len(miss)}  weak-unresolved(ok, resolve to 0)={len(wmiss)}")
        for s in sorted(miss): print("   UNRESOLVED", s)
        for s in sorted(wmiss): print("   weak-null  ", s)
        bad += len(miss)
    # mmap_registry_add is not a glibc symbol: app-provided (libsystem_shim.so)
    print("RESULT:", "OK, nothing unresolved" if bad == 0 else f"{bad} unresolved")
    sys.exit(1 if bad else 0)
main()
