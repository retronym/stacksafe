package stacksafe.samples;

import stacksafe.StackSafe;
import stacksafe.StackSafe.Depth;

public final class Samples {
    public Samples() {}

    public sealed interface Expr {
        record Lit(long value) implements Expr {}
        record Add(Expr left, Expr right) implements Expr {}
        record Neg(Expr operand) implements Expr {}
    }

    public static Expr leftDeep(int n) {
        Expr e = new Expr.Lit(1);
        for (int i = 0; i < n; i++) e = new Expr.Add(e, new Expr.Lit(1));
        return e;
    }

    @StackSafe
    public static long eval(Expr e) {
        return switch (e) {
            case Expr.Lit l -> l.value();
            case Expr.Add a -> eval(a.left()) + eval(a.right());
            case Expr.Neg n -> -eval(n.operand());
        };
    }

    /** Unannotated twin of {@link #eval}, for contrast. */
    public static long evalPlain(Expr e) {
        return switch (e) {
            case Expr.Lit l -> l.value();
            case Expr.Add a -> evalPlain(a.left()) + evalPlain(a.right());
            case Expr.Neg n -> -evalPlain(n.operand());
        };
    }

    @StackSafe
    public static boolean isEven(int n) { return n == 0 || isOdd(n - 1); }

    @StackSafe
    public static boolean isOdd(int n) { return n != 0 && isEven(n - 1); }

    // private instance method, called through an unannotated public entry point
    public long count(long n) { return countDown(n); }

    @StackSafe
    private long countDown(long n) { return n == 0 ? 0 : 1 + countDown(n - 1); }

    @StackSafe
    public static void tick(int n, int[] counter) {
        if (n == 0) return;
        counter[0]++;
        tick(n - 1, counter);
    }

    // wide params and locals above the inserted depth slot
    @StackSafe
    public static long wide(long a, double b, int n, String s, long[] arr) {
        long local = a * 2;
        double d = b + 0.5;
        int i = n;
        i += 0;
        if (n == 0) return local + (long) d + s.length() + arr.length;
        return wide(a, b, n - 1, s, arr) + 1;
    }

    @StackSafe
    public static String describe(int n) { return n == 0 ? "done" : describe(n - 1); }

    @StackSafe
    public static int fail(int n) {
        if (n == 0) throw new IllegalStateException("boom");
        return fail(n - 1) + 1;
    }

    // ---- Depth.THREAD_LOCAL ----

    /** Overridable method: PARAMETER mode could not redirect calls to this. */
    public static class Node {
        public final Node next;
        public Node(Node next) { this.next = next; }

        @StackSafe(depth = Depth.THREAD_LOCAL)
        public long length() { return next == null ? 1 : 1 + next.length(); }
    }

    /** Unannotated override sitting between the annotated frames. */
    public static final class LoudNode extends Node {
        public LoudNode(Node next) { super(next); }

        @Override
        public long length() { return super.length(); }
    }

    public static Node chain(int n, boolean loud) {
        Node head = new Node(null);
        for (int i = 0; i < n; i++) head = loud ? new LoudNode(head) : new Node(head);
        return head;
    }

    public interface Counter {
        @StackSafe(depth = Depth.THREAD_LOCAL)
        default long down(long n) { return n == 0 ? 0 : 1 + down(n - 1); }
    }

    public static final class CounterImpl implements Counter {
        public CounterImpl() {}
    }

    @StackSafe(depth = Depth.THREAD_LOCAL)
    public static boolean tlEven(int n) { return n == 0 || tlOdd(n - 1); }

    @StackSafe(depth = Depth.THREAD_LOCAL)
    public static boolean tlOdd(int n) { return n != 0 && tlEven(n - 1); }

    @StackSafe(depth = Depth.THREAD_LOCAL)
    public static void tlTick(int n, int[] counter) {
        if (n == 0) return;
        counter[0]++;
        tlTick(n - 1, counter);
    }

    @StackSafe(depth = Depth.THREAD_LOCAL)
    public static double tlWide(long a, double b, int n) {
        if (n == 0) return a + b;
        return tlWide(a, b, n - 1) + 1.0;
    }

    @StackSafe(depth = Depth.THREAD_LOCAL)
    public static int tlFail(int n) {
        if (n == 0) throw new IllegalStateException("boom");
        return tlFail(n - 1) + 1;
    }

    /** PARAMETER method calling a THREAD_LOCAL one and vice versa. */
    @StackSafe
    public static long mixedA(int n) { return n == 0 ? 0 : 1 + mixedB(n - 1); }

    @StackSafe(depth = Depth.THREAD_LOCAL)
    public static long mixedB(int n) { return n == 0 ? 0 : 1 + mixedA(n - 1); }

    // ---- crossings must not start nested segments ----

    @StackSafe
    public static void probeP(int n, Thread[] out) {
        if (out[0] == null) out[0] = Thread.currentThread();
        if (n == 0) { out[1] = Thread.currentThread(); return; }
        bridge(n - 1, out);
    }

    /** Unannotated: probeP's call to this, and this call to probeP's entry point, are a crossing. */
    public static void bridge(int n, Thread[] out) { probeP(n, out); }

    @StackSafe
    public static void probeA(int n, Thread[] out) {
        if (out[0] == null) out[0] = Thread.currentThread();
        if (n == 0) { out[1] = Thread.currentThread(); return; }
        probeB(n - 1, out);
    }

    @StackSafe(depth = Depth.THREAD_LOCAL)
    public static void probeB(int n, Thread[] out) { probeA(n, out); }

    // annotated override calling an annotated super method
    public static class P {
        public P() {}
        @StackSafe public long f(long n) { return n == 0 ? 0 : 1 + f(n - 1); }
    }

    public static class Q extends P {
        public Q() {}
        @StackSafe @Override public long f(long n) { return super.f(n); }
    }
}
