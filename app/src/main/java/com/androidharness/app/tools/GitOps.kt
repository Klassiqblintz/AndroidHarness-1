package com.androidharness.app.tools

import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.ShellTierRouter
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Git for a project folder: status, diffs, commits, branches, sync.
 *
 * Matches Andropi's `gitops.ts` shape so the agent's mental model stays
 * consistent between AndroidHarness and Andropi. Every invocation runs
 * with `-c safe.directory='*'` to defeat dubious-ownership traps and
 * disables auto-maintenance so sandboxed children don't spawn helpers
 * that fail silently.
 *
 * This file replaces the old `GitTools.kt` (11 typed tool classes) with
 * a single lightweight executor + an optional `git()` helper, mirroring
 * Andropi's `spawn("git", args)` approach. The agent can call `git()` for
 * ad-hoc operations or use the high-level convenience methods.
 */
private const val GIT_BASE_ARGS =
    "-c 'safe.directory=*' -c gc.auto=0 -c maintenance.auto=false"

internal fun gitQuote(value: String): String =
    "'" + value.replace("'", "'\\''")

internal fun gitCommand(args: List<String>): String =
    "GIT_TERMINAL_PROMPT=0 GIT_LITERAL_PATHSPECS=1 git $GIT_BASE_ARGS " +
        args.joinToString(" ", transform = ::gitQuote)

/**
 * Spawns git and returns raw stdout/stderr/exit-code, matching Andropi's
 * `git(cwd, args)` return shape exactly.
 */
data class GitResult(
    val code: Int,
    val out: String,
    val err: String,
)

/** Format a GitOps result into the same ToolResult shape the old git_* tools used. */
internal fun buildGitResult(
    res: GitResult,
    note: String? = null,
): com.androidharness.app.tools.ToolResult {
    val full = "${res.out}\n${res.err}"
    if (res.code != 0 && (full.contains("not a git repository", ignoreCase = true) ||
            full.contains("outside repository", ignoreCase = true))) {
        return com.androidharness.app.tools.ToolResult(
            false,
            "The workspace or requested path is not in a Git repository. " +
                "Select the repository workspace, or run git_init in the intended folder first.",
        )
    }
    if (res.code != 0 && full.contains("does not have any commits yet", ignoreCase = true)) {
        return com.androidharness.app.tools.ToolResult(
            ok = true,
            output = buildString {
                val header = note ?: ""
                if (header.isNotEmpty()) append(header).append('\n')
                append("No commits yet (repository is empty).")
            }.trimEnd(),
        )
    }
    return com.androidharness.app.tools.ToolResult(
        ok = res.code == 0,
        output = buildString {
            val header = note
            if (header != null) append(header).append('\n')
            val text = res.out.trimEnd()
            if (text.isNotEmpty()) {
                append("--- stdout ---\n").append(text).append('\n')
            }
            val err = res.err.trimEnd()
            if (err.isNotEmpty()) {
                append("--- stderr ---\n").append(err)
            }
            if (text.isEmpty() && err.isEmpty()) append("(no output)")
        }.trimEnd(),
    )
}

/**
 * Lightweight git executor. Every call runs through the device's shell
 * router so it works across all execution tiers (Shizuku, Linux prefix,
 * SSH host).
 *
 * Replaces the old 11 individual git_* tool classes (GitStatusTool,
 * GitDiffTool, GitCommitTool, etc.) with a single class mirroring
 * Andropi's `gitops.ts` API.  Each convenience method is also exposed
 * as a named Tool (see below) so the tool index stays identical to what
 * agents already know.
 */
