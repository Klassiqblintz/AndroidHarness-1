package com.androidharness.app.github

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * GitHub credentials — matches Andropi's simpler shape.
 *
 * Andropi stores `{ token, login, name, email, avatarUrl }` in
 * `integrations.ts`. AndroidHarness keeps the same fields but stores
 * them in EncryptedSharedPreferences instead of a JSON file.
 *
 * No refresh token or expiry tracking: PATs are long-lived, and OAuth
 * device-flow tokens get refreshed on every call (the `accessToken()`
 * probe fails fast on expiry and the UI prompts to re-sign-in).
 */
@Serializable
data class GitHubCredential(
    val token: String,
    val login: String,
    val name: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null,
)

data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val url: String,
    val expiresIn: Long,
    val interval: Long,
)

data class GitHubConnectionState(
    val login: String? = null,
    val busy: Boolean = false,
    val code: GitHubDeviceCode? = null,
    val message: String? = null,
)

/** Direct GitHub requests: device grant, API reads/writes. Matches Andropi's `api()` shell function. */
class GitHubApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val authBase: String = "https://github.com",
    private val apiBase: String = "https://api.github.com",
) {
    suspend fun form(path: String, fields: Map<String, String>): JsonObject = request(
        Request.Builder()
            .url("$authBase$path")
            .header("Accept", "application/json")
            .post(
                FormBody.Builder().apply {
                    fields.forEach { (k, v) -> add(k, v) }
                }.build(),
            ).build(),
    )

    /** OAuth device/refresh grant — form-encoded, no auth header. */
    suspend fun refresh(path: String, token: String, fields: Map<String, String>): JsonObject = request(
        Request.Builder()
            .url("$authBase$path")
            .header("Accept", "application/json")
            .post(
                FormBody.Builder().apply {
                    fields.forEach { (k, v) -> add(k, v) }
                }.build(),
            ).build(),
    )

    /** Read-only API call, optionally authenticated. */
    suspend fun get(path: String, token: String?): JsonElement = requestElement(
        Request.Builder()
            .url("$apiBase$path")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
    )

    /** Authenticated JSON write; the only caller is the reviewed pull request flow. */
    suspend fun post(path: String, token: String, body: JsonObject): JsonElement = requestElement(
        Request.Builder().url("$apiBase$path").header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build(),
    )

    private suspend fun request(request: Request): JsonObject = requestElement(request).jsonObject
    private suspend fun requestElement(request: Request): JsonElement = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val parsed = runCatching { Json.parseToJsonElement(response.body?.string().orEmpty()) }.getOrNull()
            if (!response.isSuccessful) {
                (parsed as? JsonObject)?.string("error")?.let {
                    error(oauthError(it, parsed.string("error_description")))
                }
                val message = (parsed as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: (parsed as? JsonObject)?.get("error_description")?.jsonPrimitive?.contentOrNull
                // Validation failures name the actual problem only in errors[].message.
                val details = ((parsed as? JsonObject)?.get("errors") as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.string("message") ?: (it as? JsonPrimitive)?.contentOrNull }
                throw GitHubHttpError(response.code, details,
                    "GitHub returned HTTP ${response.code}: ${message ?: "check connection and account access"}" +
                        details.joinToString("") { " $it" })
            }
            parsed ?: error("GitHub returned an unreadable response. Try again.")
        }
    }
}

