package com.mina.port;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * First-run setup. The APK contains no game data. This activity checks whether the user's own copy of
 * "Mina the Hollower.app" (plus the generated shader folders) is present under files/gdroot/gamedata and,
 * if not, lets the user pick a folder (Storage Access Framework, or a search of /sdcard) and copies it in.
 * It can also import a PC saveData.yc. The UI is built in code (no layout resources).
 */
public class SetupActivity extends Activity {
    static final String APP = "Mina the Hollower.app";
    static final String EXE = "Contents/MacOS/MinaTheHollower";
    static final String RES = "Contents/Resources/data";
    static final int REQ_TREE = 1, REQ_SAVE = 2, REQ_PERM = 3;
    static final int C_BG = 0xFF0E0E16, C_FG = 0xFFE8E0C8, C_DIM = 0xFF8C8A9A, C_ACC = 0xFFE0405A, C_BTN = 0xFF24243A;

    // ---------------------------------------------------------------- tiny file abstraction (File or SAF)
    interface Node {
        String name();
        boolean isDir();
        long size();
        List<Node> children() throws IOException;
        InputStream open() throws IOException;
    }

    static class FileNode implements Node {
        final File f;
        FileNode(File f) { this.f = f; }
        public String name() { return f.getName(); }
        public boolean isDir() { return f.isDirectory(); }
        public long size() { return f.length(); }
        public List<Node> children() throws IOException {
            List<Node> r = new ArrayList<>();
            File[] k = f.listFiles();
            if (k == null) return r;
            for (File c : k) {
                try { if (Files.isSymbolicLink(c.toPath())) continue; } catch (Exception ignored) { }
                r.add(new FileNode(c));
            }
            return r;
        }
        public InputStream open() throws IOException { return new java.io.FileInputStream(f); }
    }

