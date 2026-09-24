package pl.meshcore.monitor.data

/** Accept an observation, never an unrelated branch solely because its packet is related. */
object RouteTrackingPolicy {
    const val REVISION = 3
    fun relations(packet: LivePacket, route: ObservedRoute, keys: Set<String>): TrackedKeyRelations {
        if (!MeshPath.isTrackable(route.path)) return TrackedKeyRelations()
        return TrackedKeyMatcher.resolvedRoute(route.path, route.resolvedPath, keys)
            .merge(TrackedKeyMatcher.observer(route.observerPublicKey, keys))
            .merge(TrackedKeyRelations(sourceKeys = packet.trackedRelations.sourceKeys.intersect(keys),
                replyKeys = packet.trackedRelations.replyKeys.intersect(keys)))
    }

    fun accepts(relations: TrackedKeyRelations, key: String, mode: MapTrackingMode): Boolean {
        fun Set<String>.has() = any { it.equals(key, true) }
        val source = relations.sourceKeys.has()
        val end = relations.routeEndKeys.has()
        val inside = relations.routeKeys.has()
        val reported = relations.observerKeys.has()
        // A saved observer confirms this specific reply observation, even when
        // the addressed companion is not an observer and is absent from the RF path.
        val confirmedReply = relations.replyKeys.has() && relations.observerKeys.isNotEmpty()
        return when (mode) {
            MapTrackingMode.STARTS_AT_KEY -> source
            MapTrackingMode.ENDS_AT_KEY -> end
            MapTrackingMode.RELATED_TO_KEY -> source || end || inside || confirmedReply
            MapTrackingMode.REPORTED_BY_KEY -> reported
            MapTrackingMode.ALL_FOR_SELECTED_KEY -> source || end || inside || reported || confirmedReply
        }
    }

    fun event(packet: LivePacket, route: ObservedRoute, keys: Set<String>, key: String, mode: MapTrackingMode): MapRouteEvent? {
        val relation = relations(packet, route, keys)
        if (!accepts(relation, key, mode)) return null
        val path = route.path.toMutableList()
        val resolved = route.path.indices.map { route.resolvedPath.getOrNull(it).orEmpty() }.toMutableList()
        val source = packet.trackedRelations.sourceKeys.singleOrNull()
        if (source != null && !path.firstOrNull().let { it != null && source.startsWith(it, true) }) {
            path.add(0, source.take(4).uppercase()); resolved.add(0, source)
        }
        // A source packet with an empty RF path is still a confirmed direct route
        // from that source to the API-reported observer. Preserve that zero-hop
        // link instead of discarding the otherwise single-point map event.
        val observer = route.observerPublicKey
        val selectedIsSource = relation.sourceKeys.any { it.equals(key, true) }
        val observerIsSelected = TrackedKeyMatcher.exactFull(observer, setOf(key)).isNotEmpty()
        val observerIsExplicit = observer.length == 64 && MeshPath.isReliableHop(observer)
        if ((observerIsSelected || selectedIsSource && observerIsExplicit) &&
            !path.lastOrNull().let { it != null && observer.startsWith(it, true) }) {
            path += observer.take(4).uppercase(); resolved += observer
        }
        if (path.size < 2) return null
        val reply = relation.replyKeys.any { it.equals(key, true) }
        return MapRouteEvent(packet.stableIdentity, packet.hash, packet.payloadType, packet.timestamp,
            System.currentTimeMillis(), path, longestRouteEligible = !reply,
            replyToSelected = reply, resolvedPath = resolved, acceptedKeys = setOf(key))
    }
}
