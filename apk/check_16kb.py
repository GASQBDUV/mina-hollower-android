#!/usr/bin/env python3
"""16 KB page-size check for arm64 .so files (directory, .so, or APK).
Usage: check_16kb.py <apk | dir | file.so> ...
Per library: every PT_LOAD p_align must be >= 0x4000 (and p_offset % p_align == p_vaddr % p_align).
For APKs: .so entries stored uncompressed must start at a 16 KB aligned offset
(compressed/extractNativeLibs=true entries are extracted by the installer, so reported as INFO)."""
import struct, sys, zipfile, io, os

def check_elf(data):
    if data[:4] != b"\x7fELF" or data[4] != 2:
        return None, ["not ELF64"]
    phoff, = struct.unpack_from("<Q", data, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    problems, aligns = [], []
    for i in range(phnum):
        p_type, _fl, off, vaddr, _pa, _fs, _ms, align = struct.unpack_from("<IIQQQQQQ", data, phoff + i * phentsize)
        if p_type != 1:
            continue
        aligns.append(align)
        if align < 0x4000:
            problems.append("PT_LOAD p_align=0x%x < 0x4000" % align)
        elif align and (off % align) != (vaddr % align):
            problems.append("PT_LOAD offset/vaddr mismatch mod 0x%x" % align)
    return aligns, problems

def report(name, data, extra=()):
    aligns, probs = check_elf(data)
    probs = list(probs) + list(extra)
    print("%-5s %-40s %s" % ("FAIL" if probs else "PASS", name,
          ("aligns=" + ",".join("0x%x" % a for a in aligns or [])) + ("  " + "; ".join(probs) if probs else "")))
    return not probs

def main(args):
    ok = True
    for a in args:
        if os.path.isdir(a):
            for f in sorted(os.listdir(a)):
                if f.endswith(".so"):
                    ok &= report(f, open(os.path.join(a, f), "rb").read())
        elif zipfile.is_zipfile(a):
            raw = open(a, "rb").read()
            z = zipfile.ZipFile(a)
            for zi in z.infolist():
                if not zi.filename.endswith(".so"):
                    continue
                extra = []
                if zi.compress_type == 0:
                    # data offset = header offset + 30 + name + extra
                    nl, el = struct.unpack_from("<HH", raw, zi.header_offset + 26)
                    doff = zi.header_offset + 30 + nl + el
                    if doff % 0x4000:
                        extra.append("stored .so at zip offset 0x%x not 16KB aligned" % doff)
                else:
                    print("INFO  %s is compressed (extractNativeLibs=true: extracted at install, zip alignment n/a)" % zi.filename)
                ok &= report(zi.filename, z.read(zi), extra)
        else:
            ok &= report(os.path.basename(a), open(a, "rb").read())
    print("RESULT:", "ALL PASS" if ok else "SOME FAIL")
    return 0 if ok else 1

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]) if len(sys.argv) > 1 else print(__doc__) or 2)
