#include <fcntl.h>
/* libmain.so: SDL_main for the Mina port. Loads the glibc compat chain, libmachismo and runs the guest. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>
#include <sys/stat.h>
#include <pthread.h>
#include <jni.h>
#include <math.h>
#include <sys/types.h>

#define TAG "MinaPort"
#define LOG(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void* load(const char* dir, const char* name, int flags)
{
	char p[1024];
	snprintf(p, sizeof p, "%s/%s", dir, name);
	void* h = dlopen(p, flags);
	LOG("dlopen %s -> %s", name, h ? "OK" : dlerror());
	return h;
}

static void* logthread(void* arg)
{
	/* stderr -> logcat bridge */
	int fd = (int)(long)arg;
	char buf[2048]; FILE* f = fdopen(fd, "r");
	while (f && fgets(buf, sizeof buf, f)) { buf[strcspn(buf, "\n")] = 0; LOG("%s", buf); }
	return NULL;
}

static int is_str(const char* p){ int n=0; for(;n<64;n++){ unsigned char c=(unsigned char)p[n]; if(!c) return n>=2; if(c<32||c>126) return 0; } return 0; }
static void dump_rec(const char* name, unsigned long addr, int len)
{
	LOG("== %s @0x%lx", name, addr);
	unsigned long* q = (unsigned long*)addr;
	for (int i = 0; i < len / 8; i += 3) {
		unsigned long v = q[i];
		if (v > 0x700000000000UL && v < 0x800000000000UL) {
			unsigned long* d = (unsigned long*)v;
			const char* nm = (d[0] > 0x700000000000UL && d[0] < 0x800000000000UL && is_str((const char*)d[0])) ? (const char*)d[0] : "?";
			LOG("  field @%d name=\"%s\" len=%lu off=0x%lx type=%lx dflt=%lx", i * 8, nm, d[1], d[2], q[i + 1], q[i + 2]);
		} else LOG("  raw @%d %lx %lx %lx", i * 8, q[i], q[i + 1], q[i + 2]);
	}
}
/* scan all rw anonymous mappings for qword == vt; store up to max addresses, return count */
static int find_all(unsigned long vt, unsigned long* out, int max)
{
	unsigned long stk = (unsigned long)__builtin_frame_address(0);
	FILE* f = fopen("/proc/self/maps", "r");
	int mem = open("/proc/self/mem", O_RDONLY);
	char line[512]; int found = 0;
	unsigned long* buf = (unsigned long*)malloc(1 << 19);
	if (!buf) { if (f) fclose(f); if (mem >= 0) close(mem); return 0; }
	const size_t bsz = 1 << 19;
	while (f && mem >= 0 && fgets(line, sizeof line, f)) {
		unsigned long a, b; char perm[8];
		if (sscanf(line, "%lx-%lx %7s", &a, &b, perm) != 3) continue;
		if (perm[0] != 'r' || perm[1] != 'w' || b - a > (1UL << 30)) continue;
		if (stk >= a && stk < b) continue;
		if (strstr(line, "/dev/") || strstr(line, ".so") || strstr(line, "[stack")) continue;
		for (unsigned long p = a; p < b; p += bsz) {
			size_t n = b - p < bsz ? b - p : bsz;
			ssize_t r = pread(mem, buf, n, (off_t)p);
			if (r <= 0) continue;
			for (size_t k = 0; k < (size_t)r / 8; k++)
				if (buf[k] == vt) { if (found < max) out[found] = p + k * 8; if (++found >= max) goto done; }
		}
	}
done:
	free(buf);
	if (f) fclose(f);
	if (mem >= 0) close(mem);
	return found;
}
static unsigned long find_obj(unsigned long vt, int nth)
{
	unsigned long c[64]; int n = find_all(vt, c, 64);
	return nth < n ? c[nth] : 0;
}

static void dump_file(const char* path, unsigned long addr, size_t n)
{
	FILE* f = fopen(path, "wb");
	if (f) { fwrite((void*)addr, 1, n, f); fclose(f); }
}