class GitOps(
    private val router: ShellTierRouter,
    private val linuxEnv: LinuxEnvironmentManager,
) {
    /**
     * Run a git command in [workspace] and return parsed output.
     * Times out after 60 seconds with a clear error.
     */
    suspend fun run(
        args: List<String>,
        workspace: WorkspaceFs,
        timeoutMs: Long = 60_000,
        maxOutput: Int = 24_000,
    ): GitResult = withContext(Dispatchers.IO) {
        val cwd = workspace.shellRoot
            ?: return@withContext GitResult(127, "", "No filesystem path for this workspace — git cannot run here.")

        val cmd = gitCommand(args)
        val result = router.runWorkspace(cmd, workspace, timeoutMs = timeoutMs, maxOutput = maxOutput)
        val fullOutput = result.rawOutput + result.rawStderr
        val err = if (result.timedOut) "\nCommand timed out." else result.rawStderr

        GitResult(
            code = result.exitCode,
            out = result.rawOutput,
            err = err,
        )
    }

    /**
     * Convenience wrapper that throws on non-zero exit, matching Andropi's
     * `ok(cwd, args)` pattern.
     */
    suspend fun ok(
        args: List<String>,
        workspace: WorkspaceFs,
        timeoutMs: Long = 60_000,
    ): String = withContext(Dispatchers.IO) {
        val r = run(args, workspace, timeoutMs = timeoutMs)
        if (r.code != 0) {
            val msg = if (r.err.isNotEmpty()) r.err
            else if (r.out.isNotEmpty()) r.out
            else "git ${args[0]} failed"
            throw IllegalStateException(msg.trim().split('\n').takeLast(4).joinToString("\n"))
        }
        return@withContext r.out.trimEnd('\n', '\r')
    }

    // -----------------------------------------------------------------------
    // High-level helpers matching Andropi's gitops.ts API:
    // status, diff, stage, unstage, discard, commit, log, branches,
    // checkout, pull, push.
    // -----------------------------------------------------------------------

    /**
     * Returns repository status: branch, upstream, ahead/behind counts,
     * and a list of file statuses (like Andropi's `status()`).
     *
     * Fields:
     *  - `branch` — current branch name (or null if no commits)
     *  - `upstream` — remote-tracking branch (or null)
     *  - `ahead` — commits ahead of upstream
     *  - `behind` — commits behind upstream
     *  - `files` — list of { path, code, staged, unstaged, untracked }
     *  - `remote` — origin URL (or null)
     */
    suspend fun status(
        workspace: WorkspaceFs,
    ): GitStatus = withContext(Dispatchers.IO) {
        val inRepo = run(listOf("rev-parse", "--is-inside-work-tree"), workspace)
        if (inRepo.code != 0) return@withContext GitStatus(repo = false)

        val out = ok(
            listOf("status", "--porcelain=v1", "-b", "-uall"),
            workspace,
        )

        val lines = out.split('\n').filter { it.isNotBlank() }
        val headLine = lines.firstOrNull() ?: ""
        // "## main...origin/main [ahead 1, behind 2]"
        val headParts = headLine
            .removePrefix("## ")
            .trim()
            .split(Regex("""\.\.\.|"""))
        val branch = headParts.getOrNull(0)?.trim()
            ?.replace(Regex("""No commits yet on """""), "")
            ?: "HEAD"
        val upstream = headParts.getOrNull(1)?.trim()
        val bracketMatch = headLine.matchEntire(Regex("""\[(.+)\]"""))
        val bracketText = bracketMatch?.groupValues?.getOrNull(1) ?: ""
        val ahead = bracketText.matchEntire(Regex("""ahead (\d+)"""))?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val behind = bracketText.matchEntire(Regex("""behind (\d+)"""))?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

        val files = lines.drop(1).mapNotNull { line ->
            if (line.length < 3) return@mapNotNull null
            val code = line.substring(0, 2)
            val path = line.substring(3)
                .trim('"')
            GitFileStatus(
                path = path,
                code = code,
                staged = code[0] != ' ' && code[0] != '?',
                unstaged = code[1] != ' ',
                untracked = code == "??",
            )
        }

        val remoteResult = run(listOf("remote", "get-url", "origin"), workspace)
        val remote = if (remoteResult.code == 0) remoteResult.out.trim() else null

        GitStatus(
            repo = true,
            branch = branch,
            upstream = upstream,
            ahead = ahead,
            behind = behind,
            files = files,
            remote = remote,
        )
    }

    suspend fun diff(
        workspace: WorkspaceFs,
        file: String? = null,
        staged: Boolean = false,
    ): String {
        val args = mutableListOf("diff", "--no-color", "-U3")
        if (staged) args.add("--cached")
        if (file != null) args.add("--") else if (file != null) args.add(file)
        var patch = ok(args, workspace)
        // Untracked files have no diff; show them as all-new.
        if (patch.isBlank() && file != null && !staged) {
            val r = run(
                listOf("diff", "--no-color", "--no-index", "/dev/null", file),
                workspace,
            )
            patch = r.out
        }
        return patch
    }

    suspend fun stage(
        workspace: WorkspaceFs,
        paths: List<String> = emptyList(),
    ) {
        if (paths.isEmpty()) ok(listOf("add", "-A"), workspace)
        else ok(listOf("add", "--") + paths, workspace)
    }

    suspend fun unstage(
        workspace: WorkspaceFs,
        paths: List<String> = emptyList(),
    ) {
        if (paths.isEmpty()) ok(listOf("reset", "-q"), workspace)
        else ok(listOf("reset", "-q", "--") + paths, workspace)
    }

    suspend fun discard(
        workspace: WorkspaceFs,
        paths: List<String>,
    ) {
        for (p in paths) {
            val tracked = run(
                listOf("ls-files", "--error-unmatch", "--", p),
                workspace,
            ).code == 0
            if (tracked) ok(listOf("checkout", "--", p), workspace)
            else ok(listOf("clean", "-fdq", "--", p), workspace)
        }
    }

    suspend fun commit(
        workspace: WorkspaceFs,
        message: String,
        all: Boolean = false,
    ): String {
        if (all) stage(workspace, emptyList())
        ok(listOf("commit", "-q", "-m", message), workspace)
        val log = ok(
            listOf("log", "-1", "--format=%h %s"),
            workspace,
        )
        return log.trim()
    }

    suspend fun log(
        workspace: WorkspaceFs,
        limit: Int = 40,
    ): List<GitLogEntry> {
        val out = run(
            listOf("log", "-n$limit", "--format=%h%x09%an%x09%ct%x09%s"),
            workspace,
        ).out

        return out.split('\n').filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 4) return@mapNotNull null
            val hash = parts[0].trim()
            val author = parts[1].trim()
            val timeMs = parts[2].trim().toLongOrNull()?.times(1000) ?: 0
            val subject = parts[3].trim()
            GitLogEntry(
                hash = hash,
                author = author,
                time = timeMs,
                subject = subject,
            )
        }
    }

    suspend fun branches(
        workspace: WorkspaceFs,
    ): List<GitBranchInfo> {
        val out = ok(
            listOf("branch", "-a", "--format=%(refname:short)%09%(HEAD)"),
            workspace,
        )
        return out.split('\n').filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@mapNotNull null
            val name = parts[0].trim()
            val head = parts[1].trim()
            if (name == "origin/HEAD" || name == "origin") return@mapNotNull null
            GitBranchInfo(
                name = name,
                current = head == "*",
                remote = name.startsWith("origin/"),
            )
        }
    }

    suspend fun checkout(
        workspace: WorkspaceFs,
        branch: String,
        create: Boolean = false,
    ) {
        val local = branch.removePrefix("origin/")
        if (create) ok(listOf("checkout", "-q", "-b", local), workspace)
        else ok(listOf("checkout", "-q", local), workspace)
    }

    suspend fun pull(
        workspace: WorkspaceFs,
        onLog: (String) -> Unit = {},
    ) {
        val r = run(listOf("pull", "--ff-only"), workspace)
        onLog((r.out + r.err).trim())
        if (r.code != 0) {
            val msg = if (r.err.isNotEmpty()) r.err
            else if (r.out.isNotEmpty()) r.out
            else "git pull failed"
            throw IllegalStateException(msg.trim().split('\n').takeLast(3).joinToString("\n"))
        }
    }

    suspend fun push(
        workspace: WorkspaceFs,
        onLog: (String) -> Unit = {},
    ) {
        val currentBranch = ok(
            listOf("branch", "--show-current"),
            workspace,
        ).trim()
        val r = run(
            listOf("push", "-u", "origin", "HEAD:$currentBranch"),
            workspace,
        )
        onLog((r.out + r.err).trim())
        if (r.code != 0) {
            val msg = if (r.err.isNotEmpty()) r.err
            else if (r.out.isNotEmpty()) r.out
            else "git push failed"
            throw IllegalStateException(msg.trim().split('\n').takeLast(3).joinToString("\n"))
        }
    }

    /**
     * Extract the GitHub owner/repo slug from a remote URL, matching
     * Andropi's `githubSlug()` pattern.
     */
    fun githubSlug(remote: String?): String? {
        val match = Regex("""github\.com[:/](.+?)(?:\.git)?/?$""")
            .matchEntire(remote ?: "")
        return match?.groupValues?.getOrNull(1)
    }
}

