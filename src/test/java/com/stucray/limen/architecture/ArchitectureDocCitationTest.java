package com.stucray.limen.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toUnmodifiableSet;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Referential-integrity check on cross-cutting documentation citations.
 *
 * <p>Every {@code §N.M (Name)} citation of {@code docs/reference/architecture.md}
 * appearing in a {@code package-info.java} file must resolve to an actual heading
 * in that document. Catches drift introduced when a section is renumbered,
 * renamed, or removed without a corresponding update to the citing modules.
 *
 * <p>Semantic correctness — "is this still the right section to cite for this
 * module?" — remains a human-judgement call; this test only proves the cited
 * section exists.
 *
 * <p>Heading-text convention enforced indirectly: section headings in
 * {@code architecture.md} are plain prose (no markdown decoration, no
 * parentheticals); citations match the heading text byte-for-byte after
 * stripping the {@code §N.M (}{@code )} wrapper. The convention is documented
 * in {@code architecture.md}'s preamble.
 */
@DisplayName("package-info §N.M citations resolve to architecture.md headings")
class ArchitectureDocCitationTest {

    private static final Path ARCHITECTURE_DOC = Paths.get("docs/reference/architecture.md");
    private static final Path PACKAGE_INFO_ROOT = Paths.get("src/main/java/com/stucray/limen");

    private static final Pattern HEADING_PATTERN = Pattern.compile(
        "^#{2,6}\\s+(\\d+(?:\\.\\d+)*)\\.?\\s+(.+?)\\s*$");
    private static final Pattern CITATION_PATTERN = Pattern.compile(
        "§(\\d+(?:\\.\\d+)*)\\s+\\(([^)]+)\\)");
    private static final Pattern JAVADOC_CONTINUATION = Pattern.compile("\\n\\s*\\*\\s*");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    @Test
    @DisplayName("every cite resolves to a heading")
    void everyCitationResolvesToAHeading() {
        Set<Section> headings = readHeadings(ARCHITECTURE_DOC);
        Set<Citation> citations = readCitations(PACKAGE_INFO_ROOT);

        Set<Citation> unresolved = new TreeSet<>(Citation.BY_SOURCE_THEN_NUMBER);
        for (Citation c : citations) {
            if (!headings.contains(new Section(c.number(), c.name()))) {
                unresolved.add(c);
            }
        }
        assertTrue(unresolved.isEmpty(), failureMessage(unresolved));
    }

    private static String failureMessage(Set<Citation> unresolved) {
        StringBuilder sb = new StringBuilder();
        sb.append(unresolved.size()).append(" package-info citation(s) do not resolve "
            + "to a heading in ").append(ARCHITECTURE_DOC).append(":\n");
        for (Citation c : unresolved) {
            sb.append("  ").append(c.source()).append(": §").append(c.number())
                .append(" (").append(c.name()).append(")\n");
        }
        sb.append("\nFix one of: align the citation to the heading text, align the "
            + "heading to the citation, or update both.\nConvention: heading text is "
            + "plain prose (no markdown decoration, no parentheticals); citations "
            + "match byte-for-byte.\n");
        return sb.toString();
    }

    private static Set<Section> readHeadings(Path doc) {
        try {
            return Files.readAllLines(doc).stream()
                .map(HEADING_PATTERN::matcher)
                .filter(java.util.regex.Matcher::matches)
                .map(m -> new Section(m.group(1), m.group(2).trim()))
                .collect(toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<Citation> readCitations(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                .filter(p -> p.getFileName().toString().equals("package-info.java"))
                .flatMap(ArchitectureDocCitationTest::extractCitations)
                .collect(toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Stream<Citation> extractCitations(Path pkgInfo) {
        try {
            String raw = Files.readString(pkgInfo);
            String normalised = JAVADOC_CONTINUATION.matcher(raw).replaceAll(" ");
            String source = Paths.get("").toAbsolutePath()
                .relativize(pkgInfo.toAbsolutePath()).toString();
            Set<Citation> found = new LinkedHashSet<>();
            CITATION_PATTERN.matcher(normalised).results().forEach(m -> {
                String name = WHITESPACE_RUN.matcher(m.group(2).trim()).replaceAll(" ");
                found.add(new Citation(m.group(1), name, source));
            });
            return found.stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Section(String number, String name) { }

    private record Citation(String number, String name, String source) {
        static final java.util.Comparator<Citation> BY_SOURCE_THEN_NUMBER =
            java.util.Comparator.comparing(Citation::source).thenComparing(Citation::number);
    }
}
