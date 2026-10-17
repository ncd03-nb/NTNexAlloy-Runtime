/*
 * Copyright (C) 2026 NothingsVN contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */
package android.security.ntmanager;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads only the signed installed original NexAlloy APK using the ROM engine.
 *
 * <p>The payload and native libraries are supplied by the ROM toolbuild under the
 * {@code ntmanager/} archive directory. This class deliberately contains no LSPosed or root
 * dependency: it creates the payload class loader and invokes the module entry point inside each
 * supported application process.</p>
 */
public final class NexAlloyRuntime {
    private static final String TAG = "NTNexAlloy";
    private static final String FRAMEWORK = "/system/framework/framework.jar";
    private static final String PAYLOAD_ENTRY = "ntmanager/NTXposedEngine.dex";
    private static final String PAYLOAD_VERSION = "original-apk-1";
    private static final String RUNTIME_DIR = "ntnexalloy-" + PAYLOAD_VERSION;
    private static final String APP_SETTINGS_PREFIX = "nt_nexalloy_app_";
    private static final String ORIGINAL_MODULE = "io.github.chsbuffer.revancedxposed";
    private static final String BRIDGE_ENTRY = "ntmanager/NTModernBridge.dex";
    private static final String BRIDGE_DIGEST = "3ab1ca578f1a3f06f074ea05e1679b2a654fb5a5d3d878884c07bdc77deaaedf";

    private static Class<?> settingsService;
    private static final Set<String> INITIALIZED = new HashSet<>();

    private NexAlloyRuntime() {
    }

    /** Called only for the namespaced bridge protocol on the real SettingsProvider. */
    public static Bundle providerCall(android.content.ContentProvider provider, String authority,
            String method, String arg, Bundle extras) {
        if (!"settings".equals(authority) || method == null || !method.startsWith("nt.nexalloy.")) return null;
        Context context = provider.getContext();
        if (context == null || !"com.android.providers.settings".equals(context.getPackageName()) ||
                android.os.Process.myUid() % 100000 != 1000) return null;
        try {
            Class<?> backend;
            synchronized (NexAlloyRuntime.class) {
                if (settingsService == null) {
                    File directory = new File(context.getCodeCacheDir(), RUNTIME_DIR);
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create ROM service cache");
                    File bridge = extract(directory, BRIDGE_ENTRY, "NTModernBridge.dex");
                    ClassLoader loader = newDelegateLastClassLoader(bridge.getAbsolutePath(), null, context.getClassLoader());
                    settingsService = Class.forName("com.nothingsvn.nexalloy.NexAlloyService", true, loader);
                }
                backend = settingsService;
            }
            return (Bundle) backend.getMethod("call", Context.class, String.class, String.class, Bundle.class)
                    .invoke(null, context, method.substring("nt.nexalloy.".length()), arg, extras);
        } catch (Throwable error) {
            Throwable cause = unwrap(error);
            if (cause instanceof SecurityException) throw (SecurityException) cause;
            if (cause instanceof IllegalArgumentException) throw (IllegalArgumentException) cause;
            throw new IllegalStateException("NexAlloy ROM service failed", cause);
        }
    }

