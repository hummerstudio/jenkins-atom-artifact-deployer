#!/usr/bin/env python3
"""Plant one bug at a time in the atom and prove the checks catch it.

A green suite means nothing if it stays green for a broken atom. Each mutation is one textual change to the real
source; the mutated tree is rebuilt and the named check must fail. A survivor is a hole in the suite.

    python3 tools/mutations.py
"""
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent.parent
CODE = "artifact-deployer"
SOURCE = HERE / "src" / "main" / "java" / "com" / "jenkinschina" / "atom" / "artifactdeployer" / "ArtifactDeployerAtom.java"

MUTATIONS = [
    ("** is treated as a single-segment wildcard",
     'if ("**".equals(pattern[pi])) {', 'if ("not-a-double-star".equals(pattern[pi])) {', "ant.glob.table"),
    ("** no longer matches zero directories",
     "if (matchSegments(pattern, pi + 1, path, si)) {", "if (false && matchSegments(pattern, pi + 1, path, si)) {",
     "ant.glob.table"),
    ("pattern matching ignores case",
     "return segment.matches(regex.toString());",
     "return segment.toLowerCase().matches(regex.toString().toLowerCase());", "ant.glob.table"),
    ("excludes are collected and then ignored",
     "        for (String pattern : excludes) {\n            if (matches(pattern, relative)) {\n                return false;",
     "        for (String pattern : excludes) {\n            if (false) {\n                return false;", "excludes.remove"),
    ("include strings are only split on commas, not whitespace",
     'for (String piece : patterns.trim().split("[,\\\\s]+")) {',
     'for (String piece : patterns.trim().split(",")) {', "patterns.space.separated"),
    ("flatten is ignored, the source layout is kept",
     "Path destination = flatten ? target.resolve(file.getFileName().toString()) : target.resolve(relative);",
     "Path destination = target.resolve(relative);", "flatten.collisions"),
    ("an existing target file is overwritten instead of kept",
     "            if (Files.exists(destination)) {", "            if (false) {", "existing.kept.not.overwritten"),
    ("the skip of an existing file is reported as a fresh deploy",
     '            row.put("skipped", true);', '            row.put("skipped", false);', "existing.kept.not.overwritten"),
    ("deleteRemote never clears anything",
     "            clearContents(target);", "            if (false) clearContents(target);", "deleteRemote.clears"),
    ("the copy writes only the first bytes of every file",
     "Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);",
     "try (InputStream in = Files.newInputStream(source)) { Files.write(destination, in.readNBytes(3)); }",
     "copy.everything.default"),
    ("nothing matched is a success, the failNoFilesDeploy flag is ignored",
     "if (files.isEmpty() && failNoFiles) {", "if (false) {", "no.files.fails.by.default"),
    ("the empty deployment is reported with the generic input code",
     "return AtomResult.error(ErrorType.USER, NO_FILES,", "return AtomResult.error(ErrorType.USER, BAD_INPUT,",
     "no.files.fails.by.default"),
    ("remote may be empty, so the deploy lands on the workspace root",
     '        if (remote.isEmpty()) {', '        if (false) {', "input.remote.required"),
    ("a missing basedir is walked anyway",
     '        if (!Files.isDirectory(base)) {', '        if (false) {', "input.basedir.must.exist"),
    ("the target may be cleared even when it contains the sources",
     "        if (base.startsWith(target)) {", "        if (false) {", "unsafe.clear.parent.of.basedir.refused"),
    ("a dry run writes anyway",
     "        if (dryRun) {", "        if (false) {", "dryrun.writes.nothing"),
    ("a relative remote resolves against the process directory instead of the workspace",
     "return path.isAbsolute() ? path.normalize() : workspace.resolve(path).normalize();",
     "return path.toAbsolutePath().normalize();", "remote.relative.to.workspace"),
    ("empty directories are never created",
     "        if (!flatten) {", "        if (false) {", "empty.dirs.created"),
    ("the report numbers its rows from zero",
     "row(++id, file, base, target, flatten, size)", "row(id++, file, base, target, flatten, size)",
     "files.report.shape"),
    ("the byte total is counted twice per file",
     "            bytes += size;", "            bytes += size * 2;", "copy.everything.default"),
    ("${VAR} is no longer read from the build environment",
     "                value = System.getenv(name);", "                value = null;", "basedir.env.expanded"),
    ("no include pattern now means one directory level",
     '            includes = "**";', '            includes = "*";', "default.includes.when.absent"),
    ("the file report lists the name only, without where it went",
     'row.put("remotePath", destination.toString());', 'row.put("remotePath", destination.getFileName().toString());',
     "files.report.shape"),
]


def sdk_jar():
    env = os.environ.get("SDK_JAR")
    if env:
        return env
    for root in (HERE.parent / "jenkins-atom-sdk", HERE / "jenkins-atom-sdk"):
        candidates = sorted((root / "target").glob("jenkins-atom-sdk-*.jar"))
        if candidates:
            return str(candidates[-1])
    raise SystemExit("build the SDK first: cd ../jenkins-atom-sdk && mvn -DskipTests package")


def build(tree):
    env = dict(os.environ)
    env["SDK_JAR"] = sdk_jar()
    proc = subprocess.run(["mvn", "-q", "-B", "-ntp", "-DskipTests", "package"], cwd=tree, env=env, capture_output=True, text=True)
    if proc.returncode != 0:
        return None, proc.stderr[-1500:]
    return tree / "target" / "dist" / CODE / (CODE + ".jar"), proc.stdout


def main():
    failures = 0
    for index, (name, old, new, case) in enumerate(MUTATIONS, 1):
        tree = pathlib.Path(tempfile.mkdtemp(prefix="deployer-mut-")) / "atom"
        shutil.copytree(HERE, tree, ignore=shutil.ignore_patterns("dist", "target", ".git", "__pycache__"))
        source = tree / SOURCE.relative_to(HERE)
        text = source.read_text(encoding="utf-8")
        if old not in text:
            print("SKIP  %s: the mutation no longer matches the source" % name)
            failures += 1
            continue
        source.write_text(text.replace(old, new, 1), encoding="utf-8")
        jar, output = build(tree)
        if jar is None:
            print("SKIP  %s: the mutated atom does not build: %s" % (name, output))
            failures += 1
            continue
        proc = subprocess.run([sys.executable, "tools/check.py", case, "--jar", str(jar)],
                              cwd=tree, capture_output=True, text=True)
        if proc.returncode == 0:
            print("SURVIVED %-2d %s (case %s stayed green)" % (index, name, case))
            failures += 1
        else:
            print("caught   %-2d %s" % (index, name))
    print("\n%d/%d mutations caught" % (len(MUTATIONS) - failures, len(MUTATIONS)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
