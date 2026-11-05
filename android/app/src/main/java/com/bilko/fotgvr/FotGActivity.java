package com.bilko.fotgvr;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import org.libsdl.app.SDLActivity;

/**
 * Fate of the Galaxy launcher (a FreeSpace Open total conversion; FreeSpace 2 is not needed). The FreeSpace Open engine runs in
 * libfs2_open_*.so (android_main); this class supplies the library list,
 * the data folder (queried by the engine through getWorkingFolder) and the
 * command line.
 */
public class FotGActivity extends SDLActivity {
    private static final String TAG = "FotGVR";
    private static final int STORAGE_REQUEST = 1;

    /** Folder that contains the FotG_R mod folder (the .vp files from Knossos/Nebula). */
    public static final String GAME_DATA_DIR = "/sdcard/FotG/";
    /** Nebula mod id = folder name, passed as -mod. */
    public static final String MOD = "FotG_R";
    static final String DEFAULT_GFX_ARGS = "-no_deferred -soft_particles"; // deferred lighting: ~46 fps in the cockpit, forward: 72

    /* Called by FSO (osapi.cpp, os_android_get_working_folder_path). */
    public static String getWorkingFolder() { return GAME_DATA_DIR; }

    /* Touch overlay hooks expected by FSO; the Quest has no touch overlay. */
    public static void enableOverlay() { }
    public static void disableOverlay() { }
    public static void setOverlayOpacity(float opacity) { }

    /**
     * Called by the engine (dialogs.cpp, Error / Assert) on its own thread right before it exits:
     * an SDL message box can't be seen inside VR, so show the error on a 2D panel that runs in its
     * own process (ErrorActivity) and survives this one.
     */
    public static void showFatalError(String text) {
        Log.e(TAG, "fatal engine error: " + text);
        Context ctx = SDLActivity.getContext();
        if (ctx == null) return;
        Intent i = new Intent(ctx, ErrorActivity.class);
        i.putExtra(ErrorActivity.EXTRA_TEXT, text);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Log.w(TAG, "all-files access missing; asking the user to grant it");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception e2) {
                    // some headset OS builds have no such page: fall back to the app's settings
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e3) {
                        Log.e(TAG, "could not open the all-files access settings", e3);
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            // Android 10 (PICO OS 5): no all-files access, ask for storage instead.
            Log.w(TAG, "storage permission missing; asking the user to grant it");
            requestPermissions(new String[] {
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE }, STORAGE_REQUEST);
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != STORAGE_REQUEST)
            return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // The engine already looked for its data without access: start over.
            Log.i(TAG, "storage permission granted, restarting");
            Intent restart = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (restart != null) {
                restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                PendingIntent pending = PendingIntent.getActivity(this, 0, restart,
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                AlarmManager alarm = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 500, pending);
            }
            android.os.Process.killProcess(android.os.Process.myPid());
        } else {
            Log.w(TAG, "storage permission denied");
        }
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", BuildConfigLib.ENGINE };
    }

    @Override
    protected String getMainFunction() {
        return "android_main";
    }

    @Override
    protected String[] getArguments() {
        Intent i = getIntent();
        java.util.ArrayList<String> args = new java.util.ArrayList<>(java.util.Arrays.asList("-mod", MOD, "-vr"));
        // engine flags (Nebula mod cmdline minus -aa and -deferred_cockpit, plus -no_deferred); --es fsoGfx "..." replaces them for testing
        String gfx = (i != null) ? i.getStringExtra("fsoGfx") : null;
        if (gfx == null)
            gfx = DEFAULT_GFX_ARGS;
        if (!gfx.trim().isEmpty())
            args.addAll(java.util.Arrays.asList(gfx.trim().split("\\s+")));
        // Extra engine arguments for testing, e.g.
        //   adb shell am start -n com.bilko.fotgvr/.FotGActivity --es fsoArgs "-pilot Bilko -start_mission <mission>.fs2"
        String extra = (i != null) ? i.getStringExtra("fsoArgs") : null;
        if (extra != null && !extra.trim().isEmpty()) {
            args.addAll(java.util.Arrays.asList(extra.trim().split("\\s+")));
        }
        Log.i(TAG, "engine arguments: " + args);
        return args.toArray(new String[0]);
    }
}
