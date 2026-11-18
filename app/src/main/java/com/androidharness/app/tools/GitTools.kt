package com.androidharness.app.tools

import com.androidharness.app.tools.GitOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"

/**
 * Every git invocation runs with -c safe.directory='*'. Repos can be owned by
 * a different uid than whoever executes git (the app workspace seen by the
 * Shizuku shell uid, shared storage owned by the media uid), which otherwise
 * trips "detected dubious ownership in repository" on the very first command.
 *
 * gc.auto=0 and maintenance.auto=false stop git from spawning its detached
 * auto-maintenance subprocess after commits: the sandbox cannot exec it, and
 * the resulting "fatal: cannot exec 'maintenance'" noise made successful
 * commits look failed (on-device QA, 2026-08-28).
 */
private const val GIT_BASE_ARGS = "-c 'safe.directory=*' -c gc.auto=0 -c maintenance.auto=false"

/**
 * Builds a shell command where every git step carries the safe.directory
 * override. Multi-step commands ("add && commit", "diff && diff") need the
 * flag on EACH segment, not just the first.
 */
internal fun gitCmd(vararg steps: String): String =
    steps.joinToString(" && ") { "git $GIT_BASE_ARGS ${it.trim()}" }

internal fun gitDiffCmd(path: String?, staged: Boolean): String {
    val flags = if (staged) " --staged" else ""
    return gitCmd("rev-parse --git-dir >/dev/null", "diff$flags --stat",
        "diff$flags" + if (path.isNullOrBlank()) "" else " -- ${path.shellQuote()}")
}

private const val HARNESS_DIR = ".harness"
private const val HARNESS_DIR_GLOB = ":(glob)**/$HARNESS_DIR"
private const val HARNESS_TREE_GLOB = ":(glob)**/$HARNESS_DIR/**"
private const val HARNESS_DIR_EXCLUDE = ":(exclude,glob)**/$HARNESS_DIR"
private const val HARNESS_TREE_EXCLUDE = ":(exclude,glob)**/$HARNESS_DIR/**"

internal fun gitCommitCmd(message: String): String = gitCmd(
    "rm -r --cached --ignore-unmatch ${HARNESS_DIR_GLOB.shellQuote()} ${HARNESS_TREE_GLOB.shellQuote()}",
    "add -A -- ${HARNESS_DIR_EXCLUDE.shellQuote()} ${HARNESS_TREE_EXCLUDE.shellQuote()}",
    "rm -r --cached --ignore-unmatch ${HARNESS_DIR_GLOB.shellQuote()} ${HARNESS_TREE_GLOB.shellQuote()}",
    "commit -m ${message.shellQuote()}",
)

internal fun gitLogCmd(limit: Int, path: String?, stat: Boolean): String =
    gitCmd(
        buildString {
            append("log -n ").append(limit.coerceIn(1, 100))
            append(" --date=short --pretty=format:'%h %ad %an  %s'")
            if (stat) append(" --stat")
            if (!path.isNullOrBlank()) append(" -- ").append(path.shellQuote())
        },
    )

internal fun gitLogResult(result: ToolResult, path: String?): ToolResult {
    if (!result.ok || !result.output.endsWith("(no output)")) return result
    val message = if (path.isNullOrBlank()) "No commit history found."
        else "No commit history found for '$path'. The path filters workspace history; it does not select a repository."
    return result.copy(output = result.output.removeSuffix("(no output)") + message)
}

internal fun gitShowCmd(hash: String, noPatch: Boolean): String =
    gitCmd(
        buildString {
            append("show --stat")
            if (noPatch) append(" -s") else append(" --patch")
            append(' ').append(hash.trim().shellQuote())
        },
    )

internal fun gitCheckoutCmd(branch: String?, create: Boolean, paths: List<String>): String {
    val cleanPaths = paths.map { it.trim() }.filter { it.isNotEmpty() }
    val b = branch?.trim().orEmpty()
    if (b.isEmpty() && cleanPaths.isEmpty()) {
        throw ToolFailure("checkout needs a branch, paths, or both")
    }
    return gitCmd(
        buildString {
            append("checkout")
            if (b.isNotEmpty()) {
                if (create) append(" -b")
                append(' ').append(b.shellQuote())
            } else {
                append(" --")
            }
            if (cleanPaths.isNotEmpty()) {
                if (b.isNotEmpty()) append(" --")
                cleanPaths.forEach { append(' ').append(it.shellQuote()) }
            }
        },
    )
}

