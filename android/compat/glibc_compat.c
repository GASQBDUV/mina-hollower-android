/*
 * glibc_compat.c - minimal glibc -> bionic compatibility layer (aarch64, API 29+).
 *
 * Built to libglibc_compat.so.  Prebuilt glibc-aarch64 shared libraries
 * (libstdc++.so.6, libgcc_s.so.1, libc++.so.1, libc++abi.so.1, libatomic.so.1,
 * libgothic_patches.so) have their DT_NEEDED for libc.so.6/libpthread.so.0/
 * librt.so.1/ld-linux-aarch64.so.1 redirected to this library by patch_libs.py,
 * so every glibc-only symbol they reference is found here (and everything else
 * is reached through this library's own NEEDED: libc.so/libm.so/libdl.so).
 *
 * HARD dependencies on bionic: __errno, __assert2, getauxval, newlocale family
 * (*_l), sigsetjmp, stat family, strto*, sscanf family, wmemcpy, mbs*rtowcs.
 * All of them exist in bionic since API 21..28, so API 29 is safe.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <ctype.h>
#include <locale.h>
#include <langinfo.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/stat.h>
#include <time.h>
#include <wchar.h>
#include <wctype.h>
#include <inttypes.h>

/* bionic-internal but exported (assert.h only declares it under __BIONIC__ macros in some configs) */
extern void __assert2(const char *, int, const char *, const char *) __attribute__((noreturn));
#define EXPORT __attribute__((visibility("default"), used))
#define WEAK   __attribute__((weak))

/* ------------------------------------------------------------------ errno / assert / auxv */
EXPORT int *__errno_location(void) { return __errno(); }

EXPORT __attribute__((noreturn)) void __assert_fail(const char *assertion, const char *file,
                                                    unsigned int line, const char *function) {
    __assert2(file, (int)line, function ? function : "?", assertion);
    __builtin_unreachable();
}
EXPORT unsigned long __getauxval(unsigned long type) { return getauxval(type); }

/* ------------------------------------------------------------------ stat family */
/* glibc <2.33 inline wrappers: __xstat(ver, path, buf). aarch64 glibc and bionic use the same
 * kernel generic struct stat layout, so the buffer can be passed through unchanged. */
EXPORT int __xstat(int ver, const char *path, struct stat *buf)   { (void)ver; return stat(path, buf); }
EXPORT int __lxstat(int ver, const char *path, struct stat *buf)  { (void)ver; return lstat(path, buf); }
EXPORT int __fxstat(int ver, int fd, struct stat *buf)            { (void)ver; return fstat(fd, buf); }
EXPORT int __fxstatat(int ver, int dirfd, const char *path, struct stat *buf, int flags) {
    (void)ver; return fstatat(dirfd, path, buf, flags);
}
EXPORT int __xstat64(int ver, const char *path, struct stat *buf)  { (void)ver; return stat(path, buf); }
EXPORT int __lxstat64(int ver, const char *path, struct stat *buf) { (void)ver; return lstat(path, buf); }
EXPORT int __fxstat64(int ver, int fd, struct stat *buf)           { (void)ver; return fstat(fd, buf); }

/* ------------------------------------------------------------------ ctype tables (glibc layout) */
/* glibc: const unsigned short **__ctype_b_loc(void); table indexable from -128..255,
 * bit masks are byte-order dependent: (bit<8 ? (1<<bit)<<8 : (1<<bit)>>8) on little endian. */
#define CB(bit) ((unsigned short)((bit) < 8 ? ((1u << (bit)) << 8) : ((1u << (bit)) >> 8)))
enum { B_UPPER = 0, B_LOWER, B_ALPHA, B_DIGIT, B_XDIGIT, B_SPACE, B_PRINT, B_GRAPH,
       B_BLANK, B_CNTRL, B_PUNCT, B_ALNUM };

static unsigned short g_b_store[384];
static int32_t g_lo_store[384], g_up_store[384];
static const unsigned short *g_b_ptr;
static const int32_t *g_lo_ptr, *g_up_ptr;
static pthread_once_t g_ctype_once = PTHREAD_ONCE_INIT;

