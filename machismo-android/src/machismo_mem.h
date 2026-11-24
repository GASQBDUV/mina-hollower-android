/*
 * machismo_mem.h - page-size-independent anonymous segment loading.
 *
 * Mach-O arm64 segments are 16 KB aligned (vmaddr/vmsize multiples of 0x4000).
 * Linux/Android kernels use 4 KB or 16 KB pages, so a 16 KB-aligned region is
 * always page aligned in both cases. We therefore never hardcode PAGE_SIZE;
 * the real page size comes from sysconf(_SC_PAGESIZE).
 *
 * Segments are NOT file-mapped: the range is reserved PROT_NONE/anonymous, then
 * the segment bytes are pread() into it. This avoids file-backed executable
 * mappings that get patched later (LSE/RCPC patches, trampolines). On Android
 * (SELinux) a private file mapping that is written and then made executable
 * triggers the "execmod" check, which untrusted_app domains normally lack;
 * anonymous memory needs "execmem", which untrusted_app_27 (targetSdk <= 28)
 * is allowed.
 */
#ifndef MACHISMO_MEM_H
#define MACHISMO_MEM_H

#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <sys/mman.h>

#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif

/* Apple arm64 page size (segment alignment granule). */
#define MACHISMO_MACHO_PAGE 0x4000UL

static inline size_t machismo_page_size(void)
{
	static size_t ps;
	if (!ps) {
		long v = sysconf(_SC_PAGESIZE);
		ps = v > 0 ? (size_t)v : 4096;
	}
	return ps;
}

static inline size_t machismo_round_up(size_t x, size_t a)
{
	return (x + a - 1) & ~(a - 1);
}

/*
 * Reserve `size` bytes of PROT_NONE anonymous memory, preferably at `hint`,
 * and guarantee the returned address is aligned to `align` (16 KB for Mach-O,
 * so Apple code sees 16 KB-aligned images on 4 KB kernels as well).
 *
 * 1. If hint != 0: try MAP_FIXED_NOREPLACE at hint. Never clobbers an existing
 *    mapping (ART heap, other libs). Old kernels (<4.17) treat the flag as a
 *    plain hint, so the result is checked.
 * 2. Otherwise over-reserve by `align` and trim head/tail.
 * Returns MAP_FAILED on error.
 */
static inline void* machismo_reserve(uintptr_t hint, size_t size, size_t align)
{
	size_t ps = machismo_page_size();
	if (align < ps) align = ps;
	size = machismo_round_up(size, ps);

	if (hint && (hint & (align - 1)) == 0) {
		void* p = mmap((void*)hint, size, PROT_NONE,
		               MAP_ANONYMOUS | MAP_PRIVATE | MAP_FIXED_NOREPLACE, -1, 0);
		if (p == (void*)hint)
			return p;
		if (p != MAP_FAILED)
			munmap(p, size);   /* kernel ignored the flag and moved it */
	}

	size_t over = size + align;
	uint8_t* raw = (uint8_t*)mmap(NULL, over, PROT_NONE,
	                              MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);
	if (raw == MAP_FAILED)
		return MAP_FAILED;
	uint8_t* aligned = (uint8_t*)(((uintptr_t)raw + align - 1) & ~(uintptr_t)(align - 1));
	if (aligned > raw)
		munmap(raw, (size_t)(aligned - raw));
	uint8_t* tail = aligned + size;
	uint8_t* end = raw + over;
	if (end > tail)
		munmap(tail, (size_t)(end - tail));
	return aligned;
}

/*
 * Load one Mach-O segment into an already-reserved range:
 *   - make [addr, addr+vmsize) RW (anonymous, already zero-filled),
 *   - pread `filesize` bytes from file offset `foff`,
 *   - set the final protection.
 * Returns 0 on success, -1 (errno set) on failure.
 * The tail (vmsize > filesize, e.g. __bss in __DATA) stays zero.
 */
static inline int machismo_load_segment(int fd, uint64_t foff, uint64_t filesize,
                                        uintptr_t addr, uint64_t vmsize, int final_prot)
{
	size_t ps = machismo_page_size();
	if (ps > MACHISMO_MACHO_PAGE) {
		errno = ENOTSUP;     /* 64 KB-page kernels cannot host 16 KB-aligned segments */
		return -1;
	}
	size_t len = machismo_round_up((size_t)vmsize, ps);
	if (len == 0)
		return 0;
	if (mprotect((void*)addr, len, PROT_READ | PROT_WRITE) != 0)
		return -1;

	uint8_t* dst = (uint8_t*)addr;
	uint64_t done = 0;
	while (done < filesize) {
		ssize_t n = pread(fd, dst + done, (size_t)(filesize - done), (off_t)(foff + done));
		if (n < 0) {
			if (errno == EINTR) continue;
			return -1;
		}
		if (n == 0) {         /* truncated file */
			errno = EIO;
			return -1;
		}
		done += (uint64_t)n;
	}

	if (final_prot != (PROT_READ | PROT_WRITE) && mprotect((void*)addr, len, final_prot) != 0)
		return -1;
	return 0;
}

#endif /* MACHISMO_MEM_H */
