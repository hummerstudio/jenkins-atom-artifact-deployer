package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The artifact-deployer atom against real directory trees: the set of files that landed, their bytes, their mtime, and
 * what was kept back because it was already there.
 *
 * <p>The expected file set is recomputed here by a second Ant-glob implementation ({@link #antMatch}), and the tricky
 * patterns are pinned by a hand-written table, so a wrong implementation cannot agree with itself.
 */
class ArtifactDeployerIT {

    private static final long FIXED_MTIME = 978307200L; // 2001-01-01, so a "preserved" mtime is not "now"

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    // ---------------------------------------------------------------- copying

    @Test
    @DisplayName("copy everything by default: every file, byte for byte, with its mtime")
    void copiesEverything() throws Exception {
        Path ws = sampleTree();
        Path target = temp().resolve("release");

        AtomResult run = run(ws, "remote", target.toString());

        run.assertOk();
        Set<String> expected = selected(sampleFiles(), "**", "");
        assertEquals(new TreeSet<>(expected), filesUnder(target), "copied the wrong set");
        for (String relative : expected) {
            assertEquals(sha256(ws.resolve(relative)), sha256(target.resolve(relative)), "bytes differ for " + relative);
            assertEquals(mtime(ws.resolve(relative)), mtime(target.resolve(relative)), "mtime lost on " + relative);
        }
        assertEquals(String.valueOf(expected.size()), run.field("matched"));
        assertEquals(String.valueOf(expected.size()), run.field("copied"));
        assertEquals(String.valueOf(expected.stream().mapToLong(r -> size(ws.resolve(r))).sum()), run.field("bytes"));
    }

    @Test
    @DisplayName("the Ant glob table: ** crosses directories, * and ? do not, matching is case-sensitive")
    void antGlobTable() throws Exception {
        record Row(String pattern, List<String> must, List<String> mustNot) {}
        List<Row> table = List.of(
                new Row("**/*.jar",
                        List.of("build/app.jar", "build/lib/util.jar", "dist/deep/two.jar", "dist/one.jar", "app.jar"),
                        List.of("readme.txt", "build/lib/notes.txt")),
                new Row("*.txt", List.of("readme.txt"), List.of("build/lib/notes.txt", "docs/guide.md")),
                new Row("build/**",
                        List.of("build/app.jar", "build/lib/util.jar", "build/lib/notes.txt", "build/tmp/scratch.tmp"),
                        List.of("dist/one.jar")),
                new Row("**/lib/*.jar", List.of("build/lib/util.jar"), List.of("dist/one.jar")),
                new Row("**/*.TXT", List.of(), List.of("readme.txt")),
                new Row("build/?pp.jar", List.of("build/app.jar"), List.of("build/lib/util.jar")),
                new Row("**/two.jar", List.of("dist/deep/two.jar"), List.of("dist/one.jar")));

        for (Row row : table) {
            for (String path : row.must()) {
                assertTrue(antMatch(row.pattern(), path),
                        "the recomputation disagrees with the table: " + row.pattern() + " vs " + path);
            }
            for (String path : row.mustNot()) {
                assertFalse(antMatch(row.pattern(), path),
                        "the recomputation disagrees with the table: " + row.pattern() + " vs " + path);
            }
        }

        Path ws = sampleTree();
        Path target = temp().resolve("case");

        // a case-only difference must survive the trip through the atom, not just the table above
        AtomResult lower = run(ws, "remote", target.toString(), "includes", "**/*.TXT");
        assertEquals("error", lower.status(), "**/*.TXT matched lowercase readme.txt:\n" + lower.console());

        write(ws.resolve("upper.TXT"), "upper");
        AtomResult upper = run(ws, "remote", target.toString(), "includes", "**/*.TXT");
        upper.assertOk();
        assertEquals(Set.of("upper.TXT"), filesUnder(target));

        for (Row row : table) {
            if (row.pattern().equals("**/*.TXT")) {
                continue; // proven above, on a tree with both cases present
            }
            Path out = temp().resolve("out");
            AtomResult run = run(ws, "remote", out.toString(), "includes", row.pattern());
            run.assertOk();
            Set<String> actual = filesUnder(out);
            assertEquals(selected(sampleFiles(), row.pattern(), ""), actual,
                    row.pattern() + " copied the wrong set");
            for (String relative : row.must()) {
                assertTrue(actual.contains(relative), row.pattern() + " must copy " + relative + ", copied " + actual);
            }
            for (String relative : row.mustNot()) {
                assertFalse(actual.contains(relative), row.pattern() + " must not copy " + relative);
            }
        }
    }

    @Test
    @DisplayName("excludes remove what they match")
    void excludes() throws Exception {
        Path ws = sampleTree();
        Path target = temp().resolve("out");

        AtomResult run = run(ws, "remote", target.toString(), "includes", "**", "excludes", "**/*.tmp, **/*.md");

        run.assertOk();
        Set<String> actual = filesUnder(target);
        assertEquals(selected(sampleFiles(), "**", "**/*.tmp, **/*.md"), actual);
        assertFalse(actual.contains("build/tmp/scratch.tmp"), "the exclude did not remove a tmp file");
        assertFalse(actual.contains("docs/guide.md"), "the exclude did not remove markdown");
    }

    @Test
    @DisplayName("basedir is relative to the workspace, and the layout is kept")
    void relativeBasedir() throws Exception {
        Path ws = sampleTree();
        Path target = temp().resolve("out");

        AtomResult run = run(ws, "remote", target.toString(), "basedir", "build", "includes", "**/*.jar");

        run.assertOk();
        assertEquals(Set.of("app.jar", "lib/util.jar"), filesUnder(target));
        assertEquals("2", run.field("copied"));
    }

    @Test
    @DisplayName("basedir is expanded as ${ENV}")
    void basedirExpandsEnvironment() throws Exception {
        Path ws = sampleTree();
        Path target = temp().resolve("out");

        AtomResult run = atom.input("atom_workspace", ws.toString())
                .input("remote", target.toString())
                .input("basedir", "${SRC}")
                .input("includes", "*.jar")
                .env("SRC", "dist")
                .run();

        run.assertOk();
        assertEquals(Set.of("one.jar"), filesUnder(target));
        assertEquals("out", Path.of(run.field("remotePath")).getFileName().toString());
    }

    @Test
    @DisplayName("a relative remote resolves against the workspace, not the process working directory")
    void relativeRemote() throws Exception {
        Path ws = sampleTree();

        // the atom is started from an unrelated directory (the harness's data dir): a relative remote must resolve
        // against the workspace, not against the cwd — on a CI runner the two are often the same, which hides the bug
        AtomResult run = run(ws, "remote", "out/release", "includes", "*.jar");

        run.assertOk();
        assertEquals(ws.resolve("out").resolve("release").toString(), run.field("remotePath"));
        assertEquals(Set.of("app.jar"), filesUnder(ws.resolve("out").resolve("release")));
        assertFalse(Files.exists(run.work().resolve("out")), "a relative remote was resolved against the cwd");
    }

    @Test
    @DisplayName("the target may be outside the workspace")
    void remoteOutsideWorkspace() throws Exception {
        Path ws = sampleTree();
        Path outside = temp();

        AtomResult run = run(ws, "remote", outside.toString(), "includes", "dist/**");

        run.assertOk();
        assertEquals(Set.of("dist/one.jar", "dist/deep/two.jar"), filesUnder(outside));
    }

    @Test
    @DisplayName("flatten drops the layout and keeps the first file of a collision")
    void flattenCollisions() throws Exception {
        Path ws = temp();
        write(ws.resolve("a/same.jar"), "first");
        write(ws.resolve("a/only.jar"), "only");
        write(ws.resolve("b/same.jar"), "second");
        Path target = temp().resolve("flat");

        AtomResult run = run(ws, "remote", target.toString(), "includes", "**/*.jar", "flatten", "true");

        run.assertOk();
        assertEquals(Set.of("same.jar", "only.jar"), filesUnder(target));
        assertEquals(Set.of(), dirsUnder(target), "flatten must not create the source directories");
        // sorted order: a/same.jar is copied first, b/same.jar meets an existing file and is kept back
        assertEquals("first", Files.readString(target.resolve("same.jar")), "the first file must win");
        assertEquals("2", run.field("copied"));
        assertEquals("1", run.field("skipped"));
    }

    @Test
    @DisplayName("a file that is already there is kept, not overwritten, and reported as skipped")
    void keepsExistingFiles() throws Exception {
        Path ws = sampleTree();
        Path target = temp().resolve("out");
        write(target.resolve("app.jar"), "already-here");
        Files.setLastModifiedTime(target.resolve("app.jar"), FileTime.from(1_000_000_000L, java.util.concurrent.TimeUnit.SECONDS));

        AtomResult run = run(ws, "remote", target.toString(), "includes", "*.jar");

        run.assertOk();
        assertEquals("already-here", Files.readString(target.resolve("app.jar")), "an existing file was overwritten");
        assertEquals(1_000_000_000L, mtime(target.resolve("app.jar")), "the kept file's mtime changed");
        assertEquals("1", run.field("skipped"));
        assertEquals("0", run.field("copied"));
        List<Map<String, Object>> rows = rows(run);
        assertEquals(1, rows.size(), "rows: " + rows);
        assertEquals(true, rows.get(0).get("skipped"), "the row should say skipped: " + rows);
        assertEquals(false, rows.get(0).get("deployed"), "the row should not say deployed: " + rows);
    }

    @Test
    @DisplayName("deleteRemote clears the contents first and keeps the target directory itself")
    void deleteRemoteClears() throws Exception {
        Path ws = sampleTree();
        Path target = temp().resolve("out");
        write(target.resolve("stale.txt"), "stale");
        write(target.resolve("sub/old.jar"), "old");

        AtomResult run = run(ws, "remote", target.toString(), "includes", "dist/**", "deleteRemote", "true");

        run.assertOk();
        assertEquals(Set.of("dist/one.jar", "dist/deep/two.jar"), filesUnder(target));
        assertFalse(dirsUnder(target).contains("sub"), "stale directory survived the clear");
        assertTrue(Files.isDirectory(target), "the target directory itself must stay");
    }

    @Test
    @DisplayName("includeEmptyDirs: an empty directory the pattern selects is created")
    void createsEmptyDirectories() throws Exception {
        Path ws = sampleTree();
        Path target = temp().resolve("out");

        AtomResult run = run(ws, "remote", target.toString(), "includes", "**");

        run.assertOk();
        Set<String> dirs = dirsUnder(target);
        assertTrue(dirs.contains("build/empty-dir"), "the empty directory was not created: " + dirs);
        Set<String> expected = selectedDirs(Set.of("build/empty-dir", "build/lib", "build/tmp", "dist/deep", "docs"),
                "**", "");
        Set<String> allowed = new LinkedHashSet<>(dirs);
        allowed.add("build");
        allowed.add("dist");
        assertTrue(allowed.containsAll(expected), "missing directories: " + expected + " vs " + dirs);
    }

    // ---------------------------------------------------------------- refusals

    @Test
    @DisplayName("nothing matched fails by default, and the message shows the configuration")
    void noFilesFailsByDefault() throws Exception {
        Path ws = sampleTree();
        Path target = temp().resolve("out");

        AtomResult run = run(ws, "remote", target.toString(), "includes", "**/*.nope");

        run.assertRefused();
        assertEquals("40003", errorCode(run), message(run));
        assertTrue(message(run).contains("includes:**/*.nope"), message(run));
        assertTrue(message(run).contains("basedir:."), message(run));
        assertTrue(message(run).contains("outPath:"), message(run));
        assertFalse(Files.exists(target), "a failed deploy must not leave an empty target behind");
    }

    @Test
    @DisplayName("failNoFilesDeploy=false makes an empty deploy a green zero")
    void noFilesAllowed() throws Exception {
        Path ws = sampleTree();
        Path target = temp().resolve("out");

        AtomResult run = run(ws, "remote", target.toString(), "includes", "**/*.nope", "failNoFilesDeploy", "false");

        run.assertOk();
        assertEquals("0", run.field("matched"));
        assertEquals("0", run.field("copied"));
    }

    @Test
    @DisplayName("remote is required")
    void remoteIsRequired() throws Exception {
        AtomResult run = run(sampleTree(), "remote", "");

        run.assertRefused();
        assertEquals("40001", errorCode(run), message(run));
        assertTrue(message(run).contains("remote"), message(run));
    }

    @Test
    @DisplayName("a basedir that does not exist is refused, by name")
    void basedirMustExist() throws Exception {
        AtomResult run = run(sampleTree(), "remote", temp().resolve("out").toString(), "basedir", "no-such-dir");

        run.assertRefused();
        assertEquals("40001", errorCode(run), message(run));
        assertTrue(message(run).contains("no-such-dir"), message(run));
    }

    @Test
    @DisplayName("deleteRemote refuses to clear the workspace the deploy reads from")
    void refusesToClearTheWorkspace() throws Exception {
        Path ws = sampleTree();

        AtomResult run = run(ws, "remote", ".", "includes", "**", "deleteRemote", "true");

        run.assertRefused();
        assertEquals("40002", errorCode(run), message(run));
        assertTrue(Files.exists(ws.resolve("app.jar")), "the guard ran after the delete");
    }

    @Test
    @DisplayName("deleteRemote refuses to clear a directory that contains the basedir")
    void refusesToClearTheSource() throws Exception {
        Path ws = sampleTree();
        Path target = temp();
        write(target.resolve("src/a.jar"), "a");

        AtomResult run = run(ws, "remote", target.toString(),
                "basedir", target.resolve("src").toString(), "deleteRemote", "true");

        run.assertRefused();
        assertEquals("40002", errorCode(run), message(run));
        assertTrue(Files.exists(target.resolve("src/a.jar")), "the guard ran after the delete");
    }

    @Test
    @DisplayName("an unwritable target fails with a readable permission error")
    void unwritableTarget() throws Exception {
        Path ws = sampleTree();
        Path locked = temp();
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(locked);
        Files.setPosixFilePermissions(locked, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            assumeTrue(!Files.isWritable(locked), "this user can write anyway (root?), so the case proves nothing");

            AtomResult run = run(ws, "remote", locked.resolve("out").toString());

            run.assertRefused();
            assertEquals("40004", errorCode(run), message(run));
            String text = message(run).toLowerCase();
            assertTrue(text.contains("permission") || text.contains("denied"), message(run));
        } finally {
            Files.setPosixFilePermissions(locked, original);
        }
    }

    // ---------------------------------------------------------------- the report

    @Test
    @DisplayName("a dry run lists what it would copy and writes nothing")
    void dryRun() throws Exception {
        Path ws = sampleTree();
        Path target = temp().resolve("never");

        AtomResult run = run(ws, "remote", target.toString(), "includes", "dist/**", "dryRun", "true");

        run.assertOk();
        assertFalse(Files.exists(target), "a dry run created " + target);
        assertEquals("true", run.field("dryRun"));
        List<String> names = rows(run).stream().map(row -> String.valueOf(row.get("fileName"))).sorted().toList();
        assertEquals(List.of("one.jar", "two.jar"), names, "the dry run's rows");
        assertTrue(run.console().contains("would copy dist/one.jar"), "the log should say what it would do:\n"
                + run.console());
    }

    @Test
    @DisplayName("the per-file report: ids from 1, sorted source paths, absolute real targets, deployed flags")
    void reportShape() throws Exception {
        Path ws = sampleTree();
        Path target = temp().resolve("out");

        AtomResult run = run(ws, "remote", target.toString(), "includes", "dist/**");

        run.assertOk();
        List<Map<String, Object>> rows = rows(run);
        assertEquals(List.of("1", "2"), rows.stream().map(row -> String.valueOf(row.get("id"))).toList());
        assertEquals(List.of("dist/deep/two.jar", "dist/one.jar"),
                rows.stream().map(row -> String.valueOf(row.get("sourcePath"))).toList());
        for (Map<String, Object> row : rows) {
            Path remote = Path.of(String.valueOf(row.get("remotePath")));
            assertTrue(remote.isAbsolute(), "the remote path must be absolute: " + row);
            assertTrue(Files.isRegularFile(remote), "the reported target does not exist: " + row);
            assertEquals(size(remote), ((Number) row.get("bytes")).longValue(), "byte count: " + row);
            assertEquals(true, row.get("deployed"), row.toString());
            assertEquals(false, row.get("skipped"), row.toString());
        }
        assertEquals(rows.stream().mapToLong(row -> ((Number) row.get("bytes")).longValue()).sum(),
                Long.parseLong(run.field("bytes")));
    }

    @Test
    @DisplayName("spaces in names are ordinary paths, not a pattern")
    void unicodeAndSpaces() throws Exception {
        Path ws = temp();
        write(ws.resolve("release notes/change log 1.txt"), "notes");
        Path target = temp().resolve("out");

        AtomResult run = run(ws, "remote", target.toString(), "includes", "**/*.txt");

        run.assertOk();
        assertEquals(Set.of("release notes/change log 1.txt"), filesUnder(target));
        assertEquals("release notes/change log 1.txt", String.valueOf(rows(run).get(0).get("sourcePath")));
    }

    @Test
    @DisplayName("Ant splits an include string on commas and whitespace alike")
    void patternsSeparatedBySpace() throws Exception {
        Path ws = sampleTree();
        Path target = temp().resolve("out");

        AtomResult run = run(ws, "remote", target.toString(),
                "includes", "**/*.jar **/*.md", "excludes", "**/docs/** **/*.tmp");

        run.assertOk();
        Set<String> actual = filesUnder(target);
        assertEquals(selected(sampleFiles(), "**/*.jar **/*.md", "**/docs/** **/*.tmp"), actual);
        assertFalse(actual.contains("docs/guide.md"), "the exclude did not apply: " + actual);
    }

    @Test
    @DisplayName("with no include pattern at all, everything under the basedir is in the set")
    void defaultIncludesWhenAbsent() throws Exception {
        Path ws = sampleTree();
        Path target = temp().resolve("out");

        AtomResult run = run(ws, "remote", target.toString());

        run.assertOk();
        assertEquals(sampleFiles(), filesUnder(target));
    }

    // ---------------------------------------------------------------- the tree under test

    private AtomResult run(Path workspace, String... pairs) throws Exception {
        Atom instance = atom.input("atom_workspace", workspace.toString());
        for (int i = 0; i < pairs.length; i += 2) {
            instance.input(pairs[i], pairs[i + 1]);
        }
        return instance.run();
    }

    private static Set<String> sampleFiles() {
        return new TreeSet<>(List.of(
                "app.jar", "readme.txt",
                "build/app.jar", "build/lib/util.jar", "build/lib/notes.txt", "build/tmp/scratch.tmp",
                "docs/guide.md", "docs/empty-note.txt",
                "dist/one.jar", "dist/deep/two.jar"));
    }

    /** A workspace with nesting, an empty dir, and a couple of decoys for excludes. */
    private static Path sampleTree() throws IOException {
        Path root = temp();
        for (String relative : sampleFiles()) {
            Path file = root.resolve(relative);
            write(file, contentOf(relative));
            Files.setLastModifiedTime(file, FileTime.from(FIXED_MTIME, java.util.concurrent.TimeUnit.SECONDS));
        }
        Files.createDirectories(root.resolve("build/empty-dir"));
        return root;
    }

    private static String contentOf(String relative) {
        return switch (relative) {
            case "app.jar" -> "app-bytes";
            case "readme.txt" -> "readme";
            case "build/app.jar" -> "nested-app";
            case "build/lib/util.jar" -> "util";
            case "build/lib/notes.txt" -> "notes";
            case "build/tmp/scratch.tmp" -> "scratch";
            case "docs/guide.md" -> "guide";
            case "docs/empty-note.txt" -> "empty";
            case "dist/one.jar" -> "one";
            case "dist/deep/two.jar" -> "two";
            default -> throw new IllegalArgumentException(relative);
        };
    }

    private static Path temp() throws IOException {
        return Files.createTempDirectory("deployer-");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new AssertionError("cannot size " + file, e);
        }
    }

    private static long mtime(Path file) {
        try {
            return Files.getLastModifiedTime(file).to(java.util.concurrent.TimeUnit.SECONDS);
        } catch (IOException e) {
            throw new AssertionError("cannot stat " + file, e);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** Every file under root, relative and with '/'. */
    private static Set<String> filesUnder(Path root) throws IOException {
        return walk(root, true);
    }

    /** Every directory under root, relative and with '/'. */
    private static Set<String> dirsUnder(Path root) throws IOException {
        return walk(root, false);
    }

    private static Set<String> walk(Path root, boolean files) throws IOException {
        Set<String> found = new TreeSet<>();
        if (!Files.isDirectory(root)) {
            return found;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(path -> !path.equals(root))
                    .filter(path -> files ? Files.isRegularFile(path) : Files.isDirectory(path))
                    .forEach(path -> found.add(root.relativize(path).toString().replace('\\', '/')));
        }
        return found;
    }

    private static String errorCode(AtomResult run) {
        return String.valueOf(run.document().get("errorCode"));
    }

    private static String message(AtomResult run) {
        return String.valueOf(run.document().getOrDefault("message", ""));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(AtomResult run) {
        Object parsed = Json.parse(run.field("files"));
        return parsed instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    // ---------------------------------------------------------------- a second Ant glob

    private static List<String> splitPatterns(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> patterns = new ArrayList<>();
        for (String piece : text.strip().split("[,\\s]+")) {
            if (!piece.isEmpty()) {
                patterns.add(piece.replace('\\', '/'));
            }
        }
        return patterns;
    }

    private static Pattern segmentRegex(String segment) {
        StringBuilder out = new StringBuilder("^");
        for (char c : segment.toCharArray()) {
            if (c == '*') {
                out.append("[^/]*");
            } else if (c == '?') {
                out.append("[^/]");
            } else {
                out.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(out.append("$").toString());
    }

    /** Ant: ** is zero or more directories, * and ? never cross a /. */
    static boolean antMatch(String pattern, String path) {
        pattern = pattern.replace('\\', '/');
        while (pattern.startsWith("/")) {
            pattern = pattern.substring(1);
        }
        if (pattern.isEmpty() || pattern.endsWith("/")) {
            return false;
        }
        String[] patternSegments = pattern.split("/");
        String[] pathSegments = path.split("/");
        return match(patternSegments, pathSegments, 0, 0);
    }

    private static boolean match(String[] pattern, String[] path, int pi, int si) {
        if (pi == pattern.length) {
            return si == path.length;
        }
        if (pattern[pi].equals("**")) {
            return match(pattern, path, pi + 1, si) || (si < path.length && match(pattern, path, pi, si + 1));
        }
        if (si >= path.length) {
            return false;
        }
        return segmentRegex(pattern[pi]).matcher(path[si]).matches() && match(pattern, path, pi + 1, si + 1);
    }

    static boolean antMatchDir(String pattern, String path) {
        pattern = pattern.replace('\\', '/');
        while (pattern.startsWith("/")) {
            pattern = pattern.substring(1);
        }
        boolean directoryOnly = pattern.endsWith("/");
        while (pattern.endsWith("/")) {
            pattern = pattern.substring(0, pattern.length() - 1);
        }
        if (pattern.isEmpty() || path.isEmpty()) {
            return false;
        }
        if (pattern.equals("**")) {
            return true;
        }
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            if (prefix.isEmpty()) {
                return true;
            }
            if (prefix.contains("*") || prefix.contains("?")) {
                String[] pieces = path.split("/");
                for (int index = 1; index <= pieces.length; index++) {
                    if (antMatch(prefix, String.join("/", List.of(pieces).subList(0, index)))) {
                        return true;
                    }
                }
                return false;
            }
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        if (directoryOnly) {
            return antMatch(pattern, path);
        }
        return false;
    }

    private static Set<String> selected(Set<String> relatives, String includes, String excludes) {
        List<String> inc = splitPatterns(includes);
        List<String> exc = splitPatterns(excludes);
        Set<String> chosen = new TreeSet<>();
        for (String path : relatives) {
            if (inc.stream().anyMatch(pattern -> antMatch(pattern, path))
                    && exc.stream().noneMatch(pattern -> antMatch(pattern, path))) {
                chosen.add(path);
            }
        }
        return chosen;
    }

    private static Set<String> selectedDirs(Set<String> relatives, String includes, String excludes) {
        List<String> inc = splitPatterns(includes);
        List<String> exc = splitPatterns(excludes);
        Set<String> chosen = new TreeSet<>();
        for (String path : relatives) {
            if (inc.stream().anyMatch(pattern -> antMatchDir(pattern, path))
                    && exc.stream().noneMatch(pattern -> antMatchDir(pattern, path))) {
                chosen.add(path);
            }
        }
        return chosen;
    }

}