static void* dumpthread(void* a)
{
	(void)a;
	sleep(20);
	for (;;) {
		sleep(2);
		FILE* f = fopen("/data/user/0/com.mina.port/files/dumpnow", "r");
		if (!f) continue;
		char tag[32] = "x"; if (fscanf(f, "%31s", tag) != 1) strcpy(tag, "x");
		fclose(f); remove("/data/user/0/com.mina.port/files/dumpnow");
		unsigned long mina = find_obj(0x100dfe6d0UL, 0);
		unsigned long rm = *(unsigned long*)0x105055fe0UL;
		unsigned long mapscr = find_obj(0x100dc00e8UL, 0);
		LOG("probe %s: Mina=%lx RoomManager=%lx MapScreen=%lx", tag, mina, rm, mapscr);
		char path[256];
		if (mina) { snprintf(path, sizeof path, "/data/user/0/com.mina.port/files/mina_%s.bin", tag); dump_file(path, mina, 0x800); }
		if (rm) { snprintf(path, sizeof path, "/data/user/0/com.mina.port/files/rm_%s.bin", tag); dump_file(path, rm, 0x800); }
		if (mina) {
			unsigned long ent = *(unsigned long*)(mina + 0x10);
			LOG("entity=%lx", ent);
			if (ent > 0x10000) { snprintf(path, sizeof path, "/data/user/0/com.mina.port/files/ent_%s.bin", tag); dump_file(path, ent, 0x200); }
		}
		{
			unsigned long ss = *(unsigned long*)0x105061938UL;
			LOG("saveslot=%lx", ss);
			if (ss > 0x10000) { snprintf(path, sizeof path, "/data/user/0/com.mina.port/files/ss_%s.bin", tag); dump_file(path, ss, 0x1000); }
		}
		if (mapscr) { snprintf(path, sizeof path, "/data/user/0/com.mina.port/files/map_%s.bin", tag); dump_file(path, mapscr, 0x800); }
	}
	return NULL;
}


/* ---- live state tracker (position/area/map flags of the guest game) ---- */
#define MINA_VT 0x100dfe6d0UL
#define SAVESLOT_PTR 0x105061938UL
#define MF_SIZE 48
static volatile float g_pos[3];
static volatile unsigned g_area;
static volatile int g_valid;
static volatile unsigned char g_mf[MF_SIZE];   /* [0..3] area LE, [4] mapOwned, [5] valid, [8..24] discovered(17), [28..45] revealed(18) */
static volatile int g_mf_valid;
static int g_mem = -1;
static int rd(unsigned long a, void* out, size_t n)
{
	if (g_mem < 0) g_mem = open("/proc/self/mem", O_RDONLY);
	if (g_mem < 0 || a < 0x10000UL) return 0;
	return pread(g_mem, out, n, (off_t)a) == (ssize_t)n;
}
static int fin_ok(float f) { return f == f && f > -1e7f && f < 1e7f; }
/* full validation of a candidate Mina object; optionally returns position */
static int mina_valid(unsigned long m, float* pos)
{
	unsigned long vt = 0, ent = 0, evt = 0; float p[3];
	if (!rd(m, &vt, 8) || vt != MINA_VT) return 0;
	if (!rd(m + 0x10, &ent, 8)) return 0;
	if (ent < 0x100000UL || ent >= 0x800000000000UL || (ent & 7)) return 0;
	if (!rd(ent, &evt, 8) || evt < 0x100d00000UL || evt >= 0x100e00000UL) return 0;
	if (!rd(ent + 0xDC, p, 12)) return 0;
	if (!fin_ok(p[0]) || !fin_ok(p[1]) || !fin_ok(p[2])) return 0;
	if (pos) { pos[0] = p[0]; pos[1] = p[1]; pos[2] = p[2]; }
	return 1;
}
static void poll_mapflags(void)
{
	unsigned long ss = 0; unsigned area = 0; unsigned char fl = 0, b[35];
	unsigned char v[MF_SIZE]; memset(v, 0, sizeof v);
	if (!rd(SAVESLOT_PTR, &ss, 8) || ss < 0x100000UL || ss >= 0x800000000000UL ||
	    !rd(ss + 584, &area, 4) || !rd(ss + 3176, &fl, 1) || !rd(ss + 3405, b, 35)) { g_mf_valid = 0; return; }
	g_area = area;
	memcpy(v, &area, 4);
	v[4] = (fl >> 2) & 1; v[5] = 1;
	memcpy(v + 8, b, 17);
	memcpy(v + 28, b + 17, 18);
	{ /* area -> map location via the runtime area table (198 x 136 B at 0x10504c3f8, id @+8, loc @+12) */
		for (int i = 0; i < 198; i++) {
			unsigned id = 0; int loc = 0; unsigned long e = 0x10504c3f8UL + 136UL * i;
			if (!rd(e + 8, &id, 4)) break;
			if (id == area && rd(e + 12, &loc, 4)) { v[7] = 1; v[6] = (loc >= 0 && loc < 17) ? (unsigned char)(loc + 1) : 0; break; }
		}
	}
	for (int i = 0; i < MF_SIZE; i++) g_mf[i] = v[i];
	g_mf_valid = 1;
}

