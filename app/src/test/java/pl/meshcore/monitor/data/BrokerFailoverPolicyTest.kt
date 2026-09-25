package pl.meshcore.monitor.data

import org.junit.Assert.*
import org.junit.Test

class BrokerFailoverPolicyTest {
    @Test fun gracePeriodIncludesTimeSpentWaitingForFailedRequest() {
        val policy = BrokerFailoverPolicy()
        policy.failed(0)
        policy.failed(10_000)
        assertFalse(policy.shouldCheckAlternatives(29_999))
        assertTrue(policy.shouldCheckAlternatives(30_000))
    }

    @Test fun noAvailableBrokerWaitsThreeMinutesAfterCheckCompletes() {
        val policy = BrokerFailoverPolicy()
        policy.failed(0)
        policy.waitAfterFailedCheck(50_000)
        assertTrue(policy.waiting(229_999))
        assertFalse(policy.shouldCheckAlternatives(229_999))
        assertTrue(policy.shouldCheckAlternatives(230_000))
    }

    @Test fun freshRecoveryWakesWaitButUnchangedHealthyStateDoesNot() {
        val policy = BrokerFailoverPolicy()
        policy.observeRecovery(1)
        policy.failed(0)
        policy.waitAfterFailedCheck(50_000)
        policy.observeRecovery(1)
        assertFalse(policy.shouldCheckAlternatives(60_000))
        policy.observeRecovery(2)
        assertTrue(policy.shouldCheckAlternatives(60_000))
        policy.waitAfterFailedCheck(60_000)
        policy.observeRecovery(2)
        assertFalse(policy.shouldCheckAlternatives(61_000))
    }

    @Test fun recoveryDoesNotBypassThirtySecondGracePeriod() {
        val policy = BrokerFailoverPolicy()
        policy.failed(0)
        policy.observeRecovery(1)
        assertFalse(policy.shouldCheckAlternatives(10_000))
    }

    @Test fun successfulFetchClearsOutageAndNextOutageGetsNewGracePeriod() {
        val policy = BrokerFailoverPolicy()
        policy.failed(0)
        policy.waitAfterFailedCheck(40_000)
        policy.succeeded()
        assertFalse(policy.waiting(50_000))
        assertFalse(policy.shouldCheckAlternatives(50_000))
        policy.failed(60_000)
        assertFalse(policy.shouldCheckAlternatives(89_999))
        assertTrue(policy.shouldCheckAlternatives(90_000))
    }
}
