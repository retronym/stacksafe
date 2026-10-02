package stacksafe;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StackSafeTransformerTest {
    private static final String PKG = "stacksafe.samples.";

    /** Defines everything in {@code stacksafe.samples} itself, running the transformer over each class. */
    static final class Loader extends ClassLoader {
        private final StackSafeTransformer transformer;

        Loader(StackSafeTransformer transformer) {
            super(StackSafeTransformerTest.class.getClassLoader());
            this.transformer = transformer;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(PKG)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        byte[] bytes = in.readAllBytes();
                        byte[] out = transformer.transform(this, bytes);
                        if (out != null) bytes = out;
                        c = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
                return c;
            }
        }
    }

    private final Loader loader = new Loader(new StackSafeTransformer(256));

    private Class<?> samples() throws Exception { return loader.loadClass(PKG + "Samples"); }

    private Object call(String name, Object... args) throws Throwable {
        for (Method m : samples().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                try {
                    return m.invoke(Modifier.isStatic(m.getModifiers()) ? null : samples().getDeclaredConstructor().newInstance(), args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    /** Runs on a platform thread with a 1 MB stack: plain recursion overflows after roughly 10-20k frames. */
    interface Body<T> { T call() throws Throwable; }

    private static <T> T onSmallStack(Body<T> body) throws Throwable {
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = new Thread(null, () -> {
            try { result.set(body.call()); } catch (Throwable e) { failure.set(e); }
        }, "small", 1024 * 1024);
        t.start();
        t.join();
        if (failure.get() != null) throw failure.get();
        @SuppressWarnings("unchecked") T r = (T) result.get();
        return r;
    }

    private Object leftDeep(int n) throws Throwable { return call("leftDeep", n); }

    @Test
    void plainRecursionOverflows() throws Throwable {
        Object e = leftDeep(1_000_000);
        assertThrows(StackOverflowError.class, () -> onSmallStack(() -> call("evalPlain", e)));
    }

    @Test
    void leftDeepTree() throws Throwable {
        Object e = leftDeep(2_000_000);
        assertEquals(2_000_001L, onSmallStack(() -> call("eval", e)));
    }

    @Test
    void mutualRecursion() throws Throwable {
        assertEquals(true, onSmallStack(() -> call("isEven", 2_000_000)));
        assertEquals(false, onSmallStack(() -> call("isEven", 2_000_001)));
        assertEquals(true, onSmallStack(() -> call("isOdd", 2_000_001)));
    }

    @Test
    void privateInstanceMethod() throws Throwable {
        assertEquals(1_000_000L, onSmallStack(() -> call("count", 1_000_000L)));
    }

    @Test
    void voidReturn() throws Throwable {
        int[] counter = new int[1];
        onSmallStack(() -> call("tick", 1_000_000, counter));
        assertEquals(1_000_000, counter[0]);
    }

    @Test
    void wideParametersAndLocals() throws Throwable {
        // local = 2a = 6, d = 3.5 -> 3, "abc".length = 3, arr.length = 2; plus n frames
        assertEquals(6L + 3 + 3 + 2 + 500_000, onSmallStack(() -> call("wide", 3L, 3.0, 500_000, "abc", new long[2])));
    }

    @Test
    void objectReturn() throws Throwable {
        assertEquals("done", onSmallStack(() -> call("describe", 1_000_000)));
    }

    @Test
    void exceptionsKeepTheirType() {
        Throwable t = assertThrows(Throwable.class, () -> onSmallStack(() -> call("fail", 1_000_000)));
        assertInstanceOf(IllegalStateException.class, t);
        assertEquals("boom", t.getMessage());
    }

    // ---- Depth.THREAD_LOCAL ----

    private Object construct(String simpleName) throws Exception {
        return loader.loadClass(PKG + "Samples$" + simpleName).getDeclaredConstructor().newInstance();
    }

    private Object callOn(Object target, String name, Class<?>[] types, Object... args) throws Throwable {
        try {
            return target.getClass().getMethod(name, types).invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void threadLocalOverridableMethod() throws Throwable {
        Object chain = call("chain", 500_000, false);
        assertEquals(500_001L, onSmallStack(() -> callOn(chain, "length", new Class<?>[0])));
    }

    @Test
    void threadLocalThroughUnannotatedOverride() throws Throwable {
        Object chain = call("chain", 300_000, true);
        assertEquals(300_001L, onSmallStack(() -> callOn(chain, "length", new Class<?>[0])));
    }

    @Test
    void threadLocalInterfaceDefaultMethod() throws Throwable {
        Object c = construct("CounterImpl");
        assertEquals(1_000_000L, onSmallStack(() -> callOn(c, "down", new Class<?>[] {long.class}, 1_000_000L)));
    }

    @Test
    void threadLocalMutualRecursionVoidWideAndExceptions() throws Throwable {
        assertEquals(true, onSmallStack(() -> call("tlEven", 1_000_000)));
        assertEquals(false, onSmallStack(() -> call("tlEven", 1_000_001)));
        int[] counter = new int[1];
        onSmallStack(() -> call("tlTick", 1_000_000, counter));
        assertEquals(1_000_000, counter[0]);
        assertEquals(1.5 + 3 + 500_000, (double) onSmallStack(() -> call("tlWide", 3L, 1.5, 500_000)), 1e-9);
        Throwable t = assertThrows(Throwable.class, () -> onSmallStack(() -> call("tlFail", 1_000_000)));
        assertInstanceOf(IllegalStateException.class, t);
    }

    @Test
    void mixedModes() throws Throwable {
        assertEquals(1_000_000L, onSmallStack(() -> call("mixedA", 1_000_000)));
    }

    @Test
    void threadLocalAddsNoMembersBeyondTheSyntheticMethods() throws Exception {
        assertThrows(NoSuchFieldException.class, () -> samples().getDeclaredField("stacksafe$depth"));
    }

    private static final boolean CONTINUATION = "continuation".equals(System.getProperty("stacksafe.backend"));

    @Test
    void crossingsStayOnOneThread() throws Throwable {
        for (String probe : new String[] {"probeP", "probeA"}) {
            Thread[] out = new Thread[2];
            Thread caller = onSmallStack(() -> { call(probe, 300_000, out); return Thread.currentThread(); });
            assertNotNull(out[0]);
            assertSame(out[0], out[1], probe + " started a nested segment");
            if (CONTINUATION) assertSame(caller, out[0], "the continuation backend runs on the caller's thread");
            else assertTrue(out[0].isVirtual());
        }
    }

    @Test
    void threadIdentityFollowsTheBackend() throws Throwable {
        Thread[] out = new Thread[2];
        Thread caller = onSmallStack(() -> { call("probeP", 10, out); return Thread.currentThread(); });
        assertEquals(CONTINUATION, out[0] == caller);
    }

    @Test
    void callerThreadLocalsAreVisibleOnlyToTheContinuationBackend() throws Throwable {
        ThreadLocal<String> tl = new ThreadLocal<>();
        // body observes the caller's thread-local iff it runs on the caller's thread
        String seen = onSmallStack(() -> {
            tl.set("caller");
            Thread[] out = new Thread[2];
            call("probeP", 10, out);
            return out[0] == Thread.currentThread() ? tl.get() : "other thread";
        });
        assertEquals(CONTINUATION ? "caller" : "other thread", seen);
    }

    @Test
    void depthCounterDoesNotLeakOntoTheCallerThread() throws Throwable {
        onSmallStack(() -> { call("isEven", 100_000); return null; });
        assertNull(StackSafeRuntime.DEPTH.get());
        // a second call on the same platform thread must still be stack-safe
        assertEquals(true, onSmallStack(() -> {
            call("isEven", 10); assertNull(StackSafeRuntime.DEPTH.get());
            return call("isEven", 1_000_000);
        }));
    }

    @Test
    void parameterModeOverrideCallingSuper() throws Throwable {
        Object q = construct("Q");
        assertEquals(500_000L, onSmallStack(() -> callOn(q, "f", new Class<?>[] {long.class}, 500_000L)));
    }

    @Test
    void annotationsAndSignaturesSurviveOnTheEntryPoint() throws Throwable {
        Method eval = samples().getMethod("eval", loader.loadClass(PKG + "Samples$Expr"));
        assertEquals(long.class, eval.getReturnType());
        assertFalse(eval.isSynthetic());
        assertTrue(java.util.Arrays.stream(samples().getDeclaredMethods())
            .anyMatch(m -> m.getName().equals("eval$ss") && m.isSynthetic()));
    }

    @Test
    void classesWithoutTheAnnotationAreLeftAlone() throws Exception {
        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream("StackSafeTransformerTest.class")) {
            bytes = in.readAllBytes();
        }
        assertNull(new StackSafeTransformer().transform(getClass().getClassLoader(), bytes));
    }

    @Test
    void intervalMustBePowerOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> new StackSafeTransformer(1000));
    }
}
