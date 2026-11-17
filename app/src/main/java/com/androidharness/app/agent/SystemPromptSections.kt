package com.androidharness.app.agent

/**
 * System-prompt sections, each independently replaceable — mirroring pi's
 * `<preamble>`, `<rules>`, `<project_context>`, `<cwd>`, `<skills>` etc.
 *
 * Each field maps to one XML section. A `null` value means “no section”; a
 * empty string means “section exists but is blank” (the XML tag is rendered
 * but empty). This lets callers replace *one* section without rebuilding the
 * whole prompt, which keeps the model's cached prefix stable across turns.
 *
 * The renderers follow pi's convention:
 * - `build()`  = full prompt at session start (all sections joined)
 * - `update()` = diff against a previous snapshot → a system message the
 *   API can deliver mid-conversation so the model knows what changed.
 */
data class SystemPromptSections(
    val preamble: String? = null,          // <preamble>  Who you are, what you do, formatting rules
    val rules: String? = null,              // <rules>     Tool-use, git, sandbox, environment rules
    val richResponse: String? = null,       // <rich_response>  Native component catalog
    val planMode: String? = null,           // <plan_mode>  Plan-mode constraints (null = not plan)
    val skills: String? = null,             // <skills>    Skill catalog + environment guide
    val projectContext: String? = null,     // <project_context>  AGENTS.md, project config, auto-loaded skills, memory
    val repositoryMap: String? = null,      // <repository_map>  Codebase index
    val userInstructions: String? = null,   // <user_instructions>  Per-chat personalisation / pinned instructions
    val memory: String? = null,             // <memory>  Saved/found memories
    val environment: String? = null,        // <environment>  Live environment (device, paths, tools)
    val imageInventory: String? = null,     // <image_inventory>  Available images for this turn (Chat only)
    val searchDepth: String? = null,        // <search_depth>  Research depth setting (Chat only)
    val imageFrequency: String? = null,     // <image_frequency>  Image policy (Chat only)
    val chatAgent: String? = null,          // <chat_agent>  Chat agent policy (Chat only)
    val webSearchBackend: String? = null,   // <web_search_backend>  Web search config (Chat only)
    val extra: String? = null,              // <extra>  Anything else (plugin hooks, start notes, etc.)
) {
    companion object {
        /** Build the full system prompt from all sections (session start). */
        fun build(sections: SystemPromptSections): String {
            val parts = mutableListOf<String>()
            sections.preamble?.takeIf(String::isNotBlank)?.let { parts.add(it) }
            sections.rules?.takeIf(String::isNotBlank)?.let { parts.add(it) }
            sections.richResponse?.takeIf(String::isNotBlank)?.let { parts.add(it) }
            sections.planMode?.takeIf(String::isNotBlank)?.let { parts.add(it) }
            sections.skills?.takeIf(String::isNotBlank)?.let { parts.add(it) }
            sections.projectContext?.takeIf(String::isNotBlank)?.let { parts.add(it) }
            sections.repositoryMap?.takeIf(String::isNotBlank)?.let { parts.add(it) }
            sections.userInstructions?.takeIf(String::isNotBlank)?.let { parts.add(it) }
            sections.memory?.takeIf(String::isNotBlank)?.let { parts.add(it) }
            sections.environment?.takeIf(String::isNotBlank)?.let { parts.add(it) }
            sections.imageInventory?.takeIf(String::isNotBlank)?.let { parts.add(it) }
            sections.searchDepth?.takeIf(String::isNotBlank)?.let { parts.add(it) }
            sections.imageFrequency?.takeIf(String::isNotBlank)?.let { parts.add(it) }
            sections.chatAgent?.takeIf(String::isNotBlank)?.let { parts.add(it) }
            sections.webSearchBackend?.takeIf(String::isNotBlank)?.let { parts.add(it) }
            sections.extra?.takeIf(String::isNotBlank)?.let { parts.add(it) }
            return parts.joinToString("\n\n")
        }

        /**
         * Render a system-message update for APIs that accept system messages
         * mid-conversation. Each changed section is framed by name so the model
         * can relate it to the leading prompt.
         *
         * This mirrors pi's `renderSystemMessageUpdate`.
         */
        fun renderUpdate(
            previous: SystemPromptSections,
            current: SystemPromptSections,
        ): String {
            val changes = listOfNotNull(
                diff("preamble", previous.preamble, current.preamble),
                diff("rules", previous.rules, current.rules),
                diff("rich_response", previous.richResponse, current.richResponse),
                diff("plan_mode", previous.planMode, current.planMode),
                diff("skills", previous.skills, current.skills),
                diff("project_context", previous.projectContext, current.projectContext),
                diff("repository_map", previous.repositoryMap, current.repositoryMap),
                diff("user_instructions", previous.userInstructions, current.userInstructions),
                diff("memory", previous.memory, current.memory),
                diff("environment", previous.environment, current.environment),
                diff("image_inventory", previous.imageInventory, current.imageInventory),
                diff("search_depth", previous.searchDepth, current.searchDepth),
                diff("image_frequency", previous.imageFrequency, current.imageFrequency),
                diff("chat_agent", previous.chatAgent, current.chatAgent),
                diff("web_search_backend", previous.webSearchBackend, current.webSearchBackend),
                diff("extra", previous.extra, current.extra),
            )
            if (changes.isEmpty()) return ""
            return "System prompt updates:\n\n" + changes.joinToString("\n\n")
        }

        private fun diff(
            name: String,
            previous: String?,
            current: String?,
        ): String? {
            val prevText = previous.orEmpty()
            val currText = current.orEmpty()
            return when {
                prevText == currText -> null
                currText.isBlank() -> "Removed section `$name`."
                else -> "Updated section `$name`:\n\n$currText"
            }
        }
    }
}
