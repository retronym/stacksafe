package stacksafe;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Runs a real JVM with {@code -javaagent} on the shaded jar, so the manifest, shading and option parsing are exercised. */
class AgentIT {
    private static final String MAIN = "stacksafe.samples.AgentMain";

    private record Result(int exit, String output) {}

    private static Result run(String agentOptions, String... extraJvmArgs) throws Exception {
        String jar = System.getProperty("stacksafe.agentJar");
        assertNotNull(jar, "stacksafe.agentJar not set; run via mvn verify");
        assertTrue(new File(jar).isFile(), jar);
        // jar first so the shaded runtime is the only copy; test-classes for the sample app
        String cp = jar + File.pathSeparator + new File("target/test-classes").getAbsolutePath();
        List<String> cmd = new ArrayList<>(List.of(
            new File(System.getProperty("java.home"), "bin/java").getPath(),
            "-javaagent:" + jar + (agentOptions.isEmpty() ? "" : "=" + agentOptions)));
        cmd.addAll(List.of(extraJvmArgs));
        cmd.addAll(List.of("-cp", cp, MAIN, "1000000"));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "timed out:\n" + out);
        return new Result(p.exitValue(), out);
    }

    @Test
    void defaultBackend() throws Exception {
        Result r = run("");
        assertEquals(0, r.exit, r.output);
        assertTrue(r.output.contains("length=1000001"), r.output);
    }

    @Test
    void virtualBackendWithInterval() throws Exception {
        Result r = run("interval=256,backend=virtual");
        assertEquals(0, r.exit, r.output);
        assertTrue(r.output.contains("length=1000001"), r.output);
    }

    @Test
    void continuationBackendExportedByTheAgent() throws Exception {
        // no --add-exports on the command line: the agent has to export jdk.internal.vm itself.
        // interval=256: at the default 1024, 1024 interpreted frames plus Continuation.doYield overflow this 512 KB stack.
        Result r = run("backend=continuation,interval=256");
        assertEquals(0, r.exit, r.output);
        assertTrue(r.output.contains("length=1000001"), r.output);
    }

    @Test
    void withoutTheAgentTheSameProgramOverflows() throws Exception {
        String cp = new File("target/classes").getAbsolutePath() + File.pathSeparator
            + new File("target/test-classes").getAbsolutePath();
        Process p = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
            "-cp", cp, MAIN, "1000000").redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS));
        assertNotEquals(0, p.exitValue(), out);
        assertTrue(out.contains("StackOverflowError"), out);
    }

    @Test
    void badOptionFailsFast() throws Exception {
        Result r = run("interval=1000");
        assertNotEquals(0, r.exit, r.output);
        assertTrue(r.output.contains("power of two"), r.output);
        r = run("bogus=1");
        assertNotEquals(0, r.exit, r.output);
        assertTrue(r.output.contains("unknown agent option"), r.output);
        r = run("backend=nope");
        assertNotEquals(0, r.exit, r.output);
        assertTrue(r.output.contains("backend must be"), r.output);
    }
}
