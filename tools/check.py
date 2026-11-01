#!/usr/bin/env python3
"""Behaviour checks for the artifact-deployer atom.

Every check drives the atom the way the runner does (input.json in, output.json out) against a real directory tree,
then looks at what is actually on disk: the set of files, their bytes, their mtime. The expected set is recomputed
here by a separate Ant-glob implementation, and the tricky patterns are pinned by a hand-written table so a wrong
implementation cannot agree with itself.

    python3 tools/check.py                  # every check
    python3 tools/check.py flatten          # only checks whose name contains "flatten"
    python3 tools/check.py copy --jar X     # run a mutated build
"""
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
JAR = os.path.join(REPO, "dist", "artifact-deployer", "artifact-deployer.jar")
JAVA = os.path.join(os.environ.get("JAVA_HOME", "/Users/tangming/tools/jdk-21.0.12.1+1/Contents/Home"),
                    "bin", "java")
TASK = json.load(open(os.path.join(REPO, "task.json"), encoding="utf-8"))

CHECKS = []


def check(name):
    def register(fn):
        CHECKS.append((name, fn))
        return fn
    return register


# --------------------------------------------------------------------------- the runner's contract

def base_input(**overrides):
    data = {name: spec.get("default", "") for name, spec in TASK["input"].items()}
    data.update({"JOB_NAME": "deploy", "BUILD_NUMBER": "7", "BUILD_STATUS": "SUCCESS"})
    data.update(overrides)
    return data


def run(overrides, workspace, timeout=120, env_extra=None, drop=(), cwd=None):
    directory = tempfile.mkdtemp(prefix="deployer-check-")
    data = base_input(**overrides)
    for name in drop:
        data.pop(name, None)
    # the runner reads the workspace from the inputs, not from the environment
    data["atom_workspace"] = workspace
    json.dump(data, open(os.path.join(directory, "input.json"), "w", encoding="utf-8"), ensure_ascii=False)
    env = dict(os.environ)
    env.update({"atom_data_dir": directory, "atom_data_input": "input.json", "atom_data_output": "output.json",
                "atom_workspace": workspace})
    env.update(env_extra or {})
    # cwd is deliberately not the workspace by default in the relative-path check; elsewhere they coincide
    proc = subprocess.run([JAVA, "-cp", JAR, "com.jenkinschina.atom.sdk.AtomRunner"], cwd=cwd or workspace, env=env,
                          capture_output=True, text=True, timeout=timeout)
    out = os.path.join(directory, "output.json")
    result = json.load(open(out, encoding="utf-8")) if os.path.exists(out) else {}
    values = {key: node.get("value") for key, node in (result.get("data") or {}).items()}
    return result, values, proc


def expect_error(result, code):
    assert result.get("status") == "error", "expected an error, got %s: %s" % (result.get("status"),
                                                                              result.get("message"))
    assert result.get("errorCode") == code, "expected error %s, got %s: %s" % (code, result.get("errorCode"),
                                                                              result.get("message"))


# --------------------------------------------------------------------------- an independent Ant glob

def split_patterns(text):
    if not text or not text.strip():
        return []
    return [piece.replace("\\", "/") for piece in re.split(r"[,\s]+", text.strip()) if piece]


def _segment_regex(segment):
    out = ["^"]
    for char in segment:
        if char == "*":
            out.append("[^/]*")
        elif char == "?":
            out.append("[^/]")
        else:
            out.append(re.escape(char))
    out.append("$")
    return re.compile("".join(out))


def ant_match(pattern, path):
    """Ant: ** is zero or more directories, * and ? never cross a /."""
    pattern = pattern.replace("\\", "/").lstrip("/")
    if not pattern or pattern.endswith("/"):
        return False
    pattern_segments = pattern.split("/")
    path_segments = path.split("/")

    def walk(pi, si):
        if pi == len(pattern_segments):
            return si == len(path_segments)
        if pattern_segments[pi] == "**":
            return walk(pi + 1, si) or (si < len(path_segments) and walk(pi, si + 1))
        if si >= len(path_segments):
            return False
        return bool(_segment_regex(pattern_segments[pi]).match(path_segments[si])) and walk(pi + 1, si + 1)

    return walk(0, 0)


