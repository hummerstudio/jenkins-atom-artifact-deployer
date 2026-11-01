package com.jenkinschina.atom.artifactdeployer;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.attribute.FileTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Copies workspace files into a target directory tree, ported from the Artifact Deployer plugin
 * ({@code ArtifactDeployerCopy} + {@code LocalCopy} + {@code ArtifactDeployerPublisher#processDeployment}).
 *
 * <p>The plugin's "remote" is not a protocol: {@code new FilePath(workspace.getChannel(), outputPath)} is a plain
 * filesystem path on the same node as the workspace, so the whole thing is a local copy and can be verified for real.
 *
 * <p>Rules copied from the plugin: the basedir is relative to the workspace (and must exist); includes/excludes are
 * Ant patterns, comma or whitespace separated, default {@code **}; {@code flatten} drops the directories; existing
 * target files are never overwritten ({@code setOverwrite(false)}); last-modified time and permissions are preserved;
 * {@code deleteRemote} empties the target directory (keeping the directory) before the copy; {@code failNoFilesDeploy}
 * fails when nothing matched, printing the configuration it was given.
 *
 * <p>Two deliberate differences, both in the README. First, every copied file is read back (size, SHA-256, mtime) and
 * a read-back mismatch fails the step — the plugin counts what Ant's copy map contains and never looks at the disk.
 * Files that were already there are counted as skipped instead of silently reported as deployed.
 */
public final class ArtifactDeployerAtom implements AtomTask {

    static final int BAD_INPUT = 40001;
    static final int UNSAFE_TARGET = 40002;
    static final int NO_FILES = 40003;
    static final int IO_FAILURE = 40004;
    static final int READ_BACK = 40010;

    /** A runaway include pattern must not walk a whole disk. Ant has no cap; this atom does, and says so. */
    static final int MAX_FILES = 50000;

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        try {
            return execute(context);
        } catch (IOException e) {
            return AtomResult.error(ErrorType.ENVIRONMENT, IO_FAILURE,
                    "cannot deploy: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private AtomResult execute(AtomContext context) throws IOException {
        Path workspace = context.getWorkspace().toAbsolutePath().normalize();

        String basedir = expand(context, orEmpty(context.getParam("basedir")).trim());
        String includes = expand(context, orEmpty(context.getParam("includes")).trim());
        String excludes = expand(context, orEmpty(context.getParam("excludes")).trim());
        String remote = expand(context, orEmpty(context.getParam("remote")).trim());
        boolean flatten = context.getBooleanParam("flatten", false);
        boolean deleteRemote = context.getBooleanParam("deleteRemote", false);
        boolean failNoFiles = context.getBooleanParam("failNoFilesDeploy", true);
        boolean dryRun = context.getBooleanParam("dryRun", false);

        if (includes.isEmpty()) {
            // Ant's FileSet default: with no include pattern everything under the basedir is in the set.
            includes = "**";
        }
        if (remote.isEmpty()) {
            return AtomResult.error(ErrorType.USER, BAD_INPUT,
                    "remote is required: the directory the files are deployed to");
        }

        Path base = resolve(workspace, basedir);
        if (!Files.isDirectory(base)) {
            return AtomResult.error(ErrorType.USER, BAD_INPUT,
                    "basedir '" + basedir + "' is not a directory (resolved to " + base + ")");
        }

        Path target = resolve(workspace, remote);
        if (deleteRemote) {
            AtomResult unsafe = guardTarget(workspace, base, target);
            if (unsafe != null) {
                return unsafe;
            }
        }

        List<String> parsedIncludes = split(includes);
        final List<String> includePatterns = parsedIncludes.isEmpty() ? List.of("**") : parsedIncludes;
        final List<String> excludePatterns = split(excludes);

        List<Path> files = new ArrayList<>();
        List<String> dirs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base, 64)) {
            walk.forEach(path -> {
                if (path.equals(base)) {
                    return;
                }
                String relative = relative(base, path);
                if (Files.isDirectory(path)) {
                    if (dirMatched(includePatterns, excludePatterns, relative)) {
                        dirs.add(relative);
                    }
                } else if (Files.isRegularFile(path)) {
                    if (fileMatched(includePatterns, excludePatterns, relative)) {
                        files.add(path);
                    }
                }
            });
        } catch (java.io.UncheckedIOException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            throw e;
        }
        if (files.size() > MAX_FILES) {
            return AtomResult.error(ErrorType.USER, BAD_INPUT,
                    "includes matched more than " + MAX_FILES + " files under '" + basedir
                            + "'; narrow the pattern instead of deploying " + files.size());
        }
        files.sort(Comparator.comparing(path -> relative(base, path)));
        dirs.sort(Comparator.naturalOrder());

        if (files.isEmpty() && failNoFiles) {
            return AtomResult.error(ErrorType.USER, NO_FILES,
                    "no files to deploy with " + configuration(includes, excludes, basedir, remote, flatten));
        }

        List<Map<String, Object>> deployed = new ArrayList<>();
        long bytes = 0;
        int copied = 0;
        int skipped = 0;
        int id = 0;

        if (dryRun) {
            for (Path file : files) {
                Map<String, Object> row = row(++id, file, base, target, flatten, Files.size(file));
                row.put("deployed", false);
                row.put("skipped", false);
                row.put("dryRun", true);
                deployed.add(row);
                context.log("would copy " + relative(base, file) + " -> " + row.get("remotePath"));
            }
            if (deleteRemote) {
                context.log("would clear the contents of " + target + " before copying");
            }
            AtomResult result = AtomResult.success("dry run: " + files.size() + " file(s) would be deployed to " + target);
            return describe(result, target, files.size(), 0, 0, 0, deployed, true);
        }

        Files.createDirectories(target);
        if (deleteRemote) {
            clearContents(target);
            context.log("cleared the contents of " + target);
        }
        if (!flatten) {
            for (String dir : dirs) {
                Files.createDirectories(target.resolve(dir));
            }
        }

        for (Path file : files) {
            String relative = relative(base, file);
            Path destination = flatten ? target.resolve(file.getFileName().toString()) : target.resolve(relative);
            long size = Files.size(file);
            Map<String, Object> row = row(++id, file, base, target, flatten, size);
            if (Files.exists(destination)) {
                // LocalCopy setOverwrite(false): an existing file is left alone, not replaced.
                row.put("deployed", false);
                row.put("skipped", true);
                skipped++;
                deployed.add(row);
                context.log("kept existing " + destination + " (overwrite is off)");
                continue;
            }
            Files.createDirectories(destination.getParent());
            copy(file, destination);
            try {
                verify(file, destination, size);
            } catch (IOException e) {
                return AtomResult.error(ErrorType.SYSTEM, READ_BACK,
                        "copied " + destination + " but reading it back failed: " + e.getMessage());
            }
            row.put("deployed", true);
            row.put("skipped", false);
            copied++;
            bytes += size;
            deployed.add(row);
        }

        AtomResult result = AtomResult.success("deployed " + copied + " file(s) to " + target
                + (skipped > 0 ? ", kept " + skipped + " existing" : "")
                + (files.isEmpty() ? ", nothing matched " + configuration(includes, excludes, basedir, remote, flatten) : ""));
        return describe(result, target, files.size(), copied, skipped, bytes, deployed, false);
    }

    private static AtomResult describe(AtomResult result, Path target, int matched, int copied, int skipped, long bytes,
                                       List<Map<String, Object>> files, boolean dryRun) {
        result.data("deployed", String.valueOf(files.size()));
        result.data("copied", String.valueOf(copied));
        result.data("skipped", String.valueOf(skipped));
        result.data("matched", String.valueOf(matched));
        result.data("bytes", String.valueOf(bytes));
        result.data("remotePath", target.toString());
        result.data("files", Json.write(files));
        result.data("dryRun", String.valueOf(dryRun));
        return result;
    }

    private static Map<String, Object> row(int id, Path file, Path base, Path target, boolean flatten, long size) {
        String relative = relative(base, file);
        Path destination = flatten ? target.resolve(file.getFileName().toString()) : target.resolve(relative);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("fileName", destination.getFileName().toString());
        row.put("sourcePath", relative);
        row.put("remotePath", destination.toString());
        row.put("bytes", size);
        return row;
    }

    /** Refusals that would destroy the build instead of deploying it. */
    private static AtomResult guardTarget(Path workspace, Path base, Path target) {
        if (target.getParent() == null) {
            return AtomResult.error(ErrorType.USER, UNSAFE_TARGET,
                    "refusing to clear '" + target + "': that is the filesystem root");
        }
        if (base.startsWith(target)) {
            return AtomResult.error(ErrorType.USER, UNSAFE_TARGET,
                    "refusing to clear '" + target + "': the source basedir " + base
                            + " is inside it, so the files to deploy would be deleted first");
        }
        return null;
    }

    private static void clearContents(Path directory) throws IOException {
        List<Path> children = new ArrayList<>();
        try (Stream<Path> list = Files.list(directory)) {
            list.forEach(children::add);
        }
        // children first: Path.compareTo sorts "a/b" after "a", so reverse order removes the leaves.
        children.sort(Comparator.reverseOrder());
        for (Path child : children) {
            deleteRecursively(child);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            List<Path> children = new ArrayList<>();
            try (Stream<Path> list = Files.list(path)) {
                list.forEach(children::add);
            }
            for (Path child : children) {
                deleteRecursively(child);
            }
        }
        Files.deleteIfExists(path);
    }

    private static void copy(Path source, Path destination) throws IOException {
        try {
            Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (UnsupportedOperationException e) {
            // some filesystems refuse to copy attributes; the bytes still go over
            Files.copy(source, destination);
        }
    }

    /** Iron rule: a successful copy call is not a successful deployment. Look at the file that is now on disk. */
    private static void verify(Path source, Path destination, long size) throws IOException {
        if (!Files.isRegularFile(destination)) {
            throw new IOException("the destination is not a regular file");
        }
        long actual = Files.size(destination);
        if (actual != size) {
            throw new IOException("expected " + size + " bytes, found " + actual);
        }
        String want = sha256(source);
        String got = sha256(destination);
        if (!want.equals(got)) {
            throw new IOException("SHA-256 differs: source " + want + ", destination " + got);
        }
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
        FileTime sourceTime = attributes.lastModifiedTime();
        FileTime destinationTime = Files.getLastModifiedTime(destination);
        if (Math.abs(sourceTime.toMillis() - destinationTime.toMillis()) > 1000) {
            throw new IOException("last-modified time was not preserved: source " + sourceTime
                    + ", destination " + destinationTime);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    // ------------------------------------------------------------------ the basedir / remote resolution

    /** An absolute value is taken as it stands; anything else hangs off the workspace, like FilePath.child. */
    private static Path resolve(Path workspace, String value) {
        if (value == null || value.isEmpty()) {
            return workspace;
        }
        Path path = Path.of(value);
        return path.isAbsolute() ? path.normalize() : workspace.resolve(path).normalize();
    }

    private static String relative(Path base, Path path) {
        return base.relativize(path).toString().replace('\\', '/');
    }

    private static String configuration(String includes, String excludes, String basedir, String remote,
                                        boolean flatten) {
        StringBuilder sb = new StringBuilder("[includes:").append(includes);
        if (excludes != null && !excludes.isEmpty()) {
            sb.append(",excludes:").append(excludes);
        }
        sb.append(",basedir:").append(basedir.isEmpty() ? "." : basedir);
        sb.append(",outPath:").append(remote);
        sb.append(flatten ? ",flatten:true" : "");
        sb.append(']');
        return sb.toString();
    }

    // ------------------------------------------------------------------ Ant patterns

    /** Ant splits an include/exclude string on commas and whitespace (DirectoryScanner). */
    static List<String> split(String patterns) {
        List<String> out = new ArrayList<>();
        if (patterns == null || patterns.isBlank()) {
            return out;
        }
        for (String piece : patterns.trim().split("[,\\s]+")) {
            if (!piece.isEmpty()) {
                out.add(piece.replace('\\', '/'));
            }
        }
        return out;
    }

    static boolean fileMatched(List<String> includes, List<String> excludes, String relative) {
        boolean included = false;
        for (String pattern : includes) {
            if (matches(pattern, relative)) {
                included = true;
                break;
            }
        }
        if (!included) {
            return false;
        }
        for (String pattern : excludes) {
            if (matches(pattern, relative)) {
                return false;
            }
        }
        return true;
    }

    static boolean dirMatched(List<String> includes, List<String> excludes, String relative) {
        boolean included = false;
        for (String pattern : includes) {
            if (matchesDirectory(pattern, relative)) {
                included = true;
                break;
            }
        }
        if (!included) {
            return false;
        }
        for (String pattern : excludes) {
            if (matchesDirectory(pattern, relative) || matches(pattern, relative)) {
                return false;
            }
        }
        return true;
    }

    /** Ant globs: {@code **} is zero or more directories, {@code *} and {@code ?} stay inside one segment. */
    static boolean matches(String pattern, String path) {
        if (pattern == null || path == null) {
            return false;
        }
        String clean = pattern.replace('\\', '/');
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        if (clean.isEmpty() || clean.endsWith("/")) {
            return false;
        }
        return matchSegments(clean.split("/", -1), 0, path.split("/", -1), 0);
    }

    private static boolean matchSegments(String[] pattern, int pi, String[] path, int si) {
        if (pi == pattern.length) {
            return si == path.length;
        }
        if ("**".equals(pattern[pi])) {
            if (matchSegments(pattern, pi + 1, path, si)) {
                return true;
            }
            return si < path.length && matchSegments(pattern, pi, path, si + 1);
        }
        if (si >= path.length) {
            return false;
        }
        return segmentMatched(pattern[pi], path[si]) && matchSegments(pattern, pi + 1, path, si + 1);
    }

    private static boolean segmentMatched(String pattern, String segment) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return segment.matches(regex.toString());
    }

    /**
     * Whether a directory is part of the fileset, so the empty ones get created in the target. Ant includes a
     * directory when the pattern is meant for directories: {@code **}, {@code a/}, {@code a/**}.
     */
    static boolean matchesDirectory(String pattern, String path) {
        if (pattern == null || path == null || path.isEmpty()) {
            return false;
        }
        String clean = pattern.replace('\\', '/');
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        boolean directoryOnly = clean.endsWith("/");
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.isEmpty()) {
            return false;
        }
        if ("**".equals(clean)) {
            return true;
        }
        if (clean.endsWith("/**")) {
            String prefix = clean.substring(0, clean.length() - 3);
            if (prefix.isEmpty()) {
                return true;
            }
            if (prefix.contains("*") || prefix.contains("?")) {
                for (String candidate : ancestorsAndSelf(path)) {
                    if (matches(prefix, candidate)) {
                        return true;
                    }
                }
                return false;
            }
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        if (directoryOnly) {
            return matches(clean, path);
        }
        return false;
    }

    private static List<String> ancestorsAndSelf(String path) {
        List<String> out = new ArrayList<>();
        String current = path;
        while (true) {
            out.add(current);
            int slash = current.lastIndexOf('/');
            if (slash < 0) {
                return out;
            }
            current = current.substring(0, slash);
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** ${VAR} from the atom's inputs first, then the build environment (the plugin uses getEnvironment().expand). */
    static String expand(AtomContext context, String text) {
        if (text == null || !text.contains("${")) {
            return orEmpty(text);
        }
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (true) {
            int start = text.indexOf("${", at);
            if (start < 0) {
                out.append(text, at, text.length());
                return out.toString();
            }
            int end = text.indexOf('}', start + 2);
            if (end < 0) {
                out.append(text, at, text.length());
                return out.toString();
            }
            out.append(text, at, start);
            String name = text.substring(start + 2, end);
            String value = context.getParam(name);
            if (value == null) {
                value = System.getenv(name);
            }
            out.append(value == null ? text.substring(start, end + 1) : value);
            at = end + 1;
        }
    }
}
