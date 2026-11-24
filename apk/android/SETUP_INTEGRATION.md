# Making SetupActivity the launcher (not applied)

`SetupActivity.java` is a first-run screen. If the game data is already present it starts `MinaActivity`
immediately and finishes, so it is invisible on later launches. Otherwise it offers to copy the user's own
`Mina the Hollower.app` (+ `shaders_gles`, `shaders_spv`, optional `mapassets`) into
`files/gdroot/gamedata`, and to import a `saveData.yc`.

## AndroidManifest.xml

Move the LAUNCHER intent-filter from `.MinaActivity` to `.SetupActivity`:

```xml
<activity android:name=".SetupActivity" android:exported="true"
    android:screenOrientation="sensorLandscape"
    android:configChanges="orientation|screenSize|keyboard|keyboardHidden|navigation|uiMode|locale|layoutDirection">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>

<activity android:name=".MinaActivity" android:exported="true"
    android:screenOrientation="sensorLandscape" android:launchMode="singleTask"
    android:configChanges="layoutDirection|locale|orientation|uiMode|screenLayout|screenSize|smallestScreenSize|keyboard|keyboardHidden|navigation">
</activity>
```

`MinaActivity` stays exported so `adb shell am start -n com.mina.port/.MinaActivity` keeps working.

Existing permissions are enough: the SAF picker needs none, and the "search /sdcard" button asks for
READ_EXTERNAL_STORAGE at runtime (targetSdk 28).

## MinaActivity hook (optional)

No change is required: SetupActivity checks the same location `MinaActivity.dataDir()` uses
(`files/gdroot`, falling back to `/sdcard/Mina`). Optionally, guard against launching without data, at the
top of `MinaActivity.onCreate`:

```java
java.io.File exe = new java.io.File(getFilesDir(),
    "gdroot/gamedata/Mina the Hollower.app/Contents/MacOS/MinaTheHollower");
if (!exe.isFile() && !new java.io.File("/sdcard/Mina/gamedata").isDirectory()) {
    startActivity(new android.content.Intent(this, SetupActivity.class)
        .putExtra("setup", true));
    finish();
    return;
}
```

To reopen the setup screen on a device that already has data (e.g. to import a save):
`adb shell am start -n com.mina.port/.SetupActivity --ez setup true`

## Notes

- Copying is resumable: files are written as `*.part` then renamed; files with matching size are skipped.
- Needs roughly the size of the game install in free internal storage.
- "Ready" means: main executable, `Contents/Resources/data`, and non-empty `shaders_gles` and `shaders_spv`
  (they are looked for beside the `.app` or in the picked folder).
- `mapassets` beside the `.app` is copied to `files/mapassets` if present.
- Untested on a device; only compile-checked with javac against android.jar.