/** Kept an IllegalStateException so existing message-only handlers behave as before. */
class GitHubHttpError(val code: Int, val details: List<String>, message: String) : IllegalStateException(message)

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.seconds(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

internal fun tokenCredential(body: JsonObject, login: String, clientId: String, now: Long): GitHubCredential {
    val token = body.string("access_token")?.takeIf { it.isNotBlank() }
        ?: error("GitHub did not return an access token. Sign in again.")
    val name = body.string("name").orEmpty().trim()
    val email = body.string("email").orEmpty().trim()
    val avatarUrl = body.string("avatar_url")
    return GitHubCredential(token, login, name = if (name.isNotBlank()) name else null,
        email = if (email.isNotBlank()) email else null, avatarUrl = avatarUrl)
}

/** Fetches the current user profile and extracts name / email / avatarUrl for the credential. */
internal suspend fun tokenFrom(api: GitHubApi, token: String): GitHubCredential? = withContext(Dispatchers.IO) {
    try {
        val body = api.get("/user", token) as JsonObject
        val name = body.string("name").orEmpty().trim()
        val email = body.string("email").orEmpty().trim()
        val avatarUrl = body.string("avatar_url")
        val login = body.string("login")
            ?.takeIf { it.isNotBlank() }
            ?: error("GitHub /user returned no login.")
        val cred = tokenCredential(body, login, "", now())
        GitHubCredential(cred.token, login, name = if (name.isNotBlank()) name else null,
            email = if (email.isNotBlank()) email else null, avatarUrl = avatarUrl)
    } catch (e: Exception) { null }
}

/** Credentials and the legacy toolchain token are saved atomically by the supplied store. */
class GitHubAuth(
    val clientId: String,
    private val read: () -> GitHubCredential?,
    private val write: (GitHubCredential?) -> Unit,
    private val sync: suspend () -> Unit,
    val api: GitHubApi = GitHubApi(),
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var signIn: Job? = null
    private var refreshFailedAt: Long? = null
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val _state = MutableStateFlow(read()?.let { GitHubConnectionState(it.login) } ?: GitHubConnectionState())
    val state: StateFlow<GitHubConnectionState> = _state

    fun cancelSignIn() {
        generation.incrementAndGet()
        signIn?.cancel()
        signIn = null
        _state.value = _state.value.copy(busy = false, code = null)
    }

    fun startSignIn(workflow: Boolean = false) {
        cancelSignIn()
        val attempt = generation.get()
        signIn = scope.launch {
                _state.value = _state.value.copy(busy = true, message = "Requesting a sign-in code…")
                try {
                    check(clientId.isNotBlank()) { "This build has no GitHub Client ID. Use a personal access token, or configure GITHUB_CLIENT_ID when building the app." }
                    val body = api.form("/login/device/code", mapOf("client_id" to clientId,
                        "scope" to if (workflow) "repo workflow offline_access" else "repo offline_access"))
                    check(body.string("error") == null) { body.string("error_description") ?: "Sign in failed." }) }
                    val code = GitHubDeviceCode(
                        requireNotNull(body.string("device_code")),
                        requireNotNull(body.string("user_code")),
                        requireNotNull(body.string("verification_uri")),
                        body.seconds("expires_in") ?: 900,
                        (body.seconds("interval") ?: 5).coerceAtLeast(5),
                    )
                    check(code.url == "https://github.com/login/device") { "GitHub returned an unexpected sign-in address." }
                    _state.value = _state.value.copy(code = code, message = "Enter this code on GitHub, then approve access.")
                    val deadline = now() + code.expiresIn * 1000
                    var interval = code.interval
                    while (now() < deadline) {
                        pause(interval * 1000)
                        if (now() >= deadline) break
                        val token = api.form("/login/oauth/access_token", mapOf("client_id" to clientId,
                            "device_code" to code.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
                        when (token.string("error")) {
                            "authorization_pending" -> Unit
                            "slow_down" -> interval = maxOf(interval + 5, token.seconds("interval") ?: 0)
                            null -> {
                                val login = identity(requireNotNull(token.string("access_token")))
                                currentCoroutineContext().ensureActive()
                                mutex.withLock {
                                    currentCoroutineContext().ensureActive()
                                    check(generation.get() == attempt) { "Sign-in was cancelled." }
                                    save(tokenFrom(api, token.string("access_token")!!)
                                        ?: tokenCredential(token, login, clientId, now()))
                                }
                                return@launch
                            }
                            else -> error(oauthError(token.string("error"), token.string("error_description")))
                        }
                    }
                    error("The sign-in code expired. Request a new code.")
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    if (generation.get() == attempt) _state.value = _state.value.copy(busy = false, code = null, message = safeMessage(e))
                } finally {
                    if (generation.get() == attempt) _state.value = _state.value.copy(busy = false, code = null)
                }
        }
    }

    internal suspend fun awaitSignIn() { signIn?.join() }

    suspend fun connectPat(token: String) {
        cancelSignIn()
        val attempt = generation.get()
        mutex.withLock {
            val clean = token.trim()
            require(clean.isNotEmpty() && clean.none { it.isWhitespace() }) { "Paste the complete personal access token." }
            val login = identity(clean)
            currentCoroutineContext().ensureActive()
            check(generation.get() == attempt) { "Connection change was cancelled." }
            save(GitHubCredential(clean, login))
        }
    }

    suspend fun disconnect() {
        cancelSignIn()
        mutex.withLock {
            write(null)
            _state.value = GitHubConnectionState(message = "Signed out on this device.")
            sync()
        }
    }

    suspend fun accessToken(): String? = mutex.withLock { accessTokenLocked() }

    suspend fun <T> withConnection(action: suspend (String?) -> T): T = mutex.withLock { action(accessTokenLocked()) }

    /** Direct read of the stored token. No refresh / expiry logic — PATs are long-lived,
     *  and OAuth device-flow tokens get refreshed on every call (the `accessToken()` probe
     *  fails fast on expiry and the UI prompts to re-sign-in). */
    private suspend fun accessTokenLocked(): String? = read()?.token

    private suspend fun identity(token: String): String =
        (api.get("/user", token) as? JsonObject)?.string("login")
            ?.takeIf { it.isNotBlank() }
            ?: error("GitHub could not verify this account.")

    private suspend fun save(credential: GitHubCredential) {
        write(credential)
        _state.value = GitHubConnectionState(credential.login, message = "Connected as ${credential.login}.")
        try { sync() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.value = _state.value.copy(message = "Account saved. Repository access needs another check: ${safeMessage(e)}") }
    }

    fun safeMessage(e: Throwable): String {
        var message = e.message ?: "Could not reach GitHub. Check your connection and try again."
        read()?.let { credential ->
            message = message.replace(credential.token, "[hidden]")
        }
        return redactGitHubSecrets(message)
    }
}

internal fun oauthError(code: String?, detail: String?): String = when (code) {
    "device_flow_disabled" -> "Enable Device Flow in the GitHub OAuth app settings, then try again. PAT login is also available."
    "access_denied" -> "GitHub sign-in was declined. You can try again."
    "expired_token" -> "The sign-in code expired. Request a new code."
    "incorrect_client_credentials" -> "GitHub rejected this build's Client ID. Check the OAuth app registration."
    else -> "GitHub sign-in failed: ${detail ?: code ?: "try again"}"
}

fun redactGitHubSecrets(text: String): String = text
    .replace(Regex("https://[^/\\s]+@github\\.com/"), "https://github.com/")
    .replace(Regex("(?:gh[pousr]_[A-Za-z0-9]+|github_pat_[A-Za-z0-9_]+)"), "[hidden]")
