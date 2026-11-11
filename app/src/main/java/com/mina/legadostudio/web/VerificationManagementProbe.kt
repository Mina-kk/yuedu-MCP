package com.mina.legadostudio.web

/** Observation only: never clicks, solves or submits a challenge.
 * DOM stability and marker disappearance are NOT verification evidence. Unknown
 * sites remain manual until an independent, task-bound success verifier exists.
 */
internal class VerificationManagementProbe {
    /** Only an independent task retry/site contract may create this, never page JS.
     * Exact challenge URL binding deliberately rejects generic cross-site redirects.
     */
    data class VerifiedTaskRetry(val challengeUrl: String, val finalUrl: String)
    private var closed = false
    private var started = -1L
    private var challengeUrl: String? = null
    fun reset() { started = -1; challengeUrl = null }
    fun close() { closed = true; reset() }
    @Suppress("UNUSED_PARAMETER")
    fun sample(currentUrl: String, size: Long, challenged: Boolean, eligible: Boolean, ready: Boolean, now: Long,
               verifiedRetry: VerifiedTaskRetry? = null): Boolean {
        if (closed) return false
        if (started < 0) started = now
        if (now - started > 120_000 || !eligible) { challengeUrl = null; return false }
        if (challengeUrl != null && currentUrl != challengeUrl) challengeUrl = null
        if (challenged) { challengeUrl = currentUrl; return false }
        return ready && verifiedRetry != null && challengeUrl == currentUrl &&
            verifiedRetry.challengeUrl == challengeUrl && verifiedRetry.finalUrl == currentUrl
    }
}
