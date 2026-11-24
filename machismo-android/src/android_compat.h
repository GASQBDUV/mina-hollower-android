/*
 * android_compat.h - bionic (Android) shims for glibc-only APIs.
 * Only active under __ANDROID__. Part of the Android port experiment.
 */
#ifndef MACHISMO_ANDROID_COMPAT_H
#define MACHISMO_ANDROID_COMPAT_H
#ifdef __ANDROID__
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/types.h>

/* bionic has no <endian.h> __bswap_32 */
#ifndef __bswap_32
#define __bswap_32(x) __builtin_bswap32(x)
#endif

/* bionic: no shm_open/shm_unlink (no /dev/shm). Emulate with plain files
 * in $MACHISMO_SHM_DIR, else $TMPDIR, else /data/local/tmp. App processes
 * should set MACHISMO_SHM_DIR to their cache dir (files dir is writable,
 * /data/local/tmp is not for untrusted_app). Unlinked-file semantics match
 * POSIX shm well enough (mmap MAP_SHARED works on app-private storage;
 * NOTE: not memfd/ashmem, so no PROT_EXEC concerns but real disk I/O). */
static inline int machismo_shm_path(const char *name, char *out, size_t n)
{
	const char *d = getenv("MACHISMO_SHM_DIR");
	if (!d) d = getenv("TMPDIR");
	if (!d) d = "/data/local/tmp";
	if (*name == '/') name++;
	int r = snprintf(out, n, "%s/shm.%s", d, name);
	return (r > 0 && (size_t)r < n) ? 0 : -1;
}
static inline int shm_open(const char *name, int oflag, mode_t mode)
{
	char p[512];
	if (machismo_shm_path(name, p, sizeof p)) { errno = ENAMETOOLONG; return -1; }
	return open(p, oflag | O_CLOEXEC, mode);
}
static inline int shm_unlink(const char *name)
{
	char p[512];
	if (machismo_shm_path(name, p, sizeof p)) { errno = ENAMETOOLONG; return -1; }
	return unlink(p);
}
#endif
#endif