    public static void init(Context context) {
        if (context == null) return;
        String packageName = context.getPackageName();
        if (packageName == null || context.getApplicationInfo().uid % 100000 < 10000) return;
        boolean moduleUi = ORIGINAL_MODULE.equals(packageName);
        Bundle original;
        try {
            original = context.getContentResolver().call(Uri.parse("content://settings"), "nt.nexalloy.module", null, null);
        } catch (Throwable unavailable) {
            if (moduleUi) status(context, "ROM module service unavailable", unwrap(unavailable));
            return;
        }
        if (original == null) return;
        java.util.ArrayList<String> enabled = original.getStringArrayList("enabledScope");
        if (!moduleUi && (enabled == null || !enabled.contains(packageName))) return;
        String processName = currentProcessName();
        if (processName != null && !packageName.equals(processName)) return;
        synchronized (INITIALIZED) { if (!INITIALIZED.add(packageName)) return; }
        try {
            if (moduleUi) {
                // The original UI only needs the configuration Binder, not the native hook engine.
                // In particular a fresh reinstall has no engine cache yet.
                Bundle response = context.getContentResolver().call(Uri.parse("content://settings"),
                        "nt.nexalloy.service", null, null);
                if (response == null || response.getBinder("binder") == null)
                    throw new IllegalStateException("No module service binder");
                android.content.ContentProvider provider = (android.content.ContentProvider)
                        Class.forName("io.github.libxposed.service.XposedProvider", true, context.getClassLoader())
                                .getDeclaredConstructor().newInstance();
                if (provider.call("SendBinder", null, response) == null)
                    throw new IllegalStateException("Original APK rejected the service binder");
                status(context, "Original APK connected to ROM service", null);
                return;
            }
            File directory = new File(context.getCodeCacheDir(), RUNTIME_DIR);
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create engine cache");
            File engine = extract(directory, PAYLOAD_ENTRY, "NTXposedEngine.dex");
            String abi = is64Bit() ? "arm64-v8a" : "armeabi-v7a";
            File cxx = extract(directory, "ntmanager/" + abi + "/libc++_shared.so", "libc++_shared.so");
            File lsplant = extract(directory, "ntmanager/" + abi + "/liblsplant.so", "liblsplant.so");
            File aliuhook = extract(directory, "ntmanager/" + abi + "/libaliuhook.so", "libaliuhook.so");
            ClassLoader engineLoader = newDelegateLastClassLoader(engine.getAbsolutePath(), directory.getAbsolutePath(), context.getClassLoader());
            File bridge = extract(directory, BRIDGE_ENTRY, "NTModernBridge.dex");
            ClassLoader bridgeLoader = newDelegateLastClassLoader(bridge.getAbsolutePath(), directory.getAbsolutePath(), engineLoader);
            Class<?> contract = Class.forName("com.nothingsvn.nexalloy.BridgeContract", true, bridgeLoader);
            Bundle verified = (Bundle) contract.getMethod("inspectArchive", Context.class, Bundle.class).invoke(null, context, original);
            Class<?> host = Class.forName("com.nothingsvn.nexalloy.ModuleHost", true, bridgeLoader);
            File nativeDir = extractModuleNative(context, verified, abi);
            loadNative(engineLoader, cxx, lsplant, aliuhook);
            host.getMethod("attach", Context.class, Bundle.class, String.class).invoke(null, context, verified, nativeDir.getAbsolutePath());
            status(context, "original APK ready version=" + verified.getString("versionName"), null);
        } catch (Throwable error) {
            status(context, "original APK attach failed for " + packageName, unwrap(error));
            // A partial native attach must not be retried in the same process.
        }
    }