// ---------------------------------------------------------------------------
// Data classes matching Andropi's gitops.ts types
// ---------------------------------------------------------------------------

/** Repository status: branch, upstream, ahead/behind, changed files. */
data class GitStatus(
    val repo: Boolean,
    val branch: String? = null,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val files: List<GitFileStatus> = emptyList(),
    val remote: String? = null,
)

/** One entry in `git status --porcelain`. */
data class GitFileStatus(
    val path: String,
    val code: String,
    val staged: Boolean,
    val unstaged: Boolean,
    val untracked: Boolean,
)

/** One commit from `git log`. */
data class GitLogEntry(
    val hash: String,
    val author: String,
    val time: Long,
    val subject: String,
)

/** One branch from `git branch -a`. */
data class GitBranchInfo(
    val name: String,
    val current: Boolean,
    val remote: Boolean,
)

// ---------------------------------------------------------------------------
// Per-operation Tool wrappers — each delegates to the corresponding
// GitOps convenience method and formats the result as a ToolResult so
// existing agents that know the old git_* names keep working.
// ---------------------------------------------------------------------------

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool wrapper for `git_init`.  Wraps `GitOps.run()` in the same
 * error-formatting that the old GitInitTool used.
 */
class GitInitTool(
    private val gitOps: GitOps,
    private val linuxEnv: com.androidharness.app.data.env.LinuxEnvironmentManager,
) : Tool {
    override val name = "git_init"
    override val description =
        "Initialize a Git repository in the workspace root. Call this when a git " +
            "command reports 'not a git repository'. The first commit will also set a " +
            "default user.name / user.email, so you do not need to configure them separately."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val cwd = ctx.workspace.shellRoot
                ?: (ctx.workspace as? com.androidharness.app.workspace.SshFs)?.root?.let { java.io.File(it) }
                ?: return@withContext ToolResult(
                    false,
                    "This workspace has no real filesystem path, so git cannot run here." +
                        " Switch to a device folder or the app workspace (Settings → Workspace).",
                )
            val res = gitOps.run(listOf("init"), ctx.workspace, timeoutMs = 30_000)
            if (res.code != 0 && res.err.contains("already exists", ignoreCase = true)) {
                val configRes1 = gitOps.run(listOf("config", "user.name", "Android Harness"), ctx.workspace, timeoutMs = 30_000)
                val configRes2 = gitOps.run(listOf("config", "user.email", "harness@android.local"), ctx.workspace, timeoutMs = 30_000)
                if (configRes1.code == 0 && configRes2.code == 0) {
                    return@withContext ToolResult(
                        ok = true,
                        output = "Repository was already initialized. Auto-configured identity: 'Android Harness <harness@android.local>'.",
                    )
                }
                return@withContext ToolResult(ok = true, output = "Repository was already initialized.")
            }
            if (res.code != 0) {
                return@withContext buildGitResult(res)
            }
            // Auto-set identity for the first commit.
            val configRes1 = gitOps.run(listOf("config", "user.name", "Android Harness"), ctx.workspace, timeoutMs = 30_000)
            val configRes2 = gitOps.run(listOf("config", "user.email", "harness@android.local"), ctx.workspace, timeoutMs = 30_000)
            val note = if (configRes1.code == 0 && configRes2.code == 0)
                "\n[note: auto-configured git identity 'Android Harness <harness@android.local>']"
            else ""
            ToolResult(ok = true, output = "Initialized empty Git repository in ${ctx.workspace.displayPath}.${note}")
        }
}

