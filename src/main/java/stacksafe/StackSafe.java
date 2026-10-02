package stacksafe;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method whose recursion depth is unbounded and must not be limited
 * by the thread stack size.
 *
 * <p>The {@link StackSafeAgent} rewrites annotated methods to periodically
 * suspend and resume their enclosing virtual thread, moving stack frames to the
 * heap. How the recursion depth is tracked is chosen with {@link #depth()}.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface StackSafe {
    /** How the transformed method tracks recursion depth. */
    enum Depth {
        /**
         * Depth travels as a synthetic {@code int} parameter of a cloned {@code m$ss} method. Fastest.
         * Recursive calls are redirected to the clone only when the callee is statically bound
         * ({@code static}, {@code private}, {@code final}, or in a {@code final} class), and calls
         * through unannotated code restart the depth count.
         */
        PARAMETER,

        /**
         * Depth is a per-thread counter held in a {@code ThreadLocal} that the transform emits as a
         * {@code static final} field. Slower per call, but no call site is rewritten, so it works for
         * overridable methods, interface methods and mutual recursion across classes, and the count
         * carries through unannotated frames.
         */
        THREAD_LOCAL
    }

    Depth depth() default Depth.PARAMETER;
}