def ant_match_dir(pattern, path):
    pattern = pattern.replace("\\", "/").lstrip("/")
    directory_only = pattern.endswith("/")
    pattern = pattern.rstrip("/")
    if not pattern or not path:
        return False
    if pattern == "**":
        return True
    if pattern.endswith("/**"):
        prefix = pattern[:-3]
        if not prefix:
            return True
        if any(char in prefix for char in "*?"):
            pieces = path.split("/")
            return any(ant_match(prefix, "/".join(pieces[:index])) for index in range(1, len(pieces) + 1))
        return path == prefix or path.startswith(prefix + "/")
    if directory_only:
        return ant_match(pattern, path)
    return False


def selected(relatives, includes, excludes):
    inc = split_patterns(includes)
    exc = split_patterns(excludes)
    return {path for path in relatives
            if any(ant_match(pattern, path) for pattern in inc)
            and not any(ant_match(pattern, path) for pattern in exc)}


def selected_dirs(relatives, includes, excludes):
    inc = split_patterns(includes)
    exc = split_patterns(excludes)
    return {path for path in relatives
            if any(ant_match_dir(pattern, path) for pattern in inc)
            and not any(ant_match_dir(pattern, path) for pattern in exc)}


# --------------------------------------------------------------------------- trees on disk

def write(path, content, mtime=None):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(content)
    if mtime is not None:
        os.utime(path, (mtime, mtime))


def sample_tree(workspace):
    """A workspace with nesting, an empty dir, and a couple of decoys for excludes."""
    files = {
        "app.jar": "app-bytes",
        "readme.txt": "readme",
        "build/app.jar": "nested-app",
        "build/lib/util.jar": "util",
        "build/lib/notes.txt": "notes",
        "build/tmp/scratch.tmp": "scratch",
        "docs/guide.md": "guide",
        "docs/empty-note.txt": "empty",
        "dist/one.jar": "one",
        "dist/deep/two.jar": "two",
    }
    for relative, content in files.items():
        write(os.path.join(workspace, relative), content, mtime=978307200)
    os.makedirs(os.path.join(workspace, "build", "empty-dir"), exist_ok=True)
    return set(files)


def tree_of(root):
    """Every file under root, relative and with '/', plus every directory."""
    files = set()
    dirs = set()
    for current, subdirs, names in os.walk(root):
        for name in subdirs:
            dirs.add(os.path.relpath(os.path.join(current, name), root).replace(os.sep, "/"))
        for name in names:
            files.add(os.path.relpath(os.path.join(current, name), root).replace(os.sep, "/"))
    return files, dirs


def read(root, relative):
    with open(os.path.join(root, relative), "rb") as handle:
        return handle.read()


def digest(root, relative):
    return hashlib.sha256(read(root, relative)).hexdigest()


def fresh_workspace():
    return tempfile.mkdtemp(prefix="deployer-ws-")


# --------------------------------------------------------------------------- checks

@check("copy.everything.default")
def _():
    workspace = fresh_workspace()
    files = sample_tree(workspace)
    target = os.path.join(tempfile.mkdtemp(prefix="deployer-out-"), "release")
    result, values, proc = run({"remote": target}, workspace)
    assert result.get("status") == "success", result.get("message") + proc.stdout + proc.stderr
    expected = selected(files, "**", "")
    actual, _dirs = tree_of(target)
    assert actual == expected, "copied %s, expected %s" % (sorted(actual), sorted(expected))
    for relative in expected:
        assert digest(workspace, relative) == digest(target, relative), "bytes differ for " + relative
        assert int(os.path.getmtime(os.path.join(target, relative))) == 978307200, "mtime lost on " + relative
    assert values["matched"] == str(len(expected)), values
    assert values["copied"] == str(len(expected)), values
    assert values["bytes"] == str(sum(os.path.getsize(os.path.join(workspace, r)) for r in expected)), values


