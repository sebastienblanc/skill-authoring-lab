package org.sebi.skilllab;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillWorkspaceTest {

    @TempDir Path temp;

    @Test
    void headlessPermissionsPutSpecificAllowsAfterTheCatchAllDeny() throws IOException {
        var ws = SkillWorkspace.prepare(Path.of("skills", SkillWorkspace.SKILL_NAME),
            Path.of("domain", "catalogue-services.json"), temp);
        var permission = new ObjectMapper().readTree(ws.root().resolve("opencode.json").toFile()).path("permission");
        var external = permission.path("external_directory");
        List<String> patterns = new ArrayList<>();
        external.fieldNames().forEachRemaining(patterns::add);

        assertEquals("*", patterns.getFirst(), "OpenCode uses the last matching permission rule");
        assertEquals("deny", external.path("*").asText());
        for (String path : List.of("/tmp", "/tmp/*", "/private/tmp", "/private/tmp/*"))
            assertEquals("allow", external.path(path).asText());
        assertEquals("deny", permission.path("question").asText());
        assertEquals("deny", permission.path("doom_loop").asText());
        for (String tool : List.of("edit", "bash", "webfetch"))
            assertEquals("allow", permission.path(tool).asText());
        assertEquals("allow", permission.path("skill").path("*").asText());
    }

    @Test
    void degradedVariantInstallsAndRequestsOnlyItsOwnSkill() throws IOException {
        var ws = SkillWorkspace.prepare(SkillWorkspace.skillSource("degraded"),
            Path.of("domain/catalogue-services.json"), temp);
        assertEquals(SkillWorkspace.DEGRADED_SKILL_NAME, ws.skillName());
        assertTrue(Files.isRegularFile(ws.installedSkill().resolve("SKILL.md")));
        assertFalse(Files.exists(temp.resolve(".opencode/skills").resolve(SkillWorkspace.SKILL_NAME)));
        String prompt = SkillWorkspace.promptFor(Files.readString(Path.of("prompts/catalogue-server-request.md")), ws.skillName());
        assertTrue(prompt.startsWith("Load the " + SkillWorkspace.DEGRADED_SKILL_NAME + " skill"));
        assertEquals(ws.installedSkill(), SkillEvalTest.installedSkillAt(temp));
    }

    @Test
    void standardRemainsDefaultAndVariantsCannotMixInOneWorkspace() {
        assertEquals(Path.of("skills", SkillWorkspace.SKILL_NAME), SkillWorkspace.skillSource("standard"));
        assertThrows(IllegalArgumentException.class, () -> SkillWorkspace.skillSource("typo"));
        SkillWorkspace.prepare(SkillWorkspace.skillSource("standard"), Path.of("domain/catalogue-services.json"), temp);
        assertThrows(IllegalStateException.class, () -> SkillWorkspace.prepare(SkillWorkspace.skillSource("degraded"),
            Path.of("domain/catalogue-services.json"), temp));
    }
}