internal fun gitPushCmd(remote: String?, branch: String?, setUpstream: Boolean): String =
    gitCmd(
        buildString {
            append("push")
            if (setUpstream) append(" -u")
            append(' ').append((remote?.trim()?.ifEmpty { null } ?: "origin").shellQuote())
            val b = branch?.trim().orEmpty()
            append(' ').append(if (b.isEmpty()) "HEAD" else b.shellQuote())
        },
    )

internal fun gitHeadBranchCmd(): String = gitCmd("rev-parse --abbrev-ref HEAD")

internal fun gitUpstreamCheckCmd(branch: String): String =
    gitCmd("config --get ${"branch.$branch.merge".shellQuote()}")

internal fun ToolResult.firstOutputLine(): String? =
    output
        .substringAfter("--- stdout ---\n", "")
        .substringBefore("\n--- stderr ---")
        .lineSequence()
        .firstOrNull { it.isNotBlank() }

internal fun gitPullCmd(remote: String?, mode: String?): String {
    val r = (remote?.trim()?.ifEmpty { null } ?: "origin").shellQuote()
    return gitCmd(
        when (mode?.trim()?.lowercase()) {
            null, "", "ff-only" -> "pull --ff-only $r"
            "merge" -> "pull $r"
            "rebase" -> "pull --rebase $r"
            else -> throw ToolFailure("Unknown pull mode '$mode' (use ff-only, merge or rebase)")
        },
    )
}

internal fun isDubiousOwnership(output: String): Boolean =
    output.contains("dubious ownership", ignoreCase = true)

/** Convert a GitOps result into the same ToolResult shape the git_* tools used. */
private suspend fun runGitWithRetry(
    git: GitOps,
    ctx: ToolContext,
    command: String,
    maxRetries: Int = 3,
): ToolResult {
    var res = runGit(git, ctx, command)
    var attempt = 0
    while (!res.ok && !res.output.contains("uncertain", ignoreCase = true) && isIndexLocked(res.output) && attempt < maxRetries) {
        attempt++
        kotlinx.coroutines.delay(200L * (1L shl (attempt - 1)))
        res = runGit(git, ctx, command)
    }
    return res
}

// --- Tool classes each take GitOps instead of (ShellTierRouter, LinuxEnvironmentManager) ---

class GitStatusTool(
    private val git: GitOps,
) : Tool {
    override val name = "git_status"
    override val description =
        "Show git status for the workspace repository (branch + changed files). " +
        "Use before committing or to answer 'what changed'."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val cmd = gitCmd("status --short --branch")
            val parts = cmd.split(" && ")
            var lastRes = GitResult(1, "", "")
            for (step in parts) {
                lastRes = git.run(listOf(*step.split(Regex("\\s+")).toTypedArray()), ctx.workspace)
                if (!lastRes.ok) return@withContext buildGitResult(lastRes)
            }
            buildGitResult(lastRes)
        }
}

class GitDiffTool(
    private val git: GitOps,
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
            val cmd = gitDiffCmd(path, staged)
            val parts = cmd.split(" && ")
            var lastRes = GitResult(1, "", "")
            for (step in parts) {
                lastRes = git.run(listOf(*step.split(Regex("\\s+")).toTypedArray()), ctx.workspace)
                if (!lastRes.ok) return@withContext buildGitResult(lastRes)
            }
            buildGitResult(lastRes)
        }
}

class GitCommitTool(
    private val git: GitOps,
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
            val commitCommand = gitCommitCmd(message)
            var res = runGitWithRetry(git, ctx, commitCommand)
            if (!res.ok && isIdentityUnknown(res.output)) {
                val repoConfig = runGitWithRetry(
                    git, ctx,
                    gitCmd("config user.name 'Android Harness'", "config user.email 'harness@android.local'"),
                )
                var note =
                    "[note: auto-configured repository git identity 'Android Harness <harness@android.local>']"
                if (!repoConfig.ok) {
                    val globalConfig = runGitWithRetry(
                        git, ctx,
                        gitCmd("config --global user.name 'Android Harness'",
                            "config --global user.email 'harness@android.local'"),
                    )
                    if (globalConfig.ok) {
                        note = "[note: auto-configured global git identity 'Android Harness <harness@android.local>' in ~/.gitconfig]"
                    }
                }
                val retryRes = runGitWithRetry(git, ctx, commitCommand)
                if (retryRes.ok) {
                    return@withContext ToolResult(true, "$note\n${retryRes.output}")
                }
            }
            res
        }
}

