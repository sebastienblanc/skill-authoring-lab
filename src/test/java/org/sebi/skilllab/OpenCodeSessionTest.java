package org.sebi.skilllab;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class OpenCodeSessionTest {

    @TempDir Path temp;
    private SkillWorkspace ws;

    @BeforeEach
    void prepare() {
        ws = SkillWorkspace.prepare(Path.of("skills", SkillWorkspace.SKILL_NAME),
            Path.of("domain", "catalogue-services.json"), temp);
    }

    @AfterEach
    void stopFixtures() throws IOException {
        for (String name : List.of("parent.pid", "child.pid")) {
            Path pid = temp.resolve(name);
            if (Files.exists(pid))
                ProcessHandle.of(Long.parseLong(Files.readString(pid))).filter(ProcessHandle::isAlive)
                    .ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    void capturesEventsWithClosedStdinAndCorrectWorkingDirectory() throws IOException {
        var result = run("complete", Duration.ofSeconds(5));

        assertTrue(result.loadedSkill(SkillWorkspace.SKILL_NAME));
        assertEquals("fixture-session", result.sessionId());
        assertEquals("Created pom.xml", result.finalText());
        assertEquals(42, result.totalTokens());
        assertEquals(0.125, result.cost());
        assertTrue(result.errors().isEmpty());
        assertTrue(Files.readString(result.stderrLog()).contains("fixture stderr"));
        assertTrue(Files.readString(result.eventsLog()).contains("\"tool_use\""));
        assertTrue(Files.readString(ws.logDir().resolve("opencode-command.txt")).contains("complete"));
    }

    @Test
    void timesOutAndStopsParentAndChild() throws Exception {
        var error = assertTimeout(Duration.ofSeconds(5), () -> assertThrows(IllegalStateException.class,
            () -> run("hang", Duration.ofSeconds(2))));

        assertTrue(error.getMessage().contains("did not finish"));
        assertTrue(error.getMessage().contains("opencode-stderr.log"));
        assertStopped("parent.pid");
        assertStopped("child.pid");
    }

    @Test
    void doesNotWaitForInheritedStdoutAfterParentExits() throws Exception {
        var result = assertTimeout(Duration.ofSeconds(5), () -> run("orphan", Duration.ofSeconds(4)));

        assertTrue(result.loadedSkill(SkillWorkspace.SKILL_NAME));
        assertStopped("child.pid");
    }

    @Test
    void interruptionStopsTheProcessTreeAndPreservesInterruptStatus() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                run("hang", Duration.ofMinutes(1));
                failure.set(new AssertionError("Generation unexpectedly completed"));
            } catch (IllegalStateException e) {
                if (!Thread.currentThread().isInterrupted())
                    failure.set(new AssertionError("Interrupt status was lost", e));
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        worker.start();
        try {
            await(() -> Files.exists(temp.resolve("child.pid")));
            worker.interrupt();
            worker.join(3_000);
            assertFalse(worker.isAlive(), "Generation did not respond to interruption");
            assertNull(failure.get());
            assertStopped("parent.pid");
            assertStopped("child.pid");
        } finally {
            worker.interrupt();
            worker.join(3_000);
        }
    }

    @Test
    void surfacesProviderErrorEvenWithZeroExitCode() {
        var error = assertThrows(IllegalStateException.class, () -> run("error", Duration.ofSeconds(5)));
        assertTrue(error.getMessage().contains("ProviderAuthError: fixture authentication failed"));
    }

    @Test
    void surfacesNonzeroExitCode() {
        var error = assertThrows(IllegalStateException.class, () -> run("exit", Duration.ofSeconds(5)));
        assertTrue(error.getMessage().contains("exited with code 7"));
    }

    @Test
    void rejectsNonpositiveTimeoutBeforeLaunching() {
        assertThrows(IllegalArgumentException.class, () -> run("complete", Duration.ZERO));
        assertFalse(Files.exists(temp.resolve("parent.pid")));
    }

    private OpenCodeSession.Result run(String mode, Duration timeout) {
        return OpenCodeSession.run(ws, command(mode), timeout);
    }

    private void assertStopped(String pidFile) throws Exception {
        assertTrue(Files.exists(temp.resolve(pidFile)), "Fixture never started: " + pidFile);
        long pid = Long.parseLong(Files.readString(temp.resolve(pidFile)));
        await(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long start = System.nanoTime();
        while (!condition.getAsBoolean() && System.nanoTime() - start < Duration.ofSeconds(3).toNanos())
            Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition was not met within 3 seconds");
    }

    private static List<String> command(String mode) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            Fixture.class.getName(), mode);
    }

    public static class Fixture {
        public static void main(String[] args) throws Exception {
            String mode = args[0];
            Files.writeString(Path.of(mode.equals("child") ? "child.pid" : "parent.pid"),
                Long.toString(ProcessHandle.current().pid()));
            if (mode.equals("child")) {
                Thread.sleep(60_000);
                return;
            }
            if (System.in.read() != -1) throw new IllegalStateException("stdin is still open");
            if (!Files.isSameFile(Path.of(System.getenv("PWD")), Path.of("").toAbsolutePath()))
                throw new IllegalStateException("PWD differs from the working directory");
            System.err.println("fixture stderr");
            System.out.println("""
                {"type":"tool_use","sessionID":"fixture-session","part":{"tool":"skill","state":{"status":"completed","input":{"name":"create-quarkus-mcp-server"}}}}
                {"type":"step_finish","part":{"tokens":{"total":42},"cost":0.125}}
                {"type":"text","part":{"text":"Created pom.xml"}}
                """);
            if (mode.equals("hang") || mode.equals("orphan")) {
                new ProcessBuilder(command("child")).inheritIO().start();
                await(() -> Files.exists(Path.of("child.pid")));
                Thread.sleep(mode.equals("hang") ? 60_000 : 1_500);
            } else if (mode.equals("error")) {
                System.out.println("""
                    {"type":"error","error":{"name":"ProviderAuthError","data":{"message":"fixture authentication failed"}}}
                    """);
            } else if (mode.equals("exit")) {
                System.exit(7);
            }
        }
    }
}