static void ctype_init(void) {
    for (int c = -128; c < 256; c++) {
        unsigned short m = 0;
        int ch = c;                       /* C locale: only ASCII classified */
        int idx = c + 128;
        if (c >= 0 && c < 128) {
            if (isupper(ch))  m |= CB(B_UPPER);
            if (islower(ch))  m |= CB(B_LOWER);
            if (isalpha(ch))  m |= CB(B_ALPHA);
            if (isdigit(ch))  m |= CB(B_DIGIT);
            if (isxdigit(ch)) m |= CB(B_XDIGIT);
            if (isspace(ch))  m |= CB(B_SPACE);
            if (isprint(ch))  m |= CB(B_PRINT);
            if (isgraph(ch))  m |= CB(B_GRAPH);
            if (isblank(ch))  m |= CB(B_BLANK);
            if (iscntrl(ch))  m |= CB(B_CNTRL);
            if (ispunct(ch))  m |= CB(B_PUNCT);
            if (isalnum(ch))  m |= CB(B_ALNUM);
        }
        g_b_store[idx] = m;
        g_lo_store[idx] = (c >= 0 && c < 128) ? tolower(c) : c;
        g_up_store[idx] = (c >= 0 && c < 128) ? toupper(c) : c;
    }
    g_b_ptr = g_b_store + 128; g_lo_ptr = g_lo_store + 128; g_up_ptr = g_up_store + 128;
}
/* glibc returns the address of a per-thread pointer; a thread-local copy keeps that contract. */
EXPORT const unsigned short **__ctype_b_loc(void) {
    pthread_once(&g_ctype_once, ctype_init); static __thread const unsigned short *p; p = g_b_ptr; return &p; }
EXPORT const int32_t **__ctype_tolower_loc(void) {
    pthread_once(&g_ctype_once, ctype_init); static __thread const int32_t *p; p = g_lo_ptr; return &p; }
EXPORT const int32_t **__ctype_toupper_loc(void) {
    pthread_once(&g_ctype_once, ctype_init); static __thread const int32_t *p; p = g_up_ptr; return &p; }

/* ------------------------------------------------------------------ locale (*_l) */
/* glibc LC_*_MASK values are bit(1<<LC_x) with the same LC_x numbering for 0..5; glibc's
 * LC_ALL_MASK additionally has bits for PAPER/NAME/... which bionic rejects (EINVAL). */
#define BIONIC_MASK ((1 << LC_CTYPE) | (1 << LC_NUMERIC) | (1 << LC_TIME) | (1 << LC_COLLATE) | \
                     (1 << LC_MONETARY) | (1 << LC_MESSAGES))
EXPORT locale_t __newlocale(int mask, const char *name, locale_t base) {
    return newlocale(mask & BIONIC_MASK, name, base);
}
EXPORT locale_t __duplocale(locale_t l)    { return duplocale(l); }
EXPORT void     __freelocale(locale_t l)   { freelocale(l); }
EXPORT locale_t __uselocale(locale_t l)    { return uselocale(l); }

EXPORT double      __strtod_l(const char *s, char **e, locale_t l)  { return strtod_l(s, e, l); }
EXPORT float       __strtof_l(const char *s, char **e, locale_t l)  { return strtof_l(s, e, l); }
EXPORT long double __strtold_l(const char *s, char **e, locale_t l) { return strtold_l(s, e, l); }
EXPORT long        __strtol_l(const char *s, char **e, int b, locale_t l)  { return strtol_l(s, e, b, l); }
EXPORT unsigned long __strtoul_l(const char *s, char **e, int b, locale_t l) { return strtoul_l(s, e, b, l); }
EXPORT long long   __strtoll_l(const char *s, char **e, int b, locale_t l) { return strtoll_l(s, e, b, l); }
EXPORT unsigned long long __strtoull_l(const char *s, char **e, int b, locale_t l) { return strtoull_l(s, e, b, l); }
EXPORT int    __strcoll_l(const char *a, const char *b, locale_t l)            { return strcoll_l(a, b, l); }
EXPORT size_t __strxfrm_l(char *d, const char *s, size_t n, locale_t l)        { return strxfrm_l(d, s, n, l); }
EXPORT size_t __strftime_l(char *s, size_t m, const char *f, const struct tm *tm, locale_t l) { return strftime_l(s, m, f, tm, l); }
EXPORT int    __wcscoll_l(const wchar_t *a, const wchar_t *b, locale_t l)      { return wcscoll_l(a, b, l); }
EXPORT size_t __wcsxfrm_l(wchar_t *d, const wchar_t *s, size_t n, locale_t l)  { return wcsxfrm_l(d, s, n, l); }
EXPORT size_t __wcsftime_l(wchar_t *s, size_t m, const wchar_t *f, const struct tm *tm, locale_t l) {
    (void)l; return wcsftime(s, m, f, tm);   /* C locale only */
}
EXPORT wint_t __towlower_l(wint_t c, locale_t l)             { return towlower_l(c, l); }
EXPORT wint_t __towupper_l(wint_t c, locale_t l)             { return towupper_l(c, l); }
EXPORT int    __iswctype_l(wint_t c, wctype_t t, locale_t l) { return iswctype_l(c, t, l); }
EXPORT wctype_t __wctype_l(const char *n, locale_t l)        { return wctype_l(n, l); }
EXPORT char  *__nl_langinfo_l(nl_item i, locale_t l)         { return nl_langinfo_l(i, l); }

