package dev.ilgax.hotspot;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Starts/stops Wi-Fi tethering via the system TetheringManager, run as the ADB shell user. */
public class Main {
    private static final String PKG = "com.android.shell";
    private static final int TETHERING_WIFI = 0;

    /** Context that claims to be com.android.shell so the tethering service's uid/package check passes. */
    static class ShellContext extends ContextWrapper {
        ShellContext(Context base) {
            super(base);
        }

        public String getPackageName() {
            return PKG;
        }

        public String getOpPackageName() {
            return PKG;
        }

        public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(Process.myUid()).setPackageName(PKG).build();
        }

        public Context getApplicationContext() {
            return this;
        }

        public String getAttributionTag() {
            return null;
        }
    }

    public static void main(String[] args) {
        int code = 1;
        try {
            code = run(args);
        } catch (Throwable t) {
            printFailure("UNCAUGHT", t);
        }
        System.out.flush();
        System.exit(code);
    }

    private static int run(String[] args) {
        String cmd = args.length > 0 ? args[0] : "";
        if (!cmd.equals("start") && !cmd.equals("stop") && !cmd.equals("status")) {
            System.out.println("Usage: app_process /system/bin dev.ilgax.hotspot.Main <start|stop|status>");
            return 1;
        }
        System.out.println("SDK_INT=" + Build.VERSION.SDK_INT + " RELEASE=" + Build.VERSION.RELEASE
                + " uid=" + Process.myUid());

        Context ctx;
        stage("context bootstrap");
        try {
            ctx = bootstrapContext();
            System.out.println("OK: context=" + ctx + " pkg=" + ctx.getPackageName());
        } catch (Throwable t) {
            printFailure("context bootstrap", t);
            return 1;
        }

        Object tm = null;
        stage("TetheringManager construction");
        try {
            tm = makeTetheringManager(ctx);
            System.out.println("OK: " + tm);
            Thread.sleep(500); // constructor may fetch the connector asynchronously
        } catch (Throwable t) {
            printFailure("TetheringManager construction", t);
            if (!cmd.equals("status")) {
                return 1;
            }
        }

        int code = 0;
        if (cmd.equals("start")) {
            code = doStart(tm);
        } else if (cmd.equals("stop")) {
            code = doStop(tm);
        }
        status(ctx, tm);
        return code;
    }

    // ---- stages ----

    private static Context bootstrapContext() throws Exception {
        if (Looper.myLooper() == null && Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Context sys;
        try {
            Constructor<?> c = atClass.getDeclaredConstructor();
            c.setAccessible(true);
            Object at = c.newInstance();
            try {
                Field f = atClass.getDeclaredField("sCurrentActivityThread");
                f.setAccessible(true);
                f.set(null, at);
            } catch (Throwable t) {
                System.out.println("note: could not set sCurrentActivityThread: " + t);
            }
            sys = (Context) atClass.getMethod("getSystemContext").invoke(at);
        } catch (Throwable t) {
            System.out.println("note: direct ActivityThread path failed, trying systemMain(): "
                    + unwrap(t));
            Object at = atClass.getMethod("systemMain").invoke(null);
            sys = (Context) atClass.getMethod("getSystemContext").invoke(at);
        }
        return new ShellContext(sys);
    }

    private static Object makeTetheringManager(Context ctx) throws Exception {
        Class<?> tmClass = Class.forName("android.net.TetheringManager");
        try {
            Constructor<?> c = tmClass.getDeclaredConstructor(Context.class, Supplier.class);
            c.setAccessible(true);
            Supplier<IBinder> supplier = new Supplier<IBinder>() {
                public IBinder get() {
                    try {
                        Class<?> sm = Class.forName("android.os.ServiceManager");
                        return (IBinder) sm.getMethod("getService", String.class).invoke(null, "tethering");
                    } catch (Throwable t) {
                        printFailure("ServiceManager.getService(tethering)", t);
                        return null;
                    }
                }
            };
            return c.newInstance(ctx, supplier);
        } catch (NoSuchMethodException e) {
            System.out.println("(Context, Supplier) constructor not found; declared constructors:");
            for (Constructor<?> c : tmClass.getDeclaredConstructors()) {
                System.out.println("  " + c);
            }
            System.out.println("FALLBACK: using ShellContext.getSystemService(\"tethering\")");
            return ctx.getSystemService("tethering");
        }
    }

    private static int doStart(Object tm) {
        stage("build TetheringRequest");
        Object request = null;
        try {
            Class<?> bClass = Class.forName("android.net.TetheringManager$TetheringRequest$Builder");
            Constructor<?> bc = bClass.getDeclaredConstructor(int.class);
            bc.setAccessible(true);
            Object b = bc.newInstance(TETHERING_WIFI);
            StringBuilder applied = new StringBuilder();
            String[] setters = {"setShouldShowEntitlementUi", "setExemptFromEntitlementCheck"};
            boolean[] values = {false, true};
            for (int i = 0; i < setters.length; i++) {
                try {
                    Method m = bClass.getMethod(setters[i], boolean.class);
                    m.invoke(b, values[i]);
                    applied.append(setters[i]).append('(').append(values[i]).append(") ");
                } catch (NoSuchMethodException e) {
                    System.out.println("not present: " + setters[i]);
                }
            }
            System.out.println("applied: " + (applied.length() == 0 ? "(none)" : applied));
            Method build = bClass.getMethod("build");
            build.setAccessible(true);
            request = build.invoke(b);
            System.out.println("OK: request=" + request);
        } catch (Throwable t) {
            printFailure("build TetheringRequest", t);
            request = null;
        }

        stage("startTethering");
        final CountDownLatch latch = new CountDownLatch(1);
        final int[] result = {Integer.MIN_VALUE}; // MIN_VALUE = none, -1 = started, >=0 error
        try {
            Class<?> cbClass = Class.forName("android.net.TetheringManager$StartTetheringCallback");
            Method start = null;
            Object firstArg = null;
            if (request != null) {
                start = findStart(tm.getClass(), request.getClass(), cbClass);
                if (start == null) {
                    start = findStart(tm.getClass(),
                            Class.forName("android.net.TetheringManager$TetheringRequest"), cbClass);
                }
                if (start != null) {
                    firstArg = request;
                    System.out.println("using TetheringRequest overload");
                }
            }
            if (start == null) {
                start = findStart(tm.getClass(), int.class, cbClass);
                if (start != null) {
                    firstArg = TETHERING_WIFI;
                    System.out.println((request == null ? "request build failed; " : "TetheringRequest overload not found; ")
                            + "using int overload with type=" + TETHERING_WIFI);
                }
            }
            if (start == null) {
                System.out.println("startTethering with 3 args not found; TetheringManager methods containing \"ether\":");
                for (Method m : tm.getClass().getDeclaredMethods()) {
                    if (m.getName().contains("ether")) {
                        System.out.println("  " + m);
                    }
                }
                return 1;
            }
            System.out.println("using " + start);
            Object cb = Proxy.newProxyInstance(cbClass.getClassLoader(), new Class<?>[]{cbClass},
                    new InvocationHandler() {
                        public Object invoke(Object proxy, Method m, Object[] a) {
                            String n = m.getName();
                            if (n.equals("onTetheringStarted")) {
                                System.out.println("CALLBACK: onTetheringStarted");
                                result[0] = -1;
                                latch.countDown();
                            } else if (n.equals("onTetheringFailed")) {
                                System.out.println("CALLBACK: onTetheringFailed " + (a != null && a.length > 0 ? a[0] : ""));
                                result[0] = a != null && a.length > 0 ? ((Integer) a[0]) : 5;
                                latch.countDown();
                            } else if (n.equals("toString")) {
                                return "StartTetheringCallbackProxy";
                            } else if (n.equals("hashCode")) {
                                return System.identityHashCode(proxy);
                            } else if (n.equals("equals")) {
                                return proxy == a[0];
                            }
                            return null;
                        }
                    });
            Executor direct = new Executor() {
                public void execute(Runnable r) {
                    r.run();
                }
            };
            start.setAccessible(true);
            start.invoke(tm, firstArg, direct, cb);
            System.out.println("startTethering invoked, waiting up to 20s for callback...");
            boolean got = latch.await(20, TimeUnit.SECONDS);
            int r = result[0];
            if (!got || r == Integer.MIN_VALUE) {
                System.out.println("RESULT: timeout (no callback)");
                return 1;
            } else if (r == -1) {
                System.out.println("RESULT: started");
                return 0;
            } else {
                System.out.println("RESULT: failed error=" + r + " (" + errorName(r) + ")");
                return 1;
            }
        } catch (Throwable t) {
            printFailure("startTethering", t);
            return 1;
        }
    }

    private static int doStop(Object tm) {
        stage("stopTethering");
        try {
            Method m = tm.getClass().getMethod("stopTethering", int.class);
            m.setAccessible(true);
            m.invoke(tm, TETHERING_WIFI);
            System.out.println("RESULT: stopTethering(0) done");
            return 0;
        } catch (Throwable t) {
            printFailure("stopTethering", t);
            return 1;
        }
    }

    // ---- status ----

    private static void status(Context ctx, Object tm) {
        stage("status");
        WifiManager wm = null;
        try {
            wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            System.out.println("WifiManager: " + wm);
        } catch (Throwable t) {
            printFailure("status: get WifiManager", t);
        }
        if (wm != null) {
            final WifiManager w = wm;
            try {
                int s = (Integer) w.getClass().getMethod("getWifiApState").invoke(w);
                System.out.println("wifiApState=" + s + " (" + apStateName(s) + ")");
            } catch (Throwable t) {
                printFailure("status: getWifiApState", t);
            }
            try {
                System.out.println("isWifiApEnabled=" + w.getClass().getMethod("isWifiApEnabled").invoke(w));
            } catch (Throwable t) {
                printFailure("status: isWifiApEnabled", t);
            }
            try {
                System.out.println("isWifiEnabled=" + w.isWifiEnabled());
            } catch (Throwable t) {
                printFailure("status: isWifiEnabled", t);
            }
            try {
                WifiInfo info = w.getConnectionInfo();
                System.out.println("connection: frequency=" + info.getFrequency() + "MHz ssid=" + info.getSSID());
            } catch (Throwable t) {
                printFailure("status: getConnectionInfo", t);
            }
            try {
                System.out.println("isStaApConcurrencySupported=" + w.isStaApConcurrencySupported());
            } catch (Throwable t) {
                printFailure("status: isStaApConcurrencySupported", t);
            }
            try {
                Object cfg = w.getClass().getMethod("getSoftApConfiguration").invoke(w);
                System.out.println("softApConfiguration=" + cfg);
                printSoftAp(cfg);
            } catch (Throwable t) {
                printFailure("status: getSoftApConfiguration", t);
            }
        }
        if (tm != null) {
            for (String name : new String[]{"getTetheredIfaces", "getTetherableIfaces"}) {
                try {
                    Method m = tm.getClass().getMethod(name);
                    m.setAccessible(true);
                    System.out.println(name + "=" + fmt(m.invoke(tm)));
                } catch (Throwable t) {
                    printFailure("status: " + name, t);
                }
            }
        }
    }

    private static void printSoftAp(Object cfg) {
        if (cfg == null) {
            System.out.println("HOTSPOT SSID: <null config>");
            return;
        }
        Class<?> c = cfg.getClass();
        try {
            Object ssid = null;
            try {
                ssid = c.getMethod("getWifiSsid").invoke(cfg);
            } catch (NoSuchMethodException e) {
                ssid = c.getMethod("getSsid").invoke(cfg);
            }
            System.out.println("HOTSPOT SSID: " + ssid);
        } catch (Throwable t) {
            printFailure("status: softap ssid", t);
        }
        try {
            System.out.println("HOTSPOT PASSWORD: " + c.getMethod("getPassphrase").invoke(cfg));
        } catch (Throwable t) {
            printFailure("status: softap passphrase", t);
        }
        try {
            Object band;
            try {
                band = c.getMethod("getBands").invoke(cfg);
            } catch (NoSuchMethodException e) {
                band = c.getMethod("getBand").invoke(cfg);
            }
            System.out.println("HOTSPOT BAND: " + fmt(band) + " (1=2.4GHz 2=5GHz 4=6GHz 8=60GHz bitmask)");
        } catch (Throwable t) {
            printFailure("status: softap band", t);
        }
    }

    // ---- helpers ----

    private static void stage(String s) {
        System.out.println("STAGE: " + s);
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof InvocationTargetException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static void printFailure(String stage, Throwable t) {
        System.out.println("FAILED at STAGE: " + stage);
        unwrap(t).printStackTrace(System.out);
    }

    private static Method findStart(Class<?> c, Class<?> first, Class<?> cbClass) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("startTethering") && p.length == 3
                        && p[0] == first && p[2] == cbClass) {
                    return m;
                }
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String name, int params) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == params) {
                return m;
            }
        }
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == params) {
                    return m;
                }
            }
        }
        return null;
    }

    private static String fmt(Object o) {
        if (o == null) {
            return "null";
        }
        if (o.getClass().isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < Array.getLength(o); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(Array.get(o, i));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(o);
    }

    private static String apStateName(int s) {
        switch (s) {
            case 10: return "DISABLING";
            case 11: return "DISABLED";
            case 12: return "ENABLING";
            case 13: return "ENABLED";
            case 14: return "FAILED";
            default: return "?";
        }
    }

    private static String errorName(int err) {
        try {
            Class<?> c = Class.forName("android.net.TetheringManager");
            for (Field f : c.getFields()) {
                int mod = f.getModifiers();
                if (f.getName().startsWith("TETHER_ERROR_") && f.getType() == int.class
                        && Modifier.isStatic(mod) && Modifier.isFinal(mod) && f.getInt(null) == err) {
                    return f.getName();
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return "unknown";
    }
}
