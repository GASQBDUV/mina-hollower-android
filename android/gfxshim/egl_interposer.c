/*
 * egl_interposer.c - libEGL.so.1 stand-in for libgothic_patches.so on Android.
 *
 * Built with SONAME libEGL.so.1 (libgothic has DT_NEEDED libEGL.so.1) and with
 * DT_NEEDED libEGL.so (Android's real EGL). Because ELF symbol lookup walks
 * libgothic's dependency tree breadth-first, this library is searched before
 * the real libEGL.so, so the few functions defined here override it while every
 * other egl* symbol that libgothic imports (eglChooseConfig, eglCreateContext,
 * eglMakeCurrent, eglSwapBuffers, ...) falls through to the system library.
 *
 * Why it exists:
 *   - Android's eglGetDisplay() only accepts EGL_DEFAULT_DISPLAY (index 0);
 *     any other value yields EGL_NO_DISPLAY. libgothic passes whatever the
 *     window system handed it (X11 Display* / wl_display*) straight through,
 *     see gothic_egl_ensure @0xc8b8. Only needed for the "wayland" fake of
 *     sdl_shim.c (the "x11" fake passes display=NULL and does not need it),
 *     but harmless otherwise.
 *   - libgothic does NOT use eglGetPlatformDisplay[EXT] or
 *     eglCreatePlatformWindowSurface[EXT] (its imports are the plain
 *     eglGetDisplay / eglCreateWindowSurface). The platform entry points are
 *     provided here only so that other clients in the process get sane
 *     behaviour: native handle == ANativeWindow*.
 */
#define _GNU_SOURCE
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>

#define EXPORT __attribute__((visibility("default")))

static int dbg(void)
{
	static int v = -1;
	if (v < 0) { const char *e = getenv("MINA_GFX_DEBUG"); v = (e && *e && *e != '0'); }
	return v;
}
#define LOG(...) do { if (dbg()) { fprintf(stderr, "gfxshim_egl: " __VA_ARGS__); fputc('\n', stderr); } } while (0)

static void *real_egl(void)
{
	static void *h;
	if (!h) {
		h = dlopen("libEGL.so", RTLD_NOW | RTLD_NOLOAD);
		if (!h) h = dlopen("libEGL.so", RTLD_NOW);
		if (!h) fprintf(stderr, "gfxshim_egl: cannot open system libEGL.so: %s\n", dlerror());
	}
	return h;
}
#define REAL(ptr, name) do { if (!(ptr)) (ptr) = (__typeof__(ptr))dlsym(real_egl(), name); } while (0)

EXPORT EGLDisplay eglGetDisplay(EGLNativeDisplayType native)
{
	static EGLDisplay (*real)(EGLNativeDisplayType);
	REAL(real, "eglGetDisplay");
	if (!real) return EGL_NO_DISPLAY;
	if (native != EGL_DEFAULT_DISPLAY)
		LOG("eglGetDisplay(%p) -> EGL_DEFAULT_DISPLAY", (void *)native);
	return real(EGL_DEFAULT_DISPLAY);
}

EXPORT EGLDisplay eglGetPlatformDisplay(EGLenum platform, void *native, const EGLAttrib *attribs)
{
	static EGLDisplay (*real)(EGLenum, void *, const EGLAttrib *);
	static EGLDisplay (*real_legacy)(EGLNativeDisplayType);
	(void)attribs;
	REAL(real, "eglGetPlatformDisplay");
	LOG("eglGetPlatformDisplay(0x%x, %p) -> default display", platform, native);
	if (real) {
		/* Android's libEGL accepts EGL_PLATFORM_ANDROID_KHR (0x3141) with
		 * EGL_DEFAULT_DISPLAY; anything else is mapped to it. */
		EGLDisplay d = real(0x3141 /* EGL_PLATFORM_ANDROID_KHR */, EGL_DEFAULT_DISPLAY, NULL);
		if (d != EGL_NO_DISPLAY) return d;
	}
	REAL(real_legacy, "eglGetDisplay");
	return real_legacy ? real_legacy(EGL_DEFAULT_DISPLAY) : EGL_NO_DISPLAY;
}

EXPORT EGLDisplay eglGetPlatformDisplayEXT(EGLenum platform, void *native, const EGLint *attribs)
{
	(void)attribs;
	return eglGetPlatformDisplay(platform, native, NULL);
}

EXPORT EGLSurface eglCreatePlatformWindowSurface(EGLDisplay dpy, EGLConfig cfg, void *native_window,
                                                 const EGLAttrib *attribs)
{
	static EGLSurface (*real)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint *);
	REAL(real, "eglCreateWindowSurface");
	if (!real) return EGL_NO_SURFACE;
	/* EGLAttrib list (intptr_t) -> EGLint list. Window surfaces take few attribs. */
	EGLint ai[32];
	int n = 0;
	if (attribs) {
		for (; attribs[n] != EGL_NONE && n < 28; n += 2) {
			ai[n] = (EGLint)attribs[n];
			ai[n + 1] = (EGLint)attribs[n + 1];
		}
	}
	ai[n] = EGL_NONE;
	LOG("eglCreatePlatformWindowSurface(window=%p)", native_window);
	return real(dpy, cfg, (EGLNativeWindowType)native_window, ai);
}

EXPORT EGLSurface eglCreatePlatformWindowSurfaceEXT(EGLDisplay dpy, EGLConfig cfg, void *native_window,
                                                    const EGLint *attribs)
{
	static EGLSurface (*real)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint *);
	REAL(real, "eglCreateWindowSurface");
	if (!real) return EGL_NO_SURFACE;
	LOG("eglCreatePlatformWindowSurfaceEXT(window=%p)", native_window);
	return real(dpy, cfg, (EGLNativeWindowType)native_window, attribs);
}

/* Pass-through with a diagnostic: this is the call libgothic makes
 * (gothic_egl_ensure @0xc988, attrib list NULL). */
EXPORT EGLSurface eglCreateWindowSurface(EGLDisplay dpy, EGLConfig cfg, EGLNativeWindowType win,
                                         const EGLint *attribs)
{
	static EGLSurface (*real)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint *);
	REAL(real, "eglCreateWindowSurface");
	if (!real) return EGL_NO_SURFACE;
	EGLSurface s = real(dpy, cfg, win, attribs);
	LOG("eglCreateWindowSurface(window=%p) -> %p (eglGetError=0x%x)", (void *)win, s,
	    s == EGL_NO_SURFACE ? eglGetError() : EGL_SUCCESS);
	return s;
}
