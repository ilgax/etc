package dev.ilgax.utils.hotspot;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Starts/stops Wi-Fi tethering through the system TetheringManager. Port of the tab-hotspot-helper CLI
 * (dev.ilgax.hotspot.Main): the reflection is unchanged; only the output moved from stdout into a
 * {@link HotspotStatus} (always "verbose") and the context/TetheringManager are cached between calls.
 * Must run as the shell uid, which is what a Shizuku UserService is.
 */
public class HotspotEngine {
    private static final String PKG = "com.android.shell";
    private static final int TETHERING_WIFI = 0;
    private static final int AP_DISABLING = 10;
    private static final int AP_DISABLED = 11;
    private static final int AP_ENABLING = 12;
    private static final int AP_ENABLED = 13;
    private static final int AP_FAILED = 14;

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

    private StringBuilder log = new StringBuilder();
    private String lastError;
    private String knownPassphrase;

    private Context ctx;
    private Object tm;
    private WifiManager wm;

    /**
     * The CLI ran on a thread that owns a Looper (its main thread). A binder thread has none, and
     * ActivityThread/TetheringManager may create Handlers, so every call runs on this one thread
     * after giving it a Looper in the same way the CLI did.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
        if (Looper.myLooper() == null) {
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            } else {
                Looper.prepare();
            }
        }
        r.run();
    }, "hotspot-engine"));

    public synchronized HotspotStatus start() {
        return onWorker(this::startInternal);
    }

    public synchronized HotspotStatus stop() {
        return onWorker(this::stopInternal);
    }

    public synchronized HotspotStatus status(final boolean includePassword) {
        return onWorker(() -> statusInternal(includePassword));
    }

    private HotspotStatus onWorker(Callable<HotspotStatus> job) {
        log = new StringBuilder();
        lastError = null;
        try {
            return worker.submit(job).get(60, TimeUnit.SECONDS);
        } catch (Throwable t) {
            Throwable cause = t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t;
            fail("engine", cause);
            return finish(errorStatus());
        }
    }

    // ---- commands ----

    private HotspotStatus statusInternal(boolean includePassword) {
        vlog("SDK_INT=" + Build.VERSION.SDK_INT + " RELEASE=" + Build.VERSION.RELEASE + " uid=" + Process.myUid());
        if (!ensureReady()) {
            return finish(errorStatus());
        }
        return finish(summary(includePassword, null, 0));
    }

    private HotspotStatus startInternal() {
        vlog("SDK_INT=" + Build.VERSION.SDK_INT + " RELEASE=" + Build.VERSION.RELEASE + " uid=" + Process.myUid());
        if (!ensureReady() || tm == null) {
            return finish(errorStatus());
        }
        int state = apState();
        if (state == AP_ENABLED) {
            vlog("Hotspot already on");
            return finish(summary(false, null, 0));
        }
        int r;
        try {
            r = doStart(tm);
        } catch (Throwable t) {
            fail("startTethering", t);
            return finish(errorStatus());
        }
        if (r == -1) {
            vlog("Hotspot on");
            return finish(summary(false, null, 0));
        }
        if (r == Integer.MIN_VALUE) {
            vlog("Hotspot failed: no response from tethering service");
            return finish(summary(false, "NO_RESPONSE", r));
        }
        vlog("Hotspot failed: " + errorName(r) + " (" + r + ")");
        return finish(summary(false, errorName(r), r));
    }

    private HotspotStatus stopInternal() {
        vlog("SDK_INT=" + Build.VERSION.SDK_INT + " RELEASE=" + Build.VERSION.RELEASE + " uid=" + Process.myUid());
        if (!ensureReady() || tm == null) {
            return finish(errorStatus());
        }
        if (apState() == AP_DISABLED) {
            vlog("Hotspot already off");
            return finish(summary(false, null, 0));
        }
        try {
            doStop(tm);
        } catch (Throwable t) {
            fail("stopTethering", t);
            return finish(errorStatus());
        }
        boolean off = false;
        long end = System.currentTimeMillis() + 3000;
        while (true) {
            if (apState() == AP_DISABLED) {
                off = true;
                break;
            }
            if (System.currentTimeMillis() >= end) {
                break;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                break;
            }
        }
        vlog(off ? "Hotspot off" : "Hotspot still shutting down");
        return finish(summary(false, null, 0));
    }

    /** Context, TetheringManager and WifiManager are created once and reused. */
    private boolean ensureReady() {
        if (ctx == null) {
            stage("context bootstrap");
            try {
                ctx = bootstrapContext();
                vlog("OK: context=" + ctx + " pkg=" + ctx.getPackageName());
            } catch (Throwable t) {
                fail("context bootstrap", t);
                return false;
            }
        }
        if (tm == null) {
            stage("TetheringManager construction");
            try {
                tm = makeTetheringManager(ctx);
                vlog("OK: " + tm);
                Thread.sleep(500); // constructor may fetch the connector asynchronously
            } catch (Throwable t) {
                tm = null;
                fail("TetheringManager construction", t);
            }
        }
        if (wm == null) {
            try {
                wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
                vlog("WifiManager: " + wm);
            } catch (Throwable t) {
                note("get WifiManager", t);
            }
        }
        return true;
    }

