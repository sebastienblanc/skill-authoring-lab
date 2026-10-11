package org.sebi.skilllab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

final class RunReports {
    private static final ObjectMapper M = new ObjectMapper();

    static String outcome(double score, double minimum, double confidence) {
        return confidence < 0.5 ? "undecided" : score >= minimum ? "passed" : "failed";
    }

    static void save(Path target, Path generated, Path installedSkill, OpenCodeSession.Result session,
                     JevJudge.Verdict verdict, double score, double minimum, boolean reused) throws IOException {
        ObjectNode run = M.createObjectNode();
        run.put("run_id", UUID.randomUUID().toString());
        run.put("evaluated_at", Instant.now().toString());
        String skillName = installedSkill.getFileName().toString();
        run.put("skill_name", skillName);
        run.put("skill_variant", variant(skillName));
        run.put("skill_sha256", sha256(installedSkill.resolve("SKILL.md")));
        run.put("skill_loaded", session.loadedSkill(skillName));
        run.put("mode", reused ? "rejudged" : "generated");
        run.put("generated_project", generated.toAbsolutePath().toString());
        run.put("score", score);
        run.put("min_score", minimum);
        run.put("status", outcome(score, minimum, verdict.score("useful_descriptions").confidence()));
        run.put("judge_model", verdict.model());
        run.set("answers", M.valueToTree(verdict.answers()));
        run.put("opencode_session", session.sessionId());
        // Rejudging does not reveal the model used for the original generation.
        run.put("opencode_model", reused ? "(see original run)" :
            System.getenv().getOrDefault("OPENCODE_MODEL", "(OpenCode default)"));
        run.set("opencode_tool_calls", M.valueToTree(session.callsPerTool()));
        if (!reused) {
            run.put("opencode_total_tokens", session.totalTokens());
            run.put("opencode_cost", session.cost());
            run.put("opencode_seconds", session.elapsed().toSeconds());
        }
        Path reports = target.resolve("skill-lab");
        Path previous = generated.resolve(SkillWorkspace.LOG_DIR).resolve("run.json");
        if (Files.isRegularFile(previous)) {
            JsonNode old = M.readTree(previous.toFile());
            if (old != null && !old.hasNonNull("run_id")) {
                String legacyId = UUID.nameUUIDFromBytes(generated.toAbsolutePath().toString()
                    .getBytes(StandardCharsets.UTF_8)).toString();
                // Preserve the original timestamp when importing a pre-history report.
                if (old instanceof ObjectNode legacy && !legacy.hasNonNull("evaluated_at"))
                    legacy.put("evaluated_at", Files.getLastModifiedTime(previous).toInstant().toString());
                write(reports.resolve("runs/legacy-" + legacyId + ".json"), old);
            }
        }
        write(reports.resolve("runs").resolve(run.path("run_id").asText() + ".json"), run);
        write(generated.resolve(SkillWorkspace.LOG_DIR).resolve("run.json"), run);
        write(reports.resolve("last-run.json"), run);
    }

    static void write(Path file, JsonNode data) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".report-", ".tmp");
        try {
            M.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), data);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static ObjectNode history(Path target) throws IOException {
        List<Path> files = new ArrayList<>();
        Path archive = target.resolve("skill-lab/runs");
        if (Files.isDirectory(archive)) {
            try (Stream<Path> stream = Files.list(archive)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(files::add);
            }
        }
        Path workspaces = target.resolve("workspaces");
        if (Files.isDirectory(workspaces)) {
            try (Stream<Path> stream = Files.list(workspaces)) {
                stream.map(p -> p.resolve(SkillWorkspace.LOG_DIR).resolve("run.json"))
                    .filter(Files::isRegularFile).sorted().forEach(files::add);
            }
        }
        Path latest = target.resolve("skill-lab/last-run.json");
        if (Files.isRegularFile(latest)) files.add(latest);
        Map<String, ObjectNode> runs = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (Path file : files) {
            try {
                JsonNode json = M.readTree(file.toFile());
                if (!(json instanceof ObjectNode run) || !run.path("score").isNumber()
                    || !Double.isFinite(run.path("score").asDouble()) || run.path("score").asDouble() < 0
                    || run.path("score").asDouble() > 10 || !run.path("min_score").isNumber()
                    || !run.path("generated_project").isTextual())
                    throw new IOException("Expected a scored run with score, min_score and generated_project");
                boolean legacy = !run.hasNonNull("run_id");
                String id = legacy ? "legacy:" + run.path("generated_project").asText() : run.path("run_id").asText();
                run.put("run_id", id);
                if (!run.hasNonNull("evaluated_at"))
                    run.put("evaluated_at", Files.getLastModifiedTime(file).toInstant().toString());
                run.put("legacy", legacy);
                if (!run.hasNonNull("skill_variant"))
                    run.put("skill_variant", run.hasNonNull("skill_name") ? variant(run.path("skill_name").asText()) : "standard");
                if (!run.hasNonNull("status")) {
                    JsonNode confidence = run.path("answers").path("useful_descriptions").path("confidence");
                    run.put("status", confidence.isNumber()
                        ? outcome(run.path("score").asDouble(), run.path("min_score").asDouble(), confidence.asDouble())
                        : "unknown");
                }
                runs.putIfAbsent(id, run);
            } catch (IOException e) {
                warnings.add(file + ": " + e.getMessage());
            }
        }
        ObjectNode result = M.createObjectNode();
        result.set("runs", M.valueToTree(runs.values().stream()
            .sorted(Comparator.comparing((ObjectNode r) -> r.path("evaluated_at").asText()).reversed()).toList()));
        result.set("warnings", M.valueToTree(warnings));
        return result;
    }

    private static String variant(String skillName) {
        return switch (skillName) {
            case SkillWorkspace.SKILL_NAME -> "standard";
            case SkillWorkspace.DEGRADED_SKILL_NAME -> "degraded";
            default -> skillName;
        };
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
