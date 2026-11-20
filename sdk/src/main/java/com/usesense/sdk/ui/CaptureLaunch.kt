package com.usesense.sdk.ui

import com.usesense.sdk.ServerBranding
import com.usesense.sdk.SessionType
import com.usesense.sdk.UseSenseCallback
import com.usesense.sdk.UseSenseConfig
import com.usesense.sdk.UseSenseError
import com.usesense.sdk.UseSenseResult
import com.usesense.sdk.VerificationRequest
import com.usesense.sdk.api.UseSenseApiClient
import com.usesense.sdk.api.models.CreateSessionResponse
import com.usesense.sdk.finalization.FinalizationCallbackGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One verification the capture screen runs, from launch to the single
 * callback. It outlives the screen through a configuration change (the
 * Activity or the embedding Fragment is recreated, this object is not), so
 * the callback fires once and a client token, which is single-use, is never
 * exchanged twice.
 *
 * Replaces the static `pending*` fields HostedPageActivity used to hold: two
 * launches (an embedded Fragment next to a full-screen run) no longer share
 * state.
 */
internal class CaptureLaunch(
    val config: UseSenseConfig,
    val kind: Kind,
    /** DIRECT / PREBUILT / TOKEN: what the session is for. */
    val request: VerificationRequest? = null,
    /** TOKEN: the single-use `client_token` from `POST /v1/sessions/create-token`. */
    val clientToken: String? = null,
    /** REMOTE_*: the enrolment or remote session id. */
    val remoteId: String = "",
    /** PREBUILT (Flows): pre-minted credentials, used once. */
    var prebuiltSession: CreateSessionResponse? = null,
    /** PREBUILT (Flows): the run view's dashboard branding. */
    val serverBranding: ServerBranding? = null,
    /** PREBUILT (Flows): mints a fresh session for "Try again". */
    val sessionFactory: (suspend () -> CreateSessionResponse)? = null,
    /** Delivered as soon as a callback is attached; the screen shows nothing else. */
    val initialError: UseSenseError? = null,
    callback: UseSenseCallback? = null,
) {
    enum class Kind {
        /** `startVerification`: the SDK creates the session with the API key. */
        DIRECT,

        /** `startVerificationWithToken`: the SDK exchanges a client token, no API key on the device. */
        TOKEN,

        /** The Flows runner: a pre-minted session. */
        PREBUILT,
        REMOTE_ENROLLMENT,
        REMOTE_VERIFICATION,
    }

    val id: String = UUID.randomUUID().toString()

    /** SDK capture sequence (primer, straight to the camera) vs the remote hosted page. */
    val isSdkSequence: Boolean get() = kind == Kind.DIRECT || kind == Kind.TOKEN || kind == Kind.PREBUILT
    val isRemote: Boolean get() = kind == Kind.REMOTE_ENROLLMENT || kind == Kind.REMOTE_VERIFICATION

    val sessionType: SessionType
        get() = when (kind) {
            Kind.REMOTE_ENROLLMENT -> SessionType.ENROLLMENT
            Kind.REMOTE_VERIFICATION -> SessionType.AUTHENTICATION
            else -> request?.sessionType ?: SessionType.AUTHENTICATION
        }

    /**
     * The app may start a clean attempt with a new session ("Try again" after a
     * rejection, a fresh session before expiry). Not a Flow (its own policy)
     * and not a client token (single-use: the app's server mints the next one).
     */
    val ownsSession: Boolean get() = kind == Kind.DIRECT

    // ─── Per-verification state that survives the screen ──────────────────

    /** The session this attempt runs on, while it can still be reused (no upload yet). */
    @Volatile var currentSession: CreateSessionResponse? = null

    /** The current attempt's upload started: its session can't be captured again. */
    @Volatile var uploadStarted = false

    /** The decision on the result screen, delivered when the person leaves it. */
    @Volatile var pendingResult: UseSenseResult? = null
    @Volatile var primerShown = false
    @Volatile var retriesUsed = 0

    /** The screen ran at least once (a recreated screen resumes instead of starting over). */
    @Volatile var started = false

    /**
     * The exchange answered that this token can never work (already used,
     * expired, unknown): no screen may exchange it again.
     */
    @Volatile var tokenDead = false

    /** A client token this launch can no longer start a capture from. */
    val tokenUnusable: Boolean
        get() = kind == Kind.TOKEN && (tokenDead || (currentSession != null && uploadStarted))

    /** How the verification ended, once the callback has fired. A screen shown after that replays it. */
    sealed class Outcome {
        data class Result(val result: UseSenseResult) : Outcome()
        data class Error(val error: UseSenseError) : Outcome()
        object Cancelled : Outcome()
    }

    @Volatile var outcome: Outcome? = null
        private set

    /** Screens (capture Fragments) on this launch right now; it ends when the last one goes. */
    private val screens = java.util.concurrent.atomic.AtomicInteger(0)
    fun attachScreen() { screens.incrementAndGet() }

    /** Returns how many screens are still on this launch. */
    fun detachScreen(): Int = screens.decrementAndGet().coerceAtLeast(0)

    // ─── Callback ──────────────────────────────────────────────────────────

    private val lock = Any()
    private var target: UseSenseCallback? = callback
    private var queued: ((UseSenseCallback) -> Unit)? = null

    /**
     * Where the result goes. Null while an embedding host is being recreated:
     * a delivery then waits here and goes out when the new host attaches.
     */
    var callback: UseSenseCallback?
        get() = synchronized(lock) { target }
        set(value) {
            val flush = synchronized(lock) {
                target = value
                if (value != null) queued.also { queued = null } else null
            }
            if (value != null) flush?.invoke(value)
        }

    /** A replay of a verification another launch already reported: shows it, delivers nothing. */
    @Volatile private var silent = false

    private fun dispatch(deliver: (UseSenseCallback) -> Unit) {
        if (silent) return
        val now = synchronized(lock) {
            target ?: run { queued = deliver; null }
        }
        now?.let(deliver)
    }

    /** Exactly one of success / error / cancel, ever, for this launch. */
    val gate = FinalizationCallbackGate<UseSenseResult, UseSenseError>(
        { r -> outcome = Outcome.Result(r); dispatch { it.onSuccess(r) } },
        { e -> outcome = Outcome.Error(e); dispatch { it.onError(e) } },
        { outcome = Outcome.Cancelled; dispatch { it.onCancelled() } },
    )

    // ─── Token exchange ────────────────────────────────────────────────────

    /** Outlives the screen; cancelled when the launch is released. */
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var exchange: Deferred<Result<CreateSessionResponse>>? = null

    /** Builds the client for the exchange; tests point it at a mock server. */
    internal var exchangeClient: () -> UseSenseApiClient = { UseSenseApiClient(config) }

    /**
     * `POST /v1/sessions/exchange-token` for [clientToken], once. A second
     * caller (a screen recreated while it runs) gets the same call; a failed
     * call can be tried again (the server only spends the token on success).
     */
    fun exchangeToken(): Deferred<Result<CreateSessionResponse>> = synchronized(lock) {
        val running = exchange
        if (running != null && !(running.isCompleted && running.getCompleted().isFailure)) return running
        scope.async { exchangeClient().exchangeToken(clientToken.orEmpty()) }
            .also { exchange = it }
    }

    /** The verification is over: stop anything still running for it. */
    fun release() = scope.cancel()

    /**
     * Where the session for an attempt comes from. [resuming] is a screen
     * recreated mid-attempt (rotation, dark mode, a resized window).
     */
    fun sessionSource(resuming: Boolean): SessionSource {
        val held = currentSession
        // A session captured before but never uploaded stays good: reuse it
        // when the screen comes back, and always for a client token (it can
        // only be exchanged once).
        if (held != null && !uploadStarted && (resuming || kind == Kind.TOKEN)) {
            return SessionSource.Reuse(held)
        }
        return when (kind) {
            Kind.TOKEN -> if (held == null && !tokenDead) SessionSource.Exchange(clientToken.orEmpty()) else SessionSource.TokenSpent
            Kind.PREBUILT -> prebuiltSession?.let { SessionSource.Prebuilt(it) }
                ?: sessionFactory?.let { SessionSource.Refresh(it) }
                ?: SessionSource.Create
            else -> SessionSource.Create
        }
    }

    internal sealed class SessionSource {
        data class Reuse(val response: CreateSessionResponse) : SessionSource()
        data class Prebuilt(val response: CreateSessionResponse) : SessionSource()
        data class Refresh(val factory: suspend () -> CreateSessionResponse) : SessionSource()
        data class Exchange(val clientToken: String) : SessionSource()

        /** The client token was used by an attempt that uploaded: only the app's server can mint another. */
        object TokenSpent : SessionSource()
        object Create : SessionSource()
    }

    companion object {
        /** Exchange answers that mean the token will never work: no retry, back to the app. */
        val DEAD_TOKEN_CODES = setOf("token_already_used", "token_expired", "token_not_found")

        /**
         * The launch for a client token. A token is single-use, and a host may
         * build a second screen with the same one (a React Native screen
         * remounted with the same props, a Fragment re-created by the app):
         *  - the token's verification is still running: the same launch (the
         *    new screen picks it up; [callback] replaces the old one);
         *  - it ended with a decision: a replay that shows that result and
         *    delivers nothing (the decision was reported once already);
         *  - it was exchanged (or refused) and ended otherwise: a launch that
         *    reports [tokenSpentError] once and never calls the server;
         *  - it never reached the server: a fresh launch (the token is unspent).
         */
        fun forToken(
            config: UseSenseConfig,
            clientToken: String,
            sessionType: SessionType,
            callback: UseSenseCallback?,
            initialError: UseSenseError? = null,
        ): CaptureLaunch {
            fun fresh(error: UseSenseError? = initialError) = CaptureLaunch(
                config, Kind.TOKEN,
                request = VerificationRequest(sessionType = sessionType),
                clientToken = clientToken,
                initialError = error,
                callback = callback,
            )
            if (initialError != null) return fresh()
            val prior = TokenLedger[clientToken]
            return when {
                prior == null -> fresh()
                prior.outcome == null && CaptureLaunches[prior.id] != null ->
                    prior.also { if (callback != null) it.callback = callback }
                prior.outcome is Outcome.Result -> fresh().apply {
                    silent = true
                    gate.success((prior.outcome as Outcome.Result).result)
                    currentSession = prior.currentSession
                    uploadStarted = true
                    started = true
                }
                prior.currentSession != null || prior.tokenDead -> fresh(tokenSpentError())
                else -> fresh()
            }.also { TokenLedger[clientToken] = it }
        }

        /** The error when a token's session was used up and the screen would need another. */
        fun tokenSpentError() = UseSenseError(
            code = UseSenseError.SESSION_EXPIRED,
            serverCode = "token_already_used",
            message = "This client token has already been used. Request a new one from your server.",
        )

        /** The process was recreated: the callback and the launch are gone. */
        fun interruptedError() = UseSenseError(
            code = UseSenseError.SESSION_EXPIRED,
            serverCode = "verification_interrupted",
            message = "The verification was interrupted. Start it again.",
        )
    }
}

/**
 * Every client token this process started, with the launch that used it
 * (bounded: tokens are single-use and short-lived). Lets a second screen for
 * the same token pick up or replay the first instead of exchanging it again.
 */
internal object TokenLedger {
    private const val MAX = 64
    private val byToken = object : LinkedHashMap<String, CaptureLaunch>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CaptureLaunch>?) = size > MAX
    }

    @Synchronized operator fun get(token: String): CaptureLaunch? = byToken[token]
    @Synchronized operator fun set(token: String, launch: CaptureLaunch) { byToken[token] = launch }
    @Synchronized internal fun clearForTest() = byToken.clear()
}

/** Live launches by id. A launch leaves when its screen is finally destroyed. */
internal object CaptureLaunches {
    private val launches = ConcurrentHashMap<String, CaptureLaunch>()

    fun register(launch: CaptureLaunch): CaptureLaunch = launch.also { launches[it.id] = it }
    operator fun get(id: String?): CaptureLaunch? = id?.let { launches[it] }
    fun remove(id: String) { launches.remove(id) }

    internal fun allForTest(): List<CaptureLaunch> = launches.values.toList()
    internal fun clearForTest() = launches.clear()
}