@check("ant.glob.table")
def _():
    """Hand-written expectations; the atom's result and the Python matcher must both agree with them."""
    table = [
        ("**/*.jar", ["build/app.jar", "build/lib/util.jar", "dist/deep/two.jar", "dist/one.jar", "app.jar"],
         ["readme.txt", "build/lib/notes.txt"]),
        ("*.txt", ["readme.txt"], ["build/lib/notes.txt", "docs/guide.md"]),
        ("build/**", ["build/app.jar", "build/lib/util.jar", "build/lib/notes.txt", "build/tmp/scratch.tmp"],
         ["dist/one.jar"]),
        ("**/lib/*.jar", ["build/lib/util.jar"], ["dist/one.jar"]),
        ("**/*.TXT", [], ["readme.txt"]),
        ("build/?pp.jar", ["build/app.jar"], ["build/lib/util.jar"]),
        ("**/two.jar", ["dist/deep/two.jar"], ["dist/one.jar"]),
    ]
    for pattern, must, must_not in table:
        for path in must:
            assert ant_match(pattern, path), "python matcher disagrees with the table: %s vs %s" % (pattern, path)
        for path in must_not:
            assert not ant_match(pattern, path), "python matcher disagrees with the table: %s vs %s" % (pattern,
                                                                                                       path)

    workspace = fresh_workspace()
    files = sample_tree(workspace)
    # a case-only difference must survive the trip through the atom, not just the table above
    target = os.path.join(tempfile.mkdtemp(prefix="deployer-out-"), "case")
    result, _values, proc = run({"remote": target, "includes": "**/*.TXT"}, workspace)
    assert result.get("status") == "error", "**/*.TXT matched lowercase readme.txt: %s" % proc.stdout
    write(os.path.join(workspace, "upper.TXT"), "upper")
    result, _values, proc = run({"remote": target, "includes": "**/*.TXT"}, workspace)
    assert result.get("status") == "success", result.get("message")
    assert set(tree_of(target)[0]) == {"upper.TXT"}, sorted(tree_of(target)[0])

    for pattern, must, must_not in table:
        if pattern == "**/*.TXT":
            continue  # proven above, on a tree with both cases present
        target = os.path.join(tempfile.mkdtemp(prefix="deployer-out-"), "out")
        result, values, proc = run({"remote": target, "includes": pattern}, workspace)
        assert result.get("status") == "success", pattern + ": " + str(result.get("message")) + proc.stderr
        actual, _dirs = tree_of(target)
        assert actual == selected(files, pattern, ""), "%s copied %s, expected %s" % (
            pattern, sorted(actual), sorted(selected(files, pattern, "")))
        for relative in must:
            assert relative in actual, "%s must copy %s, copied %s" % (pattern, relative, sorted(actual))
        for relative in must_not:
            assert relative not in actual, "%s must not copy %s" % (pattern, relative)


@check("excludes.remove")
def _():
    workspace = fresh_workspace()
    files = sample_tree(workspace)
    target = os.path.join(tempfile.mkdtemp(prefix="deployer-out-"), "out")
    result, values, _proc = run({"remote": target, "includes": "**", "excludes": "**/*.tmp, **/*.md"}, workspace)
    assert result.get("status") == "success", result.get("message")
    expected = selected(files, "**", "**/*.tmp, **/*.md")
    actual, _dirs = tree_of(target)
    assert actual == expected, "got %s, expected %s" % (sorted(actual), sorted(expected))
    assert "build/tmp/scratch.tmp" not in actual
    assert "docs/guide.md" not in actual


@check("basedir.relative")
def _():
    workspace = fresh_workspace()
    sample_tree(workspace)
    target = os.path.join(tempfile.mkdtemp(prefix="deployer-out-"), "out")
    result, values, _proc = run({"remote": target, "basedir": "build", "includes": "**/*.jar"}, workspace)
    assert result.get("status") == "success", result.get("message")
    actual, _dirs = tree_of(target)
    assert actual == {"app.jar", "lib/util.jar"}, sorted(actual)
    assert values["copied"] == "2", values


@check("basedir.env.expanded")
def _():
    workspace = fresh_workspace()
    sample_tree(workspace)
    target = os.path.join(tempfile.mkdtemp(prefix="deployer-out-"), "out")
    result, values, _proc = run({"remote": target, "basedir": "${SRC}", "includes": "*.jar"}, workspace,
                                env_extra={"SRC": "dist"})
    assert result.get("status") == "success", result.get("message")
    assert set(tree_of(target)[0]) == {"one.jar"}, sorted(tree_of(target)[0])
    assert os.path.basename(values["remotePath"]) == "out"


