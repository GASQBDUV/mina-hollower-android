/* loadtest: dlopen the patched glibc chain in order, RTLD_NOW, report dlerror().
 * usage: loadtest [dir]   (default: $LIBDIR, else directory of the executable)
 * On failure dlerror() names the first missing symbol/library; the loop continues so that
 * further failures are visible too (later libs will usually fail as a consequence). */
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <libgen.h>
#include <limits.h>

/* Names as produced by patch_libs.py (APK-safe: lib*.so, no version suffix). Order matters. */
static const char *chain[] = {
    "libglibc_compat.so", "libgcc_sg.so", "libstdcxxg.so", "libcxxabig.so",
    "libatomicg.so", "libcxxg.so", "libgothic_patches.so", NULL };

int main(int argc, char **argv) {
    char dir[PATH_MAX];
    if (argc > 1) snprintf(dir, sizeof dir, "%s", argv[1]);
    else if (getenv("LIBDIR")) snprintf(dir, sizeof dir, "%s", getenv("LIBDIR"));
    else { char exe[PATH_MAX]; ssize_t n = readlink("/proc/self/exe", exe, sizeof exe - 1);
           if (n < 0) strcpy(exe, "."); else exe[n] = 0; snprintf(dir, sizeof dir, "%s", dirname(exe)); }
    int fails = 0;
    for (int i = 0; chain[i]; i++) {
        char path[PATH_MAX];
        snprintf(path, sizeof path, "%s/%s", dir, chain[i]);
        dlerror();
        void *h = dlopen(path, RTLD_NOW | (i == 0 ? RTLD_GLOBAL : RTLD_LOCAL));
        if (!h) { const char *e = dlerror();
            printf("FAIL  %-22s : %s\n", chain[i], e ? e : "(no dlerror)"); fails++; }
        else printf("OK    %-22s handle=%p\n", chain[i], h);
        fflush(stdout);
    }
    void *c = dlopen("libglc.so", RTLD_NOW | RTLD_NOLOAD);
    if (c) {
        void *b = dlsym(c, "__ctype_b_loc");
        if (b) { const unsigned short **t = ((const unsigned short **(*)(void))b)();
            printf("ctype_b['A']=0x%04x (expect 0x%04x)\n", (*t)['A'], 0x0100|0x0400|0x1000|0x4000|0x8000|0x0008); }
    }
    printf("%s (%d failures)\n", fails ? "LOAD FAILED" : "ALL LOADED", fails);
    return fails ? 1 : 0;
}