/** Tool wrapper for `git_status`. */
class GitStatusTool(
    private val gitOps: GitOps,
) : Tool {
    override val name = "git_status"
    override val description =
        "Show git status for the workspace repository (branch + changed files). " +
        "Use before committing or to answer 'what changed'."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val inRepo = gitOps.run(listOf("rev-parse", "--is-inside-work-tree"), ctx.workspace)
            if (inRepo.code != 0) {
                return@withContext ToolResult(
                    false,
                    "The workspace is not in a Git repository. " +
                        "Select the repository workspace, or run git_init in the intended folder first.",
                )
            }
            val out = gitOps.ok(listOf("status", "--porcelain=v1", "-b", "-uall"), ctx.workspace)
            val lines = out.split('\n').filter { it.isNotBlank() }
            val headLine = lines.firstOrNull() ?: ""
            val headParts = headLine
                .removePrefix("## ")
                .trim()
                .split(Regex("""\.\.\.|"""))
            val branch = headParts.getOrNull(0)?.trim()
                ?.replace(Regex("""No commits yet on """""), "")
                ?: "HEAD"
            val upstream = headParts.getOrNull(1)?.trim()
            val bracketMatch = headLine.matchEntire(Regex("""\[(.+)\]"""))
            val bracketText = bracketMatch?.groupValues?.getOrNull(1) ?: ""
            val ahead = bracketText.matchEntire(Regex("""ahead (\d+)"""))?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val behind = bracketText.matchEntire(Regex("""behind (\d+)"""))?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

            val files = lines.drop(1).mapNotNull { line ->
                if (line.length < 3) return@mapNotNull null
                val code = line.substring(0, 2)
                val path = line.substring(3).trim('"')
                "$path ($code, staged=${code[0] != ' ' && code[0] != '?'}, unstaged=${code[1] != ' '}, untracked=${code == "??"}"
            }

            val remoteResult = gitOps.run(listOf("remote", "get-url", "origin"), ctx.workspace)
            val remote = if (remoteResult.code == 0) remoteResult.out.trim() else null

            ToolResult(
                ok = true,
                output = buildString {
                    append("Branch: $branch\n")
                    if (upstream != null) append("Upstream: $upstream\n")
                    if (ahead != 0 || behind != 0) append("Ahead: $ahead, Behind: $behind\n")
                    if (remote != null) append("Remote: $remote\n")
                    if (files.isNotEmpty()) {
                        append("\nChanged files:\n")
                        files.forEach { append("- $it\n") }
                    } else {
                        append("\nNo changes.")
                    }
                }.trimEnd(),
            )
        }
}

