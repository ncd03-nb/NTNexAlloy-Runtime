/*
 * Copyright (C) 2026 NothingsVN contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */
package android.security.ntmanager;

import android.app.Application;
import android.content.Context;
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
 * Loads the pinned NexAlloy XES payload directly from framework.jar.
 *
 * <p>The payload and native libraries are supplied by the ROM toolbuild under the
 * {@code ntmanager/} archive directory. This class deliberately contains no LSPosed or root
 * dependency: it creates the payload class loader and invokes the module entry point inside each
 * supported application process.</p>
 */
public final class NexAlloyRuntime {
    private static final String TAG = "NTNexAlloy";
    private static final String FRAMEWORK = "/system/framework/framework.jar";
    private static final String PAYLOAD_ENTRY = "ntmanager/NTNexAlloy.apk";
    private static final String PAYLOAD_VERSION = "2.0.110-b61974f-compat2";
    private static final String RUNTIME_DIR = "ntnexalloy-" + PAYLOAD_VERSION;
    private static final String APP_SETTINGS_PREFIX = "nt_nexalloy_app_";

    private static final Set<String> SUPPORTED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.alltrails.alltrails",
            "com.facebook.katana",
            "com.facebook.orca",
            "com.google.android.gm",
            "com.google.android.googlequicksearchbox",
            "com.instagram.android",
            "com.microblink.photomath",
            "ch.protonvpn.android",
            "com.reddit.frontpage",
            "com.soundcloud.android",
            "com.strava",
            "com.instagram.barcelona",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.twitter.android",
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.zing.zalo"
    ));
    private static final Set<String> INITIALIZED = new HashSet<>();

    private NexAlloyRuntime() {
    }

    public static void init(Context context) {
        if (context == null) {
            return;
        }

        String packageName = context.getPackageName();
        if (packageName == null || !SUPPORTED_PACKAGES.contains(packageName)) {
            return;
        }
        if (Settings.Global.getInt(
                context.getContentResolver(), APP_SETTINGS_PREFIX + packageName, 1) == 0) {
            return;
        }

        String processName = currentProcessName();
        if (processName != null && !packageName.equals(processName)) {
            status(context, "skip secondary process " + processName, null);
            return;
        }

        status(context, "init requested for " + packageName, null);
        synchronized (INITIALIZED) {
            if (!INITIALIZED.add(packageName)) {
                return;
            }
        }

        try {
            File runtimeDir = new File(context.getCacheDir(), RUNTIME_DIR);
            if (!runtimeDir.exists() && !runtimeDir.mkdirs()) {
                throw new IllegalStateException("Cannot create runtime directory");
            }

            File payload = extract(runtimeDir, PAYLOAD_ENTRY, "NTNexAlloy.apk");
            String abi = is64Bit() ? "arm64-v8a" : "armeabi-v7a";
            File cxx = extract(
                    runtimeDir, "ntmanager/" + abi + "/libc++_shared.so", "libc++_shared.so");
            File lsplant = extract(
                    runtimeDir, "ntmanager/" + abi + "/liblsplant.so", "liblsplant.so");
            File aliuhook = extract(
                    runtimeDir, "ntmanager/" + abi + "/libaliuhook.so", "libaliuhook.so");
            extract(runtimeDir, "ntmanager/" + abi + "/libdexkit.so", "libdexkit.so");
            status(context, "payload extracted abi=" + abi, null);

            ClassLoader appClassLoader = context.getClassLoader();
            ClassLoader payloadClassLoader = newDelegateLastClassLoader(
                    payload.getAbsolutePath(), runtimeDir.getAbsolutePath(), appClassLoader);
            loadNative(payloadClassLoader, cxx, lsplant, aliuhook);
            status(context, "native hook engine loaded by payload classloader", null);
            status(context, "payload loader=" + payloadClassLoader.getClass().getName(), null);

            Class<?> mainHookClass = Class.forName(
                    "io.github.nexalloy.MainHook", true, payloadClassLoader);
            Object mainHook = mainHookClass.getDeclaredConstructor().newInstance();
            status(context, "MainHook created", null);

            Class<?> startupParamClass = Class.forName(
                    "de.robv.android.xposed.IXposedHookZygoteInit$StartupParam",
                    true,
                    payloadClassLoader);
            Object startupParam = allocateInstance(startupParamClass);
            setField(startupParam, "modulePath", payload.getAbsolutePath());
            setField(startupParam, "startsSystemServer", Boolean.FALSE);
            invokeSingleArg(
                    mainHookClass, mainHook, "initZygote", startupParamClass, startupParam);
            status(context, "initZygote completed", null);

            Class<?> loadPackageParamClass = Class.forName(
                    "de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam",
                    true,
                    payloadClassLoader);
            Object loadPackageParam = allocateInstance(loadPackageParamClass);
            setField(loadPackageParam, "appInfo", context.getApplicationInfo());
            setField(loadPackageParam, "classLoader", appClassLoader);
            setField(loadPackageParam, "isFirstApplication", Boolean.TRUE);
            setField(loadPackageParam, "packageName", packageName);
            setField(loadPackageParam, "processName", processName == null ? packageName : processName);
            status(context, "LoadPackageParam created from payload loader", null);
            invokeSingleArg(
                    mainHookClass,
                    mainHook,
                    "handleLoadPackage",
                    loadPackageParamClass,
                    loadPackageParam);
            status(context, "load-package callback registered", null);
        } catch (Throwable error) {
            synchronized (INITIALIZED) {
                INITIALIZED.remove(packageName);
            }
            status(context, "runtime attach failed for " + packageName, unwrap(error));
        }
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
        String expected = expectedDigest(entryName);
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
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : hash.digest()) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }

    // Pinned with metadata/payload-manifest.sha256; never mix a new runtime with old assets.
    private static String expectedDigest(String entry) {
        switch (entry) {
            case "ntmanager/NTNexAlloy.apk": return "8f55fe5c979132b8849b9d5530951a7d50ab2febd82c916948bbafe9e0b41e64";
            case "ntmanager/arm64-v8a/libc++_shared.so": return "b5d97c38a728afe28d341cda3369f512d35eaf9c680c1b0563cd6a63e395230f";
            case "ntmanager/arm64-v8a/liblsplant.so": return "53eff56530dbd91cdfdee42d5c7b91e6479ef088b7a5906d6abf3e61e1055a7a";
            case "ntmanager/arm64-v8a/libaliuhook.so": return "61c62a44b2b05154ba7d1545c93e1a66bf875c1f7f82fbdba87b8d033d09176c";
            case "ntmanager/arm64-v8a/libdexkit.so": return "4b9dc6cca00a8342d1be2ee445c7a0c243451130d73ca29c08b2eda2efca8ae8";
            case "ntmanager/armeabi-v7a/libc++_shared.so": return "38331a1efcb446eb1a0db440a8db8f41fa4fb698d15c061ab8c491426531c2c0";
            case "ntmanager/armeabi-v7a/liblsplant.so": return "fc965c4d2de9137e49796aa4278b0281df54694e9b0f12c82b1899909b2508cd";
            case "ntmanager/armeabi-v7a/libaliuhook.so": return "fc545ee9c7c1b367e936973139da3db2b290e6e767fef332ca7bb0619982ef05";
            case "ntmanager/armeabi-v7a/libdexkit.so": return "ec2de16dab436a1f2c7545b975abee63134597e076884f0ebe442c43e58dd253";
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
