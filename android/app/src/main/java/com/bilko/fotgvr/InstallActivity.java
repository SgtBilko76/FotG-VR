package com.bilko.fotgvr;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "One-Tap Installer": 2D panel shown instead of the game while the Fate of the Galaxy data is missing:
 * asks for file access, then downloads the data from FSNebula and unpacks it into .vp archives
 * (DataInstaller) in /sdcard/FotG/FotG_R, and starts the game when it is done.
 */
public class InstallActivity extends Activity {
    private static final String TAG = "FotGInstall";

    // The download keeps running while the panel is closed or recreated.
    private static DataInstaller sInstaller;
    private static PowerManager.WakeLock sWakeLock;
    private static WifiManager.WifiLock sWifiLock;

    private TextView mBody, mDetail;
    private ProgressBar mBar;
    private Button mButton;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mLastNet = -1, mLastTime;
    private double mSpeed;
    private boolean mStarted;

    static File gameRoot() { return new File(FotGActivity.GAME_DATA_DIR); }
    static File modDir() { return new File(gameRoot(), FotGActivity.MOD); }
    static File stateDir() { return new File(gameRoot(), ".install"); }

    private static final int STORAGE_REQUEST = 1;
    private static boolean sHasLegacyAccess;

    static boolean hasFileAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return sHasLegacyAccess; // Android 10 (PICO OS 5): checked in refresh()
    }

    /** True while the data is missing, damaged, or an install was started and not finished. */
    static boolean needsInstall(Context ctx) {
        if (!hasFileAccess()) return true;
        if (sInstaller != null && sInstaller.isRunning()) return true;
        if (stateDir().exists() || !hasGameData()) return true;
        Damage d = checkData(ctx);
        String desc = d.describe();
        if (!d.isEmpty() && !desc.equals(sLastDamage)) Log.w(TAG, "game data damaged: " + desc);
        sLastDamage = desc;
        return !d.isEmpty();
    }

    private static String sLastDamage = "";

    /** What checkData found: installer archives to fetch again, and other .vp files that are broken. */
    static final class Damage {
        final List<DataInstaller.Archive> repair = new ArrayList<>();
        final List<DataInstaller.Archive> intact = new ArrayList<>();
        final List<String> foreign = new ArrayList<>();
        boolean isEmpty() { return repair.isEmpty() && foreign.isEmpty(); }
        String describe() {
            StringBuilder sb = new StringBuilder();
            for (DataInstaller.Archive a : repair) sb.append(sb.length() > 0 ? ", " : "").append(stem(a));
            for (String f : foreign) sb.append(sb.length() > 0 ? ", " : "").append(f);
            return sb.toString();
        }
    }

    static String stem(DataInstaller.Archive a) {
        return a.name.endsWith(".7z") ? a.name.substring(0, a.name.length() - 3) : a.name;
    }

    /** A .vp is whole when its directory (at diroffset, 44 bytes per entry) ends exactly at the end of the file. */
    static boolean vpIntact(File f) {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            long len = r.length();
            if (len < 16) return false;
            byte[] h = new byte[16];
            r.readFully(h);
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(0) != 0x50565056) return false; // "VPVP"
            long dirOff = b.getInt(8) & 0xffffffffL, entries = b.getInt(12) & 0xffffffffL;
            return dirOff >= 16 && dirOff + 44 * entries == len;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks the .vp archives in the mod folder: every installer archive (fotg_FG_*.vp, made by
     * this installer) must be there, whole and not smaller than installed; other .vp files (made by
     * get_fotg_data.py on a PC) must be whole. Loose files (a Knossos copy) can't be checked here;
     * if they are incomplete, the engine's error ends on the error panel (ErrorActivity).
     */
    static Damage checkData(Context ctx) {
        Damage d = new Damage();
        File[] files = modDir().listFiles((dir, n) -> n.toLowerCase(Locale.ROOT).endsWith(".vp"));
        if (files == null || files.length == 0) return d;
        List<DataInstaller.Archive> archives;
        try {
            archives = loadArchives(ctx);
        } catch (Exception e) {
            Log.e(TAG, "cannot read the archive list", e);
            return d;
        }
        boolean installerSet = false;
        for (File f : files)
            if (f.getName().startsWith(DataInstaller.PREFIX + "FG_")) installerSet = true;
        java.util.Set<String> claimed = new java.util.HashSet<>();
        for (DataInstaller.Archive a : archives) {
            String base = DataInstaller.PREFIX + stem(a);
            long sum = 0;
            boolean whole = true;
            int parts = 0;
            for (File f : files) {
                String n = f.getName();
                boolean mine = n.equals(base + ".vp")
                        || (n.startsWith(base + "_") && n.endsWith(".vp")
                            && n.substring(base.length() + 1, n.length() - 3).matches("\\d+"));
                if (!mine) continue;
                claimed.add(n);
                parts++;
                sum += f.length();
                if (!vpIntact(f)) whole = false;
            }
            if (!installerSet) continue;
            if (parts == 0 || !whole || sum < a.installed) d.repair.add(a);
            else d.intact.add(a);
        }
        for (File f : files)
            if (!claimed.contains(f.getName()) && !vpIntact(f)) d.foreign.add(f.getName());
        return d;
    }

    /** Game data present: the installer's / install script's .vp archives, or loose files (Knossos copy). */
    static boolean hasGameData() {
        if (new File(modDir(), "data/tables/fotg-anim.tbm").isFile()) return true;
        File[] vps = modDir().listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".vp"));
        return vps != null && vps.length > 0;
    }

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            refresh();
            mHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (32 * dp);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("One-Tap Installer");
        subtitle.setTextSize(20);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle);

        mBody = new TextView(this);
        mBody.setTextSize(18);
        mBody.setPadding(0, (int) (16 * dp), 0, (int) (16 * dp));
        root.addView(mBody, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mBar.setMax(1000);
        root.addView(mBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (24 * dp)));

        mDetail = new TextView(this);
        mDetail.setTextSize(16);
        mDetail.setPadding(0, (int) (12 * dp), 0, (int) (24 * dp));
        root.addView(mDetail, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mButton = new Button(this);
        mButton.setTextSize(20);
        int bp = (int) (16 * dp);
        mButton.setPadding(bp * 2, bp, bp * 2, bp);
        root.addView(mButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
    }

    static List<DataInstaller.Archive> loadArchives(Context ctx) throws Exception {
        try (InputStream in = ctx.getAssets().open("fotg_data.json")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            JSONArray arr = new JSONObject(bo.toString("UTF-8")).getJSONArray("archives");
            List<DataInstaller.Archive> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                DataInstaller.Archive a = new DataInstaller.Archive();
                a.name = o.getString("name");
                a.size = o.getLong("size");
                a.sha256 = o.getString("sha256");
                a.unpacked = o.getLong("unpacked");
                a.installed = o.optLong("installed", 0);
                a.urls = new ArrayList<>();
                JSONArray u = o.getJSONArray("urls");
                for (int j = 0; j < u.length(); j++) a.urls.add(u.getString(j));
                list.add(a);
            }
            return list;
        }
    }

    private static String gb(long bytes) {
        return String.format(Locale.US, "%.1f GB", bytes / 1e9);
    }

    private void refresh() {
        if (mStarted) return;
        DataInstaller inst = sInstaller;
        if (inst != null && inst.isRunning()) {
            showProgress(inst);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            sHasLegacyAccess = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            if (!sHasLegacyAccess) {
                mBody.setText("To store the game data in /sdcard/FotG, this app needs access to the headset's storage.");
                mBar.setVisibility(ProgressBar.INVISIBLE);
                mDetail.setText("");
                setButton("Allow storage access", new Runnable() {
                    @Override public void run() {
                        requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE }, STORAGE_REQUEST);
                    }
                });
                return;
            }
        }
        if (!hasFileAccess()) {
            mBody.setText("To store the game data in /sdcard/FotG, this app needs access to all files.\n\n"
                    + "Press the button, switch on \"Allow access\", then press Done.");
            mBar.setVisibility(ProgressBar.INVISIBLE);
            mDetail.setText("");
            setButton("Allow file access", new Runnable() {
                @Override public void run() {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                }
            });
            return;
        }
        if (mDeleting) {
            mBody.setText("Deleting the old game data ...");
            mBar.setVisibility(ProgressBar.INVISIBLE);
            mDetail.setText("");
            mButton.setVisibility(Button.GONE);
            return;
        }
        if ((inst != null && inst.phase == DataInstaller.DONE) || !needsInstall(this)) {
            startGame();
            return;
        }

        List<DataInstaller.Archive> archives;
        try {
            archives = loadArchives(this);
        } catch (Exception e) {
            mBody.setText("Internal error: " + e);
            return;
        }
        boolean resume = stateDir().exists();
        Damage damage = (resume || (inst != null && inst.phase == DataInstaller.FAILED)) ? new Damage() : checkData(this);
        if (!damage.foreign.isEmpty()) {
            // a broken archive that this installer did not make: start over with a fresh download
            mBody.setText("Some of the game data in /sdcard/FotG/FotG_R is damaged (" + damage.describe()
                    + "), probably from a copy that did not finish.\n\n"
                    + "Press the button to delete the game data and download it again (needs Wi-Fi). "
                    + "Your pilot and saved progress are kept.");
            mBar.setVisibility(ProgressBar.INVISIBLE);
            mDetail.setText("");
            setButton("Delete game data and download it again", new Runnable() {
                @Override public void run() { deleteGameData(); }
            });
            return;
        }
        final boolean repair = !damage.repair.isEmpty();
        final List<DataInstaller.Archive> intact = damage.intact;
        List<DataInstaller.Archive> todo = repair ? damage.repair : archives;
        long total = 0, installed = 0;
        for (DataInstaller.Archive a : todo) { total += a.size; installed += a.installed > 0 ? a.installed : a.unpacked; }
        long need = DataInstaller.spaceNeeded(todo, stateDir());
        long free = new StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes();
        for (DataInstaller.Archive a : damage.repair) free += existingSize(a); // replaced by the repair

        StringBuilder sb = new StringBuilder();
        if (repair) {
            sb.append("Part of the game data is missing or damaged (").append(damage.describe())
              .append("), for example from an installation or copy that did not finish. The game can't start without it.\n\n")
              .append("Press Repair to download just these parts again: ").append(gb(total))
              .append(". Keep the headset on and connected to Wi-Fi until the game starts.\n\n");
        } else if (inst != null && inst.phase == DataInstaller.FAILED) {
            sb.append("The installation stopped: ").append(inst.error).append("\n\n")
              .append("Check the Wi-Fi connection and press Retry. It continues where it stopped.\n\n");
        } else if (resume) {
            sb.append("The game data installation was interrupted. Press Continue to finish it.\n\n");
        } else {
            sb.append("Fate of the Galaxy is a free fan game. Its game data is not on this headset yet.\n\n")
              .append("It is downloaded from the official FreeSpace mod repository (FSNebula): ")
              .append(gb(total)).append(" to download, ").append(gb(installed))
              .append(" on the headset when installed. Downloading and unpacking take about half an hour.\n\n")
              .append("Keep the headset on and connected to Wi-Fi until it is finished. ")
              .append("If it gets interrupted, start the app again and it continues where it stopped.\n\n");
        }
        sb.append("Free space: ").append(gb(free)).append(", needed: ").append(gb(need)).append('.');
        mBody.setText(sb.toString());
        mBar.setVisibility(ProgressBar.INVISIBLE);
        mDetail.setText("");
        if (free < need + 200_000_000L) {
            mDetail.setText("Not enough free space. Delete some apps or videos, then start this app again.");
            mButton.setVisibility(Button.GONE);
            return;
        }
        String label = repair ? "Repair (" + gb(total) + ")"
                : (inst != null && inst.phase == DataInstaller.FAILED) ? "Retry"
                : resume ? "Continue installation" : "Download and install (" + gb(total) + ")";
        final List<DataInstaller.Archive> list = archives;
        setButton(label, new Runnable() {
            @Override public void run() {
                if (repair) markDone(intact); // an interrupted repair then resumes without them
                startInstall(list);
            }
        });
    }

    private static long existingSize(DataInstaller.Archive a) {
        long sum = 0;
        File[] files = modDir().listFiles((d, n) -> n.startsWith(DataInstaller.PREFIX + stem(a) + ".vp")
                || n.startsWith(DataInstaller.PREFIX + stem(a) + "_"));
        if (files != null) for (File f : files) sum += f.length();
        return sum;
    }

    /** Pre-marks archives as installed in the state folder so DataInstaller skips them. */
    private static void markDone(List<DataInstaller.Archive> intact) {
        File st = stateDir();
        st.mkdirs();
        for (DataInstaller.Archive a : intact) {
            try {
                new File(st, a.name + ".done").createNewFile();
            } catch (java.io.IOException e) {
                Log.w(TAG, "cannot mark " + a.name, e);
            }
        }
    }

    private static volatile boolean mDeleting;

    private void deleteGameData() {
        if (mDeleting) return;
        mDeleting = true;
        refresh();
        new Thread(new Runnable() {
            @Override public void run() {
                Log.i(TAG, "deleting " + modDir());
                deleteTree(modDir());
                mDeleting = false;
            }
        }, "FotGDelete").start();
    }

    static void deleteTree(File f) {
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private void setButton(String label, final Runnable action) {
        mButton.setVisibility(Button.VISIBLE);
        mButton.setEnabled(true);
        mButton.setText(label);
        mButton.setOnClickListener(v -> action.run());
    }

    private void showProgress(DataInstaller inst) {
        mButton.setVisibility(Button.GONE);
        mBar.setVisibility(ProgressBar.VISIBLE);
        long total = Math.max(inst.totalBytes, 1);
        mBar.setProgress((int) (1000 * inst.doneBytes / total));

        long now = System.currentTimeMillis();
        long net = inst.downloaded;
        if (mLastNet >= 0 && now > mLastTime) {
            double s = (net - mLastNet) * 1000.0 / (now - mLastTime);
            mSpeed = mSpeed == 0 ? s : mSpeed * 0.8 + s * 0.2;
        }
        mLastNet = net;
        mLastTime = now;

        String what;
        switch (inst.phase) {
            case DataInstaller.DOWNLOADING:
                what = String.format(Locale.US, "Downloading %s: %d of %d MB (%.1f MB/s)", inst.current,
                        inst.stepDone >> 20, inst.stepTotal >> 20, mSpeed / (1 << 20));
                break;
            case DataInstaller.VERIFYING:
                what = String.format(Locale.US, "Checking %s: %d%%", inst.current,
                        inst.stepTotal > 0 ? 100 * inst.stepDone / inst.stepTotal : 0);
                break;
            default:
                what = String.format(Locale.US, "Unpacking %s: %d%%", inst.current,
                        inst.stepTotal > 0 ? 100 * inst.stepDone / inst.stepTotal : 0);
                break;
        }
        mBody.setText(String.format(Locale.US, "Installing the game data: %d%%\n\nPart %d of %d. Keep the headset on and connected to Wi-Fi.",
                100 * inst.doneBytes / total, inst.index, inst.count));
        mDetail.setText(what);
    }

    private void startInstall(List<DataInstaller.Archive> archives) {
        if (sInstaller != null && sInstaller.isRunning()) return;
        Log.i(TAG, "starting the game data installation");
        final DataInstaller inst = new DataInstaller(archives, modDir(), stateDir());
        inst.phase = DataInstaller.DOWNLOADING;
        sInstaller = inst;
        mLastNet = -1;
        mSpeed = 0;
        acquireLocks(getApplicationContext());
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    inst.run();
                } finally {
                    Log.i(TAG, "installation ended: phase " + inst.phase + (inst.error != null ? ", " + inst.error : ""));
                    releaseLocks();
                }
            }
        }, "FotGInstall");
        t.start();
        refresh();
    }

    private static synchronized void acquireLocks(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            sWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fotgvr:install");
            sWakeLock.acquire();
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            sWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fotgvr:install");
            sWifiLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "could not take the wake/wifi lock", e);
        }
    }

    private static synchronized void releaseLocks() {
        try {
            if (sWakeLock != null && sWakeLock.isHeld()) sWakeLock.release();
            if (sWifiLock != null && sWifiLock.isHeld()) sWifiLock.release();
        } catch (Exception ignored) {
        }
        sWakeLock = null;
        sWifiLock = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    private void startGame() {
        if (mStarted) return;
        mStarted = true;
        Log.i(TAG, "game data ready; starting the game");
        Intent i = new Intent(this, FotGActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }
}
