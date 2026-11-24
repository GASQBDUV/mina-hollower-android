# libgothic window/EGL analysis (summary of agent report, 2026-11-24)
- libgothic has no SDL imports; it dlsym(RTLD_DEFAULT)s SDL_* in gothic_egl_ensure (0xc5e0) and gothic_vk_create_surface (0x1dff0).
- EGL/GLES: direct PLT imports (libEGL.so.1, libGLESv2.so.2); eglGetDisplay + eglCreateWindowSurface only. libwayland-egl.so.1 and libvulkan.so.1 are lazy dlopen.
- Platform choice on SDL_SysWMinfo.subsystem (+4): 6=Wayland, 2=X11, else (incl. Android 9) = "SDL owns GL" (SDL_GL_CreateContext/SwapWindow).
- Path A (recommended): no shim to libgothic; window needs SDL_WINDOW_OPENGL + GLES3 attrs (sdl_create_window_wrapper does this when sdl_window_is_kmsdrm_env() true), libSDL2.so global, GOTHIC_BACKEND=gles, [trampoline.sdl2] lib=libSDL2.so.
- Path B (fallback): sdl_shim.c rewrites WMInfo to X11 display=NULL window=ANativeWindow*; window must NOT have SDL_WINDOW_OPENGL.
- Vulkan: sdl_window_is_kmsdrm_env() must be false; libvulkan.so.1 name carrier required.
- Proposed machismo-android change: sdl_window_is_kmsdrm_env() on Android controlled by MINA_GFX_MODE (sdlgl default | x11 | wayland), false when Vulkan.
- Shims built in android/gfxshim/out (sdl_shim.c, egl_interposer.c, wayland_egl_stub.c). Untested.