@check("remote.relative.to.workspace")
def _():
    workspace = fresh_workspace()
    sample_tree(workspace)
    # the atom is started from an unrelated directory: a relative remote must resolve against the workspace,
    # not against the process working directory (on a CI runner the two are often the same, which hides the bug)
    elsewhere = tempfile.mkdtemp(prefix="deployer-cwd-")
    result, values, _proc = run({"remote": "out/release", "includes": "*.jar"}, workspace, cwd=elsewhere)
    assert result.get("status") == "success", result.get("message")
    assert values["remotePath"] == os.path.join(workspace, "out", "release"), values["remotePath"]
    assert set(tree_of(os.path.join(workspace, "out", "release"))[0]) == {"app.jar"}
    assert not os.path.exists(os.path.join(elsewhere, "out")), "a relative remote was resolved against the cwd"


@check("remote.outside.workspace")
def _():
    workspace = fresh_workspace()
    sample_tree(workspace)
    outside = tempfile.mkdtemp(prefix="deployer-outside-")
    result, values, _proc = run({"remote": outside, "includes": "dist/**"}, workspace)
    assert result.get("status") == "success", result.get("message")
    assert set(tree_of(outside)[0]) == {"dist/one.jar", "dist/deep/two.jar"}, sorted(tree_of(outside)[0])


@check("flatten.collisions")
def _():
    workspace = fresh_workspace()
    write(os.path.join(workspace, "a", "same.jar"), "first")
    write(os.path.join(workspace, "b", "same.jar"), "second")
    write(os.path.join(workspace, "a", "only.jar"), "only")
    target = os.path.join(tempfile.mkdtemp(prefix="deployer-out-"), "flat")
    result, values, _proc = run({"remote": target, "includes": "**/*.jar", "flatten": "true"}, workspace)
    assert result.get("status") == "success", result.get("message")
    actual, dirs = tree_of(target)
    assert actual == {"same.jar", "only.jar"}, sorted(actual)
    assert dirs == set(), "flatten must not create the source directories: %s" % sorted(dirs)
    # sorted order: a/same.jar is copied first, b/same.jar meets an existing file and is kept back
    assert read(target, "same.jar") == b"first", read(target, "same.jar")
    assert values["copied"] == "2", values
    assert values["skipped"] == "1", values


@check("existing.kept.not.overwritten")
def _():
    workspace = fresh_workspace()
    sample_tree(workspace)
    target = os.path.join(tempfile.mkdtemp(prefix="deployer-out-"), "out")
    write(os.path.join(target, "app.jar"), "already-here", mtime=1000000000)
    result, values, _proc = run({"remote": target, "includes": "*.jar"}, workspace)
    assert result.get("status") == "success", result.get("message")
    assert read(target, "app.jar") == b"already-here", "an existing file was overwritten"
    assert int(os.path.getmtime(os.path.join(target, "app.jar"))) == 1000000000
    assert values["skipped"] == "1", values
    assert values["copied"] == "0", values
    rows = json.loads(values["files"])
    assert len(rows) == 1 and rows[0]["skipped"] is True and rows[0]["deployed"] is False, rows


@check("deleteRemote.clears.then.copies")
def _():
    workspace = fresh_workspace()
    sample_tree(workspace)
    target = os.path.join(tempfile.mkdtemp(prefix="deployer-out-"), "out")
    write(os.path.join(target, "stale.txt"), "stale")
    write(os.path.join(target, "sub", "old.jar"), "old")
    result, values, _proc = run({"remote": target, "includes": "dist/**", "deleteRemote": "true"}, workspace)
    assert result.get("status") == "success", result.get("message")
    actual, dirs = tree_of(target)
    assert actual == {"dist/one.jar", "dist/deep/two.jar"}, sorted(actual)
    assert "sub" not in dirs, "stale directory survived the clear: %s" % sorted(dirs)
    assert os.path.isdir(target), "the target directory itself must stay"


