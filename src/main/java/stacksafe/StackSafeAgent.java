package stacksafe;

import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.Set;

/**
 * {@code java -javaagent:stacksafe.jar[=interval=1024][,backend=virtual|continuation] ...}
 *
 * <ul>
 *   <li>{@code interval}: recursive calls between yields; a power of two, and small enough that
 *       {@code interval} frames fit comfortably in the thread stack.</li>
 *   <li>{@code backend}: {@code virtual} (default, public API) or {@code continuation} (internal API, runs the
 *       body on the caller's thread; the agent exports {@code jdk.internal.vm} to itself).</li>
 * </ul>
 */
public final class StackSafeAgent {
    private StackSafeAgent() {}

    public static void premain(String args, Instrumentation inst) {
        int interval = StackSafeTransformer.DEFAULT_INTERVAL;
        String backend = "virtual";
        if (args != null) {
            for (String kv : args.split(",")) {
                String[] p = kv.split("=", 2);
                if (p.length == 2 && p[0].trim().equals("interval")) interval = Integer.parseInt(p[1].trim());
                else if (p.length == 2 && p[0].trim().equals("backend")) backend = p[1].trim();
                else throw new IllegalArgumentException("stacksafe: unknown agent option '" + kv + "'");
            }
        }
        if (backend.equals("continuation")) {
            inst.redefineModule(Object.class.getModule(), Set.of(),
                Map.of("jdk.internal.vm", Set.of(StackSafeAgent.class.getModule())), Map.of(), Set.of(), Map.of());
        } else if (!backend.equals("virtual")) {
            throw new IllegalArgumentException("stacksafe: backend must be 'virtual' or 'continuation': " + backend);
        }
        System.setProperty("stacksafe.backend", backend);
        try {
            Class.forName("stacksafe.StackSafeRuntime");   // fail now, not in the middle of the application
            if (backend.equals("continuation")) Class.forName("stacksafe.ContinuationBackend");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        inst.addTransformer(new StackSafeTransformer(interval));
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }
}