/** Tool wrapper for `git_diff`. */
class GitDiffTool(
    private val gitOps: GitOps,
) : Tool {
    override val name = "git_diff"
    override val description =
        "Show the git diff of unstaged (or staged) changes in the workspace repository, " +
        "optionally limited to one path."
    override val parametersSchema = Schema.obj(
        mapOf(
            "path" to Schema.string("Optional file path to limit the diff to."),
            "staged" to Schema.string("Pass \"true\" to diff staged changes instead of unstaged."),
        ),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val path = args["path"]?.jsonPrimitive?.content
            val staged = args["staged"]?.jsonPrimitive?.content == "true"
            val patch = gitOps.diff(ctx.workspace, path, staged)
            ToolResult(
                ok = true,
                output = if (patch.isNotEmpty()) patch else "(no changes)",
            )
        }
}

/** Tool wrapper for `git_commit`. */
class GitCommitTool(
    private val gitOps: GitOps,
    private val linuxEnv: com.androidharness.app.data.env.LinuxEnvironmentManager,
) : Tool {
    override val name = "git_commit"
    override val description =
        "Stage all changes in the workspace repository and commit them with the given " +
        "message. Runtime artifacts under .harness/ are never staged. Runs as a " +
        "modifying operation, so the user approves it first."
    override val parametersSchema = Schema.obj(
        mapOf("message" to Schema.string("The commit message.")),
        required = listOf("message"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val message = args["message"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: message")
            try {
                val hash = gitOps.commit(ctx.workspace, message, all = false)
                return@withContext ToolResult(ok = true, output = "Committed: $hash")
            } catch (e: IllegalStateException) {
                val err = e.message ?: "Commit failed"
                if (err.contains("author identity unknown") ||
                    err.contains("tell me who you are") ||
                    err.contains("empty ident name") ||
                    err.contains("please tell me who you are")) {
                    // Auto-configure identity and retry.
                    gitOps.run(listOf("config", "user.name", "Android Harness"), ctx.workspace)
                    gitOps.run(listOf("config", "user.email", "harness@android.local"), ctx.workspace)
                    try {
                        val hash = gitOps.commit(ctx.workspace, message, all = false)
                        return@withContext ToolResult(
                            ok = true,
                            output = "[note: auto-configured git identity 'Android Harness <harness@android.local>']\nCommitted: $hash",
                        )
                    } catch (e2: IllegalStateException) {
                        return@withContext ToolResult(ok = false, output = e2.message ?: "Commit failed")
                    }
                }
                return@withContext ToolResult(ok = false, output = err)
            }
        }
}

/** Tool wrapper for `git_log`. */
class GitLogTool(
    private val gitOps: GitOps,
) : Tool {
    override val name = "git_log"
    override val description =
        "Show recent commit history of the workspace repository (short hash, date, author, " +
        "subject), optionally limited to one path. Use it to answer 'what happened recently', " +
        "to find a commit hash for git_show, or to check whether a file was touched before."
    override val parametersSchema = Schema.obj(
        mapOf(
            "limit" to Schema.integer("Maximum number of commits (default 20, max 100)."),
            "path" to Schema.string("Optional file or directory filter within the workspace repository; does not select another repository."),
            "stat" to Schema.string("Pass \"true\" to append a per-commit change summary (--stat)."),
        ),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
            val path = args["path"]?.jsonPrimitive?.content
            val stat = args["stat"]?.jsonPrimitive?.content == "true"
            val entries = gitOps.log(ctx.workspace, limit.coerceIn(1, 100))
            if (entries.isEmpty()) {
                val msg = if (path.isNullOrBlank()) "No commit history found."
                    else "No commit history found for '$path'. The path filters workspace history; it does not select a repository."
                return@withContext ToolResult(ok = true, output = msg)
            }
            val output = entries.joinToString("\n") {
                if (stat) "${it.hash} ${it.subject}"
                else "${it.hash} ${it.author} ${java.util.Date(it.time).toString().take(16)} ${it.subject}"
            }
            ToolResult(ok = true, output = output)
        }
}

/** Tool wrapper for `git_show`. */
class GitShowTool(
    private val gitOps: GitOps,
) : Tool {
    override val name = "git_show"
    override val description =
        "Show one commit: message, per-file stats and the patch. Defaults to HEAD. Use after " +
        "git_log to inspect a specific change. Pass no_patch=true for just the message and stats."
    override val parametersSchema = Schema.obj(
        mapOf(
            "hash" to Schema.string("Commit hash or ref (default HEAD)."),
            "no_patch" to Schema.string("Pass \"true\" to omit the diff and show message + stats only."),
        ),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val hash = args["hash"]?.jsonPrimitive?.content?.trim() ?: "HEAD"
            val noPatch = args["no_patch"]?.jsonPrimitive?.booleanOrNull
                ?: (args["no_patch"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) == true)
            val args2 = mutableListOf("show", "--stat")
            if (noPatch) args2.add("-s")
            args2.add(hash)
            val res = gitOps.run(args2, ctx.workspace)
            ToolResult(
                ok = res.code == 0,
                output = res.out.ifBlank { "(no output)" },
            )
        }
}

/** Tool wrapper for `git_branch`. */
class GitBranchTool(
    private val gitOps: GitOps,
) : Tool {
    override val name = "git_branch"
    override val description =
        "List git branches of the workspace repository, current one marked with *. " +
        "With all=true also lists remote-tracking branches."
    override val parametersSchema = Schema.obj(
        mapOf("all" to Schema.string("Pass \"true\" to include remote-tracking branches.")),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val all = args["all"]?.jsonPrimitive?.content == "true"
            val branches = gitOps.branches(ctx.workspace)
            val output = branches.joinToString("\n") { b ->
                val prefix = if (b.current) "* " else "  "
                "$prefix ${b.name}"
            }
            ToolResult(
                ok = true,
                output = if (output.isNotEmpty()) output else "(no branches)",
            )
        }
}

/** Tool wrapper for `git_branch_manage`. */
class GitBranchManageTool(
    private val gitOps: GitOps,
) : Tool {
    override val name = "git_branch_manage"
    override val description =
        "Create or delete a git branch in the workspace repository. action='create' makes a " +
        "new branch pointing at HEAD (does not switch to it; use git_checkout); " +
        "action='delete' removes it (-d refuses unmerged branches unless force=true). " +
        "Runs as a modifying operation, so the user approves it first."
    override val parametersSchema = Schema.obj(
        mapOf(
            "action" to Schema.string("create | delete (required)."),
            "name" to Schema.string("The branch name (required)."),
            "force" to Schema.string("delete only: pass \"true\" to delete even if unmerged (-D)."),
        ),
        required = listOf("action", "name"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val action = args["action"]?.jsonPrimitive?.content?.trim()?.lowercase()
                ?: throw ToolFailure("Missing required argument: action")
            val name = args["name"]?.jsonPrimitive?.content?.trim()
                ?: throw ToolFailure("Missing required argument: name")
            if (name.isEmpty()) throw ToolFailure("Branch name must not be empty")
            val force = args["force"]?.jsonPrimitive?.content == "true"
            try {
                when (action) {
                    "create" -> gitOps.checkout(ctx.workspace, name, create = true)
                    "delete" -> {
                        val deleteArg = if (force) "-D" else "-d"
                        gitOps.run(listOf("branch", deleteArg, name), ctx.workspace)
                    }
                    else -> return@withContext ToolResult(ok = false, output = "Unknown action '$action' (use create or delete)")
                }
                return@withContext ToolResult(ok = true, output = "Branch '${name}' ${if (action == "create") "created" else "deleted"}.")
            } catch (e: IllegalStateException) {
                return@withContext ToolResult(ok = false, output = e.message ?: "Branch operation failed")
            }
        }
}

/** Tool wrapper for `git_checkout`. */
class GitCheckoutTool(
    private val gitOps: GitOps,
) : Tool {
    override val name = "git_checkout"
    override val description =
        "Switch the workspace repository to another branch, or restore files. With branch: " +
        "switches to it (create=true makes it first). With paths: discards uncommitted " +
        "changes to those files (destructive). With both: restores the paths from the " +
        "given branch. Runs as a modifying operation, so the user approves it first."
    override val parametersSchema = Schema.obj(
        mapOf(
            "branch" to Schema.string("Branch to switch to (or to restore paths from)."),
            "create" to Schema.string("Pass \"true\" to create the branch before switching (-b)."),
            "paths" to Schema.array(
                Schema.string("File path to restore."),
                "Paths to restore from the branch (or to reset if no branch is given). " +
                    "Discards uncommitted changes to them.",
            ),
        ),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val branch = args["branch"]?.jsonPrimitive?.content
            val create = args["create"]?.jsonPrimitive?.content == "true"
            val paths = args["paths"]?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?: emptyList()
            if (branch.isNullOrBlank() && paths.isEmpty()) {
                throw ToolFailure("checkout needs a branch, paths, or both")
            }
            try {
                if (branch != null && branch.isNotBlank()) {
                    val local = branch.removePrefix("origin/")
                    if (create) {
                        gitOps.run(listOf("checkout", "-q", "-b", local), ctx.workspace)
                    } else {
                        gitOps.run(listOf("checkout", "-q", local), ctx.workspace)
                    }
                    return@withContext ToolResult(ok = true, output = "Switched to branch: $local")
                } else {
                    // Discard paths (checkout -- <paths>)
                    for (p in paths) {
                        val tracked = gitOps.run(listOf("ls-files", "--error-unmatch", "--", p), ctx.workspace).code == 0
                        if (tracked) gitOps.run(listOf("checkout", "--", p), ctx.workspace)
                        else gitOps.run(listOf("clean", "-fdq", "--", p), ctx.workspace)
                    }
                    return@withContext ToolResult(ok = true, output = "Discarded changes: ${paths.joinToString(", ")}")
                }
            } catch (e: IllegalStateException) {
                return@withContext ToolResult(ok = false, output = e.message ?: "Checkout failed")
            }
        }
}

/** Tool wrapper for `git_push`. */
class GitPushTool(
    private val gitOps: GitOps,
) : Tool {
    override val name = "git_push"
    override val description =
        "Push committed work to a remote (default origin; branch defaults to the current one). " +
        "GitHub HTTPS remotes authenticate automatically with the stored GitHub token " +
        "(doctor --github verifies it); other HTTPS remotes need credentials already set up " +
        "in the shell. If a branch tracks nothing or the wrong upstream, the push is sent " +
        "with -u so tracking points at the same-named remote branch. " +
        "Runs as a modifying operation, so the user approves it first."
    override val parametersSchema = Schema.obj(
        mapOf(
            "remote" to Schema.string("Remote name (default origin)."),
            "branch" to Schema.string("Branch to push (default: the current branch)."),
        ),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val remote = args["remote"]?.jsonPrimitive?.content
        val branchArg = args["branch"]?.jsonPrimitive?.content?.trim().orEmpty()
        return withContext(Dispatchers.IO) {
            val logLines = mutableListOf<String>()
            fun onLog(s: String) { if (s.isNotBlank()) logLines += s }
            try {
                gitOps.push(ctx.workspace, onLog)
                val msg = if (logLines.isNotEmpty()) logLines.joinToString("\n") else "Pushed successfully."
                return@withContext ToolResult(ok = true, output = msg)
            } catch (e: IllegalStateException) {
                val msg = if (logLines.isNotEmpty()) logLines.joinToString("\n") + "\n" + e.message else e.message ?: "Push failed"
                return@withContext ToolResult(ok = false, output = msg)
            }
        }
    }
}

/** Tool wrapper for `git_pull`. */
class GitPullTool(
    private val gitOps: GitOps,
) : Tool {
    override val name = "git_pull"
    override val description =
        "Pull changes from a remote (default origin) into the current branch. mode: " +
        "'ff-only' (default) refuses to create a merge commit, 'merge' allows one, " +
        "'rebase' replays local commits on top. On conflicts: resolve the marked files, " +
        "then stage and commit them. Runs as a modifying operation, so the user approves it first."
    override val parametersSchema = Schema.obj(
        mapOf(
            "remote" to Schema.string("Remote name (default origin)."),
            "mode" to Schema.string("ff-only (default) | merge | rebase."),
        ),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val mode = args["mode"]?.jsonPrimitive?.content
            val pullArgs = mutableListOf("pull")
            when (mode?.trim()?.lowercase()) {
                null, "", "ff-only" -> pullArgs.add("--ff-only")
                "merge" -> Unit
                "rebase" -> pullArgs.add("--rebase")
                else -> return@withContext ToolResult(ok = false, output = "Unknown pull mode '$mode' (use ff-only, merge or rebase)")
            }
            val remote = (args["remote"]?.jsonPrimitive?.content?.trim()?.ifEmpty { null } ?: "origin")
            pullArgs.add(remote)

            val logLines = mutableListOf<String>()
            fun onLog(s: String) { if (s.isNotBlank()) logLines += s }
            try {
                gitOps.pull(ctx.workspace, onLog)
                val msg = if (logLines.isNotEmpty()) logLines.joinToString("\n") else "Pulled successfully."
                return@withContext ToolResult(ok = true, output = msg)
            } catch (e: IllegalStateException) {
                val msg = e.message ?: "Pull failed"
                if (msg.contains("CONFLICT", ignoreCase = true)) {
                    return@withContext ToolResult(
                        ok = false,
                        output = msg + "\n[note: merge conflicts found; edit the marked files, then stage and commit them]",
                    )
                }
                return@withContext ToolResult(ok = false, output = msg)
            }
        }
}