@check("empty.dirs.created")
def _():
    workspace = fresh_workspace()
    sample_tree(workspace)
    target = os.path.join(tempfile.mkdtemp(prefix="deployer-out-"), "out")
    result, values, _proc = run({"remote": target, "includes": "**"}, workspace)
    assert result.get("status") == "success", result.get("message")
    _files, dirs = tree_of(target)
    expected_dirs = selected_dirs({"build/empty-dir", "build/lib", "build/tmp", "dist/deep", "docs"},
                                  "**", "")
    assert "build/empty-dir" in dirs, "includeEmptyDirs: the empty directory was not created: %s" % sorted(dirs)
    assert expected_dirs <= dirs | {"build", "dist"}, sorted(expected_dirs)


@check("no.files.fails.by.default")
def _():
    workspace = fresh_workspace()
    sample_tree(workspace)
    target = os.path.join(tempfile.mkdtemp(prefix="deployer-out-"), "out")
    result, _values, _proc = run({"remote": target, "includes": "**/*.nope"}, workspace)
    expect_error(result, 40003)
    assert "includes:**/*.nope" in result["message"], result["message"]
    assert "basedir:." in result["message"], result["message"]
    assert "outPath:" in result["message"], result["message"]
    assert not os.path.exists(target), "a failed deploy must not leave an empty target behind"


@check("no.files.allowed.when.asked")
def _():
    workspace = fresh_workspace()
    sample_tree(workspace)
    target = os.path.join(tempfile.mkdtemp(prefix="deployer-out-"), "out")
    result, values, _proc = run({"remote": target, "includes": "**/*.nope", "failNoFilesDeploy": "false"},
                                workspace)
    assert result.get("status") == "success", result.get("message")
    assert values["matched"] == "0" and values["copied"] == "0", values


@check("input.remote.required")
def _():
    workspace = fresh_workspace()
    sample_tree(workspace)
    result, _values, _proc = run({"remote": ""}, workspace)
    expect_error(result, 40001)
    assert "remote" in result["message"], result["message"]


@check("input.basedir.must.exist")
def _():
    workspace = fresh_workspace()
    sample_tree(workspace)
    target = os.path.join(tempfile.mkdtemp(prefix="deployer-out-"), "out")
    result, _values, _proc = run({"remote": target, "basedir": "no-such-dir"}, workspace)
    expect_error(result, 40001)
    assert "no-such-dir" in result["message"], result["message"]


@check("unsafe.clear.workspace.refused")
def _():
    workspace = fresh_workspace()
    sample_tree(workspace)
    result, _values, _proc = run({"remote": workspace, "includes": "**", "deleteRemote": "true"}, workspace)
    expect_error(result, 40002)
    assert os.path.exists(os.path.join(workspace, "app.jar")), "the guard ran after the delete"


@check("unsafe.clear.parent.of.basedir.refused")
def _():
    workspace = fresh_workspace()
    sample_tree(workspace)
    target = tempfile.mkdtemp(prefix="deployer-out-")
    write(os.path.join(target, "src", "a.jar"), "a")
    # an absolute basedir inside the target: clearing the target first would delete what is being deployed
    result, _values, _proc = run({"remote": target, "basedir": os.path.join(target, "src"),
                                  "deleteRemote": "true"}, workspace)
    expect_error(result, 40002)
    assert os.path.exists(os.path.join(target, "src", "a.jar"))


@check("dryrun.writes.nothing")
def _():
    workspace = fresh_workspace()
    sample_tree(workspace)
    target = os.path.join(tempfile.mkdtemp(prefix="deployer-out-"), "never")
    result, values, proc = run({"remote": target, "includes": "dist/**", "dryRun": "true"}, workspace)
    assert result.get("status") == "success", result.get("message")
    assert not os.path.exists(target), "a dry run created " + target
    assert values["dryRun"] == "true", values
    rows = json.loads(values["files"])
    assert sorted(row["fileName"] for row in rows) == ["one.jar", "two.jar"], rows
    assert "would copy dist/one.jar" in proc.stdout, proc.stdout


