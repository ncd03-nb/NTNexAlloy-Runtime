package com.nothingsvn.nexalloy;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Seeds the unmodified module once into /data/app, respecting later uninstalls. */
public final class UserAppInstaller {
    private static final String TAG = "NTOriginalModule";
    private static final String ENTRY = "ntmanager/NexAlloyXES.apk";
    private UserAppInstaller() {}

    static void start(Context context) {
        new Thread(() -> {
            long identity = Binder.clearCallingIdentity();
            try {
                SharedPreferences state = context.getSharedPreferences("original-module-install", 0);
                if (state.getBoolean("seeded", false)) return;
                // Wait outside provider/Binder startup and never delay Android boot.
                Class<?> props = Class.forName("android.os.SystemProperties");
                for (int attempt = 0; attempt < 300; attempt++) {
                    if ("1".equals(props.getMethod("get", String.class).invoke(null, "sys.boot_completed"))) {
                        seed(context, state);
                        return;
                    }
                    Thread.sleep(1000);
                }
            } catch (Throwable error) { Log.w(TAG, "User app seed deferred until next boot", error); }
            finally { Binder.restoreCallingIdentity(identity); }
        }, "nexalloy-user-app-seed").start();
    }

    private static void seed(Context context, SharedPreferences state) throws Exception {
        try {
            // A retained record also means the user may already have uninstalled it.
            context.getPackageManager().getPackageInfo(BridgeContract.MODULE,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES);
            if (!state.edit().putBoolean("seeded", true).commit())
                throw new IllegalStateException("Cannot remember existing user app");
            return;
        } catch (PackageManager.NameNotFoundException firstBoot) { /* No previous installation. */ }
        File apk = File.createTempFile("nexalloy-seed-", ".apk", context.getCacheDir());
        try {
            try (ZipFile framework = new ZipFile("/system/framework/framework.jar")) {
                ZipEntry entry = framework.getEntry(ENTRY);
                if (entry == null) return; // Older ROM, or toolbox not included.
                if (entry.getSize() < 1 || entry.getSize() > 100L * 1024 * 1024)
                    throw new SecurityException("Invalid seed APK size");
                try (InputStream in = framework.getInputStream(entry); FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    out.getFD().sync();
                }
                if (apk.length() != entry.getSize()) throw new SecurityException("Incomplete seed APK");
            }
            if (!BridgeContract.trusted(context.getPackageManager().getPackageArchiveInfo(apk.getPath(),
                    PackageManager.GET_SIGNING_CERTIFICATES)))
                throw new SecurityException("Untrusted seed APK");
            install(context, state, apk);
        } finally { apk.delete(); }
    }

    private static void install(Context context, SharedPreferences state, File apk) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(BridgeContract.MODULE);
        params.setSize(apk.length());
        params.setInstallReason(PackageManager.INSTALL_REASON_DEVICE_SETUP);
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = installer.createSession(params);
        String action = "com.nothingsvn.nexalloy.SEED." + UUID.randomUUID();
        CountDownLatch finished = new CountDownLatch(1);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent result) {
                int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    Log.i(TAG, "User app installation session finished");
                } else Log.w(TAG, "User app seed status=" + status + ": " + result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                finished.countDown();
            }
        };
        context.registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        // SettingsProvider is hosted by system_server; its receiver belongs to the host op-package.
        PendingIntent reply = PendingIntent.getBroadcast(context, id, new Intent(action).setPackage(context.getOpPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new java.io.FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                session.fsync(out);
            }
            session.commit(reply.getIntentSender());
            // Verify PM state as well: some vendor broadcast filters suppress installer replies.
            for (int attempt = 0; attempt < 120; attempt++) {
                boolean replied = finished.await(1, TimeUnit.SECONDS);
                try {
                    android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(
                            BridgeContract.MODULE, PackageManager.GET_SIGNING_CERTIFICATES);
                    if (BridgeContract.trusted(info) && (info.applicationInfo.flags &
                            android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0) {
                        if (!state.edit().putBoolean("seeded", true).commit())
                            throw new IllegalStateException("Cannot persist user app seed result");
                        Log.i(TAG, "Original NexAlloy installed as user app");
                        return;
                    }
                } catch (PackageManager.NameNotFoundException pending) { /* Installation still pending. */ }
                if (replied) break;
            }
            Log.w(TAG, "User app seed did not complete; will retry next boot");
        } catch (Exception error) {
            try { installer.abandonSession(id); } catch (Exception ignored) {}
            throw error;
        } finally {
            reply.cancel();
            context.unregisterReceiver(receiver);
        }
    }
}