class GitLogTool(
    private val git: GitOps,
) : Tool {
    override val name = "git_log"
    override val description =
        "Show recent commit history of the workspace repository (short hash, date, author, " +
        "subject), optionally limited to one path."
    override val parametersSchema = Schema.obj(
        mapOf(
            "limit" to Schema.integer("Maximum number of commits (default 20, max 100)."),
            "path" to Schema.string("Optional file or directory filter."),
            "stat" to Schema.string("Pass \"true\" to append per-commit change summary."),
        ),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
            val path = args["path"]?.jsonPrimitive?.content
            val stat = args["stat"]?.jsonPrimitive?.content == "true"
            val result = runGitWithRetry(git, ctx, gitLogCmd(limit, path, stat))
            gitLogResult(result, path)
        }
}

class GitShowTool(
    private val git: GitOps,
) : Tool {
    override val name = "git_show"
    override val description =
        "Show one commit: message, stats and the patch. Defaults to HEAD."
    override val parametersSchema = Schema.obj(
        mapOf(
            "hash" to Schema.string("Commit hash or ref (default HEAD)."),
            "no_patch" to Schema.string("Pass \"true\" to omit the diff."),
        ),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val hash = args["hash"]?.jsonPrimitive?.content?.trim() ?: "HEAD"
            val noPatch = args["no_patch"]?.jsonPrimitive?.booleanOrNull
                ?: (args["no_patch"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) == true)
            val cmd = gitShowCmd(hash, noPatch)
            val parts = cmd.split(" && ")
            var lastRes = GitResult(1, "", "")
            for (step in parts) {
                lastRes = git.run(listOf(*step.split(Regex("\\s+")).toTypedArray()), ctx.workspace)
                if (!lastRes.ok) return@withContext buildGitResult(lastRes)
            }
            buildGitResult(lastRes)
        }
}

class GitBranchTool(
    private val git: GitOps,
) : Tool {
    override val name = "git_branch"
    override val description =
        "List git branches of the workspace repository."
    override val parametersSchema = Schema.obj(
        mapOf("all" to Schema.string("Pass \"true\" to include remote-tracking branches.")),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val all = args["all"]?.jsonPrimitive?.content == "true"
            val cmd = gitCmd(if (all) "branch -a -v" else "branch -v")
            val parts = cmd.split(" && ")
            var lastRes = GitResult(1, "", "")
            for (step in parts) {
                lastRes = git.run(listOf(*step.split(Regex("\\s+")).toTypedArray()), ctx.workspace)
                if (!lastRes.ok) return@withContext buildGitResult(lastRes)
            }
            buildGitResult(lastRes)
        }
}

