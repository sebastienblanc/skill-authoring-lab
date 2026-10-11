package org.sebi.skilllab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * PROVIDED — one headless OpenCode session: {@code opencode run --format json --pure "<prompt>"} in the workspace.
 *
 * The raw JSON events are kept in {@code .skill-lab/opencode-events.jsonl} and parsed into a {@link Result}:
 * which tools were called (including the {@code skill} tool, which proves the skill was loaded), the final
 * answer, token usage and cost.
 *
 * Environment: {@code OPENCODE_BIN} (default {@code opencode} on the PATH), {@code OPENCODE_MODEL}
 * (provider/model, e.g. {@code opencode/claude-sonnet-4-5}; default: the model configured in OpenCode),
 * {@code OPENCODE_VARIANT} (provider-specific reasoning effort, optional).
 */
public final class OpenCodeSession {

    public record ToolCall(String tool, String status, JsonNode input, String title) {}

    public record Result(String sessionId, List<ToolCall> toolCalls, String finalText, List<String> errors,
                         long totalTokens, double cost, Duration elapsed, Path eventsLog, Path stderrLog) {

        /** True if the {@code skill} tool completed a load of this skill at least once during the session. */
        public boolean loadedSkill(String name) {
            return toolCalls.stream().anyMatch(c -> "skill".equals(c.tool())
                && "completed".equals(c.status())
                && name.equals(c.input().path("name").asText()));
        }

        /** The message of each error event (provider errors such as a disabled model, auth failures). */
        public List<String> errorMessages() {
            List<String> msgs = new ArrayList<>();
            for (String raw : errors) {
                try {
                    JsonNode e = M.readTree(raw).path("error");
                    String m = e.path("data").path("message").asText("");
                    msgs.add(m.isBlank() ? e.toString() : e.path("name").asText("error") + ": " + m);
                } catch (IOException ex) {
                    msgs.add(raw);
                }
            }
            return msgs;
        }

        /** Tool name → number of calls, for the console summary. */
        public Map<String, Long> callsPerTool() {
            Map<String, Long> m = new TreeMap<>();
            toolCalls.forEach(c -> m.merge(c.tool(), 1L, Long::sum));
            return m;
        }

        public String summary() {
            return String.format("session %s: %d tool calls %s, %d tokens, cost %.4f, %d s, %d error event(s); events in %s",
                sessionId, toolCalls.size(), callsPerTool(), totalTokens, cost, elapsed.toSeconds(), errors.size(), eventsLog);
        }
    }

    private static final ObjectMapper M = new ObjectMapper();

    public static String binary() {
        String b = System.getenv("OPENCODE_BIN");
        return b == null || b.isBlank() ? "opencode" : b;
    }

