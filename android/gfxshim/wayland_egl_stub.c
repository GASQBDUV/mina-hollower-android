/*
 * wayland_egl_stub.c - libwayland-egl.so.1 stand-in for Android.
 *
 * libgothic (Wayland branch, gothic_egl_ensure @0xc864..0xc888 and 0xc9ec..0xca2c)
 * does   dlopen("libwayland-egl.so.1", RTLD_LAZY)  and
 *        dlsym("wl_egl_window_create")  then  win = wl_egl_window_create(wl_surface, w, h)
 * and passes `win` as the native window to eglCreateWindowSurface().
 * On Android the EGL native window type is ANativeWindow*, and sdl_shim.c
 * (MINA_GFX_MODE=wayland) supplies the ANativeWindow* in place of the
 * wl_surface*. So the "wl_egl_window" IS the ANativeWindow*.
 *
 * Only used by the "wayland" fake. Build SONAME: libwayland-egl.so.1.
 */
#include <android/native_window.h>
#include <stddef.h>

#define EXPORT __attribute__((visibility("default")))

EXPORT void *wl_egl_window_create(void *surface, int width, int height)
{
	(void)width; (void)height;
	return surface; /* ANativeWindow*; NULL surface -> NULL (libgothic reports failure) */
}

EXPORT void wl_egl_window_destroy(void *win) { (void)win; }

EXPORT void wl_egl_window_resize(void *win, int width, int height, int dx, int dy)
{
	(void)win; (void)width; (void)height; (void)dx; (void)dy; /* size follows the Surface */
}

EXPORT void wl_egl_window_get_attached_size(void *win, int *width, int *height)
{
	ANativeWindow *w = (ANativeWindow *)win;
	if (width) *width = w ? ANativeWindow_getWidth(w) : 0;
	if (height) *height = w ? ANativeWindow_getHeight(w) : 0;
}
