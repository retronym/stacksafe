package stacksafe;

import java.util.function.Supplier;

/**
 * Runtime support for transformed methods. Two backends, chosen once by the {@code stacksafe.backend} system
 * property (the agent sets it from {@code backend=}):
 *
 * <ul>
 *   <li>{@code virtual} (default, public API only): each {@link #run} executes its body on a virtual thread and
 *       {@link #yieldNow} is {@link Thread#yield()}, which unmounts the virtual thread and copies its stack
 *       frames to the heap. A pinned virtual thread's yield is a no-op.</li>
 *   <li>{@code continuation}: the body runs on the caller's own thread inside a
 *       {@code jdk.internal.vm.Continuation} (see {@link ContinuationBackend}); needs {@code --add-exports}.</li>
 * </ul>
 */
public final class StackSafeRuntime {
    /**
     * Per-thread recursion counter for {@code Depth.THREAD_LOCAL}; null outside a {@link #run} segment.
     * Transformed code reads this field directly; being {@code static final}, the JIT treats it as a constant.
     */
    public static final ThreadLocal<int[]> DEPTH = new ThreadLocal<>();

    private static final boolean CONTINUATION = switch (System.getProperty("stacksafe.backend", "virtual")) {
        case "virtual" -> false;
        case "continuation" -> true;
        default -> throw new IllegalArgumentException(
            "stacksafe.backend must be 'virtual' or 'continuation': " + System.getProperty("stacksafe.backend"));
    };

    private StackSafeRuntime() {}

    public static void enter(int[] depth, int mask) {
        if ((++depth[0] & mask) == mask) yieldNow();
    }

    public static void leave(int[] depth) { depth[0]--; }

    public static <T> T run(Supplier<T> body) {
        return CONTINUATION ? ContinuationBackend.run(body) : runOnVirtualThread(body);
    }

    @SuppressWarnings("unchecked")
    private static <T> T runOnVirtualThread(Supplier<T> body) {
        Object[] result = new Object[1];
        Throwable[] failure = new Throwable[1];
        Thread vt = Thread.ofVirtual().name("stack-safe").unstarted(() -> {
            DEPTH.set(new int[1]);
            try {
                result[0] = body.get();
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        vt.start();
        boolean interrupted = false;
        while (true) {
            try {
                vt.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure[0] != null) throw sneaky(failure[0]);
        return (T) result[0];
    }

    public static void yieldNow() {
        if (CONTINUATION) ContinuationBackend.yieldNow();
        else Thread.yield();
    }

    @SuppressWarnings("unchecked")
    static <E extends Throwable> RuntimeException sneaky(Throwable t) throws E {
        throw (E) t;
    }
}