/* ------------------------------------------------------------------ C23 / C99 scanf+strto* aliases */
#define STRTO(name, ret, impl) \
    EXPORT ret __isoc23_##name(const char *s, char **e, int b) { return impl(s, e, b); }
STRTO(strtol, long, strtol) STRTO(strtoul, unsigned long, strtoul)
STRTO(strtoll, long long, strtoll) STRTO(strtoull, unsigned long long, strtoull)
STRTO(strtoimax, intmax_t, strtoimax) STRTO(strtoumax, uintmax_t, strtoumax)

#define SCANF_FAMILY(prefix)                                                                       \
    EXPORT int prefix##sscanf(const char *s, const char *f, ...) {                                 \
        va_list ap; va_start(ap, f); int r = vsscanf(s, f, ap); va_end(ap); return r; }            \
    EXPORT int prefix##vsscanf(const char *s, const char *f, va_list ap) { return vsscanf(s, f, ap); } \
    EXPORT int prefix##fscanf(FILE *fp, const char *f, ...) {                                      \
        va_list ap; va_start(ap, f); int r = vfscanf(fp, f, ap); va_end(ap); return r; }           \
    EXPORT int prefix##vfscanf(FILE *fp, const char *f, va_list ap) { return vfscanf(fp, f, ap); } \
    EXPORT int prefix##scanf(const char *f, ...) {                                                 \
        va_list ap; va_start(ap, f); int r = vscanf(f, ap); va_end(ap); return r; }                \
    EXPORT int prefix##vscanf(const char *f, va_list ap) { return vscanf(f, ap); }
SCANF_FAMILY(__isoc99_)
SCANF_FAMILY(__isoc23_)

/* ------------------------------------------------------------------ setjmp */
/* Must be a tail call (returns twice, frame must not be popped): plain branch to bionic. */
__asm__(".text\n.globl __sigsetjmp\n.type __sigsetjmp,%function\n.p2align 2\n"
        "__sigsetjmp:\n\tb sigsetjmp\n.size __sigsetjmp,.-__sigsetjmp\n");

/* ------------------------------------------------------------------ pthread / misc */
EXPORT int __pthread_key_create(pthread_key_t *k, void (*d)(void *)) { return pthread_key_create(k, d); }
EXPORT int malloc_trim(size_t pad) { (void)pad; return 0; }
EXPORT char __libc_single_threaded = 0;     /* 0 = "may be multi-threaded": libstdc++ uses atomics */
EXPORT char *secure_getenv(const char *n) { return getenv(n); }

/* libgcc_s: returning -1 makes _Unwind_Find_FDE fall back to dl_iterate_phdr (bionic has it). */
EXPORT int _dl_find_object(void *pc, void *result) { (void)pc; (void)result; return -1; }

/* gettext family: identity (no catalogs) */
EXPORT char *gettext(const char *m)                              { return (char *)m; }
EXPORT char *dgettext(const char *d, const char *m)              { (void)d; return (char *)m; }
EXPORT char *dcgettext(const char *d, const char *m, int c)      { (void)d; (void)c; return (char *)m; }
EXPORT char *ngettext(const char *a, const char *b, unsigned long n) { return (char *)(n == 1 ? a : b); }
EXPORT char *bindtextdomain(const char *d, const char *dir)      { (void)d; return (char *)dir; }
EXPORT char *textdomain(const char *d)                           { return (char *)d; }
EXPORT char *bind_textdomain_codeset(const char *d, const char *c) { (void)d; return (char *)c; }

/* ------------------------------------------------------------------ _FORTIFY *_chk (no checking) */
EXPORT int __fprintf_chk(FILE *fp, int flag, const char *f, ...) {
    (void)flag; va_list ap; va_start(ap, f); int r = vfprintf(fp, f, ap); va_end(ap); return r; }
EXPORT int __printf_chk(int flag, const char *f, ...) {
    (void)flag; va_list ap; va_start(ap, f); int r = vprintf(f, ap); va_end(ap); return r; }
