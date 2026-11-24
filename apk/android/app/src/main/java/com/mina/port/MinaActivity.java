package com.mina.port;

import org.libsdl.app.SDLActivity;
import java.io.File;

public class MinaActivity extends SDLActivity {
    @Override protected void onCreate(android.os.Bundle b) {
        try { copyAssets("conf", new File(getFilesDir(), "conf")); } catch (Exception e) { android.util.Log.e("MinaPort", "assets: " + e); }
        super.onCreate(b);
        try { startMapPanel(); } catch (Throwable t) { android.util.Log.e("MinaPort", "mappanel: " + t); }
    }

    static native int nativeState(float[] out);
    static native int nativeMapFlags(byte[] out);
    static native String nativeLevels();

    private android.app.Presentation panel;
    private void startMapPanel() {
        android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        android.view.Display[] ds = dm.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (ds.length == 0) { android.util.Log.e("MinaPort", "mappanel: no second display"); return; }
        final MapView mv = new MapView(this);
        panel = new android.app.Presentation(this, ds[0]);
        panel.setContentView(mv);
        panel.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        panel.show();
        android.util.Log.e("MinaPort", "mappanel: shown on display " + ds[0].getDisplayId());
    }

    private void copyAssets(String path, File dst) throws java.io.IOException {
        String[] kids = getAssets().list(path);
        if (kids != null && kids.length > 0) {
            dst.mkdirs();
            for (String k : kids) copyAssets(path + "/" + k, new File(dst, k));
        } else {
            dst.getParentFile().mkdirs();
            try (java.io.InputStream in = getAssets().open(path); java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }

    private String dataDir() {
        File internal = new File(getFilesDir(), "gdroot");
        return internal.isDirectory() ? internal.getAbsolutePath() : "/sdcard/Mina";
    }

    @Override protected String[] getLibraries() { return new String[] { "SDL2", "main" }; }

    // argv[1] = native lib dir, argv[2] = app files dir, argv[3] = game data dir
    @Override protected String[] getArguments() {
        File files = getFilesDir();
        return new String[] {
            getApplicationInfo().nativeLibraryDir,
            files.getAbsolutePath(),
            dataDir()
        };
    }
}
