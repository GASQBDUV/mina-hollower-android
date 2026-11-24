/*
 * sdl_shim.c - tiny SDL2 interposer for libgothic_patches.so on Android.
 *
 * Built as libgfxshim_sdl.so. Must be dlopen'ed RTLD_GLOBAL BEFORE libgothic
 * is loaded (or be a DT_NEEDED of the machismo host), so that
 *     dlsym(RTLD_DEFAULT, "SDL_GetWindowWMInfo")
 * performed by libgothic (gothic_egl_ensure @0xc5e0, resolver block @0xc734)
 * lands here first. See ANALYSIS.md section 5.
 *
 * What it does:
 *   - SDL_GetWindowWMInfo: calls the real SDL. If the real SDL reports
 *     SDL_SYSWM_ANDROID, the answer is rewritten according to MINA_GFX_MODE:
 *       "sdlgl"   (default) pass through unchanged. libgothic then takes its
 *                 "SDL owns the GL context" branch (the KMSDRM branch) and uses
 *                 SDL_GL_CreateContext / SDL_GL_SwapWindow. No faking needed.
 *       "x11"     subsystem=X11, display=NULL (-> eglGetDisplay(EGL_DEFAULT_DISPLAY)),
 *                 window=(Window)ANativeWindow*. libgothic then runs its own
 *                 EGL: eglGetDisplay/eglChooseConfig/eglCreateContext/
 *                 eglCreateWindowSurface(dpy, cfg, ANativeWindow*, NULL).
 *                 Window must NOT have been created with SDL_WINDOW_OPENGL.
 *       "wayland" subsystem=WAYLAND, display=<non-NULL sentinel>,
 *                 surface=ANativeWindow*. Needs libgfxshim_egl.so (maps the
 *                 sentinel display to EGL_DEFAULT_DISPLAY) and
 *                 libgfxshim_wlegl.so (wl_egl_window_create returns the
 *                 ANativeWindow*).
 *   - Plain forwarders for the other SDL entry points libgothic resolves with
 *     dlsym(RTLD_DEFAULT, ...). They only matter when the real libSDL2.so is
 *     not visible in the global symbol scope (typical for a lib loaded by
 *     System.loadLibrary). Define GFXSHIM_NO_FORWARDERS to leave them out.
 *
 * Layout of SDL_SysWMinfo (SDL2 2.x, LP64), as libgothic uses it
 * (memset 0x118 bytes, SDL_GetVersion(info) writes the 3 version bytes,
 *  subsystem read at +4, union fields at +8/+16 - disassembly @0xc640-0xc66c,
 *  0xc6ac, 0xc868):
 *     +0  uint8 major, minor, patch, pad
 *     +4  int32 subsystem  (SDL_SYSWM_TYPE: 2=X11 6=WAYLAND 9=ANDROID 13=KMSDRM)
 *     +8  union { x11{void* display; unsigned long window;}
 *                 wl {void* display; void* surface; ...}
 *                 android{void* window (ANativeWindow*); void* surface (EGLSurface);} }
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define EXPORT __attribute__((visibility("default")))

#define SYSWM_X11 2
#define SYSWM_WAYLAND 6
#define SYSWM_ANDROID 9

typedef struct {
	uint8_t major, minor, patch, pad;
	int32_t subsystem;
	union {
		struct { void *display; unsigned long window; } x11;
		struct { void *display; void *surface; void *shell_surface; void *xdg_surface;
		         void *xdg_toplevel; void *xdg_popup; void *xdg_positioner; } wl;
		struct { void *window; void *surface; } android;
		uint8_t dummy[64];
	} u;
} wminfo_t;

_Static_assert(__builtin_offsetof(wminfo_t, subsystem) == 4, "SDL_SysWMinfo.subsystem offset");
_Static_assert(__builtin_offsetof(wminfo_t, u) == 8, "SDL_SysWMinfo.info offset");

static int dbg(void)
{
	static int v = -1;
	if (v < 0) { const char *e = getenv("MINA_GFX_DEBUG"); v = (e && *e && *e != '0'); }
	return v;
}
#define LOG(...) do { if (dbg()) { fprintf(stderr, "gfxshim_sdl: " __VA_ARGS__); fputc('\n', stderr); } } while (0)

/* Locate the real SDL2. Prefer a handle that is already loaded. */
static void *real_sdl(void)
{
	static void *h;
	if (h) return h;
	static const char *names[] = { "libSDL2.so", "libSDL2-2.0.so", "libSDL2-2.0.so.0", NULL };
	for (int i = 0; names[i] && !h; i++) h = dlopen(names[i], RTLD_NOW | RTLD_NOLOAD);
	for (int i = 0; names[i] && !h; i++) h = dlopen(names[i], RTLD_NOW | RTLD_GLOBAL);
	if (!h) fprintf(stderr, "gfxshim_sdl: real libSDL2 not found: %s\n", dlerror());
	return h;
}