EXPORT int __vfprintf_chk(FILE *fp, int flag, const char *f, va_list ap) { (void)flag; return vfprintf(fp, f, ap); }
EXPORT int __sprintf_chk(char *s, int flag, size_t sl, const char *f, ...) {
    (void)flag; (void)sl; va_list ap; va_start(ap, f); int r = vsprintf(s, f, ap); va_end(ap); return r; }
EXPORT int __snprintf_chk(char *s, size_t n, int flag, size_t sl, const char *f, ...) {
    (void)flag; (void)sl; va_list ap; va_start(ap, f); int r = vsnprintf(s, n, f, ap); va_end(ap); return r; }
EXPORT int __vsnprintf_chk(char *s, size_t n, int flag, size_t sl, const char *f, va_list ap) {
    (void)flag; (void)sl; return vsnprintf(s, n, f, ap); }
EXPORT size_t __mbsrtowcs_chk(wchar_t *d, const char **s, size_t n, mbstate_t *ps, size_t dl) {
    (void)dl; return mbsrtowcs(d, s, n, ps); }
EXPORT size_t __mbsnrtowcs_chk(wchar_t *d, const char **s, size_t nms, size_t n, mbstate_t *ps, size_t dl) {
    (void)dl; return mbsnrtowcs(d, s, nms, n, ps); }
EXPORT wchar_t *__wmemcpy_chk(wchar_t *d, const wchar_t *s, size_t n, size_t dl) { (void)dl; return wmemcpy(d, s, n); }
EXPORT wchar_t *__wmemset_chk(wchar_t *d, wchar_t c, size_t n, size_t dl)        { (void)dl; return wmemset(d, c, n); }

/* ------------------------------------------------------------------ weak no-ops / app glue */
/* Transactional-memory / gprof hooks are weak-undefined in the glibc libs (resolve to NULL anyway);
 * defining no-ops is harmless and makes the exports explicit. */
EXPORT void _ITM_registerTMCloneTable(void *a, size_t b)   { (void)a; (void)b; }
EXPORT void _ITM_deregisterTMCloneTable(void *a)           { (void)a; }
EXPORT void __gmon_start__(void)                           { }

/* libgothic_patches.so references mmap_registry_add (normally defined by libsystem_shim.so).
 * Weak no-op fallback so that dlopen(RTLD_NOW) succeeds without the shim; if the shim is loaded
 * RTLD_GLOBAL beforehand its strong definition wins.  Signature unknown -> no-op.            */
#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>
#include <android/log.h>
EXPORT void mmap_registry_add(void *addr, size_t size)
{
	static void (*fn)(void *, size_t); static int init;
	if (!init) { fn = (void (*)(void *, size_t))dlsym(RTLD_NEXT, "mmap_registry_add"); init = 1; }
	if (fn) fn(addr, size);
}

/* debug: log failing mmap calls made by the glibc libs (MINA_MMAP_DEBUG=1) */
EXPORT void *mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off)
{
	static void *(*real)(void *, size_t, int, int, int, off_t);
	if (!real) real = (void *(*)(void *, size_t, int, int, int, off_t))dlsym(RTLD_NEXT, "mmap");
	void *r = real(addr, len, prot, flags, fd, off);
	if (r == MAP_FAILED && getenv("MINA_MMAP_DEBUG"))
		__android_log_print(ANDROID_LOG_ERROR, "MinaMmap", "mmap(%p,%zu,prot=%d,flags=0x%x,fd=%d,off=%lld) failed errno=%d", addr, len, prot, flags, fd, (long long)off, errno);
	return r;
}

/* sysconf: glibc and bionic use different _SC_* numbers (glibc _SC_PAGESIZE=30, bionic 39 ...). */
EXPORT long sysconf(int name)
{
	int b;
	switch (name) {
	case 0:  b = _SC_ARG_MAX; break;
	case 1:  b = _SC_CHILD_MAX; break;
	case 2:  b = _SC_CLK_TCK; break;
	case 3:  b = _SC_NGROUPS_MAX; break;
	case 4:  b = _SC_OPEN_MAX; break;
	case 30: b = _SC_PAGESIZE; break;
	case 83: b = _SC_NPROCESSORS_CONF; break;
	case 84: b = _SC_NPROCESSORS_ONLN; break;
	case 85: b = _SC_PHYS_PAGES; break;
	case 86: b = _SC_AVPHYS_PAGES; break;
	case 29: b = _SC_PAGESIZE; break;   /* glibc _SC_PAGE_SIZE alias */
	default: errno = EINVAL; return -1;
	}
	static long (*real)(int);
	if (!real) real = (long (*)(int))dlsym(RTLD_NEXT, "sysconf");
	return real(b);
}
