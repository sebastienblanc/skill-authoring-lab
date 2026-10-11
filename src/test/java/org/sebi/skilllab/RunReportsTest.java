package org.sebi.skilllab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RunReportsTest {
    private static final ObjectMapper M = new ObjectMapper();
    @TempDir Path temp;

    @Test
    void savesEachJudgmentAndUsesTheInstalledSkillFingerprint() throws Exception {
        Path generated = temp.resolve("workspaces/demo");
        Path installed = generated.resolve(".opencode/skills").resolve(SkillWorkspace.DEGRADED_SKILL_NAME);
        Files.createDirectories(installed);
        Files.writeString(installed.resolve("SKILL.md"), "The installed snapshot, not the current authoring file.");

        RunReports.save(temp, generated, installed, session(), verdict(1), 0.4, 7, false);
        RunReports.save(temp, generated, installed, session(), verdict(0.3), 8, 7, true);

        var history = RunReports.history(temp);
        assertEquals(2, history.path("runs").size(), "History is not overwritten by a rejudge or duplicated by snapshots");
        assertTrue(history.path("warnings").isEmpty());
        var last = M.readTree(temp.resolve("skill-lab/last-run.json").toFile());
        assertEquals("undecided", last.path("status").asText());
        assertEquals("degraded", last.path("skill_variant").asText());
        assertEquals("rejudged", last.path("mode").asText());
        assertTrue(last.path("skill_loaded").asBoolean());
        assertFalse(last.has("opencode_cost"), "Rejudging must not count generation cost again");
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(installed.resolve("SKILL.md")))), last.path("skill_sha256").asText());
        assertEquals(last, M.readTree(generated.resolve(".skill-lab/run.json").toFile()));
        assertTrue(history.path("runs").findValuesAsText("status").contains("failed"));
    }

    @Test
    void importsLegacyReportsWithoutDuplicatingTheLastRun() throws IOException {
        var report = legacy(temp.resolve("workspaces/old"));
        RunReports.write(temp.resolve("workspaces/old/.skill-lab/run.json"), report);
        RunReports.write(temp.resolve("skill-lab/last-run.json"), report);
        var history = RunReports.history(temp);
        assertEquals(1, history.path("runs").size());
        var run = history.path("runs").get(0);
        assertTrue(run.path("legacy").asBoolean());
        assertEquals("standard", run.path("skill_variant").asText());
        assertEquals("passed", run.path("status").asText());
        assertFalse(run.path("evaluated_at").asText().isBlank());
    }

    @Test
    void rejudgingALegacyWorkspaceDoesNotLoseItsOriginalScore() throws IOException {
        Path generated = temp.resolve("workspaces/old");
        RunReports.write(generated.resolve(".skill-lab/run.json"), legacy(generated));
        Path installed = generated.resolve(".opencode/skills").resolve(SkillWorkspace.DEGRADED_SKILL_NAME);
        Files.createDirectories(installed);
        Files.writeString(installed.resolve("SKILL.md"), "fixture");
        RunReports.save(temp, generated, installed, session(), verdict(1), 1, 7, true);
        var runs = RunReports.history(temp).path("runs");
        assertEquals(2, runs.size());
        assertTrue(runs.findValues("score").stream().anyMatch(n -> n.asDouble() == 9.78));
    }

    @Test
    void reportsCorruptFilesAndHandlesEmptyHistory() throws IOException {
        assertTrue(RunReports.history(temp).path("runs").isEmpty());
        Path reports = temp.resolve("skill-lab/runs");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("broken.json"), "{broken");
        Files.writeString(reports.resolve("invalid.json"), "{\"score\":100}");
        RunReports.write(reports.resolve("valid.json"), legacy(temp.resolve("workspaces/old")));
        var history = RunReports.history(temp);
        assertEquals(1, history.path("runs").size());
        assertEquals(2, history.path("warnings").size());
    }

    @Test
    void persistenceFailuresAreNotSilentlySwallowed() throws IOException {
        Path notADirectory = temp.resolve("file");
        Files.writeString(notADirectory, "not a directory");
        assertThrows(IOException.class, () -> RunReports.write(notADirectory.resolve("report.json"), M.createObjectNode()));
    }

    @Test
    void outcomePreservesThresholdAndConfidenceBoundaries() {
        assertEquals("passed", RunReports.outcome(7, 7, 0.5));
        assertEquals("failed", RunReports.outcome(6.99, 7, 0.5));
        assertEquals("undecided", RunReports.outcome(10, 7, 0.49));
    }

    static ObjectNode legacy(Path generated) {
        var report = M.createObjectNode();
        report.put("generated_project", generated.toString());
        report.put("score", 9.78);
        report.put("min_score", 7);
        report.putObject("answers").putObject("useful_descriptions").put("confidence", 1);
        return report;
    }

    private OpenCodeSession.Result session() {
        return new OpenCodeSession.Result("fixture", List.of(new OpenCodeSession.ToolCall("skill", "completed",
            M.createObjectNode().put("name", SkillWorkspace.DEGRADED_SKILL_NAME), "Loaded skill")), "", List.of(),
            42, 0.1, Duration.ofSeconds(2), temp.resolve("events"), temp.resolve("stderr"));
    }

    private JevJudge.Verdict verdict(double confidence) {
        return new JevJudge.Verdict("fixture", Map.of("useful_descriptions",
            new JevJudge.ScoreAnswer(0, confidence, Map.of(0, 1.0), Map.of(0, "poor", 1, "good"))), 42);
    }
}