    static class DocNode implements Node {
        final Context ctx; final Uri tree; final String id, name; final boolean dir; final long size;
        DocNode(Context c, Uri t, String id, String name, boolean dir, long size) {
            ctx = c; tree = t; this.id = id; this.name = name; this.dir = dir; this.size = size;
        }
        static DocNode root(Context c, Uri tree) {
            String id = DocumentsContract.getTreeDocumentId(tree);
            String nm = id;
            Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, id);
            try (Cursor q = c.getContentResolver().query(u, new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
                if (q != null && q.moveToFirst() && q.getString(0) != null) nm = q.getString(0);
            } catch (Exception ignored) { }
            return new DocNode(c, tree, id, nm, true, 0);
        }
        public String name() { return name; }
        public boolean isDir() { return dir; }
        public long size() { return size; }
        public List<Node> children() throws IOException {
            List<Node> r = new ArrayList<>();
            Uri u = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
            String[] cols = { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE };
            try (Cursor q = ctx.getContentResolver().query(u, cols, null, null, null)) {
                if (q == null) throw new IOException("query failed");
                while (q.moveToNext()) {
                    boolean d = DocumentsContract.Document.MIME_TYPE_DIR.equals(q.getString(2));
                    long sz = q.isNull(3) ? -1 : q.getLong(3);
                    r.add(new DocNode(ctx, tree, q.getString(0), q.getString(1), d, sz));
                }
            }
            return r;
        }
        public InputStream open() throws IOException {
            InputStream in = ctx.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id));
            if (in == null) throw new IOException("cannot open " + name);
            return in;
        }
    }

    // ---------------------------------------------------------------- state
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy, cancel;
    private volatile long doneBytes, totalBytes;
    private volatile int doneFiles, totalFiles;
    private volatile String current = "";
    private TextView status, progressText, intro;
    private BlockBar bar;
    private View bPick, bSd, bSave, bStart, bCancel;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!busy) return;
            bar.set(totalBytes > 0 ? (float) doneBytes / totalBytes : 0f);
            progressText.setText(fmt(doneBytes) + " / " + fmt(totalBytes) + "   (" + doneFiles + "/" + totalFiles + ")\n" + current);
            ui.postDelayed(this, 200);
        }
    };

    private String t(String sv, String en) { return en; }  // English only

    // ---------------------------------------------------------------- data checks (mirrors MinaActivity.dataDir)
    private File internalGamedata() { return new File(new File(getFilesDir(), "gdroot"), "gamedata"); }

    static boolean nonEmptyDir(File d) { String[] l = d.list(); return d.isDirectory() && l != null && l.length > 0; }

    static boolean dataOk(File gamedata) {
        File app = new File(gamedata, APP);
        File exe = new File(app, EXE);
        return exe.isFile() && exe.length() > 0 && nonEmptyDir(new File(app, RES))
                && nonEmptyDir(new File(gamedata, "shaders_gles")) && nonEmptyDir(new File(gamedata, "shaders_spv"));
    }

    // Lenient check used for auto-launch: data pushed by the adb deploy flow must never force the setup screen.
    private boolean exePresent() {
        File gdroot = new File(getFilesDir(), "gdroot");
        File gd = gdroot.isDirectory() ? internalGamedata() : new File("/sdcard/Mina/gamedata");
        File exe = new File(new File(gd, APP), EXE);
        return exe.isFile() && exe.length() > 0;
    }

    private boolean isReady() {
        File gdroot = new File(getFilesDir(), "gdroot");
        if (gdroot.isDirectory()) return dataOk(internalGamedata());
        return dataOk(new File("/sdcard/Mina/gamedata"));
    }

    // ---------------------------------------------------------------- UI
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (exePresent() && !getIntent().getBooleanExtra("setup", false)) { launchGame(); return; }
        buildUi();
        showIdle();
    }

    private void launchGame() {
        startActivity(new Intent(this, MinaActivity.class));
        finish();
    }

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s); tv.setTextSize(sp); tv.setTextColor(color);
        tv.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        tv.setPadding(0, dp(4), 0, dp(4));
        return tv;
    }

    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill); g.setStroke(dp(2), stroke); g.setCornerRadius(0);
        return g;
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView tv = text(label, 15, C_FG, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[] { android.R.attr.state_focused }, box(C_ACC, C_FG));
        sl.addState(new int[] { android.R.attr.state_pressed }, box(C_ACC, C_FG));
        sl.addState(new int[] {}, box(C_BTN, C_DIM));
        tv.setBackground(sl);
        tv.setFocusable(true); tv.setClickable(true);
        tv.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(16), dp(24), dp(16));
        col.addView(text("MINA THE HOLLOWER", 22, C_ACC, true));
        col.addView(text(t("INSTALLATION AV SPELDATA", "GAME DATA SETUP"), 13, C_DIM, true));
        intro = text("", 13, C_FG, false);
        col.addView(intro);
        status = text("", 13, C_ACC, true);
        col.addView(status);
        bar = new BlockBar(this);
        bar.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(22)));
        col.addView(bar);
        progressText = text("", 12, C_DIM, false);
        col.addView(progressText);

        bPick = button(t("VÄLJ MAPP...", "PICK FOLDER..."), v -> pickTree());
        bSd = button(t("SÖK I /sdcard (Mina, Download)", "SEARCH /sdcard (Mina, Download)"), v -> pickSdcard());
        bSave = button(t("IMPORTERA SPARFIL (saveData.yc)", "IMPORT SAVE (saveData.yc)"), v -> pickSave());
        bStart = button(t("STARTA SPELET", "START GAME"), v -> launchGame());
        bCancel = button(t("AVBRYT", "CANCEL"), v -> { cancel = true; });
        col.addView(bPick); col.addView(bSd); col.addView(bSave); col.addView(bStart); col.addView(bCancel);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        sv.setFillViewport(true);
        sv.addView(col);
        setContentView(sv);
        bPick.requestFocus();
    }

    private void showIdle() {
        busy = false;
        boolean ready = isReady();
        intro.setText(ready
                ? t("Speldata finns på plats. Du kan starta spelet eller importera en sparfil.",
                    "Game data is in place. You can start the game or import a save file.")
                : t("Spelet innehåller ingen speldata. Välj mappen som innehåller '" + APP + "' (en kopia av din egen Steam/Mac-installation). "
                    + "Mappen bör även innehålla shaders_gles och shaders_spv (genereras från din egen data, se README). Filerna kopieras till appens privata lagring.",
                    "This app contains no game data. Pick the folder that contains '" + APP + "' (a copy of your own Steam/Mac install). "
                    + "The folder should also hold shaders_gles and shaders_spv (generated from your own data, see README). Files are copied into the app's private storage."));
        bar.setVisibility(View.GONE); progressText.setText("");
        bPick.setVisibility(View.VISIBLE); bSd.setVisibility(View.VISIBLE); bSave.setVisibility(View.VISIBLE);
        bStart.setVisibility(ready ? View.VISIBLE : View.GONE);
        bCancel.setVisibility(View.GONE);
        (ready ? bStart : bPick).requestFocus();
    }

    private void showBusy() {
        busy = true; cancel = false; doneBytes = 0; totalBytes = 0; doneFiles = 0; totalFiles = 0; current = "";
        bar.setVisibility(View.VISIBLE); bar.set(0);
        bPick.setVisibility(View.GONE); bSd.setVisibility(View.GONE); bSave.setVisibility(View.GONE); bStart.setVisibility(View.GONE);
        bCancel.setVisibility(View.VISIBLE); bCancel.requestFocus();
        ui.post(poll);
    }

    private void done(final String msg) {
        ui.post(() -> {
            busy = false;
            showIdle();
            status.setText(msg);
        });
    }

    private void setStatus(final String s) { ui.post(() -> status.setText(s)); }

    static String fmt(long b) {
        if (b < 1024) return b + " B";
        double v = b / 1024.0; String[] u = { "KiB", "MiB", "GiB", "TiB" }; int i = 0;
        while (v >= 1024 && i < 3) { v /= 1024; i++; }
        return String.format(Locale.US, "%.1f %s", v, u[i]);
    }

    /** Segmented "pixel" progress bar. */
    static class BlockBar extends View {
        private float frac;
        private final Paint p = new Paint();
        BlockBar(Context c) { super(c); }
        void set(float f) { frac = Math.max(0, Math.min(1, f)); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            int n = 40, w = getWidth(), h = getHeight();
            float cell = (float) w / n;
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < n; i++) {
                p.setColor(i < Math.round(frac * n) ? C_ACC : C_BTN);
                c.drawRect(i * cell + 2, 2, (i + 1) * cell - 2, h - 2, p);
            }
        }
    }

    // ---------------------------------------------------------------- picking
    private void pickTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try { startActivityForResult(i, REQ_TREE); }
        catch (Exception e) { status.setText(t("Ingen mappväljare tillgänglig: ", "No folder picker available: ") + e.getMessage()); }
    }

    private void pickSave() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { startActivityForResult(i, REQ_SAVE); }
        catch (Exception e) { status.setText(t("Ingen filväljare tillgänglig: ", "No file picker available: ") + e.getMessage()); }
    }

    private void pickSdcard() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { android.Manifest.permission.READ_EXTERNAL_STORAGE }, REQ_PERM);
            return;
        }
        status.setText("");
        showBusy();
        current = "/sdcard ...";
        new Thread(() -> {
            try {
                File[] cands = { new File("/sdcard/Mina"), new File("/sdcard/Download"), new File("/sdcard") };
                int[] depth = { 3, 3, 1 };
                for (int k = 0; k < cands.length; k++) {
                    if (!cands[k].isDirectory()) continue;
                    Node root = new FileNode(cands[k]);
                    if (findApp(root, depth[k]) != null) { runImport(root); return; }
                }
                done(t("Hittade '" + APP + "' inte under /sdcard/Mina, /sdcard/Download eller /sdcard. Använd VÄLJ MAPP.",
                        "Could not find '" + APP + "' under /sdcard/Mina, /sdcard/Download or /sdcard. Use PICK FOLDER."));
            } catch (Throwable e) {
                done(t("Fel: ", "Error: ") + e);
            }
        }, "mina-setup").start();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_PERM) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) pickSdcard();
            else status.setText(t("Åtkomst till lagring nekades. Använd VÄLJ MAPP istället.", "Storage permission denied. Use PICK FOLDER instead."));
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        if (req == REQ_TREE) {
            status.setText("");
            showBusy();
            new Thread(() -> {
                try { runImport(DocNode.root(this, u)); }
                catch (Throwable e) { done(t("Fel: ", "Error: ") + e); }
            }, "mina-setup").start();
        } else if (req == REQ_SAVE) {
            status.setText("");
            showBusy();
            new Thread(() -> importSave(u), "mina-save").start();
        }
    }

    @Override public void onBackPressed() {
        if (busy) { cancel = true; return; }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- import
    private static class Item {
        final Node n; final File dest; final long size;
        Item(Node n, File dest, long size) { this.n = n; this.dest = dest; this.size = size; }
    }

    private static class Cancelled extends IOException { }

    /** Breadth-first search for the .app directory; returns {app, parent} (parent may be null). */
    private Node[] findApp(Node root, int maxDepth) throws IOException {
        if (root.isDir() && root.name().equalsIgnoreCase(APP)) return new Node[] { root, null };
        ArrayDeque<Object[]> q = new ArrayDeque<>();
        q.add(new Object[] { root, 0 });
        while (!q.isEmpty()) {
            if (cancel) throw new Cancelled();
            Object[] e = q.poll();
            Node d = (Node) e[0]; int lv = (Integer) e[1];
            for (Node c : d.children()) {
                if (!c.isDir()) continue;
                if (c.name().equalsIgnoreCase(APP)) return new Node[] { c, d };
                if (lv + 1 < maxDepth) q.add(new Object[] { c, lv + 1 });
            }
        }
        return null;
    }

    private static Node child(Node dir, String name) throws IOException {
        if (dir == null) return null;
        for (Node c : dir.children()) if (c.isDir() && c.name().equalsIgnoreCase(name)) return c;
        return null;
    }

    private void collect(Node dir, File dest, List<Item> out) throws IOException {
        if (cancel) throw new Cancelled();
        for (Node c : dir.children()) {
            if (cancel) throw new Cancelled();
            String nm = c.name();
            if (nm == null || nm.isEmpty() || nm.contains("/") || nm.equals("..")) continue;
            if (nm.equals(".DS_Store")) continue;
            File d = new File(dest, nm);
            if (c.isDir()) collect(c, d, out);
            else out.add(new Item(c, d, Math.max(0, c.size())));
        }
    }

    /** Runs on a worker thread; always ends by calling done(). */
    private void runImport(Node root) {
        try {
            setStatus(t("Söker efter " + APP + " ...", "Looking for " + APP + " ..."));
            Node[] found = findApp(root, 3);
            if (found == null) {
                done(t("'" + APP + "' hittades inte i den valda mappen (sökte tre nivåer ned).",
                        "'" + APP + "' not found in the selected folder (searched three levels deep)."));
                return;
            }
            Node app = found[0];
            File gamedata = internalGamedata();
            List<Item> items = new ArrayList<>();
            current = t("Läser mappstruktur ...", "Scanning folders ...");
            collect(app, new File(gamedata, APP), items);

            // Optional siblings: generated shaders and baked map art. Look beside the .app and in the picked folder.
            boolean haveGles = false, haveSpv = false;
            Node[] places = { found[1], root };
            for (Node place : places) {
                Node g = child(place, "shaders_gles"), s = child(place, "shaders_spv"), m = child(place, "mapassets");
                if (g != null && !haveGles) { collect(g, new File(gamedata, "shaders_gles"), items); haveGles = true; }
                if (s != null && !haveSpv) { collect(s, new File(gamedata, "shaders_spv"), items); haveSpv = true; }
                if (m != null) { collect(m, new File(getFilesDir(), "mapassets"), items); }
                if (place == root) break;
            }

            long total = 0, need = 0;
            for (Item it : items) {
                total += it.size;
                if (!(it.dest.isFile() && it.size > 0 && it.dest.length() == it.size)) need += it.size;
            }
            totalBytes = total; totalFiles = items.size();
            long free = new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes();
            if (free < need + (64L << 20)) {
                done(t("För lite ledigt utrymme: behöver " + fmt(need) + ", ledigt " + fmt(free) + ".",
                        "Not enough free space: need " + fmt(need) + ", free " + fmt(free) + "."));
                return;
            }
            setStatus(t("Kopierar ...", "Copying ..."));
            byte[] buf = new byte[1 << 20];
            for (Item it : items) {
                if (cancel) throw new Cancelled();
                current = it.dest.getName();
                if (it.size > 0 && it.dest.isFile() && it.dest.length() == it.size) {   // resume: already copied
                    doneBytes += it.size; doneFiles++;
                    continue;
                }
                File parent = it.dest.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdir " + parent);
                File part = new File(it.dest.getPath() + ".part");
                long before = doneBytes;
                try (InputStream in = it.n.open(); OutputStream out = new FileOutputStream(part)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        doneBytes += n;
                        if (cancel) throw new Cancelled();
                    }
                } catch (IOException e) {
                    part.delete();
                    doneBytes = before;
                    throw e;
                }
                if (it.size > 0 && part.length() != it.size) { part.delete(); throw new IOException("size mismatch: " + it.dest.getName()); }
                if (it.dest.exists() && !it.dest.delete()) throw new IOException("replace " + it.dest);
                if (!part.renameTo(it.dest)) throw new IOException("rename " + it.dest);
                doneFiles++;
            }

            // verify
            File exe = new File(new File(gamedata, APP), EXE);
            if (!exe.isFile() || exe.length() == 0) { done(t("Verifiering misslyckades: huvudprogrammet saknas (" + EXE + ").", "Verification failed: main executable missing (" + EXE + ").")); return; }
            if (!nonEmptyDir(new File(new File(gamedata, APP), RES))) { done(t("Verifiering misslyckades: " + RES + " saknas.", "Verification failed: " + RES + " missing.")); return; }
            if (!dataOk(gamedata)) {
                done(t("Kopierat, men shaders_gles och/eller shaders_spv saknas. De genereras från din egen data (se README) och måste ligga bredvid '" + APP + "'. Kör installationen igen med en mapp som innehåller dem.",
                        "Copied, but shaders_gles and/or shaders_spv are missing. They are generated from your own data (see README) and must sit next to '" + APP + "'. Run setup again with a folder that contains them."));
                return;
            }
            done(t("Klart: " + fmt(doneBytes) + " kopierat och verifierat.", "Done: " + fmt(doneBytes) + " copied and verified."));
        } catch (Cancelled c) {
            done(t("Avbrutet. Redan kopierade filer behålls; kör igen för att fortsätta.", "Cancelled. Already copied files are kept; run again to resume."));
        } catch (Throwable e) {
            done(t("Fel: ", "Error: ") + e);
        }
    }

    private void importSave(Uri u) {
        try {
            File dst = new File(getFilesDir(), "saveData.yc");
            File orig = new File(getFilesDir(), "saveData.yc.orig");
            File part = new File(getFilesDir(), "saveData.yc.part");
            long n = 0;
            try (InputStream in = getContentResolver().openInputStream(u); OutputStream out = new FileOutputStream(part)) {
                if (in == null) throw new IOException("cannot open file");
                byte[] buf = new byte[65536]; int r;
                while ((r = in.read(buf)) > 0) { out.write(buf, 0, r); n += r; }
            } catch (IOException e) { part.delete(); throw e; }
            if (n == 0) { part.delete(); done(t("Filen är tom.", "The file is empty.")); return; }
            if (dst.exists() && !orig.exists()) dst.renameTo(orig);   // keep the first pre-existing save as a backup
            if (dst.exists()) dst.delete();
            if (!part.renameTo(dst)) throw new IOException("rename " + dst);
            done(t("Sparfil importerad (" + fmt(n) + "). Tidigare sparfil sparad som saveData.yc.orig.", "Save imported (" + fmt(n) + "). Previous save kept as saveData.yc.orig."));
        } catch (Throwable e) {
            done(t("Fel vid import av sparfil: ", "Save import error: ") + e);
        }
    }
}
