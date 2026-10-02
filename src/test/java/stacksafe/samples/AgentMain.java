package stacksafe.samples;

import stacksafe.StackSafe;

/** Launched by {@code AgentIT} in a forked JVM under {@code -javaagent}; prints the result of a deep recursion. */
public final class AgentMain {
    @StackSafe
    static long length(Samples.Expr e) {
        return e instanceof Samples.Expr.Add a ? length(a.left()) + 1 : 1;
    }

    public static void main(String[] args) throws Exception {
        Samples.Expr e = Samples.leftDeep(Integer.parseInt(args[0]));
        long[] out = new long[1];
        Throwable[] failure = new Throwable[1];
        // a deliberately small stack: plain recursion this deep would overflow it
        Thread t = new Thread(null, () -> {
            try { out[0] = length(e); } catch (Throwable x) { failure[0] = x; }
        }, "small", 512 * 1024);
        t.start();
        t.join();
        if (failure[0] != null) throw new RuntimeException(failure[0]);
        System.out.println("length=" + out[0]);
    }
}
