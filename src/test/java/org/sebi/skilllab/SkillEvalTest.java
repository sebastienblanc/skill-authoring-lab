package org.sebi.skilllab;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.sebi.skilllab.JevJudge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * THE LOOP — generate with the skill, judge with Jev, compare the score to a minimum.
 *
 * 1. A fresh workspace receives the skill from {@code skills/create-quarkus-mcp-server/} and the catalogue.
 * 2. A headless OpenCode session runs the request in {@code prompts/catalogue-server-request.md}.
 * 3. The session must have loaded the skill (the {@code skill} tool call is in the event log): otherwise the
 *    score would measure the base model, not the skill.
 * 4. The generated sources go to the Jev judge as a named JSON state; the rubric below is typed questions.
 * 5. The score is computed in code from the answers, with weights you own, and must reach the minimum.
 *
 * Active only when TYPESAFE_API_KEY is set and the OpenCode binary is available (otherwise skipped, not passed).
 *
 * Knobs (system properties): {@code skill.lab.min.score} (default 7), {@code skill.lab.timeout.minutes}
 * (default 15), {@code skill.lab.workspace} (fixed workspace directory), {@code skill.lab.generated}
 * (judge an existing generated project and skip the generation: handy when tuning the rubric).
 *
 * Rules of the game for the rubric, from the Jev documentation (model jaggedness):
 *  - do not ask the judge what a regex can compute (snake_case, presence of an annotation, description length);
 *  - one question = one judgement; levels describe situations, not degrees;
 *  - literal reading: write the exact condition and the edge cases into the criteria;
 *  - state is data, not instructions: sources are sent without comments;
 *  - questions are written in English, the model's main training language.
 */
@EnabledIfEnvironmentVariable(named = "TYPESAFE_API_KEY", matches = ".+")
class SkillEvalTest {

    static final Path CATALOGUE = Path.of("domain", "catalogue-services.json");
    static final Path PROMPT = Path.of("prompts", "catalogue-server-request.md");

    static final double MIN_SCORE = Double.parseDouble(System.getProperty("skill.lab.min.score", "7"));
    static final Duration TIMEOUT = Duration.ofMinutes(Long.getLong("skill.lab.timeout.minutes", 15));

    /** The questions. The identifier is yours; it is not sent to the model. */
    static final Map<String, Question> RUBRIC = Map.of(
        "useful_descriptions", score(
            "How useful are the description strings of the @Tool and @ToolArg annotations to an AI agent "
            + "that must decide which tool to call and how to fill its arguments?",
            "Descriptions are missing, empty, or only repeat the method or parameter name",
            "Descriptions say what the tool does, but not what the argument expects nor what the tool returns",
            "Descriptions say what the tool does, what each argument expects (format or example), and what the tool returns"),

        "clean_delegation", noul(
            "Do the methods carrying MCP annotations (@Tool, @ResourceTemplate, @Prompt) only call an injected "
            + "service and format its result, without themselves loading data, parsing JSON, searching or filtering?",
            "Every annotated method delegates the lookup to an injected service; loops, filtering and JSON parsing live in the non-annotated class",
            "At least one annotated method loads the catalogue, parses JSON, or searches or filters the data itself"),

        "not_found_handled", noul(
            "When the requested service name does not exist in the catalogue, do the annotated methods return a "
            + "readable message to the caller instead of throwing an exception or returning null?",
            "Each annotated method handles the not-found case with an explicit message",
            "At least one annotated method throws, returns null, or ignores the not-found case"),

        "main_defect", choice(
            "Which of these is the most serious defect of this MCP server, if any?",
            Map.of(
                "none", "No notable defect against the conventions",
                "logic_in_adapter", "Business logic or data loading inside the MCP-annotated class",
                "poor_descriptions", "Tool or argument descriptions too vague for an agent to use",
                "missing_primitive", "A tool, resource template or prompt from the conventions is absent",
                "unhandled_errors", "Not-found or invalid input cases are not handled"))
    );

    @BeforeAll
    static void requiresOpenCode() {
        if (System.getProperty("skill.lab.generated", "").isBlank()) {
            assumeTrue(OpenCodeSession.available(),
                "OpenCode binary not found (PATH or OPENCODE_BIN): generation impossible, test skipped");
        }
    }

