package com.mina.port;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** Live exploration map: plots visited 16-unit world cells per area, persisted to files/maptrails.bin. */
public class MapView extends View {
    static final float CELL = 16f;
    static final int MAGIC = 0x4D415031; // "MAP1"
    static final int SAVE_MS = 10000;

    private final HashMap<Integer, HashSet<Long>> seen = new HashMap<>();
    private final float[] cur = new float[3];
    private final byte[] flags = new byte[48];
    private boolean haveFlags = false;
    private int area = 0;
    private boolean dirty = false;
    private final File store;

    private final Paint p = new Paint();
    private UiKit ui;
    private UiKit ui() { if (ui == null) ui = new UiKit(getContext()); return ui; }
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float[] tgt = new float[3];
    private int frameNo = 0; private boolean haveCur = false; private int lastArea = 0;
    private final android.view.Choreographer.FrameCallback fc = new android.view.Choreographer.FrameCallback() {
        public void doFrame(long t) {
            boolean busy = frame();
            if (busy) android.view.Choreographer.getInstance().postFrameCallback(this);
            else h.postDelayed(idleTick, 40);
        }
    };
    private final Runnable idleTick = new Runnable() { public void run() { android.view.Choreographer.getInstance().postFrameCallback(fc); } };
    private long lastDraw = 0;
    private boolean frame() {
        int a;
        try { a = MinaActivity.nativeState(tgt); } catch (Throwable t) { a = 0; }
        if ((frameNo++ % 15) == 0) { try { haveFlags = MinaActivity.nativeMapFlags(flags) != 0; } catch (Throwable t) { haveFlags = false; } }
        if (a == 0) { if (area != 0) invalidate(); area = 0; haveCur = false; return false; }
        if (!haveCur || a != lastArea || Math.abs(tgt[0] - cur[0]) + Math.abs(tgt[1] - cur[1]) > 30f) { cur[0] = tgt[0]; cur[1] = tgt[1]; cur[2] = tgt[2]; haveCur = true; }
        else { cur[0] += (tgt[0] - cur[0]) * 0.4f; cur[1] += (tgt[1] - cur[1]) * 0.4f; cur[2] = tgt[2]; }
        boolean moving = Math.abs(tgt[0] - cur[0]) + Math.abs(tgt[1] - cur[1]) > 0.02f;
        if (!moving) { cur[0] = tgt[0]; cur[1] = tgt[1]; }
        lastArea = a; area = a;
        HashSet<Long> s = seen.get(a);
        if (s == null) { s = new HashSet<>(); seen.put(a, s); }
        if (s.add(key((int) Math.floor(cur[0] / CELL), (int) Math.floor(cur[1] / CELL)))) dirty = true;
        long now = android.os.SystemClock.uptimeMillis();
        if (moving || lastPx != cur[0] + cur[1] * 1.37f || now - lastDraw > 120) { invalidate(); lastDraw = now; lastPx = cur[0] + cur[1] * 1.37f; }
        return moving;
    }
    private float lastPx = 0;
    private final Runnable saver = new Runnable() { public void run() { save(); h.postDelayed(this, SAVE_MS); } };

    private Bitmap bg;
    private RectF bgRect;

    public MapView(Context c) {
        super(c);
        p.setAntiAlias(false);
        p.setFilterBitmap(false);
        setBackgroundColor(0xFF000000);
        store = new File(c.getFilesDir(), "maptrails.bin");
        load();
    }

    /** Optional map image drawn beneath the cells; worldRect is the image's extent in world units (x right, y up). */
    public void setBackgroundBitmap(Bitmap b, RectF worldRect) {
        bg = b; bgRect = worldRect; invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        h.removeCallbacks(saver); android.view.Choreographer.getInstance().removeFrameCallback(fc);
        android.view.Choreographer.getInstance().postFrameCallback(fc); h.postDelayed(saver, SAVE_MS);
    }

    @Override protected void onDetachedFromWindow() {
        h.removeCallbacks(saver); android.view.Choreographer.getInstance().removeFrameCallback(fc);
        save();
        super.onDetachedFromWindow();
    }

    private static long key(int cx, int cy) { return ((long) cx << 32) | (cy & 0xFFFFFFFFL); }
    private static int kx(long k) { return (int) (k >> 32); }
    private static int ky(long k) { return (int) k; }

    private void poll() {
        int a;
        try { a = MinaActivity.nativeState(cur); } catch (Throwable t) { a = 0; }
        try { haveFlags = MinaActivity.nativeMapFlags(flags) != 0; } catch (Throwable t) { haveFlags = false; }
        if (a == 0) { area = 0; invalidate(); return; }
        area = a;
        HashSet<Long> s = seen.get(a);
        if (s == null) { s = new HashSet<>(); seen.put(a, s); }
        if (s.add(key((int) Math.floor(cur[0] / CELL), (int) Math.floor(cur[1] / CELL)))) dirty = true;
        invalidate();
    }

