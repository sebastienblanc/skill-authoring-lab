package org.sebi.skilllab;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * PROVIDED — the generated server, seen as text. Same contract as in tp3-eval, in English.
 * {@code root} is the directory that contains the generated {@code pom.xml}.
 */
public final class GeneratedProject {

    private final Path root;

    private GeneratedProject(Path root) { this.root = root; }

    public static GeneratedProject at(Path root) {
        if (!Files.exists(root.resolve("pom.xml"))) {
            throw new IllegalStateException("No pom.xml in " + root.toAbsolutePath() + ": the generation produced no Maven project");
        }
        return new GeneratedProject(root);
    }

    public Path root() { return root; }

    /** The pom.xml, verbatim. */
    public String pom() { return read(root.resolve("pom.xml")); }

    /** All sources of src/main/java concatenated, comments stripped, string literals preserved.
     *  The server's own tests (src/test) are deliberately excluded: a scorer would find anything there. */
    public String source() {
        StringBuilder sb = new StringBuilder();
        sources().values().forEach(s -> sb.append(s).append('\n'));
        return sb.toString();
    }

    /** The sources of src/main/java file by file (simple name → content without comments).
     *  For a "System One" judge: a named JSON state rather than one block of text. */
    public Map<String, String> sources() {
        Path main = root.resolve("src/main/java");
        Map<String, String> m = new LinkedHashMap<>();
        if (!Files.isDirectory(main)) return m;
        try (Stream<Path> s = Files.walk(main)) {
            s.filter(f -> f.toString().endsWith(".java")).sorted()
             .forEach(f -> m.put(f.getFileName().toString(), stripComments(read(f))));
            return m;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** True if source() contains a match of the regex (DOTALL: "." crosses lines). */
    public boolean contains(String regex) {
        return Pattern.compile(regex, Pattern.DOTALL).matcher(source()).find();
    }

    /** Removes block and line comments without touching string literals: "service://{name}" contains "//". */
    static String stripComments(String src) {
        Matcher m = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL).matcher(src);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group().startsWith("\"") ? m.group() : " "));
        m.appendTail(sb);
        return sb.toString();
    }

    private static String read(Path f) {
        try { return Files.readString(f); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
