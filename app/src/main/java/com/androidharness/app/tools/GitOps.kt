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
        timeoutMs: Int = 60_000,
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
        timeoutMs: Int = 60_000,
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
