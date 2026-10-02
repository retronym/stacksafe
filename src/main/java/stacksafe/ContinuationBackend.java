package stacksafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Supplier;

/**
 * Drives {@code jdk.internal.vm.Continuation} directly, on the caller's own thread. Internal API: it is reached
 * reflectively through method handles so the project compiles against public API only, and it needs
 * {@code --add-exports java.base/jdk.internal.vm=ALL-UNNAMED} (the agent does this for {@code backend=continuation}).
 */
final class ContinuationBackend {
    private static final Object SCOPE;
    private static final MethodHandle NEW_CONTINUATION, RUN, IS_DONE, YIELD;

    static {
        try {
            Class<?> scopeClass = Class.forName("jdk.internal.vm.ContinuationScope");
            Class<?> contClass = Class.forName("jdk.internal.vm.Continuation");
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            SCOPE = lookup.findConstructor(scopeClass, MethodType.methodType(void.class, String.class))
                .asType(MethodType.methodType(Object.class, String.class)).invoke("stack-safe");
            NEW_CONTINUATION = lookup.findConstructor(contClass, MethodType.methodType(void.class, scopeClass, Runnable.class))
                .asType(MethodType.methodType(Object.class, Object.class, Runnable.class));
            RUN = lookup.findVirtual(contClass, "run", MethodType.methodType(void.class))
                .asType(MethodType.methodType(void.class, Object.class));
            IS_DONE = lookup.findVirtual(contClass, "isDone", MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Object.class));
            YIELD = lookup.findStatic(contClass, "yield", MethodType.methodType(boolean.class, scopeClass))
                .asType(MethodType.methodType(boolean.class, Object.class));
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(new IllegalStateException(
                "stacksafe: the continuation backend needs --add-exports java.base/jdk.internal.vm=ALL-UNNAMED "
                    + "(or -javaagent:stacksafe.jar=backend=continuation): " + t, t));
        }
    }

    private ContinuationBackend() {}

    @SuppressWarnings("unchecked")
    static <T> T run(Supplier<T> body) {
        Object[] result = new Object[1];
        Throwable[] failure = new Throwable[1];
        int[] outer = StackSafeRuntime.DEPTH.get();
        try {
            Runnable segment = () -> {
                StackSafeRuntime.DEPTH.set(new int[1]);
                try {
                    result[0] = body.get();
                } catch (Throwable t) {
                    failure[0] = t;
                }
            };
            Object k = (Object) NEW_CONTINUATION.invokeExact(SCOPE, segment);
            while (!(boolean) IS_DONE.invokeExact(k)) RUN.invokeExact(k);
        } catch (Throwable t) {
            throw StackSafeRuntime.sneaky(t);
        } finally {
            // the segment ran on this thread: put its counter back
            if (outer == null) StackSafeRuntime.DEPTH.remove();
            else StackSafeRuntime.DEPTH.set(outer);
        }
        if (failure[0] != null) throw StackSafeRuntime.sneaky(failure[0]);
        return (T) result[0];
    }

    static void yieldNow() {
        try {
            boolean ignored = (boolean) YIELD.invokeExact(SCOPE);
        } catch (IllegalStateException pinned) {
            // Pinned (native frame, critical section): carry on down the stack until the next interval.
        } catch (Throwable t) {
            throw StackSafeRuntime.sneaky(t);
        }
    }
}
