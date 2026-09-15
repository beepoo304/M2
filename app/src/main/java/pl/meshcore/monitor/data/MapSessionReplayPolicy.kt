package pl.meshcore.monitor.data

/** Revalidate captured session packets; never turn an update into a history import. */
internal object MapSessionReplayPolicy {
    fun migrate(session: MapSession, available: List<LivePacket>): MapSession {
        if (session.routingRevision == RouteTrackingPolicy.REVISION) return session
        if (session.routingRevision == 0 && session.running && available.isEmpty()) return session
        val captured = session.capturedPacketIds + session.events.map { it.packetHash.ifBlank { it.packetId } }
        val legacy = if (session.routingRevision == 0 && session.running && session.startedAt > 0L) {
            available.filter { packet ->
                packet.stableIdentity in session.baseline &&
                    WarsawTimeFormatter.epochMillis(packet.timestamp)?.let { it >= session.startedAt } == true
            }.map { it.stableIdentity }
        } else emptyList()
        val ids = captured + legacy
        return session.copy(routingRevision = RouteTrackingPolicy.REVISION,
            capturedPacketIds = ids, pendingRechecks = session.pendingRechecks + ids)
    }
}