    @Test
    void skill_produces_a_server_the_judge_scores_above_the_minimum() throws IOException {
        // --- 1-3. Generate (or reuse a previous generation) ------------------------------------------
        Path generated;
        Path installedSkill;
        OpenCodeSession.Result session;
        String reuse = System.getProperty("skill.lab.generated", "");
        if (!reuse.isBlank()) {
            if (System.getProperty("skill.lab.variant") != null)
                throw new IllegalArgumentException("Do not combine skill.lab.variant with skill.lab.generated; "
                    + "rejudging uses the skill installed in that workspace");
            generated = Path.of(reuse).toAbsolutePath();
            installedSkill = installedSkillAt(generated);
            Path logs = generated.resolve(SkillWorkspace.LOG_DIR);
            Path events = logs.resolve("opencode-events.jsonl");
            session = OpenCodeSession.parse(Files.readAllLines(events), Duration.ZERO,
                events, logs.resolve("opencode-stderr.log"));
            System.out.println("Reusing generated project " + generated + " (no new OpenCode session)");
        } else {
            Path source = SkillWorkspace.skillSource(System.getProperty("skill.lab.variant", "standard"));
            SkillWorkspace ws = SkillWorkspace.prepare(source, CATALOGUE);
            System.out.println("Workspace " + ws.root() + " (skill installed in " + ws.installedSkill() + ")");
            session = OpenCodeSession.run(ws, SkillWorkspace.promptFor(Files.readString(PROMPT), ws.skillName()), TIMEOUT);
            System.out.println("OpenCode " + session.summary());
            installedSkill = ws.installedSkill();
            generated = ws.root();
        }
        String skillName = installedSkill.getFileName().toString();
        assertTrue(session.loadedSkill(skillName),
            "The session never loaded the skill '" + skillName + "' with the skill tool, so the score "
            + "would measure the base model. Tool calls: " + session.callsPerTool() + ". Events: " + session.eventsLog());

        // --- Sanity gates: no point paying a judge call for an empty or foreign project ----------------
        assertTrue(Files.exists(generated.resolve("pom.xml")), "No pom.xml in " + generated
            + ": the agent produced no Maven project. Its final answer was:\n" + session.finalText());
        GeneratedProject project = GeneratedProject.at(generated);
        Map<String, String> sources = project.sources();
        assertFalse(sources.isEmpty(), "No Java sources under " + generated.resolve("src/main/java"));
        assertTrue(project.pom().contains("quarkus-mcp-server"),
            "The generated pom.xml does not declare the quarkus-mcp-server extension: not an MCP server");

        // --- 4. Judge: a named JSON state, one field per source file, no comments ("state is data") ---
        Verdict v = new JevJudge(generated.resolve(SkillWorkspace.LOG_DIR)).evaluate(Map.of("java_sources", sources), RUBRIC);

        ScoreAnswer descriptions = v.score("useful_descriptions");
        NoulAnswer delegation = v.noul("clean_delegation");
        NoulAnswer notFound = v.noul("not_found_handled");
        ChoiceAnswer defect = v.choice("main_defect");

        // --- 5. The score: weights YOU choose, readable, changeable without touching the model ---------
        double score = 10 * (0.5 * descriptions.normalized() + 0.3 * delegation.noul() + 0.2 * notFound.noul());

        // Always visible: console (mvn without -q) and target/surefire-reports/TEST-*SkillEvalTest.xml.
        System.out.printf("%n=== Jev verdict: score %.1f/10 (minimum %.1f), main defect '%s' (%d input tokens)%n%s%n",
            score, MIN_SCORE, defect.choice(), v.inputTokens(), v.summary());
        RunReports.save(Path.of("target"), generated, installedSkill, session, v, score, MIN_SCORE, !reuse.isBlank());

        // Keep undecided judgments in the history, but never treat them as a pass or failure.
        assumeTrue(descriptions.confidence() >= 0.5,
            "Judge undecided on the descriptions (confidence " + descriptions.confidence() + "): review by a human, or reword the levels.\n" + v.summary());

        assertTrue(score >= MIN_SCORE, String.format(
            "Score %.1f/10 is below the minimum %.1f. Main defect according to the judge: %s.%n%s%nGenerated project: %s",
            score, MIN_SCORE, defect.choice(), v.summary(), generated));
    }

    static Path installedSkillAt(Path generated) throws IOException {
        try (Stream<Path> paths = Files.list(generated.resolve(".opencode/skills"))) {
            List<Path> skills = paths.filter(p -> Files.isRegularFile(p.resolve("SKILL.md"))).toList();
            if (skills.size() != 1)
                throw new IllegalStateException("Expected exactly one installed skill in " + generated + "; found " + skills);
            SkillWorkspace.validateSkill(skills.getFirst());
            return skills.getFirst();
        }
    }
}