class GitBranchManageTool(
    private val git: GitOps,
) : Tool {
    override val name = "git_branch_manage"
    override val description =
        "Create or delete a git branch."
    override val parametersSchema = Schema.obj(
        mapOf(
            "action" to Schema.string("create | delete."),
            "name" to Schema.string("The branch name."),
            "force" to Schema.string("delete only: \"true\" for -D."),
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
            val command = when (action) {
                "create" -> gitCmd("branch ${name.shellQuote()}")
                "delete" -> gitCmd("branch ${if (force) "-D" else "-d"} ${name.shellQuote()}")
                else -> throw ToolFailure("Unknown action '$action'")
            }
            runGitWithRetry(git, ctx, command)
        }
}

class GitCheckoutTool(
    private val git: GitOps,
) : Tool {
    override val name = "git_checkout"
    override val description =
        "Switch the workspace to another branch, or restore files."
    override val parametersSchema = Schema.obj(
        mapOf(
            "branch" to Schema.string("Branch to switch to or restore from."),
            "create" to Schema.string("\"true\" to create before switching."),
            "paths" to Schema.array(Schema.string("File path to restore.")),
        ),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val branch = args["branch"]?.jsonPrimitive?.content
            val create = args["create"]?.jsonPrimitive?.content == "true"
            val paths = args["paths"]?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.mapNotNull { it.jsonPrimitive.content }
                ?: emptyList()
            if (branch.isNullOrBlank() && paths.isEmpty()) {
                throw ToolFailure("checkout needs a branch, paths, or both")
            }
            val cmd = gitCheckoutCmd(branch, create, paths)
            val parts = cmd.split(" && ")
            var lastRes = GitResult(1, "", "")
            for (step in parts) {
                lastRes = git.run(listOf(*step.split(Regex("\\s+")).toTypedArray()), ctx.workspace)
                if (!lastRes.ok) return@withContext buildGitResult(lastRes)
            }
            buildGitResult(lastRes)
        }
}

class GitPushTool(
    private val git: GitOps,
) : Tool {
    override val name = "git_push"
    override val description =
        "Push committed work to a remote."
    override val parametersSchema = Schema.obj(
        mapOf(
            "remote" to Schema.string("Remote name (default origin)."),
            "branch" to Schema.string("Branch to push."),
        ),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val remote = args["remote"]?.jsonPrimitive?.content
            val branchArg = args["branch"]?.jsonPrimitive?.content?.trim().orEmpty()
            var branch = branchArg
            if (branch.isEmpty()) {
                val head = runGitWithRetry(git, ctx, gitHeadBranchCmd())
                if (!head.ok) return@withContext head
                branch = head.firstOutputLine() ?: return@withContext head
            }
            val mergeValue = runGitWithRetry(git, ctx, gitUpstreamCheckCmd(branch))
            if (mergeValue.ok && mergeValue.firstOutputLine() == "refs/heads/$branch") {
                return@withContext runGitWithRetry(git, ctx,
                    gitPushCmd(remote, branchArg.ifEmpty { null }, setUpstream = false))
            }
            val retry = runGitWithRetry(git, ctx,
                gitPushCmd(remote, branchArg.ifEmpty { null }, setUpstream = true))
            if (retry.ok) {
                retry.copy(
                    output = "[note: upstream tracking missing or wrong; pushed with -u to fix]\n${retry.output}",
                )
            } else {
                retry
            }
        }
}

class GitPullTool(
    private val git: GitOps,
) : Tool {
    override val name = "git_pull"
    override val description =
        "Pull changes from a remote into the current branch."
    override val parametersSchema = Schema.obj(
        mapOf(
            "remote" to Schema.string("Remote name (default origin)."),
            "mode" to Schema.string("ff-only | merge | rebase."),
        ),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val remote = args["remote"]?.jsonPrimitive?.content
            val mode = args["mode"]?.jsonPrimitive?.content
            val res = runGitWithRetry(git, ctx, gitPullCmd(remote, mode))
            if (!res.ok && res.output.contains("CONFLICT", ignoreCase = true)) {
                res.copy(
                    output = res.output +
                        "\n[note: merge conflicts found; edit the marked files, then stage and commit]",
                )
            } else {
                res
            }
        }
}

private fun isIndexLocked(output: String): Boolean {
    val lower = output.lowercase()
    return lower.contains("index.lock") || lower.contains("another git process seems to be running")
}

private fun isIdentityUnknown(output: String): Boolean {
    val lower = output.lowercase()
    return lower.contains("author identity unknown") ||
        lower.contains("tell me who you are") ||
        lower.contains("empty ident name") ||
        lower.contains("please tell me who you are")
}

class GitInitTool(
    private val git: GitOps,
) : Tool {
    override val name = "git_init"
    override val description =
        "Initialize a Git repository in the workspace root."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val initCmd = gitCmd("init")
            val initParts = initCmd.split(" && ")
            var res = GitResult(1, "", "")
            for (step in initParts) {
                res = git.run(listOf(*step.split(Regex("\\s+")).toTypedArray()), ctx.workspace)
            }
            val full = "${res.out}\n${res.err}"
            if (res.code != 0 && full.contains("already exists", ignoreCase = true)) {
                val configCmd = gitCmd(
                    "config user.name 'Android Harness'",
                    "config user.email 'harness@android.local'",
                )
                var configRes = GitResult(1, "", "")
                for (step in configCmd.split(" && ")) {
                    configRes = git.run(listOf(*step.split(Regex("\\s+")).toTypedArray()), ctx.workspace)
                }
                if (configRes.code == 0) {
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
            val configCmd = gitCmd(
                "config user.name 'Android Harness'",
                "config user.email 'harness@android.local'",
            )
            var configRes = GitResult(1, "", "")
            for (step in configCmd.split(" && ")) {
                configRes = git.run(listOf(*step.split(Regex("\\s+")).toTypedArray()), ctx.workspace)
            }
            val note = if (configRes.code == 0)
                "\n[note: auto-configured git identity 'Android Harness <harness@android.local>']"
            else ""
            ToolResult(ok = true, output = "Initialized empty Git repository in ${ctx.workspace.displayPath}.${note}")
        }
}
