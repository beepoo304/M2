package pl.meshcore.monitor.data

data class ChannelSummary(
    val name: String,
    val hash: String,
    val lastActivity: String = "",
    val messageCount: Int = 0,
    val isPrivate: Boolean = false,
)

data class ChannelMessage(
    val id: String,
    val sender: String,
    val text: String,
    val timestamp: String,
    val hops: Int = 0,
    val observers: List<String> = emptyList(),
    val repeats: Int = 1,
    val snr: Double? = null,
    val packetHash: String = "",
)

data class ChannelMessageDetails(
    val message: ChannelMessage,
    val path: List<String> = emptyList(),
    val rssi: Int? = null,
    val routeType: Int? = null,
    val observationCount: Int = 1,
)
