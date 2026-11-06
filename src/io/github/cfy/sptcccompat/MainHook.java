package io.github.cfy.sptcccompat;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** A scoped compatibility experiment. Never hooks system_server or other apps. */
public final class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String TARGET = "com.jiuyu.sptcc.cordova";
    private static final String TAG = "SptccCompat";
    private static int notices;
    private static boolean nativeLoaded;
    private static native void nativeApplicationLoaded();

    @Override public void initZygote(StartupParam param) {
        // This build is scoped to the connected phone's existing SPTCC install.
        if (param.startsSystemServer || android.os.Process.myUid() != 10506) return;
        note("v0.3 early initialization");
        loadNative();
    }
    private static void loadNative() {
        if (nativeLoaded) return;
        try { System.loadLibrary("sptcccompat"); nativeLoaded = true; }
        catch (Throwable error) { Log.e(TAG, "native diagnostic load failed", error); }
    }

    private static void note(String text) {
        if (notices++ < 80) Log.i(TAG, text);
    }

    private static boolean rootPackage(String name) {
        if (name == null) return false;
        return name.equals("me.weishu.kernelsu") || name.equals("me.bmax.apatch")
            || name.equals("com.topjohnwu.magisk") || name.equals("org.lsposed.manager")
            || name.equals("de.robv.android.xposed.installer")
            || name.equals("io.github.cfy.sptcccompat");
    }

    private static boolean rootPath(String path) {
        if (path == null) return false;
        return path.equals("/system/bin/su") || path.equals("/system/xbin/su")
            || path.equals("/sbin/su") || path.equals("/su/bin/su")
            || path.equals("/system/app/Superuser.apk")
            || path.equals("/system/app/SuperSU.apk")
            || path.equals("/data/adb") || path.startsWith("/data/adb/")
            || path.equals("/sbin/.magisk") || path.startsWith("/sbin/.magisk/")
            || path.equals("/debug_ramdisk/.magisk")
            || path.equals("/data/local/tmp/frida-server");
    }

    private static boolean rootCommand(String[] args) {
        if (args == null || args.length == 0) return false;
        String first = args[0].trim();
        if (first.equals("su") || rootPath(first)) return true;
        String joined = String.join(" ", args).trim();
        return joined.equals("which su") || joined.equals("/system/bin/which su")
            || joined.equals("sh -c which su") || joined.equals("sh -c command -v su");
    }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) throws Throwable {
        if (!TARGET.equals(p.packageName)) return;
        note("v0.3 active: " + p.processName);
        loadNative();

        if (nativeLoaded) nativeApplicationLoaded();

        for (String className : new String[]{"android.provider.Settings$Global", "android.provider.Settings$Secure"}) {
            Class<?> cls = XposedHelpers.findClass(className, null);
            for (String method : new String[]{"getString", "getStringForUser", "getInt", "getIntForUser"}) {
                XposedBridge.hookAllMethods(cls, method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        for (Object arg : param.args) {
                            if ("adb_enabled".equals(arg) || "development_settings_enabled".equals(arg)) {
                                Class<?> type = ((Method) param.method).getReturnType();
                                if (type == String.class) param.setResult("0");
                                else if (type == int.class) param.setResult(0);
                                note("compat setting: " + arg);
                                break;
                            }
                        }
                    }
                });
            }
        }
        Class<?> debug = XposedHelpers.findClass("android.os.Debug", null);
        for (String method : new String[]{"isDebuggerConnected", "waitingForDebugger"}) {
            XposedBridge.hookAllMethods(debug, method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) { param.setResult(false); }
            });
        }
        for (String method : new String[]{"exists", "canExecute"}) {
            XposedBridge.hookAllMethods(File.class, method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (rootPath(((File)param.thisObject).getAbsolutePath())) {
                        note("compat file probe"); param.setResult(false);
                    }
                }
            });
        }
        XposedBridge.hookAllMethods(Runtime.class, "exec", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object command = param.args[0];
                String[] args = command instanceof String[] ? (String[])command : ((String)command).split("\\s+");
                if (rootCommand(args)) { note("compat su probe"); param.setThrowable(new IOException("No such file or directory")); }
            }
        });
        XposedBridge.hookAllMethods(ProcessBuilder.class, "start", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                List<String> command = ((ProcessBuilder)param.thisObject).command();
                if (rootCommand(command.toArray(new String[0]))) {
                    note("compat su process probe"); param.setThrowable(new IOException("No such file or directory"));
                }
            }
        });
        Class<?> pm = XposedHelpers.findClass("android.app.ApplicationPackageManager", null);
        for (String method : new String[]{"getPackageInfo", "getPackageInfoAsUser", "getApplicationInfo", "getApplicationInfoAsUser"}) {
            XposedBridge.hookAllMethods(pm, method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof String && rootPackage((String)param.args[0])) {
                        note("compat package probe"); param.setThrowable(new PackageManager.NameNotFoundException((String)param.args[0]));
                    }
                }
            });
        }
        for (String method : new String[]{"getInstalledPackages", "getInstalledApplications", "getInstalledPackagesAsUser", "getInstalledApplicationsAsUser"}) {
            XposedBridge.hookAllMethods(pm, method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !(param.getResult() instanceof List)) return;
                    ArrayList<Object> filtered = new ArrayList<>();
                    int removed = 0;
                    for (Object item : (List<?>)param.getResult()) {
                        String name = item instanceof PackageInfo ? ((PackageInfo)item).packageName
                            : item instanceof ApplicationInfo ? ((ApplicationInfo)item).packageName : null;
                        if (rootPackage(name)) removed++; else filtered.add(item);
                    }
                    if (removed > 0) { param.setResult(filtered); note("compat package list: " + removed); }
                }
            });
        }
        XC_MethodHook exitLog = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Log.w(TAG, "Exit requested through " + param.method, new Throwable("Exit caller"));
            }
        };
        for (String method : new String[]{"exit", "halt"}) XposedBridge.hookAllMethods(Runtime.class, method, exitLog);
        XposedBridge.hookAllMethods(android.os.Process.class, "killProcess", exitLog);
        try {
            Class<?> guard=XposedHelpers.findClass("com.secneo.apkwrapper.H", p.classLoader);
            for (String method : new String[]{"he", "cis", "callBS", "callSW"}) {
                XposedBridge.hookAllMethods(guard, method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        String value=param.args.length>0&&param.args[0] instanceof Integer ? String.valueOf(param.args[0]) : "";
                        note("guard method: "+param.method.getName()+" "+value);
                    }
                });
            }
        } catch (Throwable error) { Log.w(TAG, "guard observer unavailable", error); }
        note("Java compatibility hooks ready");
    }
}
