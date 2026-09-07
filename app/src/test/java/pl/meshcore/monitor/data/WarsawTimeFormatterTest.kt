package pl.meshcore.monitor.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WarsawTimeFormatterTest {
    @Test fun summerTimeUsesUtcPlusTwo() {
        assertEquals("2026-09-01 19:50", WarsawTimeFormatter.dateTime("2026-09-01T17:50:00Z"))
    }

    @Test fun winterTimeUsesUtcPlusOne() {
        assertEquals("2026-01-01 18:50", WarsawTimeFormatter.dateTime("2026-01-01T17:50:00Z"))
    }
}
