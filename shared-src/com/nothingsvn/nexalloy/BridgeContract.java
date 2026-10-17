package com.nothingsvn.nexalloy;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Bundle;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.zip.ZipFile;

/** APK trust and IPC contract shared by the ROM loader and settings host. */
public final class BridgeContract {
    public static final String MODULE = "io.github.chsbuffer.revancedxposed";
    public static final Uri URI = Uri.parse("content://settings");
    public static final String SIGNER = "41b88671a623ad293d06fb1e3e396aaf22cafba8948e3cdec028dc95f2f8e8ba";
    public static final int API = 102;
    public static final String PREFIX = "nt.nexalloy.";
    public static Bundle call(Context context, String method, String arg, Bundle extras) {
        return context.getContentResolver().call(URI, PREFIX + method, arg, extras);
    }
    private BridgeContract() {}

    public static boolean trusted(PackageInfo info) throws Exception {
        if (info == null || !MODULE.equals(info.packageName) || info.signingInfo == null) return false;
        if (info.signingInfo.hasMultipleSigners()) return false;
        // Accept a properly rotated signing lineage anchored in the release key.
        for (Signature signer : info.signingInfo.getSigningCertificateHistory()) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b & 255));
            if (SIGNER.equals(hex.toString())) return true;
        }
        return false;
    }

    public static Bundle inspect(Context context) throws Exception {
        PackageInfo pkg = context.getPackageManager().getPackageInfo(MODULE,
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (!trusted(pkg)) throw new SecurityException("Untrusted NexAlloy APK signing certificate");
        return metadata(pkg, pkg.applicationInfo);
    }

    public static Bundle inspectArchive(Context context, Bundle offered) throws Exception {
        ApplicationInfo app = offered.getParcelable("applicationInfo");
        if (app == null || app.sourceDir == null) throw new SecurityException("No module archive");
        PackageInfo pkg = context.getPackageManager().getPackageArchiveInfo(app.sourceDir,
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (!trusted(pkg)) throw new SecurityException("Untrusted NexAlloy module archive");
        Bundle result = metadata(pkg, app);
        ArrayList<String> enabled = offered.getStringArrayList("enabledScope");
        if (enabled == null) throw new SecurityException("Missing module scope");
        enabled = new ArrayList<>(enabled);
        enabled.retainAll(result.getStringArrayList("scope"));
        result.putStringArrayList("enabledScope", enabled);
        return result;
    }

    private static Bundle metadata(PackageInfo pkg, ApplicationInfo app) throws Exception {
        if (app == null || app.sourceDir == null) throw new IllegalStateException("No module APK");
        Bundle result = new Bundle();
        try (ZipFile apk = new ZipFile(app.sourceDir)) {
            Properties properties = new Properties();
            properties.load(apk.getInputStream(apk.getEntry("META-INF/xposed/module.prop")));
            int min = Integer.parseInt(properties.getProperty("minApiVersion"));
            int target = Integer.parseInt(properties.getProperty("targetApiVersion"));
            if (min < 101 || min > API || target > API) throw new IllegalStateException("Unsupported module API " + min + "/" + target);
            result.putStringArrayList("entries", lines(apk, "META-INF/xposed/java_init.list"));
            result.putStringArrayList("scope", lines(apk, "META-INF/xposed/scope.list"));
            result.putInt("moduleApi", target);
        }
        result.putParcelable("applicationInfo", app);
        result.putLong("versionCode", pkg.getLongVersionCode());
        result.putString("versionName", pkg.versionName);
        result.putString("apk", app.sourceDir);
        return result;
    }

    private static ArrayList<String> lines(ZipFile zip, String name) throws Exception {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                zip.getInputStream(zip.getEntry(name)), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) result.add(line);
                if (result.size() > 256 || line.length() > 256) throw new IllegalArgumentException("Oversized module metadata");
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Empty module metadata " + name);
        return new ArrayList<>(result);
    }
}