    private static File extractModuleNative(Context context, Bundle module, String abi) throws Exception {
        File apk = new File(module.getString("apk"));
        String id = digest(apk);
        File directory = new File(context.getCodeCacheDir(), "nt-original-" + id);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create module native cache");
        try (ZipFile archive = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String prefix = "lib/" + abi + "/";
                if (!entry.getName().startsWith(prefix) || !entry.getName().endsWith(".so")) continue;
                String name = entry.getName().substring(prefix.length());
                if (name.contains("/") || name.contains("\\") || name.contains("..")) throw new SecurityException("Invalid module library name");
                File target = new File(directory, name);
                if (entry.getSize() < 1 || entry.getSize() > 100L * 1024 * 1024)
                    throw new SecurityException("Invalid module library size");
                String expected;
                try (InputStream input = archive.getInputStream(entry)) { expected = digest(input); }
                if (target.isFile() && expected.equals(digest(target))) continue;
                File temporary = File.createTempFile("native-", ".tmp", directory);
                try {
                    try (InputStream input = archive.getInputStream(entry); FileOutputStream output = new FileOutputStream(temporary)) {
                        temporary.setWritable(false, false);
                        byte[] buffer = new byte[32768]; int count;
                        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                        output.getFD().sync();
                    }
                    if (temporary.length() != entry.getSize() || !expected.equals(digest(temporary)) || !temporary.renameTo(target))
                        throw new IllegalStateException("Cannot publish module library");
                    target.setReadable(true, true); target.setExecutable(true, true);
                } finally { temporary.delete(); }
            }
        }
        return directory;
    }

    private static void loadNative(
            ClassLoader classLoader, File cxx, File lsplant, File aliuhook) throws Exception {
        Class.forName("io.github.nexalloy.NTNativeLoader", true, classLoader)
                .getMethod("load", String.class, String.class, String.class)
                .invoke(
                        null,
                        cxx.getAbsolutePath(),
                        lsplant.getAbsolutePath(),
                        aliuhook.getAbsolutePath());
    }

    private static ClassLoader newDelegateLastClassLoader(
            String dexPath, String libraryPath, ClassLoader parent) throws Exception {
        return (ClassLoader) Class.forName("dalvik.system.DelegateLastClassLoader")
                .getConstructor(String.class, String.class, ClassLoader.class)
                .newInstance(dexPath, libraryPath, parent);
    }

    private static String currentProcessName() {
        try {
            return (String) Application.class.getMethod("getProcessName").invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean is64Bit() {
        try {
            Class<?> runtimeClass = Class.forName("dalvik.system.VMRuntime");
            Object runtime = runtimeClass.getMethod("getRuntime").invoke(null);
            return (Boolean) runtimeClass.getMethod("is64Bit").invoke(runtime);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static File extract(File directory, String entryName, String outputName)
            throws Exception {
        String expected = BRIDGE_ENTRY.equals(entryName) ? BRIDGE_DIGEST : expectedDigest(entryName);
        File output = new File(directory, outputName);
        if (output.isFile() && expected.equals(digest(output))) {
            return output;
        }

        File temporary = new File(directory, outputName + ".tmp");
        try (ZipFile framework = new ZipFile(FRAMEWORK)) {
            ZipEntry entry = framework.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException("Missing framework entry: " + entryName);
            }
            try (InputStream input = framework.getInputStream(entry);
                    FileOutputStream outputStream = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, count);
                }
                outputStream.getFD().sync();
            }
        }

        if (!expected.equals(digest(temporary))) {
            temporary.delete();
            throw new IllegalStateException("NexAlloy checksum mismatch: " + entryName
                    + "; check the app's KernelSU module mount profile");
        }
        temporary.setReadable(true, true);
        temporary.setExecutable(true, true);
        temporary.setWritable(false, false);
        if (!temporary.renameTo(output)) {
            throw new IllegalStateException("Cannot publish " + outputName);
        }
        output.setReadable(true, true);
        output.setExecutable(true, true);
        output.setWritable(false, false);
        return output;
    }

    private static String digest(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            return digest(input);
        }
    }

    private static String digest(InputStream input) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32 * 1024]; int count;
        while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
        StringBuilder hex = new StringBuilder(64);
        for (byte value : hash.digest()) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }

    // Pinned with metadata/payload-manifest.sha256; never mix a new runtime with old assets.
    private static String expectedDigest(String entry) {
        switch (entry) {
            case "ntmanager/NTXposedEngine.dex": return "2b488bc44a04d3e74aa841464cb6df11f9b34935c739d5bab7cec995a2c97627";
            case "ntmanager/arm64-v8a/libc++_shared.so": return "b5d97c38a728afe28d341cda3369f512d35eaf9c680c1b0563cd6a63e395230f";
            case "ntmanager/arm64-v8a/liblsplant.so": return "53eff56530dbd91cdfdee42d5c7b91e6479ef088b7a5906d6abf3e61e1055a7a";
            case "ntmanager/arm64-v8a/libaliuhook.so": return "61c62a44b2b05154ba7d1545c93e1a66bf875c1f7f82fbdba87b8d033d09176c";
            case "ntmanager/armeabi-v7a/libc++_shared.so": return "38331a1efcb446eb1a0db440a8db8f41fa4fb698d15c061ab8c491426531c2c0";
            case "ntmanager/armeabi-v7a/liblsplant.so": return "fc965c4d2de9137e49796aa4278b0281df54694e9b0f12c82b1899909b2508cd";
            case "ntmanager/armeabi-v7a/libaliuhook.so": return "fc545ee9c7c1b367e936973139da3db2b290e6e767fef332ca7bb0619982ef05";
            default: throw new IllegalArgumentException("Unpinned NexAlloy asset: " + entry);
        }
    }

    private static Object allocateInstance(Class<?> type) throws Exception {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
        } catch (Throwable ignored) {
            return newInstanceUsingConstructor(type);
        }
    }

    private static Object newInstanceUsingConstructor(Class<?> type) throws Exception {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 0) {
                constructor.setAccessible(true);
                return constructor.newInstance();
            }
            if (parameterTypes.length == 1) {
                Constructor<?> parameterConstructor = parameterTypes[0].getDeclaredConstructor();
                parameterConstructor.setAccessible(true);
                Object parameter = parameterConstructor.newInstance();
                constructor.setAccessible(true);
                return constructor.newInstance(parameter);
            }
        }
        throw new NoSuchMethodException(type.getName() + " usable constructor");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        target.getClass().getField(name).set(target, value);
    }

    private static void invokeSingleArg(
            Class<?> owner,
            Object receiver,
            String name,
            Class<?> parameterType,
            Object argument) throws Exception {
        owner.getMethod(name, parameterType).invoke(receiver, argument);
    }

    private static Throwable unwrap(Throwable error) {
        while (error.getCause() != null && error.getCause() != error) {
            error = error.getCause();
        }
        return error;
    }

    private static void status(Context context, String message, Throwable error) {
        String line = "[" + PAYLOAD_VERSION + "] " + message;
        if (error == null) {
            Log.e(TAG, line);
        } else {
            Log.e(TAG, line, error);
        }
        System.err.println(TAG + " " + line);
        if (error != null) {
            error.printStackTrace(System.err);
        }

        PrintWriter writer = null;
        try {
            File externalFiles = context.getExternalFilesDir(null);
            if (externalFiles == null) {
                return;
            }
            if (!externalFiles.exists()) {
                externalFiles.mkdirs();
            }
            writer = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(new File(externalFiles, "ntnexalloy.log"), true),
                    "UTF-8"));
            writer.println(new Date() + " " + line);
            if (error != null) {
                error.printStackTrace(writer);
            }
        } catch (Throwable ignored) {
            // Logging must never affect application startup.
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }
}
