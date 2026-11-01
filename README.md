# jenkins-atom-artifact-deployer

Artifact Deployer — the **artifactdeployer** plugin (2,623 installs, plugin 1.3) as one atom:
copy the files a pattern selects out of the workspace into a target directory tree.

## What the plugin actually does

The queue brief guessed the remote half might be JSch/SCP. It is not. `ArtifactDeployerPublisher#processDeployment`
does `new FilePath(workspace.getChannel(), outputPath)`, so "remote" is a plain filesystem path on the same node as
the workspace. The whole plugin is a local copy, which means it can be verified for real on this machine — no ssh
server, no stub.

Rules copied from `ArtifactDeployerCopy`, `LocalCopy` and the publisher:

| plugin | atom |
|---|---|
| `basedir` is relative to the workspace, `getBasedirFilePath` fails when it does not exist | same, also accepts an absolute path |
| `Util.createFileSet(basedir, includes, excludes)` — Ant patterns, comma or whitespace separated, no include means `**` | same matcher (see the tests: `**` crosses directories, `*` and `?` do not, matching is case-sensitive) |
| `outputFilePath.mkdirs()` then `deleteContents()` when `deleteRemote` | same: the target directory is created, its *contents* cleared, the directory itself kept |
| `Copy` with `overwrite=false`, `includeEmptyDirs=true`, `flatten`, `preserveLastModified`, `preservePermissions` | same: a file that is already there is kept, not replaced; mtime and permissions are carried over; `flatten` drops the layout |
| counters numbered across entries (`++numberOfCurrentDeployedArtifacts`) and a `ArtifactDeployerVO` per file | one entry, so ids start at 1; the report carries id, source, target, size |
| `failNoFilesDeploy` fails with the configuration it was given | same message shape `[includes:...,excludes:...,basedir:...,outPath:...]` |

## Where the atom deliberately differs

1. **Every copied file is read back** — size, SHA-256 and mtime compare against the source after the copy, and a
   mismatch fails with `40010`. The plugin counts what Ant's copy map contains and never looks at the disk.
2. **A file that was already there is reported as `skipped`**, not as deployed. The plugin's `fileCopyMap` counts it
   as deployed even though `overwrite=false` left it alone.
3. **`failNoFilesDeploy` defaults to true.** The plugin's checkbox is off by default; a deploy that silently moved
   nothing is the classic false green.
4. **A relative `remote` resolves against the workspace.** `new FilePath(channel, relativePath)` resolves against
   the agent's process directory, which depends on how the agent was started.
5. **Two guards the plugin does not have**: `deleteRemote` refuses to clear the filesystem root, or a directory that
   contains the source basedir (that would delete the files about to be deployed). `40002`.
6. **Empty directories**: only directories a pattern is meant to select (`**`, `a/`, `a/**`) are created. Ant also
   creates the parents of everything it copies; that part is the same, the fringe cases are not.

Not ported: the `ArtifactDeployerBuildAction` pages, the "delete remote artifacts when the build is deleted"
`RunListener` (a build-lifecycle hook, not a step), the matrix aggregator, and `deployEvenBuildFail` (an atom runs as
a step; the build result is not its input).

Overlap with `file-ops`: that atom copies/moves inside the workspace with `REPLACE_EXISTING`. This one deploys
*outside* the workspace, never overwrites, supports Ant excludes, clears the target first, and reports per file.
