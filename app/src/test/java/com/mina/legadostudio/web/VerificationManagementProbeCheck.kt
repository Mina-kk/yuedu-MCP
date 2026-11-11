package com.mina.legadostudio.web

/** Executes the production probe, not a duplicate of its state machine. */
fun main() {
    for (destination in listOf("https://unrelated.example/help", "https://example.com/failed", "https://example.com/challenge")) {
        val p = VerificationManagementProbe()
        check(!p.sample("https://example.com/challenge", 2000, true, true, true, 0))
        repeat(5) {
            check(!p.sample(destination, 2000, false, true, true, 2000L + it * 2000)) {
                "Stable marker-free page is not success: $destination"
            }
        }
        p.close()
        check(!p.sample(destination, 2000, false, true, true, 20000))
    }
    val evidence = VerificationManagementProbe.VerifiedTaskRetry("https://example.com/challenge", "https://example.com/challenge")
    val verified = VerificationManagementProbe()
    check(!verified.sample(evidence.challengeUrl, 2000, true, true, true, 0))
    check(verified.sample(evidence.finalUrl, 2000, false, true, true, 2000, evidence))
    verified.reset()
    check(!verified.sample(evidence.finalUrl, 2000, false, true, true, 4000, evidence))
    val wrongContext = VerificationManagementProbe()
    check(!wrongContext.sample(evidence.challengeUrl, 2000, true, true, true, 0))
    check(!wrongContext.sample("https://unrelated.example/help", 2000, false, true, true, 2000, evidence))
    println("Probe: unrelated, same-site failure and same-URL marker disappearance remain manual")
}