    // ---- stages ----

    private Context bootstrapContext() throws Exception {
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
                vlog("note: could not set sCurrentActivityThread: " + t);
            }
            sys = (Context) atClass.getMethod("getSystemContext").invoke(at);
        } catch (Throwable t) {
            vlog("note: direct ActivityThread path failed, trying systemMain(): " + unwrap(t));
            Object at = atClass.getMethod("systemMain").invoke(null);
            sys = (Context) atClass.getMethod("getSystemContext").invoke(at);
        }
        return new ShellContext(sys);
    }

    private Object makeTetheringManager(Context ctx) throws Exception {
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
                        fail("ServiceManager.getService(tethering)", t);
                        return null;
                    }
                }
            };
            return c.newInstance(ctx, supplier);
        } catch (NoSuchMethodException e) {
            vlog("(Context, Supplier) constructor not found; declared constructors:");
            for (Constructor<?> c : tmClass.getDeclaredConstructors()) {
                vlog("  " + c);
            }
            vlog("FALLBACK: using ShellContext.getSystemService(\"tethering\")");
            return ctx.getSystemService("tethering");
        }
    }

    /** Returns -1 if started, a TETHER_ERROR code if failed, Integer.MIN_VALUE if no callback arrived. */
    private int doStart(Object tm) throws Throwable {
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
                    vlog("not present: " + setters[i]);
                }
            }
            vlog("applied: " + (applied.length() == 0 ? "(none)" : applied));
            Method build = bClass.getMethod("build");
            build.setAccessible(true);
            request = build.invoke(b);
            vlog("OK: request=" + request);
        } catch (Throwable t) {
            fail("build TetheringRequest", t);
            request = null;
        }

        stage("startTethering");
        final CountDownLatch latch = new CountDownLatch(1);
        final int[] result = {Integer.MIN_VALUE}; // MIN_VALUE = none, -1 = started, >=0 error
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
                vlog("using TetheringRequest overload");
            }
        }
        if (start == null) {
            start = findStart(tm.getClass(), int.class, cbClass);
            if (start != null) {
                firstArg = TETHERING_WIFI;
                vlog((request == null ? "request build failed; " : "TetheringRequest overload not found; ")
                        + "using int overload with type=" + TETHERING_WIFI);
            }
        }
        if (start == null) {
            vlog("startTethering with 3 args not found; TetheringManager methods containing \"ether\":");
            for (Method m : tm.getClass().getDeclaredMethods()) {
                if (m.getName().contains("ether")) {
                    vlog("  " + m);
                }
            }
            throw new NoSuchMethodException("startTethering(request, executor, callback) not found");
        }
        vlog("using " + start);
        Object cb = Proxy.newProxyInstance(cbClass.getClassLoader(), new Class<?>[]{cbClass},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method m, Object[] a) {
                        String n = m.getName();
                        if (n.equals("onTetheringStarted")) {
                            vlog("CALLBACK: onTetheringStarted");
                            result[0] = -1;
                            latch.countDown();
                        } else if (n.equals("onTetheringFailed")) {
                            vlog("CALLBACK: onTetheringFailed " + (a != null && a.length > 0 ? a[0] : ""));
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
        try {
            start.invoke(tm, firstArg, direct, cb);
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        }
        vlog("startTethering invoked, waiting up to 20s for callback...");
        latch.await(20, TimeUnit.SECONDS);
        vlog("RESULT code=" + result[0]);
        return result[0];
    }

    private void doStop(Object tm) throws Throwable {
        stage("stopTethering");
        Method m = tm.getClass().getMethod("stopTethering", int.class);
        m.setAccessible(true);
        try {
            m.invoke(tm, TETHERING_WIFI);
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        }
        vlog("RESULT: stopTethering(0) done");
    }

    // ---- status / summary ----

    private int apState() {
        if (wm == null) {
            return -1;
        }
        try {
            return (Integer) wm.getClass().getMethod("getWifiApState").invoke(wm);
        } catch (Throwable t) {
            note("getWifiApState", t);
            return -1;
        }
    }

    /** Reads everything the CLI's summary() printed into a HotspotStatus. */
    private HotspotStatus summary(boolean includePassword, String errName, int errCode) {
        stage("summary");
        HotspotStatus s = new HotspotStatus();
        s.apState = apState();
        s.errorName = errName != null ? errName : (s.apState == AP_FAILED ? "AP_STATE_FAILED" : null);
        s.errorCode = errCode;
        Object cfg = null;
        if (wm != null) {
            try {
                cfg = wm.getClass().getMethod("getSoftApConfiguration").invoke(wm);
                vlog("softApConfiguration=" + redactConfig(String.valueOf(cfg)));
            } catch (Throwable t) {
                note("getSoftApConfiguration", t);
            }
        }
        if (cfg != null) {
            Class<?> c = cfg.getClass();
            try {
                Object ssid;
                try {
                    ssid = c.getMethod("getWifiSsid").invoke(cfg);
                } catch (NoSuchMethodException e) {
                    ssid = c.getMethod("getSsid").invoke(cfg);
                }
                s.ssid = stripQuotes(String.valueOf(ssid));
                try {
                    s.hidden = Boolean.TRUE.equals(c.getMethod("isHiddenSsid").invoke(cfg));
                } catch (Throwable t) {
                    note("isHiddenSsid", t);
                }
                vlog("Name: " + s.ssid + (s.hidden ? " (hidden)" : ""));
            } catch (Throwable t) {
                note("softap ssid", t);
            }
            try {
                s.securityType = (Integer) c.getMethod("getSecurityType").invoke(cfg);
                vlog("Security type: " + s.securityType + (s.securityType == 0 ? " (open: hotspot has no password)" : ""));
            } catch (Throwable t) {
                note("softap security", t);
            }
            if (s.securityType != 0) {
                try {
                    Object pass = c.getMethod("getPassphrase").invoke(cfg);
                    if (pass != null) {
                        knownPassphrase = String.valueOf(pass);
                        if (includePassword) {
                            s.passphrase = knownPassphrase;
                        }
                    }
                    vlog("Password: (not logged)");
                } catch (Throwable t) {
                    note("softap passphrase", t);
                }
            }
            try {
                Object band;
                try {
                    band = c.getMethod("getBands").invoke(cfg);
                } catch (NoSuchMethodException e) {
                    band = c.getMethod("getBand").invoke(cfg);
                }
                s.bands = bandMask(band);
                vlog("Band mask: " + s.bands);
            } catch (Throwable t) {
                note("softap band", t);
            }
        }
        if (s.apState == AP_ENABLED && tm != null) {
            Integer n = countClients(tm);
            if (n != null) {
                s.clientCount = n;
                vlog("Clients: " + n);
            }
        }
        upstream(s);
        for (String name : new String[]{"getTetheredIfaces", "getTetherableIfaces"}) {
            if (tm == null) {
                break;
            }
            try {
                Method m = tm.getClass().getMethod(name);
                m.setAccessible(true);
                vlog(name + "=" + fmt(m.invoke(tm)));
            } catch (Throwable t) {
                note(name, t);
            }
        }
        return s;
    }

    private static int bandMask(Object band) {
        int mask = 0;
        if (band instanceof Number) {
            mask = ((Number) band).intValue();
        } else if (band != null && band.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(band); i++) {
                mask |= ((Number) Array.get(band, i)).intValue();
            }
        } else if (band instanceof Collection) {
            for (Object o : (Collection<?>) band) {
                mask |= ((Number) o).intValue();
            }
        }
        return mask;
    }

    private void upstream(HotspotStatus s) {
        if (wm == null) {
            return;
        }
        try {
            if (!wm.isWifiEnabled()) {
                s.upstreamFrequency = -1;
                vlog("Wi-Fi: off");
                return;
            }
        } catch (Throwable t) {
            note("isWifiEnabled", t);
            return;
        }
        try {
            WifiManager shellWm = makeShellWifiManager(ctx);
            WifiInfo info = shellWm.getConnectionInfo();
            String ssid = stripQuotes(String.valueOf(info.getSSID()));
            int freq = info.getFrequency();
            if (freq <= 0 || ssid.equals("<unknown ssid>") || ssid.equals("null")) {
                vlog("Wi-Fi: not connected");
            } else {
                s.upstreamSsid = ssid;
                s.upstreamFrequency = freq;
                vlog("Wi-Fi: " + ssid + " " + freq + " MHz");
            }
        } catch (Throwable t) {
            note("Wi-Fi connection info", t);
        }
    }

    /** WifiManager bound to the ShellContext (package com.android.shell) instead of the base context. */
    private WifiManager makeShellWifiManager(Context ctx) throws Throwable {
        Class<?> iwm = Class.forName("android.net.wifi.IWifiManager");
        Class<?> stub = Class.forName("android.net.wifi.IWifiManager$Stub");
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "wifi");
        if (binder == null) {
            throw new IllegalStateException("wifi service not found");
        }
        Object service = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        Constructor<?> found = null;
        try {
            found = WifiManager.class.getDeclaredConstructor(Context.class, iwm);
        } catch (NoSuchMethodException e) {
            for (Constructor<?> c : WifiManager.class.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length < 2 || p[0] != Context.class || p[1] != iwm) {
                    continue;
                }
                boolean ok = true;
                for (int i = 2; i < p.length; i++) {
                    ok &= !p[i].isPrimitive();
                }
                if (ok) {
                    found = c;
                    break;
                }
            }
        }
        if (found == null) {
            throw new NoSuchMethodException("no WifiManager(Context, IWifiManager[, ...]) constructor");
        }
        vlog("using " + found);
        found.setAccessible(true);
        Object[] a = new Object[found.getParameterTypes().length];
        a[0] = ctx;
        a[1] = service;
        try {
            return (WifiManager) found.newInstance(a);
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        }
    }

    /** Best-effort connected-client count; null if unavailable. Waits at most ~1.2 s. */
    private Integer countClients(Object tm) {
        Method reg = null;
        Method unreg = null;
        Object cb = null;
        try {
            Class<?> cbClass = Class.forName("android.net.TetheringManager$TetheringEventCallback");
            for (Method m : tm.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("registerTetheringEventCallback") && p.length == 2
                        && p[0] == Executor.class && p[1] == cbClass) {
                    reg = m;
                } else if (m.getName().equals("unregisterTetheringEventCallback") && p.length == 1
                        && p[0] == cbClass) {
                    unreg = m;
                }
            }
            if (reg == null || unreg == null) {
                throw new NoSuchMethodException("register/unregisterTetheringEventCallback not found");
            }
            final CountDownLatch latch = new CountDownLatch(1);
            final int[] count = {-1};
            cb = Proxy.newProxyInstance(cbClass.getClassLoader(), new Class<?>[]{cbClass},
                    new InvocationHandler() {
                        public Object invoke(Object proxy, Method m, Object[] a) {
                            String n = m.getName();
                            if (n.equals("onClientsChanged") && a != null && a.length > 0
                                    && a[0] instanceof Collection) {
                                if (count[0] < 0) {
                                    count[0] = ((Collection<?>) a[0]).size();
                                    latch.countDown();
                                }
                            } else if (n.equals("toString")) {
                                return "TetheringEventCallbackProxy";
                            } else if (n.equals("hashCode")) {
                                return System.identityHashCode(proxy);
                            } else if (n.equals("equals")) {
                                return proxy == (a == null ? null : a[0]);
                            }
                            return null;
                        }
                    });
            Executor direct = new Executor() {
                public void execute(Runnable r) {
                    r.run();
                }
            };
            reg.setAccessible(true);
            reg.invoke(tm, direct, cb);
            try {
                latch.await(1200, TimeUnit.MILLISECONDS);
            } finally {
                try {
                    unreg.setAccessible(true);
                    unreg.invoke(tm, cb);
                } catch (Throwable t) {
                    note("unregisterTetheringEventCallback", t);
                }
            }
            if (count[0] < 0) {
                vlog("no onClientsChanged callback within 1.2s");
                return null;
            }
            return count[0];
        } catch (Throwable t) {
            note("client count", t);
            return null;
        }
    }

    // ---- helpers ----

    private void vlog(String s) {
        log.append(s).append('\n');
    }

    private void stage(String s) {
        vlog("STAGE: " + s);
    }

    /** Non-fatal problem: logged with its stack trace. */
    private void note(String what, Throwable t) {
        vlog("note: " + what + " unavailable:");
        vlog(trace(unwrap(t)));
    }

    private static String trace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString().trim();
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof InvocationTargetException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static Throwable rootCause(Throwable t) {
        t = unwrap(t);
        for (int i = 0; i < 20 && t.getCause() != null && t.getCause() != t; i++) {
            t = t.getCause();
        }
        return t;
    }

    private void fail(String stage, Throwable t) {
        vlog("FAILED at STAGE: " + stage);
        vlog(trace(unwrap(t)));
        Throwable r = rootCause(t);
        String msg = r.getMessage();
        lastError = stage + ": " + r.getClass().getSimpleName() + (msg == null || msg.isEmpty() ? "" : ": " + msg);
    }

    private HotspotStatus errorStatus() {
        HotspotStatus s = new HotspotStatus();
        s.errorName = lastError != null ? lastError : "unknown failure";
        s.errorCode = -1;
        s.apState = wm != null ? apState() : -1;
        return s;
    }

    /** Attaches the scrubbed log. Called for every result. */
    private HotspotStatus finish(HotspotStatus s) {
        s.diagnostics = scrub(log.toString());
        if (s.errorName != null) {
            s.errorName = scrub(s.errorName);
        }
        return s;
    }

    private String scrub(String text) {
        if (knownPassphrase != null && knownPassphrase.length() >= 4) {
            text = text.replace(knownPassphrase, "<redacted>");
        }
        return redactConfig(text);
    }

    private static String redactConfig(String text) {
        return text.replaceAll("(?i)(passphrase|password|psk)(\\s*[=:]\\s*)[^,}\\n]*", "$1$2<redacted>");
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
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
