/*
 * machismo_lib.h - public entry points of libmachismo.so (embedding API).
 *
 * Typical Android use (JNI): start a native thread, call machismo_main() with
 * argv = { "machismo", "<path to arm64 Mach-O executable>", args... } after
 * setting MACHISMO_CONFIG, MACHISMO_LIB_DIR, MACHISMO_SHM_DIR (setenv), and
 * return the exit code to Java. Only one call per process is supported.
 */
#ifndef MACHISMO_LIB_H
#define MACHISMO_LIB_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MACHISMO_API __attribute__((visibility("default")))

/* Same behaviour as the CLI's main(): [-v] <mach-o> [args...]. argv[0] is the
 * program name (ignored apart from usage text). Returns the guest's exit code,
 * or a loader error code, instead of terminating the process. Runs the guest's
 * main() on the CALLING thread's stack. */
MACHISMO_API int machismo_main(int argc, char** argv);

/* Like machismo_main() but runs it on a new pthread with `stack_bytes` of stack
 * (0 = 64 MiB) and blocks until it returns. Java threads and most worker
 * threads have 1 MiB stacks, too small for a game's main(). */
MACHISMO_API int machismo_main_bigstack(int argc, char** argv, size_t stack_bytes);

#ifdef __cplusplus
}
#endif

#endif /* MACHISMO_LIB_H */
