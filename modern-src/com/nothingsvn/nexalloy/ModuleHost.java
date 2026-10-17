package com.nothingsvn.nexalloy;

import android.app.AppComponentFactory;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.io.FileNotFoundException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Bridges an unmodified, installed modern module onto the embedded native engine. */
public final class ModuleHost implements XposedInterface {
    private final Context context;
    private final ApplicationInfo moduleInfo;

    private ModuleHost(Context context, ApplicationInfo moduleInfo) {
        this.context = context;
        this.moduleInfo = moduleInfo;
    }

    public static void attach(Context context, Bundle module, String libraryPath) throws Exception {
        ApplicationInfo info = module.getParcelable("applicationInfo");
        ClassLoader loader = (ClassLoader) Class.forName("dalvik.system.DelegateLastClassLoader")
                .getConstructor(String.class, String.class, ClassLoader.class)
                .newInstance(info.sourceDir, libraryPath, ModuleHost.class.getClassLoader());
        ModuleHost host = new ModuleHost(context, info);
        for (String entry : module.getStringArrayList("entries")) {
            XposedModule instance = (XposedModule) Class.forName(entry, true, loader).getDeclaredConstructor().newInstance();
            instance.attachFramework(host, () -> {});
            instance.onModuleLoaded(new XposedModuleInterface.ModuleLoadedParam() {
                @Override public boolean isSystemServer() { return false; }
                @Override public String getProcessName() { return android.app.Application.getProcessName(); }
            });
            ApplicationInfo targetInfo = new ApplicationInfo(context.getApplicationInfo());
            if (targetInfo.className == null) targetInfo.className = android.app.Application.class.getName();
            XposedModuleInterface.PackageReadyParam ready = new XposedModuleInterface.PackageReadyParam() {
                @Override public String getPackageName() { return context.getPackageName(); }
                @Override public ApplicationInfo getApplicationInfo() { return targetInfo; }
                @Override public boolean isFirstPackage() { return true; }
                @Override public ClassLoader getDefaultClassLoader() { return context.getClassLoader(); }
                @Override public ClassLoader getClassLoader() { return context.getClassLoader(); }
                @Override public AppComponentFactory getAppComponentFactory() { return new AppComponentFactory(); }
            };
            instance.onPackageLoaded(ready);
            instance.onPackageReady(ready);
        }
        Log.e("NTNexAlloy", "ORIGINAL APK attached version=" + module.getString("versionName") + " path=" + info.sourceDir);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                java.lang.reflect.Field records = XposedBridge.class.getDeclaredField("hookRecords");
                records.setAccessible(true);
                Map<?, ?> hooks = (Map<?, ?>) records.get(null);
                StringBuilder state = new StringBuilder("apk=").append(info.sourceDir)
                        .append("\nentries=").append(module.getStringArrayList("entries"))
                        .append("\nhooks=").append(hooks.size()).append('\n');
                for (Object member : hooks.keySet()) state.append(member).append('\n');
                try (java.io.FileOutputStream output = context.openFileOutput("nt-original-hooks.txt", 0)) {
                    output.write(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                Log.i("NTNexAlloy", "Original APK active engine records=" + hooks.size());
            } catch (Throwable error) { Log.w("NTNexAlloy", "Cannot report engine status", error); }
        });
    }

    public static void attachSettings(Context context) throws Exception {
        Bundle response = BridgeContract.call(context, "service", null, null);
        if (response == null) throw new IllegalStateException("No original module settings service");
        IBinder binder = response.getBinder("binder");
        if (binder == null) throw new IllegalStateException("No module service binder");
        // Use libxposed's public provider protocol. Its internal helper may be
        // renamed by R8 in an otherwise unmodified release APK. A local provider
        // instance also avoids re-entering ActivityThread while Application is
        // still being created. SendBinder does not require provider attachment.
        android.content.ContentProvider provider = (android.content.ContentProvider)
                Class.forName("io.github.libxposed.service.XposedProvider", true, context.getClassLoader())
                .getDeclaredConstructor().newInstance();
        if (provider.call("SendBinder", null, response) == null)
            throw new IllegalStateException("Original APK rejected the service binder");
        Log.e("NTNexAlloy", "Original module settings service connected");
    }

    @Override public String getFrameworkName() { return "NTFramework"; }
    @Override public String getFrameworkVersion() { return "original-apk-1"; }
    @Override public long getFrameworkVersionCode() { return 1; }
    @Override public long getFrameworkProperties() { return PROP_CAP_REMOTE; }
    @Override public ApplicationInfo getModuleApplicationInfo() { return new ApplicationInfo(moduleInfo); }
    @Override public void log(int priority, String tag, String message) { Log.println(priority, tag, message); }
    @Override public void log(int priority, String tag, String message, Throwable error) { Log.println(priority, tag, message + "\n" + Log.getStackTraceString(error)); }
    @Override public boolean deoptimize(Executable method) {
        try { return (Boolean) XposedBridge.class.getMethod("deoptimizeMethod", java.lang.reflect.Member.class).invoke(null, method); }
        catch (Exception unavailable) { return false; }
    }
    @Override public HookBuilder hookClassInitializer(Class<?> type) { throw new UnsupportedOperationException("Class initializer hooks are not supported by this embedded engine"); }
    @Override public String[] listRemoteFiles() { return new String[0]; }
    @Override public ParcelFileDescriptor openRemoteFile(String name) throws FileNotFoundException { throw new FileNotFoundException("No shared module files"); }

    @Override public SharedPreferences getRemotePreferences(String group) {
        Bundle bundle = BridgeContract.call(context, "prefs", group, null);
        if (bundle == null) throw new IllegalStateException("No module preferences");
        @SuppressWarnings("unchecked") Map<String, Object> values = (Map<String, Object>) bundle.getSerializable("map");
        return new SnapshotPreferences(values == null ? Collections.emptyMap() : values);
    }

    @Override public HookBuilder hook(Executable executable) {
        return new HookBuilder() {
            private int priority = PRIORITY_DEFAULT;
            private String id;
            private ExceptionMode mode = ExceptionMode.DEFAULT;
            @Override public HookBuilder setPriority(int value) { priority = value; return this; }
            @Override public HookBuilder setId(String value) { id = value; return this; }
            @Override public HookBuilder setExceptionMode(ExceptionMode value) { mode = value; return this; }
            @Override public HookHandle intercept(Hooker hooker) {
                AtomicReference<Hooker> current = new AtomicReference<>(hooker);
                XC_MethodHook callback = new XC_MethodHook(priority) {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        final boolean[] proceeded = {false};
                        final Object[] result = {null};
                        try {
                            Object value = current.get().intercept(new Chain() {
                                @Override public Executable getExecutable() { return executable; }
                                @Override public Object getThisObject() { return param.thisObject; }
                                @Override public List<Object> getArgs() { return Collections.unmodifiableList(Arrays.asList(param.args)); }
                                @Override public Object getArg(int index) { return param.args[index]; }
                                @Override public Object proceed() throws Throwable { return proceedWith(param.thisObject, param.args); }
                                @Override public Object proceed(Object[] args) throws Throwable { return proceedWith(param.thisObject, args); }
                                @Override public Object proceedWith(Object receiver) throws Throwable { return proceedWith(receiver, param.args); }
                                @Override public Object proceedWith(Object receiver, Object[] args) throws Throwable {
                                    if (proceeded[0]) throw new IllegalStateException("A chain may proceed only once");
                                    proceeded[0] = true;
                                    try { result[0] = XposedBridge.invokeOriginalMethod(executable, receiver, args); return result[0]; }
                                    catch (InvocationTargetException error) { throw error.getCause(); }
                                }
                            });
                            param.setResult(value);
                        } catch (Throwable error) {
                            if (mode == ExceptionMode.PASSTHROUGH) param.setThrowable(error);
                            else if (proceeded[0]) param.setResult(result[0]);
                            else log(Log.ERROR, "NTNexAlloy", "Modern hook failed", error);
                        }
                    }
                };
                XC_MethodHook.Unhook unhook = XposedBridge.hookMethod(executable, callback);
                return new HookHandle() {
                    @Override public Executable getExecutable() { return executable; }
                    @Override public String getId() { return id; }
                    @Override public void unhook() { unhook.unhook(); }
                    @Override public HookHandle replaceHook(Hooker replacement) { current.set(replacement); return this; }
                };
            }
        };
    }

    @Override public Invoker<?, Method> getInvoker(Method method) {
        method.setAccessible(true);
        return new MethodInvoker(method);
    }

    private static final class MethodInvoker implements Invoker<MethodInvoker, Method> {
        final Method method;
        boolean original;
        MethodInvoker(Method method) { this.method = method; }
        @Override public MethodInvoker setType(Type type) { original = type == Type.ORIGIN; return this; }
        @Override public Object invoke(Object receiver, Object... args) throws InvocationTargetException, IllegalAccessException {
            try { return original ? XposedBridge.invokeOriginalMethod(method, receiver, args) : method.invoke(receiver, args); }
            catch (InvocationTargetException | IllegalAccessException error) { throw error; }
            catch (Throwable error) { throw new InvocationTargetException(error); }
        }
        @Override public Object invokeSpecial(Object receiver, Object... args) throws InvocationTargetException, IllegalAccessException {
            if (Modifier.isStatic(method.getModifiers())) return invoke(receiver, args);
            try {
                Class<?> caller = receiver.getClass();
                return MethodHandles.privateLookupIn(caller, MethodHandles.lookup())
                        .findSpecial(method.getDeclaringClass(), method.getName(),
                                MethodType.methodType(method.getReturnType(), method.getParameterTypes()), caller)
                        .bindTo(receiver).invokeWithArguments(args);
            } catch (Throwable error) { throw new InvocationTargetException(error); }
        }
    }

    @Override public <T> CtorInvoker<T> getInvoker(Constructor<T> constructor) {
        constructor.setAccessible(true);
        return new CtorInvoker<T>() {
            @Override public CtorInvoker<T> setType(Type type) { return this; }
            @Override public T newInstance(Object... args) throws InvocationTargetException, IllegalAccessException, InstantiationException { return constructor.newInstance(args); }
            @Override public <U> U newInstanceSpecial(Class<U> type, Object... args) throws InvocationTargetException {
                try {
                    if (!constructor.getDeclaringClass().isAssignableFrom(type)) throw new IllegalArgumentException("Unrelated special constructor class");
                    U value = type.cast(XposedBridge.class.getMethod("allocateInstance", Class.class).invoke(null, type));
                    boolean ok = (Boolean) XposedBridge.class.getMethod("invokeConstructor", Object.class, Constructor.class, Object[].class).invoke(null, value, constructor, args);
                    if (!ok) throw new IllegalStateException("Special constructor invocation failed");
                    return value;
                } catch (InvocationTargetException error) { throw error; }
                catch (Throwable error) { throw new InvocationTargetException(error); }
            }
            @Override public Object invoke(Object receiver, Object... args) throws InvocationTargetException { throw new UnsupportedOperationException("Use newInstance for a constructor"); }
            @Override public Object invokeSpecial(Object receiver, Object... args) throws InvocationTargetException {
                try { return XposedBridge.class.getMethod("invokeConstructor", Object.class, Constructor.class, Object[].class).invoke(null, receiver, constructor, args); }
                catch (InvocationTargetException error) { throw error; }
                catch (Throwable error) { throw new InvocationTargetException(error); }
            }
        };
    }

    private static final class SnapshotPreferences implements SharedPreferences {
        final Map<String, Object> values;
        SnapshotPreferences(Map<String, Object> values) { this.values = new HashMap<>(values); }
        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public String getString(String key, String def) { return (String) values.getOrDefault(key, def); }
        @SuppressWarnings("unchecked") @Override public Set<String> getStringSet(String key, Set<String> def) { return (Set<String>) values.getOrDefault(key, def); }
        @Override public int getInt(String key, int def) { return (Integer) values.getOrDefault(key, def); }
        @Override public long getLong(String key, long def) { return (Long) values.getOrDefault(key, def); }
        @Override public float getFloat(String key, float def) { return (Float) values.getOrDefault(key, def); }
        @Override public boolean getBoolean(String key, boolean def) { return (Boolean) values.getOrDefault(key, def); }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() { throw new UnsupportedOperationException("Module preferences are read-only in a target app"); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    }
}
