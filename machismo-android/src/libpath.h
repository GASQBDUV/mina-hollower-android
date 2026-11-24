/*
 * libpath.h - configurable lookup of native (Linux/Android) shared libraries.
 *
 * machismo dlopen()s native .so files named in dylib_map.conf / machismo.conf
 * (e.g. "libSDL2-2.0.so.0"). On Android those names do not exist (the app ships
 * "libSDL2.so" in its nativeLibraryDir) and relative paths are not searched.
 * machismo_dlopen() is a drop-in for dlopen() that applies, in order:
 *
 *   1. alias table   name -> replacement       (built-in Android defaults,
 *                    config "lib_alias = from=to", env MACHISMO_LIB_ALIAS)
 *   2. library dirs  <dir>/<basename>          (config "lib_dir = a:b",
 *                    env MACHISMO_LIB_DIR, or machismo_set_lib_dir())
 *   3. the name exactly as given               (original upstream behaviour)
 *   4. versioned-soname fallback "libfoo.so.N" -> "libfoo.so" (steps 2+3 again)
 *
 * An alias target of "-" means "this library does not exist here": the call
 * fails quietly (NULL) without trying anything.
 */
#ifndef MACHISMO_LIBPATH_H
#define MACHISMO_LIBPATH_H

#include <dlfcn.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Colon-separated list of directories searched first (e.g. the app's
 * nativeLibraryDir). NULL/"" clears it. Copied. */
void machismo_set_lib_dir(const char* dirs);

/* Add alias "from" -> "to". Later aliases override earlier ones. Copied. */
void machismo_add_lib_alias(const char* from, const char* to);

/* Apply the config-file values (either may be NULL/0), then the environment
 * (MACHISMO_LIB_DIR, MACHISMO_LIB_ALIAS="a=b;c=d"). Aliases are "from=to". */
void machismo_libpath_configure(const char* lib_dir, const char* const* aliases, int n_aliases);

/* dlopen() with the lookup rules above. */
void* machismo_dlopen(const char* name, int flags);

#ifdef __cplusplus
}
#endif

#endif /* MACHISMO_LIBPATH_H */
