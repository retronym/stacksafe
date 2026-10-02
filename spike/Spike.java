import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;
import java.util.function.Supplier;

public class Spike {
    sealed interface Expr {
        record Lit(long value) implements Expr {}
        record Add(Expr left, Expr right) implements Expr {}
        record Neg(Expr operand) implements Expr {}
    }

    // ---- runtime support (as in the proposal) ----
    static final ContinuationScope SCOPE = new ContinuationScope("stack-safe");
    static long yields;

    @SuppressWarnings("unchecked")
    static <T> T run(Supplier<T> body) {
        Object[] result = new Object[1];
        Throwable[] failure = new Throwable[1];
        Continuation k = new Continuation(SCOPE, () -> {
            try { result[0] = body.get(); } catch (Throwable t) { failure[0] = t; }
        });
        while (!k.isDone()) k.run();
        if (failure[0] != null) throw new RuntimeException(failure[0]);
        return (T) result[0];
    }
    static void yieldNow() { yields++; Continuation.yield(SCOPE); }

    // ---- $ss form ----
    static int MASK;
    static long evalSs(Expr e, int depth) {
        if ((depth & MASK) == MASK) yieldNow();
        return switch (e) {
            case Expr.Lit l -> l.value();
            case Expr.Add a -> evalSs(a.left(), depth + 1) + evalSs(a.right(), depth + 1);
            case Expr.Neg n -> -evalSs(n.operand(), depth + 1);
        };
    }
    // virtual-thread variant
    static long evalVt(Expr e, int depth) {
        if ((depth & MASK) == MASK) { yields++; Thread.yield(); }
        return switch (e) {
            case Expr.Lit l -> l.value();
            case Expr.Add a -> evalVt(a.left(), depth + 1) + evalVt(a.right(), depth + 1);
            case Expr.Neg n -> -evalVt(n.operand(), depth + 1);
        };
    }
    // plain
    static long eval(Expr e) {
        return switch (e) {
            case Expr.Lit l -> l.value();
            case Expr.Add a -> eval(a.left()) + eval(a.right());
            case Expr.Neg n -> -eval(n.operand());
        };
    }

    static Expr build(int n) {
        Expr e = new Expr.Lit(1);
        for (int i = 0; i < n; i++) e = new Expr.Add(e, new Expr.Lit(1));
        return e;
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int n = Integer.parseInt(args[1]);
        MASK = Integer.parseInt(args[2]) - 1;           // N, power of two
        long stack = args.length > 3 ? Long.parseLong(args[3]) : 1L << 30;
        Expr e = build(n);
        System.gc();
        long t0 = System.nanoTime();
        long[] out = new long[1];
        Throwable[] err = new Throwable[1];
        switch (mode) {
            case "cont" -> out[0] = run(() -> evalSs(e, 0));
            case "vt" -> {
                Thread t = Thread.ofVirtual().start(() -> {
                    try { out[0] = evalVt(e, 0); } catch (Throwable x) { err[0] = x; }
                });
                t.join();
            }
            case "big" -> {
                Thread t = new Thread(null, () -> {
                    try { out[0] = eval(e); } catch (Throwable x) { err[0] = x; }
                }, "big", stack);
                t.start(); t.join();
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("mode=%s n=%d N=%d result=%d err=%s time=%dms yields=%d%n",
            mode, n, MASK + 1, out[0], err[0], ms, yields);
    }
}