static char g_levels[512];
static volatile int g_levels_ver;
static void scan_levels(void)
{
	unsigned long hits[16]; int n = find_all(0x100e18ab8UL, hits, 16); char buf[512]; int o = 0; buf[0] = 0;
	for (int i = 0; i < n; i++) {
		unsigned long ptr = 0; char sb[100] = {0};
		if (!rd(hits[i] + 0x1c8, &ptr, 8) || ptr < 0x100000UL || !rd(ptr, sb, 96)) continue;
		sb[95] = 0; if (strncmp(sb, "levels/", 7)) continue;
		o += snprintf(buf + o, sizeof buf - o, "%s%s", o ? ";" : "", sb);
	}
	if (strcmp(buf, g_levels)) { strcpy(g_levels, buf); g_levels_ver++; LOG("levels: %s", buf); }
}
static void* trackthread(void* a)
{
	(void)a;
	unsigned long mina = 0, lastlog = 0;
	unsigned long cands[8]; int nc = 0;
	float base[8][3];
	int nextscan = 0, backoff = 10, tick = 0, provisional = 0;
	sleep(20);
	for (;;) {
		usleep(16000);
		{ /* debug: files/probewr -> find WorldRegion objects and log c-strings starting with "levels/" reachable in first 0x400 bytes */
			if (access("/data/user/0/com.mina.port/files/probewr", F_OK) == 0) {
				remove("/data/user/0/com.mina.port/files/probewr");
				unsigned long hits[16]; int n = find_all(0x100e18ab8UL, hits, 16);
				LOG("probewr: %d WorldRegion objects", n);
				for (int i = 0; i < n; i++) {
					unsigned long q[0x80]; if (!rd(hits[i], q, sizeof q)) continue;
					{ float* f = (float*)q; char ln[512]; int o = 0; for (int k = 0; k < 0x1c0 / 4 && o < 480; k++) if (fabsf(f[k]) > 0.01f && fabsf(f[k]) < 1e6f) o += snprintf(ln + o, 480 - o, "[%x]%.1f ", k * 4, f[k]); LOG("probewr obj%d floats: %s", i, ln); }
					for (int k = 0; k < 0x80; k++) {
						char sb[96] = {0};
						if (q[k] > 0x100000UL && q[k] < 0x800000000000UL && rd(q[k], sb, 90) && !strncmp(sb, "levels/", 7)) LOG("probewr obj%d +0x%x -> %s", i, k * 8, sb);
					}
					for (int k = 0; k < 0x80 * 8 - 8; k += 8) { char sb[96] = {0}; memcpy(sb, (char*)q + k, 80); if (!strncmp(sb, "levels/", 7)) LOG("probewr obj%d inline +0x%x -> %s", i, k, sb); }
				}
			}
		}
		{ /* debug: files/setarea contains a hex area id -> write it into SaveSlot+584 (map view test only) */
			FILE* sf = fopen("/data/user/0/com.mina.port/files/setarea", "r");
			if (sf) {
				unsigned v = 0; int ok = fscanf(sf, "%x", &v); fclose(sf); remove("/data/user/0/com.mina.port/files/setarea");
				unsigned long ss = 0, cur = 0;
				if (ok == 1 && rd(SAVESLOT_PTR, &ss, 8) && ss > 0x100000UL && rd(ss + 584, &cur, 4)) { *(volatile unsigned*)(ss + 584) = v; LOG("setarea %08x (was %08x)", v, (unsigned)cur); }
			}
		}
		{ static int sub; if (++sub % 6 == 0) { tick++; poll_mapflags(); } }
		float p[3];
		if (mina && !mina_valid(mina, p)) { g_valid = 0; mina = 0; provisional = 0; nc = 0; nextscan = tick; backoff = 10; }
		if (!mina && tick >= nextscan) {
			/* rescan: collect all valid candidates, then pick the one that moves / stays valid */
			unsigned long all[64]; int n = find_all(MINA_VT, all, 64);
			nc = 0;
			for (int i = 0; i < n && nc < 8; i++) {
				float q[3];
				if (mina_valid(all[i], q)) { cands[nc] = all[i]; memcpy(base[nc], q, 12); nc++; }
			}
			if (nc == 1) { mina = cands[0]; provisional = 0; }
			else if (nc > 1) { mina = cands[nc - 1]; provisional = 1; }  /* newest heap copy preferred until one proves live */
			if (!mina) { nextscan = tick + backoff; if (backoff < 100) backoff = backoff * 2; }
			else backoff = 10;
			if (mina && mina != lastlog) { LOG("tracker: Mina object at %lx (%d valid candidates)", mina, nc); lastlog = mina; }
		}
		if (!mina) { g_valid = 0; continue; }
		if (provisional) {
			/* among candidates, prefer one whose position changed since discovery */
			for (int i = 0; i < nc; i++) {
				float q[3];
				if (!mina_valid(cands[i], q)) continue;
				if (q[0] != base[i][0] || q[1] != base[i][1]) {
					if (cands[i] != mina) { mina = cands[i]; if (mina != lastlog) { LOG("tracker: switched to live Mina object at %lx", mina); lastlog = mina; } }
					provisional = 0; break;
				}
			}
		}
		if (!mina_valid(mina, p)) { mina = 0; g_valid = 0; continue; }
		{ static unsigned la; static int lt; if (g_area != la || ++lt > 1800) { la = g_area; lt = 0; scan_levels(); } }
		g_pos[0] = p[0]; g_pos[1] = p[1]; g_pos[2] = p[2]; g_valid = 1;
		{ static float lp[2]; if (fabsf(p[0] - lp[0]) + fabsf(p[1] - lp[1]) > 12.0f) { { unsigned long rm = 0; float bb[6] = {0};
				if (rd(0x105055fe0UL, &rm, 8) && rm > 0x100000UL && rd(rm + 512, bb, 24)) LOG("pos %.1f %.1f  room c=%.1f,%.1f,%.1f half=%.1f,%.1f,%.1f", p[0], p[1], bb[0], bb[1], bb[2], bb[3], bb[4], bb[5]);
				else LOG("pos %.1f %.1f %.1f", p[0], p[1], p[2]); } lp[0] = p[0]; lp[1] = p[1]; } }
	}
	return NULL;
}
JNIEXPORT jint JNICALL Java_com_mina_port_MinaActivity_nativeState(JNIEnv* env, jclass c, jfloatArray out)
{
	(void)c;
	if (!g_valid) return 0;
	float v[3] = { g_pos[0], g_pos[1], g_pos[2] };
	(*env)->SetFloatArrayRegion(env, out, 0, 3, v);
	return (jint)(g_area | 1u);
}
/* out: byte[>=48]; layout see g_mf. returns 1 if valid else 0 */

