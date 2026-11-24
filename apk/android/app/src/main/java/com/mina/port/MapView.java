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
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() { public void run() { poll(); h.postDelayed(this, 100); } };
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
        h.removeCallbacks(tick); h.removeCallbacks(saver);
        h.post(tick); h.postDelayed(saver, SAVE_MS);
    }

    @Override protected void onDetachedFromWindow() {
        h.removeCallbacks(tick); h.removeCallbacks(saver);
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
    private boolean drawWorld(Canvas c, int W, int H, float ts) {
        if (!worldTried) loadWorld();
        if (worldBmp == null || (mode != 1 && mode != 0)) return false;
        int bw = worldBmp.getWidth(), bh = worldBmp.getHeight();
        float top = ts * 1.4f;
        int sc = (int) Math.max(1, Math.floor(Math.min((W - 10f) / bw, (H - top - 6f) / bh)));
        float ox = (int) ((W - bw * sc) / 2f), oy = (int) (top + (H - top - bh * sc) / 2f);
        android.graphics.Paint q = new android.graphics.Paint(); q.setFilterBitmap(false); q.setAntiAlias(false);
        c.drawBitmap(worldBmp, null, new RectF(ox, oy, ox + bw * sc, oy + bh * sc), q);
        int loc = (haveFlags && flags[7] != 0) ? (flags[6] & 0xFF) - 1 : -1;
        String name = (loc >= 0 && loc < LOC.length) ? LOC[loc] : null;
        float[] a = name == null ? null : locs.get(name);
        if (a != null) {
            float mx = ox + (bw / 2f + a[0]) * sc, my = oy + (bh / 2f - a[1]) * sc;
            float r = Math.max(4, sc * 4f);
            p.setStyle(Paint.Style.FILL); p.setColor(0xFFFF3030);
            c.drawRect(Math.round(mx - r), Math.round(my - r), Math.round(mx + r), Math.round(my + r), p);
            p.setColor(0xFFFFFFFF); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2);
            c.drawRect(Math.round(mx - r), Math.round(my - r), Math.round(mx + r), Math.round(my + r), p);
            p.setStyle(Paint.Style.FILL);
        }
        p.setColor(0xFFCCCCCC);
        String an = areaNames.get(haveFlags ? ((flags[0] & 0xFF) | ((flags[1] & 0xFF) << 8) | ((flags[2] & 0xFF) << 16) | ((flags[3] & 0xFF) << 24)) : area); c.drawText((an == null ? "?" : an) + "  →  " + (name == null ? "?" : name) + " (" + loc + ")  – tryck för spårvy", 10, ts, p);
        return true;
    }


    // ---- room map: crop of the baked level class map around the player (runtime units * 10 = authoring px) ----
    private static class Lv { String file; int ox, oy, w, h; android.graphics.BitmapRegionDecoder dec; }
    private final java.util.HashMap<String, Lv> lvIndex = new java.util.HashMap<>();
    private boolean lvTried = false;
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
            if (v.dec == null) v.dec = android.graphics.BitmapRegionDecoder.newInstance(
                new java.io.File(getContext().getFilesDir(), "mapassets/levels/" + v.file + "_preview.png").getPath(), false);
            int px = Math.round(cur[0] * 10f) - v.ox, py = Math.round(v.oy - cur[1] * 10f);
            float top = ts * 1.4f;
            int sc = 3;
            int cw = (int) ((W - 4) / sc), ch = (int) ((H - top - 4) / sc);
            int l = Math.max(0, Math.min(v.w - cw, px - cw / 2)), t = Math.max(0, Math.min(v.h - ch, py - ch / 2));
            android.graphics.Rect src = new android.graphics.Rect(l, t, Math.min(v.w, l + cw), Math.min(v.h, t + ch));
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            Bitmap bm = v.dec.decodeRegion(src, o);
            android.graphics.Paint q = new android.graphics.Paint(); q.setFilterBitmap(false); q.setAntiAlias(false);
            float ox = (W - src.width() * sc) / 2f, oy = top + 2;
            c.drawBitmap(bm, null, new RectF(ox, oy, ox + src.width() * sc, oy + src.height() * sc), q);
            float mx = ox + (px - l) * sc, my = oy + (py - t) * sc, r = 8;
            p.setStyle(Paint.Style.FILL); p.setColor(0xFFFF3030);
            c.drawRect(Math.round(mx - r), Math.round(my - r), Math.round(mx + r), Math.round(my + r), p);
            p.setColor(0xFFFFFFFF); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2);
            c.drawRect(Math.round(mx - r), Math.round(my - r), Math.round(mx + r), Math.round(my + r), p);
            p.setStyle(Paint.Style.FILL); p.setColor(0xFFCCCCCC);
            c.drawText(v.file.replace("levels__", "").replace("__", "/") + "   x " + Math.round(cur[0]) + "  y " + Math.round(cur[1]) + "   – tryck: världskarta", 10, ts, p);
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
        if (area == 0) { c.drawText("Väntar på spelet...", 20, 20 + ts, p); return; }

        HashSet<Long> s = seen.get(area);
        int minx = Integer.MAX_VALUE, maxx = Integer.MIN_VALUE, miny = Integer.MAX_VALUE, maxy = Integer.MIN_VALUE;
        if (s != null) for (long k : s) {
            int x = kx(k), y = ky(k);
            if (x < minx) minx = x; if (x > maxx) maxx = x;
            if (y < miny) miny = y; if (y > maxy) maxy = y;
        }
        int ccx = (int) Math.floor(cur[0] / CELL), ccy = (int) Math.floor(cur[1] / CELL);
        if (s == null || s.isEmpty()) { minx = maxx = ccx; miny = maxy = ccy; }
        minx -= 3; miny -= 3; maxx += 3; maxy += 3;
        int cw = maxx - minx + 1, ch = maxy - miny + 1;

        float top = ts * 2.6f, bottom = ts * 1.8f;   // header and legend space
        float availW = W - 20, availH = H - top - bottom;
        int cs = (int) Math.max(2, Math.min(24, Math.floor(Math.min(availW / cw, availH / ch)))); // integer px per cell
        float ox = (int) ((W - cw * cs) / 2f), oy = (int) (top + (availH - ch * cs) / 2f);

        if (bg != null && bgRect != null) {
            float l = ox + (bgRect.left / CELL - minx) * cs, r = ox + (bgRect.right / CELL - minx) * cs;
            float t = oy + (maxy + 1 - bgRect.top / CELL) * cs, b = oy + (maxy + 1 - bgRect.bottom / CELL) * cs;
            c.drawBitmap(bg, null, new RectF(Math.round(l), Math.round(Math.min(t, b)), Math.round(r), Math.round(Math.max(t, b))), p);
        }

        // faint grid frame
        p.setColor(0xFF15202A);
        c.drawRect(ox, oy, ox + cw * cs, oy + ch * cs, p);
        p.setColor(0xFF2A6F8F);
        if (s != null) for (long k : s) {
            float x = ox + (kx(k) - minx) * cs, y = oy + (maxy - ky(k)) * cs;
            c.drawRect(x, y, x + Math.max(1, cs - 1), y + Math.max(1, cs - 1), p);
        }
        // player
        p.setColor(0xFFFF3030);
        float px = ox + (cur[0] / CELL - minx) * cs, py = oy + (maxy + 1 - cur[1] / CELL) * cs;
        float r = Math.max(3, cs * 0.6f);
        c.drawRect(Math.round(px - r), Math.round(py - r), Math.round(px + r), Math.round(py + r), p);

        // header
        p.setColor(0xFFCCCCCC);
        c.drawText(String.format("Område %08x", area), 20, 10 + ts, p);
        c.drawText(String.format("x %.0f  y %.0f  celler %d", cur[0], cur[1], s == null ? 0 : s.size()), 20, 10 + ts * 2.1f, p);
        if (haveFlags) {
            String m = String.format("karta:%s  upptäckt %d  avslöjat %d", (flags[4] != 0) ? "ja" : "nej",
                    popcnt(flags, 8, 17), popcnt(flags, 28, 18));
            c.drawText(m, W - p.measureText(m) - 20, 10 + ts, p);
        }

        // legend
        float ly = H - ts * 0.6f, lx = 20, sq = ts * 0.8f;
        p.setColor(0xFF2A6F8F); c.drawRect(lx, ly - sq, lx + sq, ly, p);
        p.setColor(0xFFCCCCCC); c.drawText("besökt", lx + sq + 8, ly, p);
        lx += sq + 8 + p.measureText("besökt") + 30;
        p.setColor(0xFFFF3030); c.drawRect(lx, ly - sq, lx + sq, ly, p);
        p.setColor(0xFFCCCCCC); c.drawText("spelare", lx + sq + 8, ly, p);
        lx += sq + 8 + p.measureText("spelare") + 30;
        c.drawText("1 ruta = 16 enheter", lx, ly, p);
    }
}