@check("files.report.shape")
def _():
    workspace = fresh_workspace()
    sample_tree(workspace)
    target = os.path.join(tempfile.mkdtemp(prefix="deployer-out-"), "out")
    result, values, _proc = run({"remote": target, "includes": "dist/**"}, workspace)
    assert result.get("status") == "success", result.get("message")
    rows = json.loads(values["files"])
    assert [row["id"] for row in rows] == [1, 2], rows
    assert [row["sourcePath"] for row in rows] == ["dist/deep/two.jar", "dist/one.jar"], rows
    for row in rows:
        assert os.path.isabs(row["remotePath"]), row
        assert os.path.isfile(row["remotePath"]), row
        assert row["bytes"] == os.path.getsize(row["remotePath"]), row
        assert row["deployed"] is True and row["skipped"] is False, row
    assert int(values["bytes"]) == sum(row["bytes"] for row in rows), values


@check("unicode.and.spaces.in.names")
def _():
    workspace = fresh_workspace()
    write(os.path.join(workspace, "release notes", "change log 1.txt"), "notes")
    target = os.path.join(tempfile.mkdtemp(prefix="deployer-out-"), "out")
    result, values, _proc = run({"remote": target, "includes": "**/*.txt"}, workspace)
    assert result.get("status") == "success", result.get("message")
    assert set(tree_of(target)[0]) == {"release notes/change log 1.txt"}, sorted(tree_of(target)[0])
    rows = json.loads(values["files"])
    assert rows[0]["sourcePath"] == "release notes/change log 1.txt", rows


@check("unwritable.target.fails")
def _():
    workspace = fresh_workspace()
    sample_tree(workspace)
    target = tempfile.mkdtemp(prefix="deployer-locked-")
    os.chmod(target, stat.S_IRUSR | stat.S_IXUSR)
    try:
        result, _values, _proc = run({"remote": os.path.join(target, "out")}, workspace)
        expect_error(result, 40004)
        assert "Permission" in result["message"] or "denied" in result["message"].lower(), result["message"]
    finally:
        os.chmod(target, stat.S_IRWXU)


@check("patterns.space.separated")
def _():
    """Ant splits an include string on commas and whitespace alike."""
    workspace = fresh_workspace()
    files = sample_tree(workspace)
    target = os.path.join(tempfile.mkdtemp(prefix="deployer-out-"), "out")
    result, values, _proc = run({"remote": target, "includes": "**/*.jar **/*.md", "excludes": "**/docs/** **/*.tmp"},
                                workspace)
    assert result.get("status") == "success", result.get("message")
    actual, _dirs = tree_of(target)
    expected = selected(files, "**/*.jar **/*.md", "**/docs/** **/*.tmp")
    assert actual == expected, "got %s, expected %s" % (sorted(actual), sorted(expected))
    assert "docs/guide.md" not in actual, sorted(actual)


@check("default.includes.when.absent")
def _():
    """With no include pattern at all, Ant's fileset default is everything under the basedir."""
    workspace = fresh_workspace()
    files = sample_tree(workspace)
    target = os.path.join(tempfile.mkdtemp(prefix="deployer-out-"), "out")
    result, values, _proc = run({"remote": target}, workspace, drop=("includes",))
    assert result.get("status") == "success", result.get("message")
    actual, _dirs = tree_of(target)
    assert actual == files, "got %s, expected %s" % (sorted(actual), sorted(files))


def main():
    global JAR
    args = sys.argv[1:]
    if "--jar" in args:
        at = args.index("--jar")
        JAR = args[at + 1]
        del args[at:at + 2]
    # the atom's mtime check compares against a fixed 2001 timestamp; the clock must not matter
    assert time.time() > 978307200
    needle = args[0] if args else ""
    failures = 0
    for name, fn in CHECKS:
        if needle and needle not in name:
            continue
        try:
            fn()
            print("ok   " + name)
        except AssertionError as error:
            failures += 1
            print("FAIL " + name + ": " + str(error))
        except Exception as error:  # noqa: BLE001 - one broken check must not hide the rest
            failures += 1
            print("ERROR " + name + ": %r" % (error,))
    total = len([1 for name, _ in CHECKS if not needle or needle in name])
    print("\n%d/%d checks passed" % (total - failures, total))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
