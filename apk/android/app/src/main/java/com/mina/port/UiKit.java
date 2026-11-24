package com.mina.port;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;

/** Draws the game's own UI assets (bitmap font, location plates, marker) extracted from the user's game data. */
final class UiKit {
    static final String[] PLATE = { "AstralOrrery", "BayouA", "BayouB", "BoneBeachA", "BoneBeachB", "CryptA", "CryptB",
        "OWEast", "FrozenTrainyard", "IntroBeach", "MansionA", "MansionB", "Ossex", "SeptemburgA", "SeptemburgB", "OWSouth", "OWWest" };

    private static final class Font {
        Bitmap atlas; JSONObject glyphs; float line;
    }

    private final File dir;
    private final Font[] fonts = new Font[2];
    private final HashMap<String, Bitmap> plates = new HashMap<>();
    private Bitmap marker;
    private final Paint bp = new Paint();
    private final Paint tint = new Paint();
    boolean ok = false;

    UiKit(Context ctx) {
        dir = new File(ctx.getFilesDir(), "mapassets/ui");
        bp.setFilterBitmap(false); bp.setAntiAlias(false);
        tint.setFilterBitmap(false); tint.setAntiAlias(false);
        try {
            fonts[0] = loadFont("Gothic8px-Regular");
            fonts[1] = loadFont("Gothic8px-Bold");
            marker = BitmapFactory.decodeFile(new File(dir, "marker.png").getPath());
            ok = fonts[0] != null && fonts[1] != null;
        } catch (Throwable t) { android.util.Log.e("MinaPort", "uikit: " + t); }
    }

    private Font loadFont(String n) throws Exception {
        File jf = new File(dir, "fonts/" + n + ".json");
        if (!jf.exists()) return null;
        byte[] b = new byte[(int) jf.length()];
        FileInputStream in = new FileInputStream(jf); int o = 0; while (o < b.length) { int r = in.read(b, o, b.length - o); if (r < 0) break; o += r; } in.close();
        JSONObject j = new JSONObject(new String(b, "UTF-8"));
        Font f = new Font();
        f.glyphs = j.getJSONObject("glyphs");
        f.line = (float) j.getDouble("lineHeight");
        f.atlas = BitmapFactory.decodeFile(new File(dir, "fonts/" + n + ".png").getPath());
        return f.atlas == null ? null : f;
    }

    float width(String s, float sc, boolean bold) {
        Font f = fonts[bold ? 1 : 0]; if (f == null) return 0;
        float x = 0;
        for (int i = 0; i < s.length(); i++) {
            JSONObject g = f.glyphs.optJSONObject(Integer.toString(s.charAt(i)));
            if (g == null) g = f.glyphs.optJSONObject("63");
            if (g != null) x += (float) g.optDouble("adv", 4) * sc;
        }
        return x;
    }

    /** Draw text with its baseline at y; sc = integer pixel scale. */
    void text(Canvas c, String s, float x, float y, float sc, int color, boolean bold) {
        Font f = fonts[bold ? 1 : 0]; if (f == null) return;
        tint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        float px = x;
        Rect src = new Rect(); RectF dst = new RectF();
        for (int i = 0; i < s.length(); i++) {
            JSONObject g = f.glyphs.optJSONObject(Integer.toString(s.charAt(i)));
            if (g == null) g = f.glyphs.optJSONObject("63");
            if (g == null) continue;
            int gx = g.optInt("x"), gy = g.optInt("y"), gw = g.optInt("w"), gh = g.optInt("h");
            float xo = (float) g.optDouble("xoff", 0), top = (float) g.optDouble("top", 0), adv = (float) g.optDouble("adv", 4);
            src.set(gx, gy, gx + gw, gy + gh);
            float dx = px + xo * sc, dy = y - top * sc;
            dst.set(Math.round(dx), Math.round(dy), Math.round(dx) + gw * sc, Math.round(dy) + gh * sc);
            c.drawBitmap(f.atlas, src, dst, tint);
            px += adv * sc;
        }
    }

    private Bitmap plate(String name) {
        Bitmap b = plates.get(name);
        if (b == null && !plates.containsKey(name)) {
            b = BitmapFactory.decodeFile(new File(dir, "plates/locationPlate_" + name + ".png").getPath());
            plates.put(name, b);
        }
        return b;
    }

    /** 9-slice the 64x24 plate (8 px corners/edges) into r, scaled by sc. */
    boolean plateBox(Canvas c, int locIdx, RectF r, float sc) {
        Bitmap b = plate(locIdx >= 0 && locIdx < PLATE.length ? PLATE[locIdx] : "default");
        if (b == null) b = plate("default");
        if (b == null) return false;
        int w = b.getWidth(), h = b.getHeight(), m = 8;
        float cm = m * sc;
        float[] xs = { r.left, r.left + cm, r.right - cm, r.right }, ys = { r.top, r.top + cm, r.bottom - cm, r.bottom };
        int[] sx = { 0, m, w - m, w }, sy = { 0, m, h - m, h };
        for (int j = 0; j < 3; j++) for (int i = 0; i < 3; i++)
            c.drawBitmap(b, new Rect(sx[i], sy[j], sx[i + 1], sy[j + 1]), new RectF(xs[i], ys[j], xs[i + 1], ys[j + 1]), bp);
        return true;
    }

    private final HashMap<String, Bitmap> icons = new HashMap<>();
    Bitmap icon(String n) {
        Bitmap b = icons.get(n);
        if (b == null && !icons.containsKey(n)) { b = BitmapFactory.decodeFile(new File(dir, "inv/" + n).getPath()); icons.put(n, b); }
        return b;
    }
    private JSONObject invj; private boolean invTried;
    JSONObject inv() {
        if (!invTried) {
            invTried = true;
            try {
                File jf = new File(dir, "inv/inv.json"); byte[] b = new byte[(int) jf.length()];
                FileInputStream in = new FileInputStream(jf); int o = 0; while (o < b.length) { int r = in.read(b, o, b.length - o); if (r < 0) break; o += r; } in.close();
                invj = new JSONObject(new String(b, "UTF-8"));
            } catch (Throwable t) { android.util.Log.e("MinaPort", "inv.json: " + t); }
        }
        return invj;
    }
    /** Draw an icon scaled by an integer factor, optionally dimmed. */
    void drawIcon(Canvas c, Bitmap b, float x, float y, int sc, boolean dim) {
        if (b == null) return;
        Paint q = dim ? tint : bp;
        if (dim) q.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[] { .2f,0,0,0,0, 0,.2f,0,0,0, 0,0,.25f,0,0, 0,0,0,.7f,0 }));
        c.drawBitmap(b, null, new RectF(x, y, x + b.getWidth() * sc, y + b.getHeight() * sc), q);
        if (dim) q.setColorFilter(null);
    }

    /** The game's Mina marker, bottom-centre anchored at (x, y). */
    boolean marker(Canvas c, float x, float y, float sc) {
        if (marker == null) return false;
        float w = marker.getWidth() * sc, h = marker.getHeight() * sc;
        c.drawBitmap(marker, null, new RectF(Math.round(x - w / 2), Math.round(y - h), Math.round(x + w / 2), Math.round(y)), bp);
        return true;
    }
}
