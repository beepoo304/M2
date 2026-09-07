package pl.meshcore.monitor.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object WarsawTimeFormatter {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun dateTime(value: String): String = format(value, dateTime, value.take(16).replace('T', ' '))
    fun time(value: String): String = format(value, time, "--:--:--")

    private fun format(value: String, formatter: DateTimeFormatter, fallback: String): String = runCatching {
        Instant.parse(value).atZone(zone).format(formatter)
    }.getOrElse { fallback }
}
