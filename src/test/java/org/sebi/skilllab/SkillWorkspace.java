package org.sebi.skilllab;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * PROVIDED — a fresh directory in which OpenCode generates the server.
 *
 * It contains the skill under test, installed where OpenCode discovers it ({@code .opencode/skills/<name>/}),
 * the catalogue file the server must expose, and an {@code opencode.json} granting the permissions a headless
 * session needs (edit, bash, skill, and the system temp directory: a headless session ENDS on the first rejected
 * permission, with no answer and no files). The directory is its own git worktree so that skill discovery, which walks
 * up to the worktree root, never picks up skills from the surrounding repository.
 *
 * Location: {@code -Dskill.lab.workspace=/some/dir}, otherwise {@code target/workspaces/<timestamp>}.
 * The workspace is kept after the test so you can open the generated code and the logs.
 */
public final class SkillWorkspace {

    public static final String SKILL_NAME = "create-quarkus-mcp-server";
    public static final String DEGRADED_SKILL_NAME = SKILL_NAME + "-degraded";
    /** Logs and reports written by the harness inside the workspace (never read as generated sources). */
    public static final String LOG_DIR = ".skill-lab";

    private static final String OPENCODE_JSON = """
        {
          "$schema": "https://opencode.ai/config.json",
          "permission": {
            "edit": "allow",
            "bash": "allow",
            "webfetch": "allow",
            "skill": { "*": "allow" },
            "question": "deny",
            "doom_loop": "deny",
            "external_directory": { "*": "deny", "/tmp": "allow", "/tmp/*": "allow", "/private/tmp": "allow", "/private/tmp/*": "allow" }
          }
        }
        """;

    private final Path root;
    private final String skillName;

    private SkillWorkspace(Path root, String skillName) {
        this.root = root;
        this.skillName = skillName;
    }

    public Path root() { return root; }
    public Path logDir() { return root.resolve(LOG_DIR); }
    public String skillName() { return skillName; }
    public Path installedSkill() { return root.resolve(".opencode").resolve("skills").resolve(skillName); }

    static Path skillSource(String variant) {
        return Path.of("skills", switch (variant) {
            case "standard" -> SKILL_NAME;
            case "degraded" -> DEGRADED_SKILL_NAME;
            default -> throw new IllegalArgumentException("Unknown skill.lab.variant '" + variant
                + "': choose standard or degraded");
        });
    }

    static String promptFor(String prompt, String skillName) {
        if (!prompt.contains(SKILL_NAME))
            throw new IllegalArgumentException("The generation prompt must name " + SKILL_NAME);
        return prompt.replace(SKILL_NAME, skillName);
    }

    /**
     * @param skillSource the skill directory under authoring (contains SKILL.md and references/)
     * @param catalogue   the JSON catalogue copied at the workspace root
     */
    public static SkillWorkspace prepare(Path skillSource, Path catalogue) {
        return prepare(skillSource, catalogue, chooseRoot());
    }

    static SkillWorkspace prepare(Path skillSource, Path catalogue, Path root) {
        try {
            validateSkill(skillSource);
            root = root.toAbsolutePath();
            Files.createDirectories(root);
            SkillWorkspace ws = new SkillWorkspace(root, skillSource.getFileName().toString());
            Path installed = root.resolve(".opencode/skills");
            if (Files.isDirectory(installed)) {
                try (Stream<Path> entries = Files.list(installed)) {
                    if (entries.anyMatch(p -> !p.equals(ws.installedSkill())))
                        throw new IllegalStateException("Workspace contains another skill; use a fresh workspace: " + root);
                }
            }
            copyTree(skillSource, ws.installedSkill());
            Files.copy(catalogue, root.resolve(catalogue.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(root.resolve("opencode.json"), OPENCODE_JSON);
            Files.createDirectories(ws.logDir());
            gitInit(root);
            return ws;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path chooseRoot() {
        String configured = System.getProperty("skill.lab.workspace", "");
        if (!configured.isBlank()) return Path.of(configured).toAbsolutePath();
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        return Path.of("target", "workspaces", stamp).toAbsolutePath();
    }

    /**
     * OpenCode silently ignores a skill whose frontmatter is invalid or whose {@code name} differs from its
     * directory name. The session would then run without the skill and the score would say nothing about it:
     * fail here, before paying for a generation.
     */
    static void validateSkill(Path skillSource) throws IOException {
        Path skillMd = skillSource.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) throw new IllegalStateException("No SKILL.md in " + skillSource.toAbsolutePath());
        String text = Files.readString(skillMd);
        Matcher fm = Pattern.compile("\\A---\\R(.*?)\\R---", Pattern.DOTALL).matcher(text);
        if (!fm.find()) throw new IllegalStateException(skillMd + " must start with a YAML frontmatter block (--- ... ---)");
        String front = fm.group(1);
        String dirName = skillSource.getFileName().toString();
        Matcher name = Pattern.compile("^name:\\s*(\\S+)\\s*$", Pattern.MULTILINE).matcher(front);
        if (!name.find() || !name.group(1).equals(dirName))
            throw new IllegalStateException("Frontmatter 'name' must equal the directory name '" + dirName + "' in " + skillMd);
        if (!dirName.matches("[a-z0-9]+(-[a-z0-9]+)*"))
            throw new IllegalStateException("Skill name '" + dirName + "' must be lowercase alphanumerics separated by single hyphens");
        if (!Pattern.compile("^description:\\s*\\S", Pattern.MULTILINE).matcher(front).find())
            throw new IllegalStateException("Frontmatter 'description' is missing or empty in " + skillMd);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Best effort: without git the workspace still works, discovery just walks further up. */
    private static void gitInit(Path root) {
        try {
            Process p = new ProcessBuilder("git", "init", "-q").directory(root.toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (IOException | InterruptedException ignored) {
            // not fatal
        }
    }
}