    private void load() {
        if (!store.isFile()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(store)))) {
            if (in.readInt() != MAGIC) return;
            int na = in.readInt();
            for (int i = 0; i < na && i < 100000; i++) {
                int a = in.readInt(), n = in.readInt();
                HashSet<Long> s = new HashSet<>();
                for (int j = 0; j < n; j++) { int cx = in.readInt(), cy = in.readInt(); s.add(key(cx, cy)); }
                seen.put(a, s);
            }
        } catch (Exception e) {
            android.util.Log.e("MinaPort", "maptrails load: " + e);
        }
    }

    public void save() {
        if (!dirty) return;
        File tmp = new File(store.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(seen.size());
            for (Map.Entry<Integer, HashSet<Long>> e : seen.entrySet()) {
                out.writeInt(e.getKey());
                out.writeInt(e.getValue().size());
                for (long k : e.getValue()) { out.writeInt(kx(k)); out.writeInt(ky(k)); }
            }
            out.flush();
        } catch (Exception e) {
            android.util.Log.e("MinaPort", "maptrails save: " + e);
            return;
        }
        if (tmp.renameTo(store)) dirty = false;
        else if (store.delete() && tmp.renameTo(store)) dirty = false;
    }

    private static int popcnt(byte[] b, int off, int n) {
        int c = 0;
        for (int i = 0; i < n; i++) c += Integer.bitCount(b[off + i] & 0xFF);
        return c;
    }


    // ---- world map (game art baked locally into files/mapassets/, never bundled in the APK) ----
    private static final String[] LOC = { "astralOrrery", "bayouA", "bayouB", "boneBeachA", "boneBeachB", "cryptA", "cryptB",
        "easternFields", "frozenTrainyard", "introBeach", "mansionA", "mansionB", "ossex", "septemburgA", "septemburgB",
        "southernFields", "westernFields" };
    private Bitmap worldBmp;
    private final java.util.HashMap<Integer, String> areaNames = new java.util.HashMap<>();
    private final java.util.HashMap<String, float[]> locs = new java.util.HashMap<>();
    private int mode = 0; private boolean worldTried = false;
    private void loadWorld() {
        worldTried = true;
        try {
            java.io.File d = new java.io.File(getContext().getFilesDir(), "mapassets");
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inScaled = false;
            worldBmp = android.graphics.BitmapFactory.decodeFile(new java.io.File(d, "worldmap_big.png").getPath(), o);
            java.io.FileInputStream in = new java.io.FileInputStream(new java.io.File(d, "locators_big.json"));
            byte[] b = new byte[in.available()]; in.read(b); in.close();
            org.json.JSONObject jo = new org.json.JSONObject(new String(b, "UTF-8")).getJSONObject("locators");
            java.util.Iterator<String> it = jo.keys();
            while (it.hasNext()) { String k = it.next(); org.json.JSONArray a = jo.getJSONArray(k); locs.put(k, new float[] { (float) a.getDouble(0), (float) a.getDouble(1) }); }
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(new java.io.File(d, "area_table.csv")))) {
                String ln; br.readLine();
                while ((ln = br.readLine()) != null) { String[] f = ln.split(","); if (f.length > 2) areaNames.put((int) Long.parseLong(f[1], 16), f[2]); }
            } catch (Throwable t2) { android.util.Log.e("MinaPort", "areanames: " + t2); }
        } catch (Throwable t) { worldBmp = null; android.util.Log.e("MinaPort", "worldmap: " + t); }
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent e) {
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) { mode = (mode + 1) % 3; invalidate(); }
        return true;
    }
    // ---- shared look (navy panels, teal edges, cream text like the game UI) ----
    static final int C_BG = 0xFF0B1220, C_BAR = 0xFF0D1B2A, C_EDGE = 0xFF2A6F8F, C_TEXT = 0xFFE8DCC0, C_DIM = 0xFF7F93A6, C_RED = 0xFFFF3030, C_TEAL = 0xFF4FD1C5;
    private static String pretty(String s) {
        if (s == null) return "?";
        s = s.replace("levels/", "").replace("overworld/", "").replace("core/", "").replace(".tlb.yc", "").replace('_', ' ').replace("  ", " ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (i > 0 && Character.isUpperCase(ch) && Character.isLowerCase(s.charAt(i - 1))) sb.append(' ');
            sb.append(i == 0 ? Character.toUpperCase(ch) : ch);
        }
        return sb.toString();
    }
    private String curAreaName() {
        int h = haveFlags ? ((flags[0] & 0xFF) | ((flags[1] & 0xFF) << 8) | ((flags[2] & 0xFF) << 16) | ((flags[3] & 0xFF) << 24)) : area;
        return areaNames.get(h);
    }
    private float barH(float ts) { return ts * 1.9f; }
    private void drawBar(Canvas c, int W, float ts, String title, String sub) {
        p.setStyle(Paint.Style.FILL); p.setColor(C_BAR); c.drawRect(0, 0, W, barH(ts), p);
        p.setColor(C_EDGE); c.drawRect(0, barH(ts) - 4, W, barH(ts), p);
        if (ui().ok) {
            float s2 = Math.max(2, Math.round(ts / 8f));
            ui().text(c, title, 16, barH(ts) * 0.5f + 4 * s2, s2, C_TEXT, true);
            if (sub != null) ui().text(c, sub, W - 16 - ui().width(sub, s2, false), barH(ts) * 0.5f + 4 * s2, s2, C_TEAL, false);
            return;
        }
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); p.setTextSize(ts * 1.05f);
        p.setColor(C_TEXT); c.drawText(title, 16, ts * 1.3f, p);
        p.setTypeface(android.graphics.Typeface.DEFAULT); p.setTextSize(ts * 0.9f);
        if (sub != null) { p.setColor(C_TEAL); c.drawText(sub, W - 16 - p.measureText(sub), ts * 1.3f, p); }
        p.setTextSize(ts);
    }
    private void drawFooter(Canvas c, int W, int H, float ts, String hint) {
        float fh = ts * 1.6f;
        p.setStyle(Paint.Style.FILL); p.setColor(C_BAR); c.drawRect(0, H - fh, W, H, p);
        p.setColor(C_EDGE); c.drawRect(0, H - fh, W, H - fh + 3, p);
        if (ui().ok) { float s2 = Math.max(2, Math.round(ts / 10f)); ui().text(c, hint, 16, H - fh * 0.5f + 4 * s2, s2, C_DIM, false); }
        else { p.setTextSize(ts * 0.8f); p.setColor(C_DIM); c.drawText(hint, 16, H - fh * 0.32f, p); }
        float s = ts * 0.55f, gx = W - 16 - 3 * (s * 1.8f);
        for (int i = 0; i < 3; i++) { p.setColor(i == mode ? C_TEXT : 0xFF2B3B4C); c.drawRect(gx + i * s * 1.8f, H - fh * 0.7f, gx + i * s * 1.8f + s, H - fh * 0.7f + s, p); }
        p.setTextSize(ts);
    }
    private void drawMarker(Canvas c, float mx, float my, float r) {
        float ph = (android.os.SystemClock.uptimeMillis() % 1400) / 1400f;
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2, r * 0.25f)); p.setColor(C_RED); p.setAlpha((int) (200 * (1f - ph)));
        float rr = r + ph * r * 2.6f; c.drawRect(mx - rr, my - rr, mx + rr, my + rr, p); p.setAlpha(255);
        if (ui().ok) {
            float bob = (float) Math.sin(android.os.SystemClock.uptimeMillis() / 180.0) * r * 0.25f;
            if (ui().marker(c, mx, my - r * 0.6f + bob, Math.max(2, Math.round(r / 3f)))) { p.setStyle(Paint.Style.FILL); return; }
        }
        p.setStyle(Paint.Style.FILL); p.setColor(C_RED); c.drawRect(Math.round(mx - r), Math.round(my - r), Math.round(mx + r), Math.round(my + r), p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(0xFFFFFFFF); c.drawRect(Math.round(mx - r), Math.round(my - r), Math.round(mx + r), Math.round(my + r), p);
        p.setStyle(Paint.Style.FILL);
    }
    private int tagLoc = -1;
    private void drawTag(Canvas c, String text, float x, float y, float ts, int W) {
        if (ui().ok) {
            float sc = Math.max(2, Math.round(ts / 10f)), pad = 5 * sc;
            float tw = ui().width(text, sc, true), bw = tw + 2 * pad + 16 * sc, bh = 13 * sc + 16 * sc * 0.6f;
            float l = Math.max(6, Math.min(W - bw - 6, x)), t = y - bh;
            if (ui().plateBox(c, tagLoc, new RectF(l, t, l + bw, t + bh), sc)) {
                ui().text(c, text, l + bw / 2 - tw / 2, t + bh / 2 + 4 * sc, sc, C_TEXT, true);
                return;
            }
        }
        p.setTextSize(ts * 0.85f); p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        float tw = p.measureText(text), pad = ts * 0.4f;
        float l = Math.max(6, Math.min(W - tw - 2 * pad - 6, x));
        p.setStyle(Paint.Style.FILL); p.setColor(0xDD0D1B2A); c.drawRect(l, y - ts, l + tw + 2 * pad, y + ts * 0.45f, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(C_EDGE); c.drawRect(l, y - ts, l + tw + 2 * pad, y + ts * 0.45f, p);
        p.setStyle(Paint.Style.FILL); p.setColor(C_TEXT); c.drawText(text, l + pad, y, p);
        p.setTypeface(android.graphics.Typeface.DEFAULT); p.setTextSize(ts);
    }

    private final byte[] sv = new byte[4552 + 96]; private boolean haveSv = false; private long svAt = 0;
    private java.nio.ByteBuffer svb; private boolean dbgInv = false;
    private int u32(int o) { return svb.getInt(o); }
    private static int pop(int v) { return Integer.bitCount(v); }
    private String cstr(int o) { int k = o == 312 ? 0 : o == 328 ? 1 : 2; int b = 4552 + 32 * k, e = b; while (e < b + 31 && sv[e] != 0) e++; return new String(sv, b, e - b); }

    private void drawInventory(Canvas c, int W, int H, float ts) {
        p.setStyle(Paint.Style.FILL); p.setColor(C_BG); c.drawRect(0, 0, W, H, p);
        drawBar(c, W, ts, "Inventory", null);
        drawFooter(c, W, H, ts, "Inventory  \u2022  tap for next view");
        long now = android.os.SystemClock.uptimeMillis();
        if (now - svAt > 250) {
            svAt = now;
            try { haveSv = MinaActivity.nativeSave(sv) != 0; } catch (Throwable t) { haveSv = false; }
            if (haveSv) svb = java.nio.ByteBuffer.wrap(sv).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        }
        org.json.JSONObject inv = ui().inv();
        if (!haveSv || inv == null || !ui().ok) {
            if (ui().ok) ui().text(c, haveSv ? "No inventory data" : "Waiting for the game...", 24, barH(ts) + 60, 3, C_DIM, false);
            return;
        }
        try {
            final int S = 3;
            float x0 = 24, y = barH(ts) + 20;
            float maxHp = 8f * pop(u32(304)) * (((u32(3176) & 32) != 0) ? 1.25f : 1f);
            float hp = svb.getFloat(288);
            // health bar
            float bw = W - 48, bh = 30;
            p.setStyle(Paint.Style.FILL); p.setColor(0xFF2B0A10); c.drawRect(x0, y, x0 + bw, y + bh, p);
            p.setColor(0xFFE8132E); c.drawRect(x0, y, x0 + bw * Math.max(0, Math.min(1, maxHp > 0 ? hp / maxHp : 0)), y + bh, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(C_TEXT); c.drawRect(x0, y, x0 + bw, y + bh, p); p.setStyle(Paint.Style.FILL);
            ui().text(c, "Health " + Math.round(hp) + " / " + Math.round(maxHp), x0 + 12, y + 4 + 3 * S * 2.4f, S, 0xFFFFFFFF, true);
            y += bh + 22;
            // counters row
            int bones = u32(408), vials = u32(392), maxVials = pop(u32(396));
            int keys = pop(svb.getInt(496)) + pop(svb.getInt(500)) - u32(504);
            int joules = u32(364), maxJ = Math.min(99, 10 * pop(u32(368)));
            int bst = 0; for (int i = 0; i < 7; i++) bst += pop(u32(3080 + 4 * i));
            android.graphics.Bitmap bi = ui().icon("UI_HUD_HUD_boneCounter_icon.png");
            float cx = x0;
            if (bi != null) { ui().drawIcon(c, bi, cx, y - 6, S, false); cx += bi.getWidth() * S + 8; }
            String s1 = "Bones " + bones; ui().text(c, s1, cx, y + 20, S, C_TEXT, true); cx += ui().width(s1, S, true) + 40;
            String s2 = "Vials " + vials + "/" + maxVials; ui().text(c, s2, cx, y + 20, S, C_TEXT, false); cx += ui().width(s2, S, false) + 40;
            String s3 = "Keys " + keys; ui().text(c, s3, cx, y + 20, S, C_TEXT, false); cx += ui().width(s3, S, false) + 40;
            String s4 = "Bonestones " + bst + "/20"; ui().text(c, s4, cx, y + 20, S, C_TEAL, false);
            y += 44;
            String lv = "Attack " + u32(372) + "    Defense " + u32(376) + "    Sidearms " + u32(380) + "    Deaths " + u32(592);
            ui().text(c, lv, x0, y + 16, S, C_DIM, false);
            y += 46;
            // weapon + sidearm panels
            String wn = cstr(312), sn = cstr(328);
            if (!dbgInv) { dbgInv = true; android.util.Log.e("MinaPort", "inv strings w=[" + wn + "] s=[" + sn + "] s2=[" + cstr(344) + "]"); }
            org.json.JSONArray ws = inv.getJSONArray("weapons"), ss = inv.getJSONArray("sidearms");
            float pw = (W - 48 - 16) / 2f, ph = 120;
            for (int k = 0; k < 2; k++) {
                float px = x0 + k * (pw + 16);
                ui().plateBox(c, 12, new RectF(px, y, px + pw, y + ph), 3);
                String key = k == 0 ? wn : sn, label = k == 0 ? "Weapon" : "Sidearm", nm = "-", ic = null;
                org.json.JSONArray arr = k == 0 ? ws : ss;
                for (int i = 0; i < arr.length(); i++) { org.json.JSONObject o = arr.getJSONObject(i); if (o.getString("s").equalsIgnoreCase(key)) { nm = o.getString("name"); ic = o.getString("icon"); } }
                ui().text(c, label, px + 36, y + 44, 2, C_DIM, false);
                Bitmap b = ic == null ? null : ui().icon(ic);
                float tx = px + 36;
                if (b != null) { ui().drawIcon(c, b, px + 30, y + 52, 3, false); tx = px + 30 + b.getWidth() * 3 + 18; }
                if (k == 0) { int wi = -1; for (int i = 0; i < arr.length(); i++) if (arr.getJSONObject(i).getString("s").equalsIgnoreCase(key)) wi = i; int wl = wi >= 0 && wi < 5 ? weaponLevel(wi) : 0; if (wl > 0) nm = nm + "  Lv " + wl; }
                ui().text(c, nm, tx, y + 84, S, C_TEXT, true);
                if (k == 1 && sn.length() > 0) ui().text(c, "Joules " + joules + "/" + maxJ, tx, y + 108, 2, C_TEAL, false);
            }
            y += ph + 22;
            // trinkets
            org.json.JSONArray tr = inv.getJSONArray("trinkets");
            int owned = 0; for (int i = 0; i < 61; i++) if (u32(1136 + 4 * i) != 0) owned++;
            ui().text(c, "Trinkets " + owned + "/61", x0, y + 16, S, C_TEXT, true);
            y += 34;
            boolean[] eq = new boolean[61];
            StringBuilder en = new StringBuilder();
            for (int i = 0; i < 6; i++) { int id = u32(1868 + 4 * i); if (id >= 134 && id < 195) { eq[id - 134] = true; if (en.length() > 0) en.append(", "); en.append(tr.getJSONObject(id - 134).getString("name")); } }
            int cols = 13; float cell = (W - 48) / (float) cols;
            for (int i = 0; i < 61 && i < tr.length(); i++) {
                int col = i % cols, row = i / cols;
                float gx = x0 + col * cell, gy = y + row * (cell * 0.9f);
                Bitmap b = ui().icon(tr.getJSONObject(i).getString("icon"));
                boolean have = u32(1136 + 4 * i) != 0;
                if (eq[i]) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(0xFFFFD34D); c.drawRect(gx + 2, gy + 2, gx + cell - 4, gy + cell * 0.9f - 4, p); p.setStyle(Paint.Style.FILL); }
                if (b != null) { int s = Math.max(1, (int) ((cell - 14) / Math.max(b.getWidth(), b.getHeight()))); ui().drawIcon(c, b, gx + (cell - b.getWidth() * s) / 2f, gy + (cell * 0.9f - b.getHeight() * s) / 2f, s, !have); }
            }
            int rows = (61 + cols - 1) / cols;
            y += rows * cell * 0.9f + 12;
            if (en.length() > 0 && y < H - ts * 1.6f - 10) ui().text(c, "Equipped: " + en, x0, y + 14, 2, 0xFFFFD34D, false);
            if (en.length() > 0) y += 26;
            drawAreas(c, inv, x0, y, W, H, ts);
        } catch (Throwable t) { android.util.Log.e("MinaPort", "inventory: " + t); }
    }


    // weapon level 1..3 (0 = not owned), formula decoded from Player::CanUseWeaponLevelAbility
    private int weaponLevel(int w) {
        int pick = u32(3108 + 4 * w), st = u32(3128 + 4 * w);
        if (st < 0 || st > 31 || ((pick >>> st) & 1) == 0) return 0;
        int lv = 1;
        for (int L = 2; L <= 3; L++) { int sh = (L - 1) - ((st != 0 && st >= L - 1) ? 1 : 0); if (((pick >>> sh) & 1) != 0) lv = L; }
        return lv;
    }

    private boolean bit64(int off, int b) { return b >= 0 && b < 64 && (((svb.getLong(off) >>> b) & 1L) != 0); }
    private int collectedOf(int cat, org.json.JSONArray ids, org.json.JSONObject masks) throws org.json.JSONException {
        int n = 0;
        for (int i = 0; i < ids.length(); i++) {
            boolean got = false; int id = ids.optInt(i, -1);
            switch (cat) {
                case 0: got = id >= 0 && id < 32 && ((u32(304) >>> id) & 1) != 0; break;
                case 1: got = id >= 0 && id < 61 && u32(1136 + 4 * id) == 1; break;
                case 2: got = bit64(496, id); break;
                case 3: got = id >= 2 && id <= 16 && ((u32(3108 + 4 * ((id - 2) / 3)) >>> ((id - 2) % 3)) & 1) != 0; break;
                case 4: got = id >= 0 && id < 32 && ((u32(368) >>> id) & 1) != 0; break;
                case 6: got = ids.optString(i).equals("first3") ? (u32(396) & 7) == 7 : (id >= 0 && id < 32 && ((u32(396) >>> id) & 1) != 0); break;
                case 7: got = id >= 0 && id < 32 && ((u32(2384) >>> id) & 1) != 0; break;
                case 8: got = id >= 0 && id < 32 && ((u32(84) >>> id) & 1) != 0; break;
                case 10: got = id >= 0 && id < 224 && ((u32(3080 + 4 * (id >> 5)) >>> (id & 31)) & 1) != 0; break;
                case 11: got = id >= 0 && id + 1 < 32 && ((u32(3440) >>> (id + 1)) & 1) != 0; break;
                case 13: got = bit64(512, id); break;
                case 16: got = (sv[448] & 0xFF) != 0; break;
                default: { String m = masks.optString(String.valueOf(id), null); if (m != null) got = (u32(3176) & (int) Long.decode(m).longValue()) != 0; }
            }
            if (got) n++;
        }
        return n;
    }

    private void drawAreas(Canvas c, org.json.JSONObject inv, float x0, float y, int W, int H, float ts) {
        try {
            org.json.JSONObject cp = inv.optJSONObject("completion");
            if (cp == null) return;
            org.json.JSONObject locsO = cp.getJSONObject("locations"), masks = cp.getJSONObject("upgrade_masks");
            if (y > H - ts * 1.6f - 60) return;
            ui().text(c, "Areas  (items found)", x0, y + 16, 3, C_TEXT, true);
            y += 38;
            int cols = 2; float cw = (W - 48) / cols, rh = 26;
            for (int i = 0; i < LOC.length; i++) {
                float gx = x0 + (i % cols) * cw, gy = y + (i / cols) * rh;
                if (gy + rh > H - ts * 1.6f) break;
                org.json.JSONObject lo = locsO.optJSONObject(LOC[i]); if (lo == null) continue;
                int got = 0, tot = 0;
                java.util.Iterator<String> it = lo.keys();
                while (it.hasNext()) { String k = it.next(); org.json.JSONObject e = lo.getJSONObject(k); tot += e.getInt("total"); got += Math.min(e.getInt("total"), collectedOf(Integer.parseInt(k), e.getJSONArray("ids"), masks)); }
                boolean seen = (sv[3405 + i] & 0xFF) != 0;
                String nm = LOC[i];
                ui().text(c, nm + " " + got + "/" + tot, gx, gy + 14, 2, !seen ? C_DIM : got >= tot ? 0xFFFFD34D : C_TEXT, false);
            }
        } catch (Throwable t) { android.util.Log.e("MinaPort", "areas: " + t); }
    }

    private boolean drawWorld(Canvas c, int W, int H, float ts) {
        if (!worldTried) loadWorld();
        if (worldBmp == null || (mode != 1 && mode != 0)) return false;
        int bw = worldBmp.getWidth(), bh = worldBmp.getHeight();
        float top = barH(ts), foot = ts * 1.6f;
        int sc = (int) Math.max(1, Math.floor(Math.min((W - 28f) / bw, (H - top - foot - 28f) / bh)));
        float ox = (int) ((W - bw * sc) / 2f), oy = (int) (top + (H - top - foot - bh * sc) / 2f);
        p.setStyle(Paint.Style.FILL); p.setColor(C_BG); c.drawRect(0, 0, W, H, p);
        android.graphics.Paint q = new android.graphics.Paint(); q.setFilterBitmap(false); q.setAntiAlias(false);
        c.drawBitmap(worldBmp, null, new RectF(ox, oy, ox + bw * sc, oy + bh * sc), q);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4); p.setColor(C_EDGE); c.drawRect(ox - 3, oy - 3, ox + bw * sc + 3, oy + bh * sc + 3, p); p.setStyle(Paint.Style.FILL);
        int loc = (haveFlags && flags[7] != 0) ? (flags[6] & 0xFF) - 1 : -1;
        for (int i = 0; i < LOC.length; i++) {
            if (i == loc) continue;
            float[] a = locs.get(LOC[i]); if (a == null) continue;
            float mx = ox + (bw / 2f + a[0]) * sc, my = oy + (bh / 2f - a[1]) * sc, r = Math.max(3, sc * 2f);
            boolean disc = haveFlags && i + 8 < flags.length && flags[8 + i] != 0;
            p.setStyle(Paint.Style.FILL); p.setColor(disc ? C_TEAL : 0xAA10161F);
            c.drawRect(Math.round(mx - r), Math.round(my - r), Math.round(mx + r), Math.round(my + r), p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(disc ? 0xFFFFFFFF : 0xFF5C6B7A);
            c.drawRect(Math.round(mx - r), Math.round(my - r), Math.round(mx + r), Math.round(my + r), p); p.setStyle(Paint.Style.FILL);
        }
        String name = (loc >= 0 && loc < LOC.length) ? LOC[loc] : null;
        float[] a = name == null ? null : locs.get(name);
        if (a != null) {
            float mx = ox + (bw / 2f + a[0]) * sc, my = oy + (bh / 2f - a[1]) * sc;
            drawMarker(c, mx, my, Math.max(5, sc * 3.2f));
            tagLoc = loc; drawTag(c, pretty(name), mx + sc * 6, my - sc * 3, ts, W);
        }
        drawBar(c, W, ts, pretty(curAreaName()), name == null ? null : pretty(name));
        drawFooter(c, W, H, ts, "World map  \u2022  tap for next view");
        return true;
    }

    // ---- room map: crop of the baked level class map around the player (runtime units * 10 = authoring px) ----
    private static class Lv { String file; int ox, oy, w, h; android.graphics.BitmapRegionDecoder dec; }
    private final java.util.HashMap<String, Lv> lvIndex = new java.util.HashMap<>();
    private boolean lvTried = false;
    private Bitmap cropBmp; private android.graphics.Rect cropRect; private Object cropOwner;
    private String levelsStr = ""; private long levelsAt = 0;
    private void loadLevelIndex() {
        lvTried = true;
        try {
            java.io.File d = new java.io.File(getContext().getFilesDir(), "mapassets/levels");
            java.io.FileInputStream in = new java.io.FileInputStream(new java.io.File(d, "index.json"));
            byte[] b = new byte[in.available()]; in.read(b); in.close();
            org.json.JSONObject jo = new org.json.JSONObject(new String(b, "UTF-8"));
            java.util.Iterator<String> it = jo.keys();
            while (it.hasNext()) {
                String k = it.next(); org.json.JSONObject o = jo.getJSONObject(k); Lv v = new Lv();
                v.file = o.getString("file"); v.ox = o.getInt("origin_wx"); v.oy = o.getInt("origin_wy");
                v.w = o.getInt("width_px"); v.h = o.getInt("height_px"); lvIndex.put(k, v);
            }
        } catch (Throwable t) { android.util.Log.e("MinaPort", "levelindex: " + t); }
    }
    private Lv pickLevel(float rx, float ry) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - levelsAt > 1000) { try { levelsStr = MinaActivity.nativeLevels(); } catch (Throwable t) { levelsStr = ""; } levelsAt = now; }
        float ax = rx * 10f, ay = ry * 10f;
        Lv best = null; float bestD = 1e30f;
        for (String n : levelsStr.split(";")) {
            String key = n.replace(".tlb.yc", "");
            Lv v = lvIndex.get(key); if (v == null) continue;
            if (ax >= v.ox && ax < v.ox + v.w && ay <= v.oy && ay > v.oy - v.h) {
                float cx = v.ox + v.w / 2f, cy = v.oy - v.h / 2f, dd = (ax - cx) * (ax - cx) + (ay - cy) * (ay - cy);
                if (dd < bestD) { bestD = dd; best = v; }
            }
        }
        return best;
    }
    private boolean drawRoom(Canvas c, int W, int H, float ts) {
        if (mode != 0) return false;
        if (!lvTried) loadLevelIndex();
        Lv v = pickLevel(cur[0], cur[1]);
        if (v == null) return false;
        try {
            if (v.dec == null) {
                java.io.File cf = new java.io.File(getContext().getFilesDir(), "mapassets/levels/" + v.file + "_color.png");
                java.io.File pf = new java.io.File(getContext().getFilesDir(), "mapassets/levels/" + v.file + "_preview.png");
                v.dec = android.graphics.BitmapRegionDecoder.newInstance((cf.exists() ? cf : pf).getPath(), false);
            }
            int px = Math.round(cur[0] * 10f) - v.ox, py = Math.round(v.oy - cur[1] * 10f);
            float top = barH(ts), foot = ts * 1.6f;
            int sc = 3;
            int cw = (int) ((W - 4) / sc), ch = (int) ((H - top - foot - 4) / sc);
            int l = Math.max(0, Math.min(v.w - cw, px - cw / 2)), t = Math.max(0, Math.min(v.h - ch, py - ch / 2));
            android.graphics.Rect src = new android.graphics.Rect(l, t, Math.min(v.w, l + cw), Math.min(v.h, t + ch));
            if (cropBmp == null || cropOwner != v || !cropRect.contains(src)) {
                android.graphics.Rect win = new android.graphics.Rect(Math.max(0, src.left - 200), Math.max(0, src.top - 200), Math.min(v.w, src.right + 200), Math.min(v.h, src.bottom + 200));
                cropBmp = v.dec.decodeRegion(win, new android.graphics.BitmapFactory.Options());
                cropRect = win; cropOwner = v;
            }
            Bitmap bm = cropBmp; final int bx = cropRect.left, by = cropRect.top;
            p.setStyle(Paint.Style.FILL); p.setColor(C_BG); c.drawRect(0, 0, W, H, p);
            float ox = (W - src.width() * sc) / 2f, oy = top + 2;
            android.graphics.Paint dim = new android.graphics.Paint(); dim.setFilterBitmap(false);
            dim.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[] { .30f,0,0,0,0, 0,.30f,0,0,0, 0,0,.34f,0,6, 0,0,0,1,0 }));
            android.graphics.Paint lit = new android.graphics.Paint(); lit.setFilterBitmap(false);
            c.drawBitmap(bm, new android.graphics.Rect(src.left - bx, src.top - by, src.right - bx, src.bottom - by), new RectF(ox, oy, ox + src.width() * sc, oy + src.height() * sc), dim);
            java.util.HashSet<Long> s = seen.get(area);
            int ccx = (int) Math.floor(cur[0] / CELL), ccy = (int) Math.floor(cur[1] / CELL);
            java.util.ArrayList<long[]> cells = new java.util.ArrayList<>();
            if (s != null) for (long k : s) cells.add(new long[] { kx(k), ky(k) });
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) cells.add(new long[] { ccx + dx, ccy + dy });
            for (long[] ce : cells) {
                int x0 = (int) (ce[0] * 160) - v.ox, x1 = x0 + 160;
                int y1 = (int) (v.oy - ce[1] * 160), y0 = y1 - 160;
                int ix0 = Math.max(x0, src.left), ix1 = Math.min(x1, src.right), iy0 = Math.max(y0, src.top), iy1 = Math.min(y1, src.bottom);
                if (ix0 >= ix1 || iy0 >= iy1) continue;
                android.graphics.Rect sr = new android.graphics.Rect(ix0 - src.left, iy0 - src.top, ix1 - src.left, iy1 - src.top);
                RectF dr = new RectF(ox + sr.left * sc, oy + sr.top * sc, ox + sr.right * sc, oy + sr.bottom * sc);
                c.drawBitmap(bm, new android.graphics.Rect(sr.left + src.left - bx, sr.top + src.top - by, sr.right + src.left - bx, sr.bottom + src.top - by), dr, lit);
            }
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4); p.setColor(C_EDGE); c.drawRect(ox - 2, oy - 2, ox + src.width() * sc + 2, oy + src.height() * sc + 2, p); p.setStyle(Paint.Style.FILL);
            drawMarker(c, ox + (px - l) * sc, oy + (py - t) * sc, 9);
            String an0 = curAreaName(); String lvn = pretty(v.file.replace("__", "/")); drawBar(c, W, ts, an0 != null ? pretty(an0) : lvn, an0 != null ? lvn : null);
            drawFooter(c, W, H, ts, "Room map  \u2022  tap for next view");
            return true;
        } catch (Throwable t) { android.util.Log.e("MinaPort", "room: " + t); v.dec = null; return false; }
    }

    @Override protected void onDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        float ts = Math.max(20, W / 32f);
        p.setTextSize(ts);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFCCCCCC);
        if (area != 0 && drawRoom(c, W, H, ts)) return;
        if (area != 0 && drawWorld(c, W, H, ts)) return;
        if (area == 0) { c.drawText("Waiting for the game...", 20, 20 + ts, p); return; }
        if (mode == 2) { drawInventory(c, W, H, ts); return; }
        c.drawText("No map data for this area", 20, 20 + ts, p);
    }
}