JNIEXPORT jstring JNICALL Java_com_mina_port_MinaActivity_nativeLevels(JNIEnv* env, jclass c)
{
	(void)c; char b[512]; strncpy(b, g_levels, sizeof b - 1); b[sizeof b - 1] = 0;
	return (*env)->NewStringUTF(env, b);
}
JNIEXPORT jint JNICALL Java_com_mina_port_MinaActivity_nativeMapFlags(JNIEnv* env, jclass c, jbyteArray out)
{
	(void)c;
	if (!g_mf_valid || !out || (*env)->GetArrayLength(env, out) < MF_SIZE) return 0;
	jbyte v[MF_SIZE];
	for (int i = 0; i < MF_SIZE; i++) v[i] = (jbyte)g_mf[i];
	(*env)->SetByteArrayRegion(env, out, 0, MF_SIZE, v);
	return 1;
}

JNIEXPORT jint JNICALL Java_com_mina_port_MinaActivity_nativeSave(JNIEnv* env, jclass c, jbyteArray out)
{
	(void)c;
	static unsigned char sv[4552 + 96];
	unsigned long ss = 0;
	if (!g_valid || !out || (*env)->GetArrayLength(env, out) < 4552 + 96) return 0;
	if (!rd(SAVESLOT_PTR, &ss, 8) || ss < 0x100000UL || !rd(ss, sv, 4552)) return 0;
	{ /* weapon / sidearm names are {u32 flag, u32 len, char* ptr} structs; resolve them into 3 x 32 bytes after the slot */
		static const int offs[3] = { 312, 328, 344 };
		memset(sv + 4552, 0, 96);
		for (int k = 0; k < 3; k++) {
			unsigned len = *(unsigned*)(sv + offs[k] + 4); unsigned long ptr = *(unsigned long*)(sv + offs[k] + 8);
			if (len > 0 && len < 31 && ptr > 0x100000UL && ptr < 0x800000000000UL) rd(ptr, sv + 4552 + 32 * k, len);
		}
	}
	(*env)->SetByteArrayRegion(env, out, 0, 4552 + 96, (const jbyte*)sv);
	return 1;
}

