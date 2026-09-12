package pl.meshcore.monitor.data

enum class TrackedRelationType(val label: String) {
    SOURCE("SOURCE"),
    DESTINATION("DESTINATION"),
    IN_ROUTE("IN ROUTE"),
    REPORTED_BY("REPORTED BY"),
}

data class TrackedKeyRelations(
    val sourceKeys: Set<String> = emptySet(),
    val destinationKeys: Set<String> = emptySet(),
    val routeKeys: Set<String> = emptySet(),
    val observerKeys: Set<String> = emptySet(),
    val possibleKeys: Set<String> = emptySet(),
) {
    val confirmedKeys: Set<String>
        get() = sourceKeys + destinationKeys + routeKeys + observerKeys

    val hasConfirmed: Boolean
        get() = confirmedKeys.isNotEmpty()

    fun merge(other: TrackedKeyRelations): TrackedKeyRelations = TrackedKeyRelations(
        sourceKeys = sourceKeys + other.sourceKeys,
        destinationKeys = destinationKeys + other.destinationKeys,
        routeKeys = routeKeys + other.routeKeys,
        observerKeys = observerKeys + other.observerKeys,
        possibleKeys = (possibleKeys + other.possibleKeys) - (confirmedKeys + other.confirmedKeys),
    )

    fun labels(): List<String> = buildList {
        sourceKeys.sorted().forEach { add("${TrackedRelationType.SOURCE.label} · ${it.take(4).uppercase()}") }
        destinationKeys.sorted().forEach { add("${TrackedRelationType.DESTINATION.label} · ${it.take(4).uppercase()}") }
        routeKeys.sorted().forEach { add("${TrackedRelationType.IN_ROUTE.label} · ${it.take(4).uppercase()}") }
        observerKeys.sorted().forEach { add("${TrackedRelationType.REPORTED_BY.label} · ${it.take(4).uppercase()}") }
    }
}

object TrackedKeyMatcher {
    fun exactFull(value: String?, savedKeys: Set<String>): Set<String> {
        val normalized = value.orEmpty().trim()
        if (normalized.length != 64) return emptySet()
        return savedKeys.filterTo(mutableSetOf()) { it.equals(normalized, true) }
    }

    /** A 2-byte or longer hash is reliable only when it resolves to one saved key. */
    fun reliableHash(value: String?, savedKeys: Set<String>): Set<String> {
        val normalized = value.orEmpty().trim()
        if (normalized.length < 4 || normalized.length % 2 != 0) return emptySet()
        return savedKeys.filter { it.startsWith(normalized, true) }.singleOrNull()?.let(::setOf).orEmpty()
    }

    /** 1-byte hashes are intentionally excluded from tracked-key matching. */
    fun possibleOneByte(value: String?, savedKeys: Set<String>): Set<String> {
        return emptySet()
    }

    fun resolvedRoute(path: List<String>, resolvedPath: List<String>, savedKeys: Set<String>): TrackedKeyRelations {
        val confirmed = mutableSetOf<String>()
        val possible = mutableSetOf<String>()
        path.forEachIndexed { index, hop ->
            if (hop.length < 4) return@forEachIndexed
            val resolved = resolvedPath.getOrNull(index)
            val fullMatch = exactFull(resolved, savedKeys)
            // A resolver can suggest a full key for a 1-byte path entry, but the
            // packet itself still does not contain enough bytes to confirm it.
            if (hop.length >= 4 && fullMatch.isNotEmpty()) confirmed += fullMatch
            else {
                confirmed += reliableHash(hop, savedKeys)
            }
        }
        return TrackedKeyRelations(routeKeys = confirmed, possibleKeys = possible - confirmed)
    }

    fun observer(observerKey: String?, savedKeys: Set<String>): TrackedKeyRelations =
        TrackedKeyRelations(observerKeys = exactFull(observerKey, savedKeys))
}