static void *real_sym(const char *name, void *self)
{
	void *h = real_sdl();
	void *p = h ? dlsym(h, name) : NULL;
	if (p == self) p = NULL; /* never recurse into ourselves */
	return p;
}

enum { MODE_SDLGL, MODE_X11, MODE_WAYLAND };
static int gfx_mode(void)
{
	static int m = -1;
	if (m < 0) {
		const char *e = getenv("MINA_GFX_MODE");
		m = MODE_SDLGL;
		if (e && !strcmp(e, "x11")) m = MODE_X11;
		else if (e && !strcmp(e, "wayland")) m = MODE_WAYLAND;
		LOG("MINA_GFX_MODE=%s -> mode %d", e ? e : "(unset)", m);
	}
	return m;
}

/* Non-NULL, never dereferenced (libgothic only passes it to eglGetDisplay,
 * which libgfxshim_egl.so maps to EGL_DEFAULT_DISPLAY). Exported so the EGL
 * interposer can recognise it. */
EXPORT char gfxshim_fake_wl_display[16];

EXPORT int SDL_GetWindowWMInfo(void *window, void *info)
{
	static int (*real)(void *, void *);
	if (!real) real = (int (*)(void *, void *))real_sym("SDL_GetWindowWMInfo", (void *)SDL_GetWindowWMInfo);
	if (!real) return 0;
	int r = real(window, info);
	if (!r || !info) return r;

	wminfo_t *w = (wminfo_t *)info;
	int mode = gfx_mode();
	LOG("real SDL_GetWindowWMInfo: subsystem=%d window=%p", w->subsystem, w->u.android.window);
	if (w->subsystem != SYSWM_ANDROID || mode == MODE_SDLGL)
		return r;

	void *anw = w->u.android.window;
	if (!anw) {
		fprintf(stderr, "gfxshim_sdl: ANativeWindow is NULL (surface not created yet / destroyed)\n");
		return 0; /* libgothic prints 'SDL_GetWindowWMInfo failed' and retries later */
	}
	/* Zero the union, keep version bytes. */
	memset(&w->u, 0, sizeof w->u);
	if (mode == MODE_X11) {
		w->subsystem = SYSWM_X11;
		w->u.x11.display = NULL;                       /* -> EGL_DEFAULT_DISPLAY */
		w->u.x11.window = (unsigned long)(uintptr_t)anw; /* "Window" = ANativeWindow* */
	} else {
		w->subsystem = SYSWM_WAYLAND;
		w->u.wl.display = gfxshim_fake_wl_display;
		w->u.wl.surface = anw;                          /* wl_surface* = ANativeWindow* */
	}
	LOG("rewritten to subsystem=%d anw=%p", w->subsystem, anw);
	return r;
}

#ifndef GFXSHIM_NO_FORWARDERS
/* SDL_Window* / SDL_bool / SDL_GLContext are all pointer or int sized. */
#define FWD_I(name, params, args) \
	EXPORT int name params { \
		static int (*f) params; \
		if (!f) f = (int (*) params)real_sym(#name, (void *)name); \
		return f ? f args : -1; }
#define FWD_V(name, params, args) \
	EXPORT void name params { \
		static void (*f) params; \
		if (!f) f = (void (*) params)real_sym(#name, (void *)name); \
		if (f) f args; }
#define FWD_P(name, params, args) \
	EXPORT void *name params { \
		static void *(*f) params; \
		if (!f) f = (void *(*) params)real_sym(#name, (void *)name); \
		return f ? f args : NULL; }

FWD_V(SDL_GetVersion, (void *ver), (ver))
FWD_V(SDL_GetWindowSize, (void *win, int *w, int *h), (win, w, h))
FWD_P(SDL_GL_CreateContext, (void *win), (win))
FWD_V(SDL_GL_SwapWindow, (void *win), (win))
FWD_I(SDL_GL_SetSwapInterval, (int interval), (interval))
FWD_I(SDL_GL_SetAttribute, (int attr, int value), (attr, value))
FWD_I(SDL_GL_GetAttribute, (int attr, int *value), (attr, value))
FWD_V(SDL_GL_GetDrawableSize, (void *win, int *w, int *h), (win, w, h))
FWD_V(SDL_Vulkan_GetDrawableSize, (void *win, int *w, int *h), (win, w, h))
FWD_I(SDL_Vulkan_GetInstanceExtensions, (void *win, unsigned *count, const char **names), (win, count, names))
FWD_I(SDL_Vulkan_CreateSurface, (void *win, void *instance, void *surface_out), (win, instance, surface_out))
EXPORT const char *SDL_GetError(void)
{
	static const char *(*f)(void);
	if (!f) f = (const char *(*)(void))real_sym("SDL_GetError", (void *)SDL_GetError);
	return f ? f() : "(libSDL2 not found)";
}
#endif