int SDL_main(int argc, char** argv)
{
	if (argc < 4) { LOG("usage: libdir filesdir datadir"); return 1; }
	const char *lib = argv[1], *files = argv[2], *data = argv[3];
	char b[2048];

	/* stdout/stderr to logcat and to files/mina.log */
	int pfd[2];
	if (pipe(pfd) == 0) {
		dup2(pfd[1], 1); dup2(pfd[1], 2);
		pthread_t th;
		pthread_create(&th, NULL, logthread, (void*)(long)pfd[0]);
	}
	setvbuf(stderr, NULL, _IONBF, 0); setvbuf(stdout, NULL, _IONBF, 0);

	snprintf(b, sizeof b, "%s/userdata", files); mkdir(b, 0755);
	setenv("MACHISMO_LIB_DIR", lib, 1);
	setenv("MACHISMO_LIB_ALIAS", "libc++.so.1=libcxxg.so;libatomic.so.1=libatomicg.so;libSDL2-2.0.so.0=libSDL2.so", 1);
	snprintf(b, sizeof b, "%s/conf/machismo.conf", files); setenv("MACHISMO_CONFIG", b, 1);
	snprintf(b, sizeof b, "%s/userdata", files);
	setenv("MACHISMO_HOME", b, 1); setenv("HOME", b, 1); setenv("XDG_DATA_HOME", b, 1); setenv("XDG_CONFIG_HOME", b, 1);
	setenv("GOTHIC_BACKEND", getenv("GOTHIC_BACKEND") ? getenv("GOTHIC_BACKEND") : "gles", 1);
	snprintf(b, sizeof b, "%s/gamedata/Mina the Hollower.app/Contents/Resources/", data); setenv("GOTHIC_BASE_PATH", b, 1);
	snprintf(b, sizeof b, "%s/gamedata/shaders_gles", data); setenv("GOTHIC_SHADER_DIR", b, 1);
	snprintf(b, sizeof b, "%s/gamedata/shaders_spv", data); setenv("GOTHIC_VK_SHADER_DIR", b, 1);
	setenv("MINA_MMAP_DEBUG", "1", 1);
	setenv("MACHISMO_UNWIND_LIB", "libgcc_sg.so", 1);

	/* SDL global so libgothic's dlsym(RTLD_DEFAULT, "SDL_*") finds it */
	load(lib, "libSDL2.so", RTLD_NOW | RTLD_GLOBAL);
	load(lib, "libglibc_compat.so", RTLD_NOW | RTLD_GLOBAL);
	const char* chain[] = { "libgcc_sg.so", "libstdcxxg.so", "libcxxabig.so", "libatomicg.so", "libcxxg.so", NULL };
	for (int i = 0; chain[i]; i++) load(lib, chain[i], RTLD_NOW | RTLD_LOCAL);
	load(lib, "libgfxshim_sdl.so", RTLD_NOW | RTLD_GLOBAL);
	load(lib, "libsystem_shim.so", RTLD_NOW | RTLD_GLOBAL);
	void* m = load(lib, "libmachismo.so", RTLD_NOW | RTLD_GLOBAL);
	load(lib, "libgothic_patches.so", RTLD_NOW | RTLD_GLOBAL);
	if (!m) return 2;


	load(lib, "libmachismo_unwind.so", RTLD_LAZY | RTLD_LOCAL);

	if (chdir(files) != 0) LOG("chdir failed");
	int (*mm)(int, char**, size_t) = (int (*)(int, char**, size_t))dlsym(m, "machismo_main_bigstack");
	if (!mm) { LOG("machismo_main_bigstack missing"); return 3; }
	snprintf(b, sizeof b, "%s/gamedata/Mina the Hollower.app/Contents/MacOS/MinaTheHollower", data);
	char* av[] = { (char*)"machismo", (char*)"-v", b, NULL };
	LOG("starting guest %s", b);
	{ pthread_t dt; if (getenv("MINA_DUMP")) pthread_create(&dt, NULL, dumpthread, NULL); pthread_create(&dt, NULL, trackthread, NULL); }
	int rc = mm(3, av, 64u << 20);
	LOG("guest exited rc=%d", rc);
	return rc;
}
