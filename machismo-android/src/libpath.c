/*
 * libpath.c - configurable native library lookup (see libpath.h).
 */
#include "libpath.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <fcntl.h>
#include <errno.h>
#include <unistd.h>

#define MAX_ALIASES 48

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static char* g_dirs;
static struct { char* from; char* to; } g_alias[MAX_ALIASES];
static int g_nalias;
static int g_defaults_done;

extern int machismo_verbose;

static void add_alias_locked(const char* from, const char* to)
{
	if (!from || !*from || !to) return;
	for (int i = 0; i < g_nalias; i++) {          /* replace same key */
		if (strcmp(g_alias[i].from, from) == 0) {
			free(g_alias[i].to);
			g_alias[i].to = strdup(to);
			return;
		}
	}
	if (g_nalias >= MAX_ALIASES) return;
	g_alias[g_nalias].from = strdup(from);
	g_alias[g_nalias].to = strdup(to);
	g_nalias++;
}

static void add_alias_spec_locked(const char* spec)   /* "from=to" */
{
	char* tmp = strdup(spec);
	char* eq = strchr(tmp, '=');
	if (eq) {
		*eq = '\0';
		add_alias_locked(tmp, eq + 1);
	}
	free(tmp);
}

static void ensure_defaults_locked(void)
{
	if (g_defaults_done) return;
	g_defaults_done = 1;
#ifdef __ANDROID__
	/* Pattern: the Linux soname used by upstream configs -> the unversioned
	 * name Android packages (jniLibs/<abi>/lib*.so) actually ship. */
	add_alias_locked("libSDL2-2.0.so.0", "libSDL2.so");
	add_alias_locked("libSDL2-2.0.so",   "libSDL2.so");
	add_alias_locked("libSDL3.so.0",     "libSDL3.so");
	add_alias_locked("libEGL.so.1",      "libEGL.so");
	add_alias_locked("libGLESv2.so.2",   "libGLESv2.so");
	add_alias_locked("libGLESv1_CM.so.1","libGLESv1_CM.so");
	add_alias_locked("libz.so.1",        "libz.so");
	add_alias_locked("libstdc++.so.6",   "libc++_shared.so");
	add_alias_locked("libc++.so.1",      "libc++_shared.so");
	/* glibc-only / not on Android: fail quietly. */
	add_alias_locked("libgcc_s.so.1",    "-");
	add_alias_locked("libwayland-egl.so.1", "-");
	add_alias_locked("libdrm.so.2",      "-");
	add_alias_locked("libvulkan.so.1",   "libvulkan.so");
#endif
}

void machismo_set_lib_dir(const char* dirs)
{
	pthread_mutex_lock(&g_lock);
	free(g_dirs);
	g_dirs = (dirs && *dirs) ? strdup(dirs) : NULL;
	pthread_mutex_unlock(&g_lock);
}

void machismo_add_lib_alias(const char* from, const char* to)
{
	pthread_mutex_lock(&g_lock);
	ensure_defaults_locked();
	add_alias_locked(from, to);
	pthread_mutex_unlock(&g_lock);
}

void machismo_libpath_configure(const char* lib_dir, const char* const* aliases, int n_aliases)
{
	pthread_mutex_lock(&g_lock);
	ensure_defaults_locked();
	if (lib_dir && *lib_dir) {
		free(g_dirs);
		g_dirs = strdup(lib_dir);
	}
	for (int i = 0; i < n_aliases; i++)
		if (aliases[i]) add_alias_spec_locked(aliases[i]);

	/* environment overrides config */
	const char* e = getenv("MACHISMO_LIB_DIR");
	if (e && *e) {
		free(g_dirs);
		g_dirs = strdup(e);
	}
	e = getenv("MACHISMO_LIB_ALIAS");
	if (e && *e) {
		char* tmp = strdup(e);
		char* save = NULL;
		for (char* t = strtok_r(tmp, ";", &save); t; t = strtok_r(NULL, ";", &save))
			add_alias_spec_locked(t);
		free(tmp);
	}
	if (machismo_verbose) fprintf(stderr, "libpath: dir=%s nalias=%d\n", g_dirs ? g_dirs : "(null)", g_nalias);
	pthread_mutex_unlock(&g_lock);
}

static const char* base_name(const char* p)
{
	const char* s = strrchr(p, '/');
	return s ? s + 1 : p;
}

/* alias lookup: exact string first, then basename. Returned pointer is stable
 * (table entries are only replaced, never freed, except on replace; callers
 * copy immediately under the lock). */
static int alias_lookup_locked(const char* name, char* out, size_t n)
{
	const char* b = base_name(name);
	for (int pass = 0; pass < 2; pass++) {
		const char* key = pass == 0 ? name : b;
		for (int i = g_nalias - 1; i >= 0; i--) {
			if (strcmp(g_alias[i].from, key) == 0) {
				snprintf(out, n, "%s", g_alias[i].to);
				return 1;
			}
		}
	}
	return 0;
}

static void* try_open(const char* path, int flags)
{
	void* h = dlopen(path, flags);
	if (machismo_verbose)
		fprintf(stderr, "libpath: dlopen('%s') -> %s\n", path, h ? "ok" : dlerror());
	if (!h && machismo_verbose && path[0] == '/') { int fd = open(path, O_RDONLY); fprintf(stderr, "libpath:   open() fd=%d errno=%d\n", fd, errno); if (fd >= 0) close(fd); }
	return h;
}

/* try <dir>/<basename(cand)> for every dir, then cand itself */
static void* open_candidate(const char* dirs, const char* cand, int flags)
{
	void* h;
	if (cand[0] != '/' && dirs) {
		char* d = strdup(dirs);
		char* save = NULL;
		for (char* t = strtok_r(d, ":", &save); t; t = strtok_r(NULL, ":", &save)) {
			char path[1024];
			snprintf(path, sizeof path, "%s/%s", t, base_name(cand));
			h = try_open(path, flags);
			if (h) { free(d); return h; }
		}
		free(d);
	}
	return try_open(cand, flags);
}

void* machismo_dlopen(const char* name, int flags)
{
	if (!name)
		return dlopen(NULL, flags);

	char mapped[1024];
	char* dirs = NULL;
	int have_alias;

	pthread_mutex_lock(&g_lock);
	ensure_defaults_locked();
	have_alias = alias_lookup_locked(name, mapped, sizeof mapped);
	if (g_dirs) dirs = strdup(g_dirs);
	pthread_mutex_unlock(&g_lock);

	void* h = NULL;
	if (have_alias && strcmp(mapped, "-") == 0) {
		if (machismo_verbose)
			fprintf(stderr, "libpath: '%s' is aliased to '-' (not available here)\n", name);
		free(dirs);
		return NULL;
	}
	if (have_alias && strcmp(mapped, name) != 0)
		h = open_candidate(dirs, mapped, flags);
	if (!h)
		h = open_candidate(dirs, name, flags);

	if (!h) {
		/* "libfoo.so.1.2" -> "libfoo.so" */
		char stripped[1024];
		snprintf(stripped, sizeof stripped, "%s", base_name(name));
		char* so = strstr(stripped, ".so.");
		if (so) {
			so[3] = '\0';
			h = open_candidate(dirs, stripped, flags);
		}
	}
	free(dirs);
	return h;
}