    /** True if the OpenCode binary answers {@code --version}. Used as a test assumption, not an assertion. */
    public static boolean available() {
        Process p = null;
        try {
            p = new ProcessBuilder(binary(), "--version")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                System.err.println("OpenCode --version timed out after 30 seconds");
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            System.err.println("Cannot run OpenCode --version: " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking OpenCode", e);
        } finally {
            if (p != null) terminate(p, new LinkedHashSet<>());
        }
    }

    /** Runs the prompt in the workspace and waits for the session to end, at most {@code timeout}. */
    public static Result run(SkillWorkspace ws, String prompt, Duration timeout) {
        List<String> cmd = new ArrayList<>(List.of(binary(), "run", "--format", "json", "--pure", "--dir", ws.root().toString()));
        String model = System.getenv("OPENCODE_MODEL");
        if (model != null && !model.isBlank()) cmd.addAll(List.of("--model", model));
        String variant = System.getenv("OPENCODE_VARIANT");
        if (variant != null && !variant.isBlank()) cmd.addAll(List.of("--variant", variant));
        cmd.add(prompt);

        return run(ws, cmd, timeout);
    }

    static Result run(SkillWorkspace ws, List<String> cmd, Duration timeout) {
        if (timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("OpenCode timeout must be positive");
        long budget = timeout.toNanos();
        Path events = ws.logDir().resolve("opencode-events.jsonl");
        Path stderr = ws.logDir().resolve("opencode-stderr.log");
        Process p = null;
        Set<ProcessHandle> descendants = new LinkedHashSet<>();
        try {
            Files.writeString(ws.logDir().resolve("opencode-command.txt"), String.join(" ", cmd) + "\n");
            long start = System.nanoTime();
            // A descendant may inherit stdout after OpenCode exits. A file redirect avoids an unbounded
            // pipe-reader join, which would otherwise bypass the process timeout.
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(ws.root().toFile())
                .redirectOutput(events.toFile()).redirectError(stderr.toFile());
            // ProcessBuilder changes the working directory but inherits PWD from the Maven JVM; OpenCode resolves its
            // project from PWD, so without this it would look for skills next to this pom.xml instead of the workspace.
            pb.environment().put("PWD", ws.root().toString());
            p = pb.start();
            // Headless OpenCode reads a prompt from stdin when stdin is open: close it or the session never starts.
            p.getOutputStream().close();

            System.out.println("OpenCode generation started (timeout " + timeout + "). Events: " + events
                + "; stderr: " + stderr);
            long nextProgress = TimeUnit.SECONDS.toNanos(10);
            while (true) {
                p.descendants().forEach(descendants::add);
                long elapsed = System.nanoTime() - start;
                long remaining = budget - elapsed;
                if (remaining <= 0) {
                    throw new IllegalStateException("OpenCode did not finish within " + timeout
                        + " (-Dskill.lab.timeout.minutes). Events: " + events + "; stderr: " + stderr);
                }
                if (p.waitFor(Math.min(remaining, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS)) break;
                if (elapsed >= nextProgress) {
                    System.out.printf("OpenCode still running: %d s elapsed, %d bytes of events; %s%n",
                        TimeUnit.NANOSECONDS.toSeconds(elapsed), Files.size(events), events);
                    nextProgress = elapsed + TimeUnit.SECONDS.toNanos(10);
                }
            }
            terminate(p, descendants);
            Result result = parse(Files.readAllLines(events), Duration.ofNanos(System.nanoTime() - start), events, stderr);
            if (p.exitValue() != 0 || !result.errors().isEmpty()) {
                throw new IllegalStateException("OpenCode exited with code " + p.exitValue() + ". "
                    + (result.errors().isEmpty() ? "" : "Error events: " + result.errorMessages() + ". ")
                    + "See " + stderr + " and " + events);
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for OpenCode", e);
        } finally {
            if (p != null) terminate(p, descendants);
        }
    }

    private static void terminate(Process p, Set<ProcessHandle> descendants) {
        p.descendants().forEach(descendants::add);
        // Remember children observed while the parent was alive: after it exits they may be reparented.
        List<ProcessHandle> handles = new ArrayList<>(descendants);
        for (int i = handles.size() - 1; i >= 0; i--) {
            ProcessHandle child = handles.get(i);
            if (child.isAlive() && !child.destroyForcibly() && child.isAlive())
                System.err.println("Could not stop OpenCode descendant PID " + child.pid());
        }
        if (p.isAlive()) p.destroyForcibly();
    }

    /** One JSON object per line: step_start, text, tool_use, step_finish, error. Non-JSON lines are ignored. */
    static Result parse(List<String> lines, Duration elapsed, Path events, Path stderr) {
        String sessionId = "?";
        List<ToolCall> calls = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String lastText = "";
        long tokens = 0;
        double cost = 0;
        for (String line : lines) {
            if (line.isBlank()) continue;
            JsonNode e;
            try { e = M.readTree(line); } catch (IOException ex) { continue; }
            if (!e.isObject()) continue;
            if (e.hasNonNull("sessionID")) sessionId = e.get("sessionID").asText();
            JsonNode part = e.path("part");
            switch (e.path("type").asText()) {
                case "tool_use" -> calls.add(new ToolCall(part.path("tool").asText(), part.path("state").path("status").asText(),
                    part.path("state").path("input"), part.path("state").path("title").asText()));
                case "text" -> lastText = part.path("text").asText();
                case "step_finish" -> {
                    tokens += part.path("tokens").path("total").asLong();
                    cost += part.path("cost").asDouble();
                }
                case "error" -> errors.add(line);
                default -> { }
            }
        }
        return new Result(sessionId, List.copyOf(calls), lastText, List.copyOf(errors), tokens, cost, elapsed, events, stderr);
    }
}
