#!/usr/bin/env python3
"""Make glibc-aarch64 shared libs loadable by the Android (bionic) linker.

usage: patch_libs.py <indir-with-original-libs> <outdir>

Per library (pure-python, in-place string/tag edits, no patchelf needed):
  1. DT_SONAME / DT_NEEDED strings are rewritten (new name must not be longer than the old one):
       libc.so.6, libpthread.so.0, librt.so.1, ld-linux-aarch64.so.1 -> libglc.so  (= libglibc_compat.so's SONAME)
       libm.so.6 -> libm.so, libdl.so.2 -> libdl.so, libEGL.so.1 -> libEGL.so, libGLESv2.so.2 -> libGLESv2.so
       libgcc_s.so.1 -> libgcc_sg.so, libstdc++.so.6 -> libstdcxxg.so, libc++abi.so.1 -> libcxxabig.so,
       libc++.so.1 -> libcxxg.so, libatomic.so.1 -> libatomicg.so
     (Android only extracts lib*.so from the APK and matches NEEDED by exact soname/file name;
      versioned names such as libstdc++.so.6 can neither be packaged nor found.)
  2. Symbol versioning is disabled: DT_VERSYM/DT_VERDEF/DT_VERDEFNUM/DT_VERNEED/DT_VERNEEDNUM are turned
     into DT_RELACOUNT (an ignored hint).  See README "Symbol versions" for why.
"""
import os, struct, sys, shutil

RENAME = {
    "libc.so.6": "libglc.so", "libpthread.so.0": "libglc.so", "librt.so.1": "libglc.so",
    "ld-linux-aarch64.so.1": "libglc.so", "libdl.so.2": "libdl.so", "libm.so.6": "libm.so",
    "libEGL.so.1": "libEGL.so", "libGLESv2.so.2": "libGLESv2.so",
    "libgcc_s.so.1": "libgcc_sg.so", "libstdc++.so.6": "libstdcxxg.so",
    "libc++abi.so.1": "libcxxabig.so", "libc++.so.1": "libcxxg.so", "libatomic.so.1": "libatomicg.so",
}
OUTNAME = {v: v for v in RENAME.values()}
FILES = ["libgcc_s.so.1", "libstdc++.so.6", "libc++abi.so.1", "libc++.so.1", "libatomic.so.1",
         "libgothic_patches.so"]
DT_NULL, DT_NEEDED, DT_STRTAB, DT_STRSZ, DT_SONAME = 0, 1, 5, 10, 14
VER_TAGS = {0x6ffffff0, 0x6ffffffc, 0x6ffffffd, 0x6ffffffe, 0x6fffffff}
DT_RELACOUNT = 0x6ffffff9

def patch(data):
    b = bytearray(data)
    assert b[:4] == b"\x7fELF" and b[4] == 2, "ELF64 only"
    phoff, = struct.unpack_from("<Q", b, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", b, 0x36)
    loads, dyn = [], None
    for i in range(phnum):
        p_type, _, p_off, p_va, _, p_fsz, p_msz, _ = struct.unpack_from("<IIQQQQQQ", b, phoff + i * phentsize)
        if p_type == 1: loads.append((p_va, p_off, p_fsz))
        elif p_type == 2: dyn = (p_off, p_fsz)
    def v2o(va):
        for lv, lo, ls in loads:
            if lv <= va < lv + ls: return va - lv + lo
        raise ValueError("va not mapped")
    ents = []
    off = dyn[0]
    while off < dyn[0] + dyn[1]:
        tag, val = struct.unpack_from("<qQ", b, off)
        ents.append((off, tag, val))
        if tag == DT_NULL: break
        off += 16
    strtab = next(v for _, t, v in ents if t == DT_STRTAB)
    so = v2o(strtab)
    log = []
    done = {}
    for eoff, tag, val in ents:
        if tag in (DT_NEEDED, DT_SONAME):
            s = so + val
            e = b.index(0, s)
            old = b[s:e].decode()
            new = RENAME.get(old)
            if new is None: continue
            assert len(new) <= len(old), (old, new)
            if s in done:        # same string slot already rewritten (shared by two entries)
                continue
            b[s:s + len(old) + 1] = new.encode() + b"\0" * (len(old) - len(new) + 1)
            done[s] = new
            log.append(f"{'SONAME' if tag == DT_SONAME else 'NEEDED'} {old} -> {new}")
        elif tag in VER_TAGS:
            struct.pack_into("<qQ", b, eoff, DT_RELACOUNT, 0)
            log.append(f"tag 0x{tag:x} disabled")
    # STB_GNU_UNIQUE (10) is rejected by bionic: turn into GLOBAL (1)
    shoff, = struct.unpack_from("<Q", b, 0x28)
    shentsize, shnum = struct.unpack_from("<HH", b, 0x3a)
    nuniq = 0
    for i in range(shnum):
        base = shoff + i * shentsize
        sh_type, = struct.unpack_from("<I", b, base + 4)
        if sh_type == 11:  # SHT_DYNSYM
            sh_off, sh_size = struct.unpack_from("<QQ", b, base + 0x18)
            for so_ in range(sh_off, sh_off + sh_size, 24):
                info = b[so_ + 4]
                if info >> 4 == 10:
                    b[so_ + 4] = (1 << 4) | (info & 15); nuniq += 1
    if nuniq: log.append(f"{nuniq} GNU_UNIQUE -> GLOBAL")
    return bytes(b), log

def main():
    src, dst = sys.argv[1], sys.argv[2]
    os.makedirs(dst, exist_ok=True)
    for f in FILES:
        data, log = patch(open(os.path.join(src, f), "rb").read())
        out = RENAME.get(f, f)
        open(os.path.join(dst, out), "wb").write(data)
        print(f"{f} -> {out}: " + "; ".join(log))

if __name__ == "__main__":
    main()
