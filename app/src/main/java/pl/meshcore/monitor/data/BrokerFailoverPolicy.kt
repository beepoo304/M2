package pl.meshcore.monitor.data

/** Monotonic timing shared by the live loop; successful checks never shorten the grace period. */
internal class BrokerFailoverPolicy {
    private var failedSince: Long? = null
    private var retryAt = 0L
    private var recoveryVersion = 0L

    @Synchronized fun failed(requestStartedAt: Long) {
        if (failedSince == null) failedSince = requestStartedAt
    }

    @Synchronized fun succeeded() { failedSince = null; retryAt = 0L }

    @Synchronized fun observeRecovery(version: Long): Boolean {
        val changed = version > recoveryVersion
        if (changed) retryAt = 0L
        recoveryVersion = version
        return changed
    }

    @Synchronized fun shouldCheckAlternatives(now: Long): Boolean =
        failedSince?.let { now - it >= 30_000L && now >= retryAt } == true

    @Synchronized fun waitAfterFailedCheck(now: Long) { retryAt = now + 180_000L }
    @Synchronized fun waiting(now: Long): Boolean = now < retryAt
}
